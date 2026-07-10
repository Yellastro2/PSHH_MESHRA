package com.yellastro.btration.voice

import com.yellastro.btration.domain.model.PeerId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * Добавляет к PCM-stream короткий бинарный заголовок с исходным PeerId и читает его на принимающей стороне.
 */
object VoiceStreamCodec {
    /**
     * Возвращает stream из заголовка BTV1 и исходного PCM без копирования всего голосового потока в память.
     */
    fun frame(originPeerId: PeerId, pcmInputStream: InputStream): InputStream {
        val peerIdBytes = originPeerId.value.encodeToByteArray()
        require(peerIdBytes.isNotEmpty() && peerIdBytes.size <= MAX_PEER_ID_BYTES) {
            "Некорректная длина PeerId голосового stream: ${peerIdBytes.size}"
        }
        val headerBytes = ByteArrayOutputStream(HEADER_FIXED_BYTES + peerIdBytes.size).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeShort(peerIdBytes.size)
                output.write(peerIdBytes)
            }
            buffer.toByteArray()
        }
        return SequenceInputStream(ByteArrayInputStream(headerBytes), pcmInputStream)
    }

    /**
     * Читает исходный PeerId и оставляет переданный stream установленным на первом PCM-байте.
     */
    fun readOriginPeerId(framedInputStream: InputStream): PeerId {
        val input = DataInputStream(framedInputStream)
        val magic = input.readInt()
        require(magic == MAGIC) {
            "Неизвестный заголовок голосового stream: magic=$magic"
        }
        val peerIdLength = input.readUnsignedShort()
        require(peerIdLength in 1..MAX_PEER_ID_BYTES) {
            "Некорректная длина PeerId голосового stream: $peerIdLength"
        }
        val peerIdBytes = ByteArray(peerIdLength)
        input.readFully(peerIdBytes)
        return PeerId(peerIdBytes.decodeToString())
    }

    private const val MAGIC = 0x42545631
    private const val HEADER_FIXED_BYTES = Int.SIZE_BYTES + Short.SIZE_BYTES
    private const val MAX_PEER_ID_BYTES = 512
}
