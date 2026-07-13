package com.yellastro.btration.data.nearby

import android.util.Log
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.yellastro.btration.data.wire.WireCodec
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.WirePacket
import com.yellastro.btration.voice.VoiceFrame
import com.yellastro.btration.voice.VoiceFrameCodec
import com.yellastro.btration.voice.VoiceStreamCodec
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Передает Nearby payload-ы по endpointId и декодирует входящие payload-ы в wire/voice события.
 */
internal class NearbyPayloadTransport(
    private val connectionsClient: ConnectionsClient,
    private val wireCodec: WireCodec,
    private val emitEvent: (NearbyPayloadTransportEvent) -> Unit,
) {
    /**
     * Callback Nearby Connections для приема bytes/stream payload-ов.
     */
    val payloadCallback: PayloadCallback = object : PayloadCallback() {
        /**
         * Маршрутизирует входящий payload в bytes, stream или unsupported ветку.
         */
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> handleBytesPayload(endpointId, payload)
                Payload.Type.STREAM -> handleStreamPayload(endpointId, payload)
                else -> {
                    Log.w(TAG, "[onPayloadReceived] Получен неподдерживаемый payload endpointId=$endpointId type=${payload.type}")
                    emitEvent(NearbyPayloadTransportEvent.UnsupportedPayloadReceived(endpointId, payload.type))
                }
            }
        }

        /**
         * Передает наружу статус передачи payload-а без дополнительной интерпретации.
         */
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            emitEvent(NearbyPayloadTransportEvent.PayloadTransferUpdated(endpointId, update))
        }
    }

    /**
     * Отправляет wire packet напрямую в Nearby endpointId.
     */
    fun sendPacket(endpointId: String, peerId: PeerId?, packet: WirePacket, onFailure: (Throwable) -> Unit) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(wireCodec.encode(packet)))
            .addOnSuccessListener {
                Log.i(TAG, "[sendPacket] Packet type=${packet.type} передан Nearby endpointId=$endpointId peerId=${peerId?.value}")
            }
            .addOnFailureListener { cause ->
                Log.w(TAG, "[sendPacket] Не удалось отправить packet type=${packet.type} endpointId=$endpointId peerId=${peerId?.value}: ${cause.message}", cause)
                onFailure(cause)
            }
    }

    /**
     * Отправляет wire packet списку Nearby endpointId.
     */
    fun broadcastPacket(endpointIds: List<String>, packet: WirePacket, onFailure: (Throwable) -> Unit) {
        connectionsClient.sendPayload(endpointIds, Payload.fromBytes(wireCodec.encode(packet)))
            .addOnSuccessListener {
                Log.i(TAG, "[broadcastPacket] Packet type=${packet.type} передан Nearby endpointCount=${endpointIds.size}")
            }
            .addOnFailureListener { cause ->
                Log.w(TAG, "[broadcastPacket] Не удалось отправить broadcast packet type=${packet.type}: ${cause.message}", cause)
                onFailure(cause)
            }
    }

    /**
     * Отправляет голосовой stream выбранным endpoint-ам одним Nearby STREAM payload.
     */
    fun sendStreamToEndpoints(
        endpointIds: List<String>,
        peerCount: Int,
        originPeerId: PeerId,
        inputStream: InputStream,
        onFailure: (Throwable) -> Unit,
    ) {
        val sendStartedAtMillis = System.currentTimeMillis()
        Log.i(TAG, "[sendStreamToEndpoints] Вызываем sendPayload для голосового stream originPeerId=${originPeerId.value} endpointCount=${endpointIds.size} peerCount=$peerCount startedAtMs=$sendStartedAtMillis")
        val loggingInputStream = NearbyStreamLoggingInputStream(
            delegate = VoiceStreamCodec.frame(originPeerId, inputStream),
            endpointCount = endpointIds.size,
            peerCount = peerCount,
            sendStartedAtMillis = sendStartedAtMillis,
        )
        connectionsClient.sendPayload(endpointIds, Payload.fromStream(loggingInputStream))
            .addOnSuccessListener {
                Log.i(
                    TAG,
                    "[sendStreamToEndpoints] Голосовой stream принят Nearby endpointCount=${endpointIds.size} elapsedMs=${System.currentTimeMillis() - sendStartedAtMillis}",
                )
            }
            .addOnFailureListener { cause ->
                Log.w(TAG, "[sendStreamToEndpoints] Не удалось отправить голосовой stream: ${cause.message}", cause)
                runCatching { loggingInputStream.close() }
                onFailure(cause)
            }
    }

    /**
     * Отправляет короткий голосовой frame выбранным endpoint-ам через Nearby BYTES payload.
     */
    fun sendVoiceFrameToEndpoints(endpointIds: List<String>, frame: VoiceFrame, onFailure: (Throwable) -> Unit) {
        val bytes = VoiceFrameCodec.encode(frame)
        connectionsClient.sendPayload(endpointIds, Payload.fromBytes(bytes))
            .addOnSuccessListener {
                if (frame.sequence == FIRST_VOICE_FRAME_SEQUENCE || frame.isFinal) {
                    Log.i(
                        TAG,
                        "[sendVoiceFrameToEndpoints] Voice frame передан Nearby originPeerId=${frame.originPeerId.value} sequence=${frame.sequence} endpointCount=${endpointIds.size} final=${frame.isFinal}",
                    )
                }
            }
            .addOnFailureListener { cause ->
                Log.w(
                    TAG,
                    "[sendVoiceFrameToEndpoints] Не удалось отправить voice frame originPeerId=${frame.originPeerId.value} sequence=${frame.sequence} final=${frame.isFinal}: ${cause.message}",
                    cause,
                )
                onFailure(cause)
            }
    }

    /**
     * Обрабатывает bytes payload как voice frame или wire-пакет протокола комнаты.
     */
    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        val bytes = payload.asBytes()
        if (bytes == null) {
            Log.w(TAG, "[handleBytesPayload] Payload bytes пустые endpointId=$endpointId")
            emitEvent(
                NearbyPayloadTransportEvent.PayloadDecodeFailed(
                    endpointId,
                    IllegalArgumentException("Payload bytes are null"),
                ),
            )
            return
        }

        if (VoiceFrameCodec.isVoiceFrame(bytes)) {
            handleVoiceFramePayload(endpointId, bytes)
            return
        }

        val packet = runCatching { wireCodec.decode(bytes) }
            .onFailure { cause ->
                Log.w(TAG, "[handleBytesPayload] Не удалось декодировать payload endpointId=$endpointId bytes=${bytes.size}: ${cause.message}", cause)
                emitEvent(NearbyPayloadTransportEvent.PayloadDecodeFailed(endpointId, cause))
            }
            .getOrNull()
            ?: return

        Log.i(
            TAG,
            "[handleBytesPayload] Получен packet endpointId=$endpointId type=${packet.type} roomId=${packet.roomId?.value}",
        )
        emitEvent(NearbyPayloadTransportEvent.PacketReceived(endpointId, packet))
    }

    /**
     * Обрабатывает bytes payload как короткий голосовой frame.
     */
    private fun handleVoiceFramePayload(endpointId: String, bytes: ByteArray) {
        val frame = runCatching { VoiceFrameCodec.decode(bytes) }
            .onFailure { cause ->
                Log.w(TAG, "[handleVoiceFramePayload] Не удалось декодировать voice frame endpointId=$endpointId bytes=${bytes.size}: ${cause.message}", cause)
                emitEvent(NearbyPayloadTransportEvent.PayloadDecodeFailed(endpointId, cause))
            }
            .getOrNull()
            ?: return
        if (frame.sequence == FIRST_VOICE_FRAME_SEQUENCE || frame.isFinal) {
            Log.i(
                TAG,
                "[handleVoiceFramePayload] Получен voice frame endpointId=$endpointId originPeerId=${frame.originPeerId.value} sequence=${frame.sequence} encodedBytes=${frame.encodedBytes.size} final=${frame.isFinal}",
            )
        }
        emitEvent(NearbyPayloadTransportEvent.VoiceFrameReceived(endpointId, frame))
    }

    /**
     * Обрабатывает stream payload как входящий голосовой поток.
     */
    private fun handleStreamPayload(endpointId: String, payload: Payload) {
        val receivedAtMillis = System.currentTimeMillis()
        val inputStream = payload.asStream()?.asInputStream()
        if (inputStream == null) {
            Log.w(TAG, "[handleStreamPayload] Stream payload без InputStream endpointId=$endpointId")
            emitEvent(
                NearbyPayloadTransportEvent.PayloadDecodeFailed(
                    endpointId,
                    IllegalArgumentException("Payload stream input is null"),
                ),
            )
            return
        }
        Log.i(TAG, "[handleStreamPayload] Получен голосовой stream endpointId=$endpointId receivedAtMs=$receivedAtMillis")
        emitEvent(NearbyPayloadTransportEvent.StreamReceived(endpointId, inputStream))
    }

    /**
     * Оборачивает голосовой stream, ограничивает размер одного чтения Nearby и логирует первый реальный read.
     */
    private class NearbyStreamLoggingInputStream(
        delegate: InputStream,
        private val endpointCount: Int,
        private val peerCount: Int,
        private val sendStartedAtMillis: Long,
    ) : FilterInputStream(delegate) {
        private var firstReadLogged = false

        /**
         * Читает один байт и отмечает первый успешный read со стороны Nearby.
         */
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                logFirstRead(readBytes = 1)
            } else {
                logEndBeforeFirstRead()
            }
            return value
        }

        /**
         * Читает порцию байтов и отмечает первый успешный read со стороны Nearby.
         */
        override fun read(buffer: ByteArray, byteOffset: Int, byteCount: Int): Int {
            val limitedByteCount = minOf(byteCount, STREAM_READ_LIMIT_BYTES)
            val readBytes = super.read(buffer, byteOffset, limitedByteCount)
            if (readBytes > 0) {
                logFirstRead(
                    readBytes = readBytes,
                    requestedBytes = byteCount,
                    limitedBytes = limitedByteCount,
                )
            } else if (readBytes < 0) {
                logEndBeforeFirstRead()
            }
            return readBytes
        }

        /**
         * Логирует первый момент, когда Nearby забрал из stream хотя бы один байт.
         */
        private fun logFirstRead(readBytes: Int, requestedBytes: Int = 1, limitedBytes: Int = 1) {
            if (firstReadLogged) {
                return
            }
            firstReadLogged = true
            Log.i(
                TAG,
                "[read] Nearby впервые прочитал голосовой stream readBytes=$readBytes requestedBytes=$requestedBytes limitedBytes=$limitedBytes endpointCount=$endpointCount peerCount=$peerCount elapsedMs=${System.currentTimeMillis() - sendStartedAtMillis}",
            )
        }

        /**
         * Логирует ситуацию, когда stream закончился до первого полезного чтения Nearby.
         */
        private fun logEndBeforeFirstRead() {
            if (firstReadLogged) {
                return
            }
            firstReadLogged = true
            Log.w(
                TAG,
                "[read] Голосовой stream закончился до первого чтения Nearby endpointCount=$endpointCount peerCount=$peerCount elapsedMs=${System.currentTimeMillis() - sendStartedAtMillis}",
            )
        }
    }

    private companion object {
        private const val TAG = "NearbyPayloadTransport"
        private const val STREAM_READ_LIMIT_BYTES = 320
        private const val FIRST_VOICE_FRAME_SEQUENCE = 0L
    }
}

/**
 * Событие уровня Nearby payload, которое фасад дополняет PeerId/RoomId из registry.
 */
internal sealed class NearbyPayloadTransportEvent {
    /**
     * Входящий bytes payload успешно декодирован как wire packet комнаты.
     */
    data class PacketReceived(val endpointId: String, val packet: WirePacket) : NearbyPayloadTransportEvent()

    /**
     * Входящий bytes payload успешно декодирован как voice frame.
     */
    data class VoiceFrameReceived(val endpointId: String, val frame: VoiceFrame) : NearbyPayloadTransportEvent()

    /**
     * Входящий stream payload открыт как голосовой InputStream.
     */
    data class StreamReceived(val endpointId: String, val inputStream: InputStream) : NearbyPayloadTransportEvent()

    /**
     * Payload не удалось разобрать как поддерживаемый формат приложения.
     */
    data class PayloadDecodeFailed(val endpointId: String, val cause: Throwable) : NearbyPayloadTransportEvent()

    /**
     * Nearby прислал payload типа, который транспорт сейчас не поддерживает.
     */
    data class UnsupportedPayloadReceived(val endpointId: String, val payloadType: Int) : NearbyPayloadTransportEvent()

    /**
     * Nearby сообщил промежуточный статус передачи payload-а.
     */
    data class PayloadTransferUpdated(val endpointId: String, val update: PayloadTransferUpdate) : NearbyPayloadTransportEvent()
}
