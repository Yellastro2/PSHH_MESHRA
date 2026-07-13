package com.yellastro.btration.ui.lobby

import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.runtime.RoomRuntimeErrorAction

/**
 * UI-состояние лобби с редактированием локального имени, поиском и списком комнат.
 */
data class LobbyUiState(
    val selfName: String = "",
    val nameInput: String = "",
    val isEditingName: Boolean = false,
    val canSaveName: Boolean = false,
    val scanCycleId: Long = 0L,
    val scanCycleDurationMillis: Long = 10_000L,
    val isSearching: Boolean = false,
    val availableRooms: List<RoomItemUi> = emptyList(),
    val ignoredHostCount: Int = 0,
    val isInRoom: Boolean = false,
    val errorMessage: String? = null,
    val errorAction: RoomRuntimeErrorAction? = null,
)

/**
 * UI-модель одной найденной комнаты.
 */
data class RoomItemUi(
    val roomId: RoomId,
    val hostPeerId: PeerId,
    val roomName: String,
    val hostName: String,
    val memberCountText: String?,
)
