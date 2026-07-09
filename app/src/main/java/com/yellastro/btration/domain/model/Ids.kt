package com.yellastro.btration.domain.model

import kotlinx.serialization.Serializable

/**
 * Стабильный идентификатор участника, которым приложение оперирует независимо от Nearby endpointId.
 */
@JvmInline
@Serializable
value class PeerId(val value: String)

/**
 * Стабильный идентификатор комнаты, видимый бизнес-логике и UI.
 */
@JvmInline
@Serializable
value class RoomId(val value: String)

/**
 * Стабильный идентификатор сообщения в общем текстовом чате.
 */
@JvmInline
@Serializable
value class MessageId(val value: String)

/**
 * Идентификатор сетевого пакета для будущих dedup/relay-сценариев.
 */
@JvmInline
@Serializable
value class WirePacketId(val value: String)
