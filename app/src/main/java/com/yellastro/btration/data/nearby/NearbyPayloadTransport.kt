package com.yellastro.btration.data.nearby

import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import java.io.InputStream

/**
 * Передает Nearby payload-ы по endpointId и не интерпретирует содержимое bytes/stream.
 */
internal class NearbyPayloadTransport(
    private val connectionsClient: ConnectionsClient,
    private val emitEvent: (NearbyPayloadTransportEvent) -> Unit,
) {
    private var realtimeLogWindowStartedAtMillis = 0L
    private var realtimePayloadCount = 0
    private var realtimeEndpointDeliveryCount = 0
    private var realtimeByteCount = 0L

    /**
     * Callback Nearby Connections для приема bytes/stream payload-ов.
     */
    val payloadCallback: PayloadCallback = object : PayloadCallback() {
        /**
         * Маршрутизирует входящий payload в bytes, stream или unsupported ветку без декодирования контента.
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
     * Отправляет атомарное bytes-сообщение в один Nearby endpointId.
     *
     * Realtime payload-ы логируются отдельной секундной статистикой вместо строки на каждый voice frame.
     */
    fun sendMessageToEndpoint(
        endpointId: String,
        bytes: ByteArray,
        isRealtime: Boolean = false,
        onFailure: (Throwable) -> Unit,
    ) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnSuccessListener {
                if (isRealtime) {
                    recordRealtimeBytesSent(endpointCount = 1, bytes = bytes.size)
                } else {
                    Log.i(TAG, "[sendMessageToEndpoint] Bytes payload передан Nearby endpointId=$endpointId bytes=${bytes.size}")
                }
            }
            .addOnFailureListener { cause ->
                logBytesSendFailure(
                    functionName = "sendMessageToEndpoint",
                    endpointSummary = "endpointId=$endpointId",
                    bytes = bytes.size,
                    isRealtime = isRealtime,
                    cause = cause,
                )
                onFailure(cause)
            }
    }

    /**
     * Отправляет атомарное bytes-сообщение списку Nearby endpointId.
     *
     * Realtime payload-ы логируются отдельной секундной статистикой вместо строки на каждый voice frame.
     */
    fun sendMessageToEndpoints(
        endpointIds: List<String>,
        bytes: ByteArray,
        isRealtime: Boolean = false,
        onFailure: (Throwable) -> Unit,
    ) {
        connectionsClient.sendPayload(endpointIds, Payload.fromBytes(bytes))
            .addOnSuccessListener {
                if (isRealtime) {
                    recordRealtimeBytesSent(endpointCount = endpointIds.size, bytes = bytes.size)
                } else {
                    Log.i(TAG, "[sendMessageToEndpoints] Bytes payload передан Nearby endpointCount=${endpointIds.size} bytes=${bytes.size}")
                }
            }
            .addOnFailureListener { cause ->
                logBytesSendFailure(
                    functionName = "sendMessageToEndpoints",
                    endpointSummary = "endpointCount=${endpointIds.size}",
                    bytes = bytes.size,
                    isRealtime = isRealtime,
                    cause = cause,
                )
                onFailure(cause)
            }
    }

    /**
     * Логирует ошибку bytes-отправки: штатный shutdown heartbeat-а пишет без stack trace, остальные ошибки — полно.
     */
    private fun logBytesSendFailure(
        functionName: String,
        endpointSummary: String,
        bytes: Int,
        isRealtime: Boolean,
        cause: Throwable,
    ) {
        val message = "[$functionName] Не удалось отправить bytes payload $endpointSummary bytes=$bytes realtime=$isRealtime: ${cause.message}"
        if (isRealtime && bytes == HEARTBEAT_PACKET_BYTES && isEndpointClosedFailure(cause)) {
            Log.i(TAG, "$message; штатно игнорируем stack trace для закрытого heartbeat link-а")
            return
        }
        Log.w(TAG, message, cause)
    }

    /**
     * Возвращает true для Nearby-ошибок, ожидаемых при отправке в уже закрытый endpoint.
     */
    private fun isEndpointClosedFailure(cause: Throwable): Boolean {
        val apiException = cause as? ApiException
        return apiException?.statusCode == ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN ||
            cause.message?.contains("SOCKET_CLOSED", ignoreCase = true) == true
    }

    /**
     * Отправляет поток байт выбранным endpoint-ам одним Nearby STREAM payload.
     */
    fun sendStreamToEndpoints(
        endpointIds: List<String>,
        inputStream: InputStream,
        onFailure: (Throwable) -> Unit,
    ) {
        val sendStartedAtMillis = System.currentTimeMillis()
        Log.i(TAG, "[sendStreamToEndpoints] Вызываем sendPayload для stream endpointCount=${endpointIds.size} startedAtMs=$sendStartedAtMillis")
        val loggingInputStream = NearbyStreamLoggingInputStream(
            delegate = inputStream,
            endpointCount = endpointIds.size,
            sendStartedAtMillis = sendStartedAtMillis,
        )
        connectionsClient.sendPayload(endpointIds, Payload.fromStream(loggingInputStream))
            .addOnSuccessListener {
                Log.i(
                    TAG,
                    "[sendStreamToEndpoints] Stream принят Nearby endpointCount=${endpointIds.size} elapsedMs=${System.currentTimeMillis() - sendStartedAtMillis}",
                )
            }
            .addOnFailureListener { cause ->
                Log.w(TAG, "[sendStreamToEndpoints] Не удалось отправить stream: ${cause.message}", cause)
                runCatching { loggingInputStream.close() }
                onFailure(cause)
            }
    }

    /**
     * Обрабатывает bytes payload как непрозрачное сообщение.
     */
    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        val bytes = payload.asBytes()
        if (bytes == null) {
            Log.w(TAG, "[handleBytesPayload] Payload bytes пустые endpointId=$endpointId")
            emitEvent(
                NearbyPayloadTransportEvent.PayloadReadFailed(
                    endpointId,
                    IllegalArgumentException("Payload bytes are null"),
                ),
            )
            return
        }

        Log.i(TAG, "[handleBytesPayload] Получен bytes payload endpointId=$endpointId bytes=${bytes.size}")
        emitEvent(NearbyPayloadTransportEvent.MessageReceived(endpointId, bytes))
    }

    /**
     * Обрабатывает stream payload как непрозрачный входящий поток.
     */
    private fun handleStreamPayload(endpointId: String, payload: Payload) {
        val receivedAtMillis = System.currentTimeMillis()
        val inputStream = payload.asStream()?.asInputStream()
        if (inputStream == null) {
            Log.w(TAG, "[handleStreamPayload] Stream payload без InputStream endpointId=$endpointId")
            emitEvent(
                NearbyPayloadTransportEvent.PayloadReadFailed(
                    endpointId,
                    IllegalArgumentException("Payload stream input is null"),
                ),
            )
            return
        }
        Log.i(TAG, "[handleStreamPayload] Получен stream endpointId=$endpointId receivedAtMs=$receivedAtMillis")
        emitEvent(NearbyPayloadTransportEvent.StreamReceived(endpointId, inputStream))
    }

    /**
     * Накопительно логирует успешную отправку realtime bytes payload-ов не чаще одного раза в секунду.
     */
    @Synchronized
    private fun recordRealtimeBytesSent(endpointCount: Int, bytes: Int) {
        val nowMillis = System.currentTimeMillis()
        if (realtimeLogWindowStartedAtMillis == 0L) {
            realtimeLogWindowStartedAtMillis = nowMillis
        }
        realtimePayloadCount += 1
        realtimeEndpointDeliveryCount += endpointCount
        realtimeByteCount += bytes.toLong()

        val windowMillis = nowMillis - realtimeLogWindowStartedAtMillis
        if (windowMillis < REALTIME_LOG_WINDOW_MILLIS) {
            return
        }

        Log.i(
            TAG,
            "[recordRealtimeBytesSent] Realtime BYTES переданы Nearby payloadCount=$realtimePayloadCount endpointDeliveries=$realtimeEndpointDeliveryCount bytes=$realtimeByteCount windowMs=$windowMillis",
        )
        realtimeLogWindowStartedAtMillis = 0L
        realtimePayloadCount = 0
        realtimeEndpointDeliveryCount = 0
        realtimeByteCount = 0L
    }

    /**
     * Оборачивает stream, ограничивает размер одного чтения Nearby и логирует первый реальный read.
     */
    private class NearbyStreamLoggingInputStream(
        delegate: InputStream,
        private val endpointCount: Int,
        private val sendStartedAtMillis: Long,
    ) : java.io.FilterInputStream(delegate) {
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
                "[read] Nearby впервые прочитал stream readBytes=$readBytes requestedBytes=$requestedBytes limitedBytes=$limitedBytes endpointCount=$endpointCount elapsedMs=${System.currentTimeMillis() - sendStartedAtMillis}",
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
                "[read] Stream закончился до первого чтения Nearby endpointCount=$endpointCount elapsedMs=${System.currentTimeMillis() - sendStartedAtMillis}",
            )
        }
    }

    private companion object {
        private const val TAG = "NearbyPayloadTransport"
        private const val STREAM_READ_LIMIT_BYTES = 320
        private const val REALTIME_LOG_WINDOW_MILLIS = 1_000L
        private const val HEARTBEAT_PACKET_BYTES = 4
    }
}

/**
 * Событие уровня Nearby payload без знания формата сообщения или назначения stream-а.
 */
internal sealed class NearbyPayloadTransportEvent {
    /**
     * Входящий bytes payload получен как непрозрачное сообщение.
     */
    data class MessageReceived(val endpointId: String, val bytes: ByteArray) : NearbyPayloadTransportEvent() {
        /**
         * Сравнивает byte-array по содержимому, потому что data class иначе сравнит ссылки.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as MessageReceived

            if (endpointId != other.endpointId) return false
            return bytes.contentEquals(other.bytes)
        }

        /**
         * Возвращает hash с учетом содержимого byte-array.
         */
        override fun hashCode(): Int {
            var result = endpointId.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    /**
     * Входящий stream payload открыт как InputStream.
     */
    data class StreamReceived(val endpointId: String, val inputStream: InputStream) : NearbyPayloadTransportEvent()

    /**
     * Payload не удалось прочитать из Nearby API.
     */
    data class PayloadReadFailed(val endpointId: String, val cause: Throwable) : NearbyPayloadTransportEvent()

    /**
     * Nearby прислал payload типа, который транспорт сейчас не поддерживает.
     */
    data class UnsupportedPayloadReceived(val endpointId: String, val payloadType: Int) : NearbyPayloadTransportEvent()

    /**
     * Nearby сообщил промежуточный статус передачи payload-а.
     */
    data class PayloadTransferUpdated(val endpointId: String, val update: PayloadTransferUpdate) : NearbyPayloadTransportEvent()
}
