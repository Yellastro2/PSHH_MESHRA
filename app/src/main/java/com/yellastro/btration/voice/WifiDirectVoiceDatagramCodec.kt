package com.yellastro.btration.voice

import com.yellastro.btration.domain.model.PeerId
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Кодирует UDP-датаграммы Wi-Fi Direct voice transport: HELLO для привязки PeerId к IP и FRAME для Opus voice frame.
 */
object WifiDirectVoiceDatagramCodec {
    /**
     * Кодирует HELLO-датаграмму, по которой host узнает IP участника.
     */
    fun encodeHello(senderPeerId: PeerId): ByteArray {
        val senderBytes = senderPeerId.value.encodeToByteArray()
        require(senderBytes.isNotEmpty() && senderBytes.size <= MAX_PEER_ID_BYTES) {
            "Некорректная длина PeerId Wi-Fi Direct HELLO: ${senderBytes.size}"
        }
        return ByteArrayOutputStream(HEADER_BYTES + senderBytes.size).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(TYPE_HELLO)
                output.writeShort(senderBytes.size)
                output.writeInt(0)
                output.write(senderBytes)
            }
            buffer.toByteArray()
        }
    }

    /**
     * Кодирует FRAME-датаграмму с PeerId прямого отправителя и бинарным BTVO-фреймом.
     */
    fun encodeFrame(senderPeerId: PeerId, frame: VoiceFrame): ByteArray {
        val senderBytes = senderPeerId.value.encodeToByteArray()
        val frameBytes = VoiceFrameCodec.encode(frame)
        require(senderBytes.isNotEmpty() && senderBytes.size <= MAX_PEER_ID_BYTES) {
            "Некорректная длина PeerId Wi-Fi Direct FRAME: ${senderBytes.size}"
        }
        require(frameBytes.size <= MAX_FRAME_BYTES) {
            "Слишком большая Wi-Fi Direct voice datagram: ${frameBytes.size}"
        }
        return ByteArrayOutputStream(HEADER_BYTES + senderBytes.size + frameBytes.size).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(TYPE_FRAME)
                output.writeShort(senderBytes.size)
                output.writeInt(frameBytes.size)
                output.write(senderBytes)
                output.write(frameBytes)
            }
            buffer.toByteArray()
        }
    }

    /**
     * Декодирует входящую UDP-датаграмму Wi-Fi Direct voice transport.
     */
    fun decode(bytes: ByteArray, length: Int): WifiDirectVoiceDatagram {
        DataInputStream(bytes.inputStream(0, length)).use { input ->
            val magic = input.readInt()
            require(magic == MAGIC) {
                "Неизвестный Wi-Fi Direct voice magic=$magic"
            }
            val type = input.readUnsignedByte()
            val senderLength = input.readUnsignedShort()
            val frameLength = input.readInt()
            require(senderLength in 1..MAX_PEER_ID_BYTES) {
                "Некорректная длина PeerId Wi-Fi Direct datagram: $senderLength"
            }
            require(frameLength in 0..MAX_FRAME_BYTES) {
                "Некорректная длина Wi-Fi Direct voice frame: $frameLength"
            }
            val senderBytes = ByteArray(senderLength)
            input.readFully(senderBytes)
            val senderPeerId = PeerId(senderBytes.decodeToString())
            return when (type) {
                TYPE_HELLO -> WifiDirectVoiceDatagram.Hello(senderPeerId)
                TYPE_FRAME -> {
                    val frameBytes = ByteArray(frameLength)
                    input.readFully(frameBytes)
                    WifiDirectVoiceDatagram.Frame(
                        senderPeerId = senderPeerId,
                        frame = VoiceFrameCodec.decode(frameBytes),
                    )
                }

                else -> error("Неизвестный тип Wi-Fi Direct voice datagram=$type")
            }
        }
    }

    private const val MAGIC = 0x42545655
    private const val TYPE_HELLO = 1
    private const val TYPE_FRAME = 2
    private const val MAX_PEER_ID_BYTES = 512
    private const val MAX_FRAME_BYTES = 8_192
    private val HEADER_BYTES = Int.SIZE_BYTES + 1 + Short.SIZE_BYTES + Int.SIZE_BYTES
}

/**
 * Декодированная UDP-датаграмма Wi-Fi Direct voice transport.
 */
sealed class WifiDirectVoiceDatagram {
    /**
     * HELLO от прямого участника, по которому запоминается его UDP endpoint.
     */
    data class Hello(
        val senderPeerId: PeerId,
    ) : WifiDirectVoiceDatagram()

    /**
     * Voice frame от прямого участника.
     */
    data class Frame(
        val senderPeerId: PeerId,
        val frame: VoiceFrame,
    ) : WifiDirectVoiceDatagram()
}
