package com.yellastro.btration.ui.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yellastro.btration.domain.model.ChatMessage
import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.runtime.RoomRuntimeState
import com.yellastro.btration.repository.RoomRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel комнаты: показывает connecting, участников и чат, затем открывает команды сообщения и PTT.
 */
class RoomViewModel(
    private val roomRepository: RoomRepository,
) : ViewModel() {
    private val inputText = MutableStateFlow("")
    private val isTalking = MutableStateFlow(false)

    /**
     * UI-состояние комнаты, собранное из runtime, сообщений и текущего ввода.
     */
    val uiState: StateFlow<RoomUiState> = combine(
        roomRepository.runtimeState,
        roomRepository.messages,
        roomRepository.talkingPeerIds,
        inputText,
        isTalking,
    ) { runtimeState: RoomRuntimeState,
        messages: List<ChatMessage>,
        talkingPeerIds: Set<PeerId>,
        input: String,
        talking: Boolean ->
        mapUiState(runtimeState, messages, talkingPeerIds, input, talking)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = mapUiState(
            runtimeState = roomRepository.runtimeState.value,
            messages = roomRepository.messages.value,
            talkingPeerIds = roomRepository.talkingPeerIds.value,
            input = inputText.value,
            talking = isTalking.value,
        ),
    )

    /**
     * Одноразовые уведомления runtime, которые экран комнаты показывает через snackbar.
     */
    val notices = roomRepository.notices

    /**
     * Обновляет текст в поле ввода.
     */
    fun onMessageChanged(value: String) {
        inputText.value = value
    }

    /**
     * Отправляет сообщение и очищает поле ввода только если runtime сейчас готов к отправке.
     */
    fun onSendClicked() {
        val text = inputText.value.trim()
        if (text.isBlank() || !uiState.value.canSend) {
            return
        }

        viewModelScope.launch {
            roomRepository.sendMessage(text)
            inputText.value = ""
        }
    }

    /**
     * Покидает текущую комнату.
     */
    fun onLeaveClicked() {
        viewModelScope.launch {
            roomRepository.leaveRoom()
        }
    }

    /**
     * Закрывает хостимую комнату.
     */
    fun onCloseRoomClicked() {
        viewModelScope.launch {
            roomRepository.closeRoom()
        }
    }

    /**
     * Начинает передачу микрофона, если runtime сейчас находится в активной комнате.
     */
    fun onMicPressed() {
        if (!uiState.value.canTalk || isTalking.value) {
            return
        }
        viewModelScope.launch {
            isTalking.value = roomRepository.startTalking()
        }
    }

    /**
     * Останавливает передачу микрофона.
     */
    fun onMicReleased() {
        if (!isTalking.value) {
            return
        }
        isTalking.value = false
        viewModelScope.launch {
            roomRepository.stopTalking()
        }
    }

    /**
     * Преобразует runtime-состояние комнаты в UI-состояние.
     */
    private fun mapUiState(
        runtimeState: RoomRuntimeState,
        messages: List<ChatMessage>,
        talkingPeerIds: Set<PeerId>,
        input: String,
        talking: Boolean,
    ): RoomUiState {
        val selfPeerId = roomRepository.getSelfPeerId()
        val canSend = input.isNotBlank() &&
            (runtimeState is RoomRuntimeState.Hosting || runtimeState is RoomRuntimeState.Client)
        val canTalk = runtimeState is RoomRuntimeState.Hosting || runtimeState is RoomRuntimeState.Client

        return when (runtimeState) {
            is RoomRuntimeState.Hosting -> RoomUiState(
                roomName = runtimeState.room.name,
                isHost = true,
                members = runtimeState.members.map { mapMember(it, selfPeerId, talkingPeerIds, talking) },
                messages = messages.map { mapMessage(it, selfPeerId) },
                inputText = input,
                canSend = canSend,
                canTalk = canTalk,
                isTalking = talking,
                isClosed = false,
                errorMessage = null,
            )

            is RoomRuntimeState.Client -> RoomUiState(
                roomName = runtimeState.room.name,
                isHost = false,
                members = runtimeState.members.map { mapMember(it, selfPeerId, talkingPeerIds, talking) },
                messages = messages.map { mapMessage(it, selfPeerId) },
                inputText = input,
                canSend = canSend,
                canTalk = canTalk,
                isTalking = talking,
                isClosed = false,
                errorMessage = null,
            )

            is RoomRuntimeState.Joining -> RoomUiState(
                roomName = runtimeState.room.name,
                inputText = input,
                canSend = false,
                canTalk = false,
                isConnecting = true,
                isTalking = false,
                isClosed = false,
            )

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            -> RoomUiState(
                inputText = input,
                canTalk = false,
                isTalking = false,
                isClosed = true,
            )

            is RoomRuntimeState.Error -> RoomUiState(
                inputText = input,
                canSend = false,
                canTalk = false,
                isTalking = false,
                isClosed = false,
                errorMessage = runtimeState.message,
                errorAction = runtimeState.action,
            )
        }
    }

    /**
     * Преобразует участника в UI-модель с признаком локального пользователя и активности voice stream.
     */
    private fun mapMember(
        peer: Peer,
        selfPeerId: PeerId,
        talkingPeerIds: Set<PeerId>,
        selfTalking: Boolean,
    ): MemberUi {
        return MemberUi(
            peerId = peer.peerId,
            name = peer.name,
            isSelf = peer.peerId == selfPeerId,
            isTalking = peer.peerId in talkingPeerIds || (peer.peerId == selfPeerId && selfTalking),
        )
    }

    /**
     * Преобразует доменное сообщение в UI-модель чата.
     */
    private fun mapMessage(message: ChatMessage, selfPeerId: PeerId): ChatMessageUi {
        return ChatMessageUi(
            id = message.messageId,
            senderName = message.author.name,
            text = message.text,
            isOwn = message.author.peerId == selfPeerId,
            timeText = formatTime(message.createdAtMillis),
        )
    }

    /**
     * Форматирует timestamp сообщения в локальное HH:mm.
     */
    private fun formatTime(timestampMillis: Long): String {
        return SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(timestampMillis))
    }

    private companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val TIME_PATTERN = "HH:mm"
    }
}
