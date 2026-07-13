package com.yellastro.btration.ui.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yellastro.btration.domain.model.ChatMessage
import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.runtime.DirectAudioStatus
import com.yellastro.btration.domain.runtime.RoomRuntimeState
import com.yellastro.btration.repository.RoomRepository
import com.yellastro.btration.repository.VoiceSettingsRepository
import com.yellastro.btration.voice.VoiceTransportPreference
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
 * ViewModel комнаты: собирает runtime, voice-настройки, direct-аудио статус, участников, чат и команды UI.
 */
class RoomViewModel(
    private val roomRepository: RoomRepository,
    private val voiceSettingsRepository: VoiceSettingsRepository,
) : ViewModel() {
    private val inputText = MutableStateFlow("")
    private val isTalking = MutableStateFlow(false)

    private val roomUiInputs = combine(
        roomRepository.runtimeState,
        roomRepository.messages,
        roomRepository.talkingPeerIds,
        inputText,
    ) { runtimeState: RoomRuntimeState,
        messages: List<ChatMessage>,
        talkingPeerIds: Set<PeerId>,
        input: String ->
        RoomUiInputs(
            runtimeState = runtimeState,
            messages = messages,
            talkingPeerIds = talkingPeerIds,
            input = input,
        )
    }

    /**
     * UI-состояние комнаты, собранное из runtime, voice-настроек, сообщений, talking-состояния и ввода.
     */
    val uiState: StateFlow<RoomUiState> = combine(
        roomUiInputs,
        isTalking,
        voiceSettingsRepository.voiceTransportPreference,
    ) { inputs: RoomUiInputs,
        talking: Boolean,
        voiceTransportPreference: VoiceTransportPreference ->
        mapUiState(
            runtimeState = inputs.runtimeState,
            messages = inputs.messages,
            talkingPeerIds = inputs.talkingPeerIds,
            input = inputs.input,
            talking = talking,
            voiceTransportPreference = voiceTransportPreference,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = mapUiState(
            runtimeState = roomRepository.runtimeState.value,
            messages = roomRepository.messages.value,
            talkingPeerIds = roomRepository.talkingPeerIds.value,
            input = inputText.value,
            talking = isTalking.value,
            voiceTransportPreference = voiceSettingsRepository.voiceTransportPreference.value,
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
     * Преобразует runtime-состояние комнаты в UI-состояние с явным статусом прямого аудиоканала.
     */
    private fun mapUiState(
        runtimeState: RoomRuntimeState,
        messages: List<ChatMessage>,
        talkingPeerIds: Set<PeerId>,
        input: String,
        talking: Boolean,
        voiceTransportPreference: VoiceTransportPreference,
    ): RoomUiState {
        val selfPeerId = roomRepository.getSelfPeerId()
        val canSend = input.isNotBlank() &&
            (runtimeState is RoomRuntimeState.Hosting || runtimeState is RoomRuntimeState.Client)
        val directAudioStatus = directAudioStatusOf(runtimeState)
        val canTalk = (runtimeState is RoomRuntimeState.Hosting || runtimeState is RoomRuntimeState.Client) &&
            directAudioStatus !is DirectAudioStatus.Unavailable

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
                voiceTransportPreference = voiceTransportPreference,
                roomVoiceTransportPreference = VoiceTransportPreference.fromTransportMode(runtimeState.room.voiceTransportMode),
                directAudioStatusText = directAudioStatusText(runtimeState.directAudioStatus),
                directAudioIssueMessage = directAudioIssueMessage(runtimeState.directAudioStatus),
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
                voiceTransportPreference = voiceTransportPreference,
                roomVoiceTransportPreference = VoiceTransportPreference.fromTransportMode(runtimeState.room.voiceTransportMode),
                directAudioStatusText = directAudioStatusText(runtimeState.directAudioStatus),
                directAudioIssueMessage = directAudioIssueMessage(runtimeState.directAudioStatus),
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
                voiceTransportPreference = voiceTransportPreference,
                roomVoiceTransportPreference = VoiceTransportPreference.fromTransportMode(runtimeState.room.voiceTransportMode),
                directAudioStatusText = directAudioStatusText(runtimeState.directAudioStatus),
                directAudioIssueMessage = directAudioIssueMessage(runtimeState.directAudioStatus),
            )

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            -> RoomUiState(
                inputText = input,
                canTalk = false,
                isTalking = false,
                isClosed = true,
                voiceTransportPreference = voiceTransportPreference,
                roomVoiceTransportPreference = voiceTransportPreference,
                directAudioStatusText = "",
            )

            is RoomRuntimeState.Error -> RoomUiState(
                inputText = input,
                canSend = false,
                canTalk = false,
                isTalking = false,
                isClosed = false,
                voiceTransportPreference = voiceTransportPreference,
                roomVoiceTransportPreference = voiceTransportPreference,
                directAudioStatusText = "",
                errorMessage = runtimeState.message,
                errorAction = runtimeState.action,
            )
        }
    }

    /**
     * Возвращает direct-аудио статус только для runtime-состояний, где transport-сессия имеет смысл.
     */
    private fun directAudioStatusOf(runtimeState: RoomRuntimeState): DirectAudioStatus? {
        return when (runtimeState) {
            is RoomRuntimeState.Hosting -> runtimeState.directAudioStatus
            is RoomRuntimeState.Joining -> runtimeState.directAudioStatus
            is RoomRuntimeState.Client -> runtimeState.directAudioStatus
            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Error,
            -> null
        }
    }

    /**
     * Формирует короткий текст для шапки комнаты из статуса прямого аудиоканала.
     */
    private fun directAudioStatusText(status: DirectAudioStatus?): String {
        return when (status) {
            DirectAudioStatus.Connecting -> "ПРЯМОЙ КАНАЛ • ПОДКЛЮЧЕНИЕ"
            DirectAudioStatus.Ready -> "ПРЯМОЙ КАНАЛ • ГОТОВ"
            is DirectAudioStatus.Unavailable -> "ПРЯМОЙ КАНАЛ • НЕ УСТАНОВЛЕН"
            null -> ""
        }
    }

    /**
     * Формирует предупреждение для UI, когда direct-аудио не установилось, но комната остается активной.
     */
    private fun directAudioIssueMessage(status: DirectAudioStatus?): String? {
        val unavailable = status as? DirectAudioStatus.Unavailable ?: return null
        return if (unavailable.message == DIRECT_AUDIO_UNAVAILABLE_MESSAGE) {
            "$DIRECT_AUDIO_UNAVAILABLE_MESSAGE. Проверьте VPN, частный DNS или сетевую защиту и переподключитесь."
        } else {
            unavailable.message
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

    /**
     * Промежуточный снимок runtime/UI-входов, чтобы не зависеть от перегрузок combine на много потоков.
     */
    private data class RoomUiInputs(
        val runtimeState: RoomRuntimeState,
        val messages: List<ChatMessage>,
        val talkingPeerIds: Set<PeerId>,
        val input: String,
    )

    private companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val TIME_PATTERN = "HH:mm"
        private const val DIRECT_AUDIO_UNAVAILABLE_MESSAGE = "Прямой аудиоканал не установлен"
    }
}
