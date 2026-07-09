package com.yellastro.btration.domain.model

import kotlinx.serialization.Serializable

/**
 * Тип сетевого пакета протокола комнаты.
 */
@Serializable
enum class WirePacketType {
    ROOM_INFO,
    JOIN_REQUEST,
    JOIN_ACCEPTED,
    JOIN_REJECTED,
    MEMBER_LIST,
    MEMBER_JOINED,
    MEMBER_LEFT,
    CHAT_MESSAGE,
    ROOM_CLOSED,
    PING,
    PONG
}

/**
 * Универсальный пакет MVP-протокола поверх Nearby с полями для будущего relay/dedup.
 */
@Serializable
data class WirePacket(
    val type: WirePacketType,
    val packetId: WirePacketId? = null,
    val roomId: RoomId? = null,
    val sender: Peer? = null,
    val roomInfo: RoomInfo? = null,
    val peer: Peer? = null,
    val members: List<Peer> = emptyList(),
    val message: ChatMessage? = null,
    val reason: String? = null,
    val ttl: Int = 0,
    val sentAtMillis: Long = 0L,
)
