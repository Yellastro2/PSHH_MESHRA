package com.yellastro.btration.ui.lobby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.runtime.RoomRuntimeState
import com.yellastro.btration.repository.ProfileRepository
import com.yellastro.btration.repository.RoomRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel лобби: запускает поиск, создает комнаты и входит в найденные комнаты.
 */
class LobbyViewModel(
    private val roomRepository: RoomRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    /**
     * UI-состояние лобби, собранное из RoomRuntime и локального профиля.
     */
    val uiState: StateFlow<LobbyUiState> = combine(
        roomRepository.runtimeState,
        roomRepository.availableRooms,
    ) { runtimeState, rooms ->
        mapUiState(runtimeState, rooms)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = mapUiState(roomRepository.runtimeState.value, roomRepository.availableRooms.value),
    )

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
    private fun mapUiState(runtimeState: RoomRuntimeState, rooms: List<RoomInfo>): LobbyUiState {
        return LobbyUiState(
            selfName = profileRepository.getPeerName().orEmpty(),
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

    private companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
