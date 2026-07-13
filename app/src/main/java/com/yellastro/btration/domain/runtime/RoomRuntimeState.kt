package com.yellastro.btration.domain.runtime

import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.RoomInfo

/**
 * Состояние рабочей машины комнаты, включая статус прямого voice media-plane для repository и ViewModel.
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
     * Локальный пользователь хостит комнату, ведет список участников и хранит статус direct-аудио.
     */
    data class Hosting(
        val room: RoomInfo,
        val members: List<Peer>,
        val directAudioStatus: DirectAudioStatus = DirectAudioStatus.Connecting,
    ) : RoomRuntimeState

    /**
     * Локальный пользователь подключается к найденной комнате и ожидает установку transport-сессии.
     */
    data class Joining(
        val room: RoomInfo,
        val directAudioStatus: DirectAudioStatus = DirectAudioStatus.Connecting,
    ) : RoomRuntimeState

    /**
     * Локальный пользователь является клиентом комнаты и хранит статус direct-аудио с host-ом.
     */
    data class Client(
        val room: RoomInfo,
        val members: List<Peer>,
        val directAudioStatus: DirectAudioStatus = DirectAudioStatus.Connecting,
    ) : RoomRuntimeState

    /**
     * Runtime столкнулся с ошибкой команды, протокола или транспорта.
     */
    data class Error(
        val message: String,
        val action: RoomRuntimeErrorAction? = null,
    ) : RoomRuntimeState
}

/**
 * Стабильный статус прямого аудиоканала, который идет от transport-слоя до UI через состояние комнаты.
 */
sealed interface DirectAudioStatus {
    /**
     * Transport-сессия запущена или ожидает handshake, но готовность еще не подтверждена.
     */
    data object Connecting : DirectAudioStatus

    /**
     * Media-plane подтвердил хотя бы один рабочий двусторонний direct-аудиоканал.
     */
    data object Ready : DirectAudioStatus

    /**
     * Media-plane не установился или недоступен; комната и чат при этом могут продолжать работать.
     */
    data class Unavailable(
        val message: String,
    ) : DirectAudioStatus
}
