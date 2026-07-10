package com.yellastro.btration.ui.lobby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.runtime.RoomRuntimeState
import com.yellastro.btration.repository.ProfileRepository
import com.yellastro.btration.repository.RoomRepository
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
    ) { runtimeState, rooms, nameEditor, cycleId ->
        mapUiState(runtimeState, rooms, nameEditor, cycleId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = mapUiState(
            roomRepository.runtimeState.value,
            roomRepository.availableRooms.value,
            nameEditorState.value,
            scanCycleId.value,
        ),
    )

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
     * Создает комнату с заданным именем.
     */
    fun onCreateRoomClicked(name: String) {
        viewModelScope.launch {
            roomRepository.createRoom(name)
        }
    }

    /**
     * Подключается к найденной комнате.
     */
    fun onJoinRoomClicked(roomId: RoomId) {
        viewModelScope.launch {
            roomRepository.joinRoom(roomId)
        }
    }

    /**
     * Преобразует runtime-состояние и доменные комнаты в UI-состояние лобби.
     */
    private fun mapUiState(
        runtimeState: RoomRuntimeState,
        rooms: List<RoomInfo>,
        nameEditor: NameEditorState,
        cycleId: Long,
    ): LobbyUiState {
        return LobbyUiState(
            selfName = nameEditor.savedName,
            nameInput = nameEditor.inputName,
            isEditingName = nameEditor.isEditing,
            canSaveName = canSaveName(nameEditor.inputName),
            scanCycleId = cycleId,
            scanCycleDurationMillis = SEARCH_REFRESH_INTERVAL_MILLIS,
            isSearching = runtimeState is RoomRuntimeState.Searching,
            availableRooms = rooms.map(::mapRoomItem),
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
        return RoomItemUi(
            roomId = roomInfo.roomId,
            roomName = roomInfo.name,
            hostName = roomInfo.host.name,
            memberCountText = null,
        )
    }

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
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val SEARCH_REFRESH_INTERVAL_MILLIS = 10_000L
        private const val MAX_NAME_LENGTH = 18
    }
}
