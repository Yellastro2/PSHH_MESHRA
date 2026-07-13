package com.yellastro.btration.ui.lobby

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.model.RoomTransportMode
import com.yellastro.btration.domain.runtime.RoomRuntimeState
import com.yellastro.btration.repository.IgnoredNearbyRepository
import com.yellastro.btration.repository.ProfileRepository
import com.yellastro.btration.repository.RoomRepository
import com.yellastro.btration.repository.RoomSettingsRepository
import com.yellastro.btration.repository.VoiceSettingsRepository
import com.yellastro.btration.voice.VoiceTransportPreference
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel лобби: редактирует локальное имя, держит discovery-циклы, создает комнаты и входит в них.
 */
class LobbyViewModel(
    private val roomRepository: RoomRepository,
    private val profileRepository: ProfileRepository,
    private val ignoredNearbyRepository: IgnoredNearbyRepository,
    private val roomSettingsRepository: RoomSettingsRepository,
    private val voiceSettingsRepository: VoiceSettingsRepository,
) : ViewModel() {
    private var searchRefreshJob: Job? = null
    private val nameEditorState = MutableStateFlow(initialNameEditorState())
    private val scanCycleId = MutableStateFlow(0L)

    /**
     * UI-состояние лобби, собранное из RoomRuntime и локального профиля.
     */
    val uiState: StateFlow<LobbyUiState> = combine(
        roomRepository.runtimeState,
        roomRepository.availableRooms,
        nameEditorState,
        scanCycleId,
        ignoredNearbyRepository.ignoredPeerIds,
    ) { runtimeState, rooms, nameEditor, cycleId, ignoredPeerIds ->
        mapUiState(runtimeState, rooms, nameEditor, cycleId, ignoredPeerIds)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = mapUiState(
            roomRepository.runtimeState.value,
            roomRepository.availableRooms.value,
            nameEditorState.value,
            scanCycleId.value,
            ignoredNearbyRepository.ignoredPeerIds.value,
        ),
    )

    /**
     * Одноразовые уведомления runtime, которые лобби показывает через snackbar.
     */
    val notices = roomRepository.notices

    /**
     * Переводит приветствие в режим редактирования сохраненного имени.
     */
    fun onEditNameClicked() {
        nameEditorState.value = nameEditorState.value.copy(
            inputName = nameEditorState.value.savedName,
            isEditing = true,
        )
    }

    /**
     * Обновляет текст локального имени во время редактирования.
     */
    fun onNameChanged(value: String) {
        if (!nameEditorState.value.isEditing) {
            return
        }
        nameEditorState.value = nameEditorState.value.copy(inputName = value)
    }

    /**
     * Валидирует и сохраняет новое имя, после чего возвращает приветствие в обычный режим.
     */
    fun onSaveNameClicked() {
        val cleanName = nameEditorState.value.inputName.trim()
        if (cleanName.isBlank() || cleanName.length > MAX_NAME_LENGTH) {
            return
        }
        profileRepository.setPeerName(cleanName)
        nameEditorState.value = NameEditorState(
            savedName = cleanName,
            inputName = cleanName,
            isEditing = false,
        )
    }

    /**
     * Запускает поиск комнат.
     */
    fun onStartSearchClicked() {
        viewModelScope.launch {
            roomRepository.startSearch()
        }
    }

    /**
     * Останавливает поиск комнат.
     */
    fun onStopSearchClicked() {
        viewModelScope.launch {
            roomRepository.stopSearch()
        }
    }

    /**
     * При каждом resume немедленно запускает поиск и затем обновляет его снимок каждые десять секунд.
     */
    fun onLobbyResumed() {
        searchRefreshJob?.cancel()
        searchRefreshJob = viewModelScope.launch {
            roomRepository.startSearch()
            scanCycleId.value += 1L
            while (isActive) {
                delay(SEARCH_REFRESH_INTERVAL_MILLIS)
                roomRepository.refreshSearch()
                scanCycleId.value += 1L
            }
        }
    }

    /**
     * При pause останавливает периодические циклы и активный Nearby discovery.
     */
    fun onLobbyPaused() {
        searchRefreshJob?.cancel()
        searchRefreshJob = null
        viewModelScope.launch {
            roomRepository.stopSearch()
        }
    }

    /**
     * Сохраняет выбранные room/voice transport настройки и создает комнату с заданным именем.
     */
    fun onCreateRoomClicked(
        name: String,
        roomTransportMode: RoomTransportMode,
        voiceTransportPreference: VoiceTransportPreference,
    ) {
        viewModelScope.launch {
            val cleanName = name.trim()
            roomSettingsRepository.setRoomTransportMode(roomTransportMode)
            if (!voiceSettingsRepository.setVoiceTransportPreference(voiceTransportPreference)) {
                return@launch
            }
            roomRepository.createRoom(cleanName, roomTransportMode)
            profileRepository.setLastRoomName(cleanName)
        }
    }

    /**
     * Возвращает последнее использованное название комнаты для предзаполнения диалога создания.
     */
    fun lastRoomNameForDialog(): String {
        return profileRepository.getLastRoomName()
    }

    /**
     * Возвращает сохраненный тип комнаты для предзаполнения диалога создания.
     */
    fun roomTransportModeForDialog(): RoomTransportMode {
        return roomSettingsRepository.roomTransportMode.value
    }

    /**
     * Возвращает сохраненный voice transport для предзаполнения диалога создания комнаты.
     */
    fun voiceTransportPreferenceForDialog(): VoiceTransportPreference {
        return voiceSettingsRepository.voiceTransportPreference.value
    }

    /**
     * Подключается к найденной комнате.
     */
    fun onJoinRoomClicked(room: RoomItemUi) {
        viewModelScope.launch {
            if (ignoredNearbyRepository.isPeerIgnored(room.gatewayPeerId)) {
                Log.i(TAG, "[onJoinRoomClicked] Вход пропущен, gateway в ignore-list roomId=${room.roomId.value} gatewayPeerId=${room.gatewayPeerId.value}")
                return@launch
            }
            roomRepository.joinRoom(room.roomId)
        }
    }

    /**
     * Добавляет прямой gateway выбранной комнаты в ignore-list Nearby и скрывает только его рекламу.
     */
    fun onIgnoreRoomClicked(room: RoomItemUi) {
        ignoredNearbyRepository.ignorePeer(room.gatewayPeerId)
    }

    /**
     * Очищает локальный ignore-list Nearby peer-ов/gateway-ев после подтверждения пользователя.
     */
    fun onClearIgnoredPeersConfirmed() {
        ignoredNearbyRepository.clearIgnoredPeers()
    }

    /**
     * Преобразует runtime-состояние и доменные комнаты в UI-состояние лобби.
     */
    private fun mapUiState(
        runtimeState: RoomRuntimeState,
        rooms: List<RoomInfo>,
        nameEditor: NameEditorState,
        cycleId: Long,
        ignoredPeerIds: Set<PeerId>,
    ): LobbyUiState {
        val visibleRooms = dedupeVisibleRooms(
            rooms = rooms.filterNot { roomInfo -> gatewayPeer(roomInfo).peerId in ignoredPeerIds },
        )
        return LobbyUiState(
            selfName = nameEditor.savedName,
            nameInput = nameEditor.inputName,
            isEditingName = nameEditor.isEditing,
            canSaveName = canSaveName(nameEditor.inputName),
            scanCycleId = cycleId,
            scanCycleDurationMillis = SEARCH_REFRESH_INTERVAL_MILLIS,
            isSearching = runtimeState is RoomRuntimeState.Searching,
            availableRooms = visibleRooms.map(::mapRoomItem),
            ignoredPeerCount = ignoredPeerIds.size,
            isInRoom = runtimeState is RoomRuntimeState.Hosting ||
                runtimeState is RoomRuntimeState.Joining ||
                runtimeState is RoomRuntimeState.Client,
            errorMessage = (runtimeState as? RoomRuntimeState.Error)?.message,
            errorAction = (runtimeState as? RoomRuntimeState.Error)?.action,
        )
    }

    /**
     * Преобразует доменное описание комнаты в элемент списка.
     */
    private fun mapRoomItem(roomInfo: RoomInfo): RoomItemUi {
        val gateway = gatewayPeer(roomInfo)
        return RoomItemUi(
            roomId = roomInfo.roomId,
            hostPeerId = roomInfo.host.peerId,
            gatewayPeerId = gateway.peerId,
            roomName = roomInfo.name,
            hostName = roomInfo.host.name,
            gatewayName = gateway.name,
            memberCountText = memberCountText(roomInfo),
        )
    }

    /**
     * Собирает короткую подпись найденного endpoint-а и количества участников для карточки комнаты.
     */
    private fun memberCountText(roomInfo: RoomInfo): String? {
        val endpointId = roomInfo.discoveryEndpointId
        val countText = roomInfo.memberCount?.let { count -> "$count участников" }
            ?: endpointId?.let { "участники рядом" }
        return listOfNotNull(endpointId, countText)
            .takeIf { parts -> parts.isNotEmpty() }
            ?.joinToString(" • ")
    }

    /**
     * Дедупит одну логическую mesh-комнату после фильтрации ignored gateway-ев, оставляя доступный gateway-кандидат.
     */
    private fun dedupeVisibleRooms(rooms: List<RoomInfo>): List<RoomInfo> {
        val result = mutableListOf<RoomInfo>()
        val seenGroups = mutableSetOf<String>()
        rooms
            .sortedByDescending { roomInfo -> roomInfo.createdAtMillis }
            .forEach { roomInfo ->
                val groupId = roomInfo.discoveryGroupId ?: roomInfo.roomId.value
                if (seenGroups.add(groupId)) {
                    result += roomInfo
                }
            }
        return result.sortedBy { roomInfo -> roomInfo.name.lowercase() }
    }

    /**
     * Возвращает прямой gateway комнаты; для Nearby Star gateway совпадает с host-ом.
     */
    private fun gatewayPeer(roomInfo: RoomInfo) = roomInfo.gateway ?: roomInfo.host

    /**
     * Создает начальное состояние редактора из сохраненного профиля.
     */
    private fun initialNameEditorState(): NameEditorState {
        val savedName = profileRepository.getPeerName().orEmpty()
        return NameEditorState(
            savedName = savedName,
            inputName = savedName,
            isEditing = false,
        )
    }

    /**
     * Возвращает true для непустого имени допустимой длины.
     */
    private fun canSaveName(value: String): Boolean {
        val cleanName = value.trim()
        return cleanName.isNotEmpty() && cleanName.length <= MAX_NAME_LENGTH
    }

    /**
     * Внутреннее состояние сохраненного имени и открытого редактора.
     */
    private data class NameEditorState(
        val savedName: String,
        val inputName: String,
        val isEditing: Boolean,
    )

    private companion object {
        private const val TAG = "LobbyViewModel"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val SEARCH_REFRESH_INTERVAL_MILLIS = 10_000L
        private const val MAX_NAME_LENGTH = 18
    }
}
