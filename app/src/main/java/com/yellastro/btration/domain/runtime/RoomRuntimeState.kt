package com.yellastro.btration.domain.runtime

import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.RoomInfo

/**
 * Состояние рабочей машины комнаты, которое затем будут читать repository и ViewModel.
 */
sealed interface RoomRuntimeState {
    /**
     * Runtime не ищет комнаты и не состоит в комнате.
     */
    data object Idle : RoomRuntimeState

    /**
     * Runtime ищет Nearby-комнаты.
     */
    data object Searching : RoomRuntimeState

    /**
     * Локальный пользователь хостит комнату и ведет список участников.
     */
    data class Hosting(
        val room: RoomInfo,
        val members: List<Peer>,
    ) : RoomRuntimeState

    /**
     * Локальный пользователь подключается к найденной комнате.
     */
    data class Joining(
        val room: RoomInfo,
    ) : RoomRuntimeState

    /**
     * Локальный пользователь является клиентом комнаты.
     */
    data class Client(
        val room: RoomInfo,
        val members: List<Peer>,
    ) : RoomRuntimeState

    /**
     * Runtime столкнулся с ошибкой команды, протокола или транспорта.
     */
    data class Error(
        val message: String,
        val action: RoomRuntimeErrorAction? = null,
    ) : RoomRuntimeState
}
