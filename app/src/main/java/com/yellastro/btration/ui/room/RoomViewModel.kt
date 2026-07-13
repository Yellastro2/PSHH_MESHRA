package com.yellastro.btration.ui.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yellastro.btration.R
import com.yellastro.btration.domain.model.ChatMessage
import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomTransportMode
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
 * ViewModel комнаты: собирает runtime, voice-настройки, mesh-connect статусы, участников, чат и команды UI.
 */
class RoomViewModel(
    private val roomRepository: RoomRepository,
    private val voiceSettingsRepository: VoiceSettingsRepository,
) : ViewModel() {
    private val inputText = MutableStateFlow("")
    private val isTalking = MutableStateFlow(false)
    private val participantColorByPeerId = mutableMapOf<PeerId, Int>()

    private val roomUiInputs = combine(
        roomRepository.runtimeState,
        roomRepository.messages,
        roomRepository.talkingPeerIds,
        roomRepository.directMeshPeerIds,
        inputText,
    ) { runtimeState: RoomRuntimeState,
        messages: List<ChatMessage>,
        talkingPeerIds: Set<PeerId>,
        directMeshPeerIds: Set<PeerId>,
        input: String ->
        RoomUiInputs(
            runtimeState = runtimeState,
            messages = messages,
            talkingPeerIds = talkingPeerIds,
            directMeshPeerIds = directMeshPeerIds,
            input = input,
        )
    }

    /**
     * UI-состояние комнаты, собранное из runtime, voice-настроек, сообщений, talking/direct-connect состояний и ввода.
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
            directMeshPeerIds = inputs.directMeshPeerIds,
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
            directMeshPeerIds = roomRepository.directMeshPeerIds.value,
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
     * Преобразует runtime-состояние комнаты в UI-состояние с явным типом комнаты и статусом прямого аудиоканала.
     */
    private fun mapUiState(
        runtimeState: RoomRuntimeState,
        messages: List<ChatMessage>,
        talkingPeerIds: Set<PeerId>,
        directMeshPeerIds: Set<PeerId>,
        input: String,
        talking: Boolean,
        voiceTransportPreference: VoiceTransportPreference,
    ): RoomUiState {
        val selfPeerId = roomRepository.getSelfPeerId()
        val canSend = input.isNotBlank() &&
            (runtimeState is RoomRuntimeState.Hosting || runtimeState is RoomRuntimeState.Client)
        val directAudioStatus = directAudioStatusOf(runtimeState)
        val isMeshActiveRoom = when (runtimeState) {
            is RoomRuntimeState.Hosting -> runtimeState.room.roomTransportMode == RoomTransportMode.MESHRA
            is RoomRuntimeState.Client -> runtimeState.room.roomTransportMode == RoomTransportMode.MESHRA
            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> false
        }
        val canTalk = (runtimeState is RoomRuntimeState.Hosting || runtimeState is RoomRuntimeState.Client) &&
            directAudioStatus !is DirectAudioStatus.Unavailable &&
            (!isMeshActiveRoom || directMeshPeerIds.isNotEmpty())

        return when (runtimeState) {
            is RoomRuntimeState.Hosting -> {
                val roomVoiceTransportPreference = VoiceTransportPreference.fromTransportMode(runtimeState.room.voiceTransportMode)
                val isMeshRoom = runtimeState.room.roomTransportMode == RoomTransportMode.MESHRA
                val participantColors = ensureParticipantColors(runtimeState.members, selfPeerId)
                RoomUiState(
                    roomName = runtimeState.room.name,
                    isHost = true,
                    isMeshRoom = isMeshRoom,
                    members = runtimeState.members.map { mapMember(it, selfPeerId, talkingPeerIds, directMeshPeerIds, participantColors, talking, isMeshRoom) },
                    messages = messages.map { mapMessage(it, selfPeerId, participantColors.values.toSet()) },
                    inputText = input,
                    canSend = canSend,
                    canTalk = canTalk,
                    isTalking = talking,
                    isClosed = false,
                    voiceTransportPreference = voiceTransportPreference,
                    roomVoiceTransportPreference = roomVoiceTransportPreference,
                    directAudioStatusText = directAudioStatusText(runtimeState.directAudioStatus, roomVoiceTransportPreference, runtimeState.room.roomTransportMode),
                    directAudioIssueMessage = directAudioIssueMessage(runtimeState.directAudioStatus),
                    errorMessage = null,
                )
            }

            is RoomRuntimeState.Client -> {
                val roomVoiceTransportPreference = VoiceTransportPreference.fromTransportMode(runtimeState.room.voiceTransportMode)
                val isMeshRoom = runtimeState.room.roomTransportMode == RoomTransportMode.MESHRA
                val participantColors = ensureParticipantColors(runtimeState.members, selfPeerId)
                RoomUiState(
                    roomName = runtimeState.room.name,
                    isHost = false,
                    isMeshRoom = isMeshRoom,
                    members = runtimeState.members.map { mapMember(it, selfPeerId, talkingPeerIds, directMeshPeerIds, participantColors, talking, isMeshRoom) },
                    messages = messages.map { mapMessage(it, selfPeerId, participantColors.values.toSet()) },
                    inputText = input,
                    canSend = canSend,
                    canTalk = canTalk,
                    isTalking = talking,
                    isClosed = false,
                    voiceTransportPreference = voiceTransportPreference,
                    roomVoiceTransportPreference = roomVoiceTransportPreference,
                    directAudioStatusText = directAudioStatusText(runtimeState.directAudioStatus, roomVoiceTransportPreference, runtimeState.room.roomTransportMode),
                    directAudioIssueMessage = directAudioIssueMessage(runtimeState.directAudioStatus),
                    errorMessage = null,
                )
            }

            is RoomRuntimeState.Joining -> {
                val roomVoiceTransportPreference = VoiceTransportPreference.fromTransportMode(runtimeState.room.voiceTransportMode)
                RoomUiState(
                    roomName = runtimeState.room.name,
                    isMeshRoom = runtimeState.room.roomTransportMode == RoomTransportMode.MESHRA,
                    inputText = input,
                    canSend = false,
                    canTalk = false,
                    isConnecting = true,
                    isTalking = false,
                    isClosed = false,
                    voiceTransportPreference = voiceTransportPreference,
                    roomVoiceTransportPreference = roomVoiceTransportPreference,
                    directAudioStatusText = directAudioStatusText(runtimeState.directAudioStatus, roomVoiceTransportPreference, runtimeState.room.roomTransportMode),
                    directAudioIssueMessage = directAudioIssueMessage(runtimeState.directAudioStatus),
                )
            }

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
     * Формирует короткий текст для шапки комнаты из статуса выбранного voice transport.
     */
    private fun directAudioStatusText(
        status: DirectAudioStatus?,
        roomVoiceTransportPreference: VoiceTransportPreference,
        roomTransportMode: RoomTransportMode,
    ): String {
        val transportName = if (roomTransportMode == RoomTransportMode.MESHRA) {
            MESH_VOICE_TRANSPORT_NAME
        } else {
            roomVoiceTransportPreference.shortName.uppercase(Locale.ROOT)
        }
        return when (status) {
            DirectAudioStatus.Connecting -> "$transportName • ПОДКЛЮЧЕНИЕ"
            DirectAudioStatus.Ready -> "$transportName • ГОТОВ"
            is DirectAudioStatus.Unavailable -> "$transportName • НЕ УСТАНОВЛЕН"
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
     * Преобразует участника в UI-модель с локальной ролью, цветом, mesh-connect и voice stream состояниями.
     */
    private fun mapMember(
        peer: Peer,
        selfPeerId: PeerId,
        talkingPeerIds: Set<PeerId>,
        directMeshPeerIds: Set<PeerId>,
        participantColors: Map<PeerId, Int>,
        selfTalking: Boolean,
        isMeshRoom: Boolean,
    ): MemberUi {
        return MemberUi(
            peerId = peer.peerId,
            name = peer.name,
            isSelf = peer.peerId == selfPeerId,
            participantColorResId = participantColors[peer.peerId],
            isConnectIndicatorVisible = isMeshRoom,
            isDirectlyConnected = peer.peerId in directMeshPeerIds || (peer.peerId == selfPeerId && directMeshPeerIds.isNotEmpty()),
            isTalking = peer.peerId in talkingPeerIds || (peer.peerId == selfPeerId && selfTalking),
        )
    }

    /**
     * Преобразует доменное сообщение в UI-модель чата и назначает цвет bubble для чужого автора.
     */
    private fun mapMessage(message: ChatMessage, selfPeerId: PeerId, reservedColorResIds: Set<Int>): ChatMessageUi {
        return ChatMessageUi(
            id = message.messageId,
            senderName = message.author.name,
            text = message.text,
            isOwn = message.author.peerId == selfPeerId,
            participantColorResId = colorForMessageAuthor(message.author.peerId, selfPeerId, reservedColorResIds),
            timeText = formatTime(message.createdAtMillis),
        )
    }

    /**
     * Назначает текущим не-self участникам случайные цвета без повторов, пока участников не больше палетки.
     */
    private fun ensureParticipantColors(members: List<Peer>, selfPeerId: PeerId): Map<PeerId, Int> {
        val currentPeerIds = members
            .map { peer -> peer.peerId }
            .filterNot { peerId -> peerId == selfPeerId }
            .distinct()
        val usedColorResIds = mutableSetOf<Int>()
        val currentColors = mutableMapOf<PeerId, Int>()

        currentPeerIds.forEach { peerId ->
            val existingColor = participantColorByPeerId[peerId]
            if (existingColor != null && existingColor !in usedColorResIds) {
                currentColors[peerId] = existingColor
                usedColorResIds.add(existingColor)
            }
        }

        currentPeerIds
            .filterNot { peerId -> peerId in currentColors }
            .forEach { peerId ->
                val nextColor = randomParticipantColorExcept(usedColorResIds)
                participantColorByPeerId[peerId] = nextColor
                currentColors[peerId] = nextColor
                usedColorResIds.add(nextColor)
            }

        return currentColors
    }

    /**
     * Возвращает цвет автора сообщения; self-сообщения остаются в собственной стандартной стилистике.
     */
    private fun colorForMessageAuthor(peerId: PeerId, selfPeerId: PeerId, reservedColorResIds: Set<Int>): Int? {
        if (peerId == selfPeerId) {
            return null
        }
        val existingColor = participantColorByPeerId[peerId]
        if (existingColor != null) {
            return existingColor
        }
        val nextColor = randomParticipantColorExcept(reservedColorResIds)
        participantColorByPeerId[peerId] = nextColor
        return nextColor
    }

    /**
     * Выбирает случайный цвет из палетки, избегая уже занятых цветов, если свободные еще есть.
     */
    private fun randomParticipantColorExcept(usedColorResIds: Set<Int>): Int {
        val availableColors = PARTICIPANT_COLOR_RES_IDS.filterNot { colorResId -> colorResId in usedColorResIds }
        return (availableColors.ifEmpty { PARTICIPANT_COLOR_RES_IDS }).random()
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
        val directMeshPeerIds: Set<PeerId>,
        val input: String,
    )

    private companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val TIME_PATTERN = "HH:mm"
        private const val DIRECT_AUDIO_UNAVAILABLE_MESSAGE = "Прямой аудиоканал не установлен"
        private const val MESH_VOICE_TRANSPORT_NAME = "MESH"
        private val PARTICIPANT_COLOR_RES_IDS = listOf(
            R.color.participant_color_03,
            R.color.participant_color_04,
            R.color.participant_color_05,
            R.color.participant_color_06,
            R.color.participant_color_07,
            R.color.participant_color_08,
            R.color.participant_color_09,
            R.color.participant_color_10,
        )
    }
}
