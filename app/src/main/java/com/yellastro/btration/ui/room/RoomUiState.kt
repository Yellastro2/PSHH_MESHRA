package com.yellastro.btration.ui.room

import androidx.annotation.ColorRes
import com.yellastro.btration.domain.model.MessageId
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.runtime.RoomRuntimeErrorAction
import com.yellastro.btration.voice.VoiceTransportPreference

/**
 * UI-состояние экрана комнаты, включая роль пользователя, mesh-режим комнаты, voice-настройку, чат и ошибки.
 */
data class RoomUiState(
    val roomName: String = "",
    val isHost: Boolean = false,
    val isMeshRoom: Boolean = false,
    val members: List<MemberUi> = emptyList(),
    val messages: List<ChatMessageUi> = emptyList(),
    val inputText: String = "",
    val canSend: Boolean = false,
    val canTalk: Boolean = false,
    val isConnecting: Boolean = false,
    val isTalking: Boolean = false,
    val isClosed: Boolean = false,
    val voiceTransportPreference: VoiceTransportPreference = VoiceTransportPreference.WIFI_DIRECT,
    val roomVoiceTransportPreference: VoiceTransportPreference = voiceTransportPreference,
    val directAudioStatusText: String = "",
    val directAudioIssueMessage: String? = null,
    val errorMessage: String? = null,
    val errorAction: RoomRuntimeErrorAction? = null,
)

/**
 * UI-модель участника комнаты с participant-цветом, voice-активностью и прямым mesh-connect статусом.
 */
data class MemberUi(
    val peerId: PeerId,
    val name: String,
    val isSelf: Boolean,
    @ColorRes val participantColorResId: Int? = null,
    val isConnectIndicatorVisible: Boolean = false,
    val isDirectlyConnected: Boolean = false,
    val isTalking: Boolean = false,
)

/**
 * UI-модель сообщения чата с опциональным цветом автора для чужих сообщений.
 */
data class ChatMessageUi(
    val id: MessageId,
    val senderName: String,
    val text: String,
    val isOwn: Boolean,
    @ColorRes val participantColorResId: Int? = null,
    val timeText: String,
)
