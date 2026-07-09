package com.yellastro.btration.domain.model

import kotlinx.serialization.Serializable

/**
 * Текстовое сообщение комнаты с автором и локальным временем создания.
 */
@Serializable
data class ChatMessage(
    val messageId: MessageId,
    val roomId: RoomId,
    val author: Peer,
    val text: String,
    val createdAtMillis: Long,
)
