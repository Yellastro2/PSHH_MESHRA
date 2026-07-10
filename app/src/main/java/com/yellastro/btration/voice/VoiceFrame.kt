package com.yellastro.btration.voice

import com.yellastro.btration.domain.model.PeerId

/**
 * Один короткий Opus-фрейм голоса для отправки через Nearby BYTES payload вместо длинного STREAM.
 */
data class VoiceFrame(
    val originPeerId: PeerId,
    val sequence: Long,
    val encodedBytes: ByteArray,
    val isFinal: Boolean,
) {
    /**
     * Сравнивает фреймы с учетом содержимого закодированных аудио-байтов.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceFrame) return false
        return originPeerId == other.originPeerId &&
            sequence == other.sequence &&
            encodedBytes.contentEquals(other.encodedBytes) &&
            isFinal == other.isFinal
    }

    /**
     * Возвращает hash с учетом содержимого закодированных аудио-байтов.
     */
    override fun hashCode(): Int {
        var result = originPeerId.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + encodedBytes.contentHashCode()
        result = 31 * result + isFinal.hashCode()
        return result
    }
}
