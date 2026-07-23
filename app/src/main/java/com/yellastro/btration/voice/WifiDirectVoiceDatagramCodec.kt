package com.yellastro.btration.voice

import com.yellastro.btration.domain.model.PeerId
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Кодирует Wi-Fi Direct UDP: handshake несет полный PeerId один раз, а FRAME — только compact Star voice packet.
 */
class WifiDirectVoiceDatagramCodec(
    private val voicePacketCodec: CompactVoicePacketCodec,
) {
    /**
     * Кодирует HELLO-датаграмму, по которой host узнает PeerId и UDP endpoint участника.
     */
    fun encodeHello(senderPeerId: PeerId): ByteArray {
        return encodeHandshake(senderPeerId, TYPE_HELLO)
    }

    /**
     * Кодирует подтверждение HELLO, по которому client проверяет двустороннюю UDP-связность.
     */
    fun encodeHelloAck(senderPeerId: PeerId): ByteArray {
        return encodeHandshake(senderPeerId, TYPE_HELLO_ACK)
    }

    /**
     * Кодирует handshake с полным PeerId; voice FRAME больше этот идентификатор не повторяет.
     */
    private fun encodeHandshake(senderPeerId: PeerId, type: Int): ByteArray {
        val senderBytes = senderPeerId.value.encodeToByteArray()
        require(senderBytes.isNotEmpty() && senderBytes.size <= MAX_PEER_ID_BYTES) {
            "Некорректная длина PeerId Wi-Fi Direct handshake: ${senderBytes.size}"
        }
        return ByteArrayOutputStream(HANDSHAKE_HEADER_BYTES + senderBytes.size).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(type)
                output.writeShort(senderBytes.size)
                output.write(senderBytes)
            }
            buffer.toByteArray()
        }
    }

    /**
     * Кодирует FRAME как пятибайтовую UDP-обертку и общий compact Star voice packet.
     */
    fun encodeFrame(packet: CompactVoicePacket): ByteArray {
        val frameBytes = voicePacketCodec.encode(packet)
        require(frameBytes.size <= MAX_FRAME_BYTES) {
            "Слишком большая Wi-Fi Direct voice datagram: ${frameBytes.size}"
        }
        return ByteArrayOutputStream(FRAME_HEADER_BYTES + frameBytes.size).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(TYPE_FRAME)
                output.write(frameBytes)
            }
            buffer.toByteArray()
        }
    }

    /**
     * Декодирует handshake или compact FRAME из фактической длины входящей UDP-датаграммы.
     */
    fun decode(bytes: ByteArray, length: Int): WifiDirectVoiceDatagram {
        require(length in FRAME_HEADER_BYTES..bytes.size) {
            "Некорректная длина Wi-Fi Direct datagram: $length"
        }
        DataInputStream(bytes.inputStream(0, length)).use { input ->
            val magic = input.readInt()
            require(magic == MAGIC) {
                "Неизвестный Wi-Fi Direct voice magic=$magic"
            }
            return when (val type = input.readUnsignedByte()) {
                TYPE_HELLO -> WifiDirectVoiceDatagram.Hello(readHandshakePeerId(input))
                TYPE_HELLO_ACK -> WifiDirectVoiceDatagram.HelloAck(readHandshakePeerId(input))
                TYPE_FRAME -> {
                    val frameLength = length - FRAME_HEADER_BYTES
                    require(frameLength in CompactVoicePacketCodec.HEADER_SIZE..MAX_FRAME_BYTES) {
                        "Некорректная длина Wi-Fi Direct voice frame: $frameLength"
                    }
                    val frameBytes = ByteArray(frameLength)
                    input.readFully(frameBytes)
                    WifiDirectVoiceDatagram.Frame(voicePacketCodec.decode(frameBytes))
                }

                else -> error("Неизвестный тип Wi-Fi Direct voice datagram=$type")
            }
        }
    }

    /**
     * Читает и валидирует полный PeerId из HELLO/HELLO_ACK.
     */
    private fun readHandshakePeerId(input: DataInputStream): PeerId {
        val senderLength = input.readUnsignedShort()
        require(senderLength in 1..MAX_PEER_ID_BYTES) {
            "Некорректная длина PeerId Wi-Fi Direct handshake: $senderLength"
        }
        val senderBytes = ByteArray(senderLength)
        input.readFully(senderBytes)
        return PeerId(senderBytes.decodeToString())
    }

    private companion object {
        private const val MAGIC = 0x42545655
        private const val TYPE_HELLO = 1
        private const val TYPE_FRAME = 2
        private const val TYPE_HELLO_ACK = 3
        private const val MAX_PEER_ID_BYTES = 512
        private const val MAX_FRAME_BYTES = 8_192
        private const val FRAME_HEADER_BYTES = Int.SIZE_BYTES + 1
        private const val HANDSHAKE_HEADER_BYTES = FRAME_HEADER_BYTES + Short.SIZE_BYTES
    }
}

/**
 * Декодированная Wi-Fi Direct datagram: handshake с PeerId или compact voice frame без повторного UUID.
 */
sealed class WifiDirectVoiceDatagram {
    /**
     * HELLO от прямого участника, по которому запоминается его UDP endpoint.
     */
    data class Hello(
        val senderPeerId: PeerId,
    ) : WifiDirectVoiceDatagram()

    /**
     * Ответ host-а, подтверждающий получение HELLO и обратную UDP-доступность.
     */
    data class HelloAck(
        val senderPeerId: PeerId,
    ) : WifiDirectVoiceDatagram()

    /**
     * Compact voice packet, автор и прямой сосед которого разрешаются transport-ом по room index/UDP endpoint.
     */
    data class Frame(
        val packet: CompactVoicePacket,
    ) : WifiDirectVoiceDatagram()
}
