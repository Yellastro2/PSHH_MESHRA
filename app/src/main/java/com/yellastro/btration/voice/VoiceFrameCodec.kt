package com.yellastro.btration.voice

import com.yellastro.btration.domain.model.PeerId
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Кодирует и распознает короткие Opus voice frames BTVO внутри Nearby BYTES payload.
 */
object VoiceFrameCodec {
    /**
     * Возвращает true, если payload начинается с magic голосового фрейма.
     */
    fun isVoiceFrame(bytes: ByteArray): Boolean {
        if (bytes.size < Int.SIZE_BYTES) {
            return false
        }
        val magic = ((bytes[0].toInt() and BYTE_MASK) shl 24) or
            ((bytes[1].toInt() and BYTE_MASK) shl 16) or
            ((bytes[2].toInt() and BYTE_MASK) shl 8) or
            (bytes[3].toInt() and BYTE_MASK)
        return magic == MAGIC
    }

    /**
     * Кодирует voice frame в компактный бинарный payload.
     */
    fun encode(frame: VoiceFrame): ByteArray {
        val peerIdBytes = frame.originPeerId.value.encodeToByteArray()
        require(peerIdBytes.isNotEmpty() && peerIdBytes.size <= MAX_PEER_ID_BYTES) {
            "Некорректная длина PeerId voice frame: ${peerIdBytes.size}"
        }
        require(frame.encodedBytes.size <= MAX_ENCODED_AUDIO_BYTES) {
            "Слишком большой Opus voice frame: ${frame.encodedBytes.size}"
        }
        return ByteArrayOutputStream(HEADER_BYTES + peerIdBytes.size + frame.encodedBytes.size).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeLong(frame.sequence)
                output.writeBoolean(frame.isFinal)
                output.writeShort(peerIdBytes.size)
                output.writeShort(frame.encodedBytes.size)
                output.write(peerIdBytes)
                output.write(frame.encodedBytes)
            }
            buffer.toByteArray()
        }
    }

    /**
     * Декодирует voice frame из бинарного Nearby BYTES payload.
     */
    fun decode(bytes: ByteArray): VoiceFrame {
        DataInputStream(bytes.inputStream()).use { input ->
            val magic = input.readInt()
            require(magic == MAGIC) {
                "Неизвестный voice frame magic=$magic"
            }
            val sequence = input.readLong()
            val isFinal = input.readBoolean()
            val peerIdLength = input.readUnsignedShort()
            val encodedAudioLength = input.readUnsignedShort()
            require(peerIdLength in 1..MAX_PEER_ID_BYTES) {
                "Некорректная длина PeerId voice frame: $peerIdLength"
            }
            require(encodedAudioLength <= MAX_ENCODED_AUDIO_BYTES) {
                "Некорректная длина Opus voice frame: $encodedAudioLength"
            }
            val peerIdBytes = ByteArray(peerIdLength)
            input.readFully(peerIdBytes)
            val encodedBytes = ByteArray(encodedAudioLength)
            input.readFully(encodedBytes)
            return VoiceFrame(
                originPeerId = PeerId(peerIdBytes.decodeToString()),
                sequence = sequence,
                encodedBytes = encodedBytes,
                isFinal = isFinal,
            )
        }
    }

    private const val MAGIC = 0x4254564F
    private const val BYTE_MASK = 0xFF
    private const val MAX_PEER_ID_BYTES = 512
    private const val MAX_ENCODED_AUDIO_BYTES = 4_096
    private val HEADER_BYTES = Int.SIZE_BYTES + Long.SIZE_BYTES + 1 + Short.SIZE_BYTES + Short.SIZE_BYTES
}
