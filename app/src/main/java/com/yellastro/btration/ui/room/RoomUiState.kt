package com.yellastro.btration.ui.room

import com.yellastro.btration.domain.model.MessageId
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.runtime.RoomRuntimeErrorAction

/**
 * UI-состояние экрана комнаты.
 */
data class RoomUiState(
    val roomName: String = "",
    val isHost: Boolean = false,
    val members: List<MemberUi> = emptyList(),
    val messages: List<ChatMessageUi> = emptyList(),
    val inputText: String = "",
    val canSend: Boolean = false,
    val isClosed: Boolean = false,
    val errorMessage: String? = null,
    val errorAction: RoomRuntimeErrorAction? = null,
)

/**
 * UI-модель участника комнаты.
 */
data class MemberUi(
    val peerId: PeerId,
    val name: String,
    val isSelf: Boolean,
)

/**
 * UI-модель сообщения чата.
 */
data class ChatMessageUi(
    val id: MessageId,
    val senderName: String,
    val text: String,
    val isOwn: Boolean,
    val timeText: String,
)
