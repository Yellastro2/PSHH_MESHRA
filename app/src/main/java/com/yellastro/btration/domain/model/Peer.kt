package com.yellastro.btration.domain.model

import kotlinx.serialization.Serializable

/**
 * Описание участника комнаты без привязки к конкретному транспорту.
 */
@Serializable
data class Peer(
    val peerId: PeerId,
    val name: String,
)
