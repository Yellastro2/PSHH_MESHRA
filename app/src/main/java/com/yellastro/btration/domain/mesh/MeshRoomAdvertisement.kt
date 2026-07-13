package com.yellastro.btration.domain.mesh

import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.model.RoomTransportMode
import com.yellastro.btration.voice.VoiceTransportMode

/**
 * Компактная mesh-визитка комнаты для NeighborTransport advertising: кто gateway и к какой комнате он дает вход.
 */
data class MeshRoomAdvertisement(
    val roomToken: String,
    val roomName: String,
    val knownHostShortId: String,
    val knownHostName: String,
    val gatewayShortId: String,
    val gatewayName: String,
    val memberCount: Int,
    val updatedAtMillis: Long,
) {
    /**
     * Кодирует mesh-визитку в короткую endpointName-строку.
     */
    fun encode(): String {
        val cleanRoomName = sanitizeName(roomName)
        val cleanHostName = sanitizeName(knownHostName)
        val cleanGatewayName = sanitizeName(gatewayName)
        val updatedAtToken = updatedAtMillis.toString(NUMBER_RADIX)
        val memberCountToken = memberCount.coerceAtLeast(0).toString(NUMBER_RADIX)
        val fixedBytes = utf8Size(FORMAT_PREFIX) +
            utf8Size(roomToken) +
            utf8Size(knownHostShortId) +
            utf8Size(gatewayShortId) +
            utf8Size(memberCountToken) +
            utf8Size(updatedAtToken) +
            SEPARATOR_BYTES * SEPARATOR_COUNT
        val availableNameBytes = (MAX_ENDPOINT_NAME_BYTES - fixedBytes).coerceAtLeast(0)
        val roomNameBudget = availableNameBytes / 2
        val hostNameBudget = availableNameBytes / 4
        val gatewayNameBudget = availableNameBytes - roomNameBudget - hostNameBudget

        return listOf(
            FORMAT_PREFIX,
            roomToken,
            knownHostShortId,
            gatewayShortId,
            memberCountToken,
            updatedAtToken,
            cleanRoomName.takeUtf8Bytes(roomNameBudget),
            cleanHostName.takeUtf8Bytes(hostNameBudget),
            cleanGatewayName.takeUtf8Bytes(gatewayNameBudget),
        ).joinToString(SEPARATOR)
    }

    /**
     * Возвращает временный RoomId конкретного gateway, чтобы ignore одного gateway не затирал другие рекламы комнаты.
     */
    fun toAdvertisedRoomId(): RoomId {
        return RoomId("$ADVERTISED_ROOM_ID_PREFIX${roomToken}_gw_$gatewayShortId")
    }

    /**
     * Возвращает ключ логической mesh-комнаты для UI-дедупликации после фильтрации ignored gateway-ев.
     */
    fun toDiscoveryGroupId(): String {
        return roomToken
    }

    /**
     * Возвращает временный Peer для known host-а из короткой mesh-визитки.
     */
    fun toAdvertisedKnownHost(): Peer {
        return Peer(
            peerId = PeerId("$ADVERTISED_PEER_ID_PREFIX$knownHostShortId"),
            name = knownHostName.ifBlank { DEFAULT_HOST_NAME },
        )
    }

    /**
     * Возвращает временный Peer для gateway из короткой mesh-визитки.
     */
    fun toAdvertisedGateway(): Peer {
        return Peer(
            peerId = PeerId("$ADVERTISED_PEER_ID_PREFIX$gatewayShortId"),
            name = gatewayName.ifBlank { DEFAULT_GATEWAY_NAME },
        )
    }

    /**
     * Возвращает временное публичное описание mesh-комнаты для списка лобби до получения полного snapshot-а.
     */
    fun toRoomInfo(): RoomInfo {
        return RoomInfo(
            roomId = toAdvertisedRoomId(),
            name = roomName.ifBlank { DEFAULT_ROOM_NAME },
            host = toAdvertisedKnownHost(),
            createdAtMillis = updatedAtMillis,
            roomTransportMode = RoomTransportMode.MESHRA,
            voiceTransportMode = VoiceTransportMode.WIFI_DIRECT_UDP,
            gateway = toAdvertisedGateway(),
            discoveryGroupId = toDiscoveryGroupId(),
        )
    }

    companion object {
        private const val FORMAT_PREFIX = "BTM4"
        private const val SEPARATOR = "|"
        private const val FIELD_COUNT = 9
        private const val SEPARATOR_COUNT = FIELD_COUNT - 1
        private const val SEPARATOR_BYTES = 1
        private const val MAX_ENDPOINT_NAME_BYTES = 120
        private const val NUMBER_RADIX = 36
        private const val ADVERTISED_ROOM_ID_PREFIX = "mesh_room_"
        private const val ADVERTISED_PEER_ID_PREFIX = "mesh_peer_"
        private const val DEFAULT_ROOM_NAME = "Комната"
        private const val DEFAULT_HOST_NAME = "Host"
        private const val DEFAULT_GATEWAY_NAME = "Gateway"

        /**
         * Создает mesh-визитку из snapshot-а комнаты и текущего gateway peer-а.
         */
        fun fromSnapshot(snapshot: MeshRoomSnapshot, gateway: Peer): MeshRoomAdvertisement {
            return MeshRoomAdvertisement(
                roomToken = roomTokenFor(snapshot.roomId),
                roomName = snapshot.roomName,
                knownHostShortId = shortPeerIdFor(snapshot.knownHost.peerId),
                knownHostName = snapshot.knownHost.name,
                gatewayShortId = shortPeerIdFor(gateway.peerId),
                gatewayName = gateway.name,
                memberCount = snapshot.members.size,
                updatedAtMillis = snapshot.updatedAtMillis,
            )
        }

        /**
         * Декодирует endpointName, если это mesh-визитка комнаты.
         */
        fun decode(endpointName: String): MeshRoomAdvertisement? {
            val parts = endpointName.split(SEPARATOR, limit = FIELD_COUNT)
            if (parts.size != FIELD_COUNT || parts[0] != FORMAT_PREFIX) {
                return null
            }
            val roomToken = parts[1].takeIf { value -> value.isNotBlank() } ?: return null
            val knownHostShortId = parts[2].takeIf { value -> value.isNotBlank() } ?: return null
            val gatewayShortId = parts[3].takeIf { value -> value.isNotBlank() } ?: return null
            val memberCount = parts[4].toIntOrNull(NUMBER_RADIX) ?: 0
            val updatedAtMillis = parts[5].toLongOrNull(NUMBER_RADIX) ?: return null
            return MeshRoomAdvertisement(
                roomToken = roomToken,
                knownHostShortId = knownHostShortId,
                gatewayShortId = gatewayShortId,
                memberCount = memberCount,
                updatedAtMillis = updatedAtMillis,
                roomName = parts[6],
                knownHostName = parts[7],
                gatewayName = parts[8],
            )
        }

        /**
         * Возвращает true, если RoomId был создан из mesh-визитки до полной синхронизации комнаты.
         */
        fun isAdvertisedMeshRoomId(roomId: RoomId): Boolean {
            return roomId.value.startsWith(ADVERTISED_ROOM_ID_PREFIX)
        }

        /**
         * Возвращает true, если временный RoomId относится к указанной реальной mesh-комнате независимо от gateway.
         */
        fun matchesAdvertisedRoomId(advertisedRoomId: RoomId, realRoomId: RoomId): Boolean {
            return advertisedRoomId.value.startsWith("${ADVERTISED_ROOM_ID_PREFIX}${roomTokenFor(realRoomId)}_gw_")
        }

        /**
         * Создает короткий токен комнаты для группировки дублей рекламы.
         */
        private fun roomTokenFor(roomId: RoomId): String {
            return Integer.toUnsignedString(roomId.value.hashCode(), NUMBER_RADIX)
        }

        /**
         * Создает короткий токен peer-а для endpointName-рекламы.
         */
        private fun shortPeerIdFor(peerId: PeerId): String {
            return Integer.toUnsignedString(peerId.value.hashCode(), NUMBER_RADIX)
        }

        /**
         * Убирает символы, которые ломают простой endpointName-формат.
         */
        private fun sanitizeName(value: String): String {
            return value.trim()
                .replace(SEPARATOR, " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
        }

        /**
         * Возвращает размер строки в байтах UTF-8.
         */
        private fun utf8Size(value: String): Int {
            return value.encodeToByteArray().size
        }

        /**
         * Возвращает префикс строки, который помещается в указанный UTF-8 бюджет.
         */
        private fun String.takeUtf8Bytes(maxBytes: Int): String {
            if (maxBytes <= 0) {
                return ""
            }
            val builder = StringBuilder()
            var usedBytes = 0
            var index = 0
            while (index < length) {
                val codePoint = codePointAt(index)
                val charText = String(Character.toChars(codePoint))
                val charBytes = charText.encodeToByteArray().size
                if (usedBytes + charBytes > maxBytes) {
                    break
                }
                builder.append(charText)
                usedBytes += charBytes
                index += Character.charCount(codePoint)
            }
            return builder.toString()
        }
    }
}
