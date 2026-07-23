package com.yellastro.btration.voice

import com.yellastro.btration.domain.model.PeerId

/**
 * Один Opus-фрейм PTT-сессии с исходным PeerId, UInt16 session/sequence и признаком завершения.
 */
data class VoiceFrame(
    val originPeerId: PeerId,
    val sessionId: Int,
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
            sessionId == other.sessionId &&
            sequence == other.sequence &&
            encodedBytes.contentEquals(other.encodedBytes) &&
            isFinal == other.isFinal
    }

    /**
     * Возвращает hash с учетом содержимого закодированных аудио-байтов.
     */
    override fun hashCode(): Int {
        var result = originPeerId.hashCode()
        result = 31 * result + sessionId
        result = 31 * result + sequence.hashCode()
        result = 31 * result + encodedBytes.contentHashCode()
        result = 31 * result + isFinal.hashCode()
        return result
    }
}
