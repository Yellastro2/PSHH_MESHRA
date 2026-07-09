package com.yellastro.btration.domain.model

import kotlinx.serialization.Serializable

/**
 * Публичное описание комнаты, которое можно показать в лобби до входа.
 */
@Serializable
data class RoomInfo(
    val roomId: RoomId,
    val name: String,
    val host: Peer,
    val createdAtMillis: Long,
)
