package com.yellastro.btration.domain.model

import kotlinx.serialization.Serializable

/**
 * Публичное описание комнаты и ее capabilities, которое можно показать в лобби и передать участникам.
 */
@Serializable
data class RoomInfo(
    val roomId: RoomId,
    val name: String,
    val host: Peer,
    val createdAtMillis: Long,
    val isDirectAudioReady: Boolean = false,
)
