package com.yellastro.btration.data.nearby

import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomInfo

/**
 * Компактная визитка комнаты для Nearby endpointName: только данные для лобби и подключения к endpoint-у.
 */
data class NearbyRoomAdvertisement(
    val roomName: String,
    val hostShortId: String,
    val hostName: String,
    val sessionId: String,
    val createdAtMillis: Long,
) {
    /**
     * Кодирует визитку в короткую строку endpointName, отдавая основной бюджет имени комнаты и имени хоста.
     */
    fun encode(): String {
        val cleanRoomName = sanitizeName(roomName)
        val cleanHostName = sanitizeName(hostName)
        val createdAtToken = createdAtMillis.toString(CREATED_AT_RADIX)
        val fixedBytes = utf8Size(FORMAT_PREFIX) +
            utf8Size(sessionId) +
            utf8Size(createdAtToken) +
            utf8Size(hostShortId) +
            SEPARATOR_BYTES * SEPARATOR_COUNT
        val availableNameBytes = (MAX_ENDPOINT_NAME_BYTES - fixedBytes).coerceAtLeast(0)
        val roomNameBudget = (availableNameBytes * ROOM_NAME_WEIGHT_NUMERATOR) / ROOM_NAME_WEIGHT_DENOMINATOR
        var encodedRoomName = cleanRoomName.takeUtf8Bytes(roomNameBudget)
        val hostNameBudget = availableNameBytes - utf8Size(encodedRoomName)
        val encodedHostName = cleanHostName.takeUtf8Bytes(hostNameBudget)
        val unusedBytes = availableNameBytes - utf8Size(encodedRoomName) - utf8Size(encodedHostName)
        if (unusedBytes > 0) {
            encodedRoomName = cleanRoomName.takeUtf8Bytes(utf8Size(encodedRoomName) + unusedBytes)
        }

        return listOf(
            FORMAT_PREFIX,
            sessionId,
            createdAtToken,
            hostShortId,
            encodedRoomName,
            encodedHostName,
        ).joinToString(SEPARATOR)
    }

    /**
     * Превращает визитку обратно в RoomInfo для показа комнаты в лобби.
     */
    fun toRoomInfo(): RoomInfo {
        return RoomInfo(
            roomId = RoomId("$ADVERTISED_ROOM_ID_PREFIX$sessionId"),
            name = roomName.ifBlank { DEFAULT_ROOM_NAME },
            host = Peer(
                peerId = PeerId("$ADVERTISED_HOST_PEER_ID_PREFIX$hostShortId"),
                name = hostName.ifBlank { DEFAULT_HOST_NAME },
            ),
            createdAtMillis = createdAtMillis,
        )
    }

    companion object {
        private const val FORMAT_PREFIX = "BTR3"
        private const val SEPARATOR = "|"
        private const val FIELD_COUNT = 6
        private const val SEPARATOR_COUNT = FIELD_COUNT - 1
        private const val SEPARATOR_BYTES = 1
        private const val MAX_ENDPOINT_NAME_BYTES = 120
        private const val CREATED_AT_RADIX = 36
        private const val ROOM_NAME_WEIGHT_NUMERATOR = 2
        private const val ROOM_NAME_WEIGHT_DENOMINATOR = 3
        private const val DEFAULT_ROOM_NAME = "Комната"
        private const val DEFAULT_HOST_NAME = "Хост"
        private const val ADVERTISED_ROOM_ID_PREFIX = "ad_room_"
        private const val ADVERTISED_HOST_PEER_ID_PREFIX = "ad_host_"

        /**
         * Создает визитку из доменного описания комнаты.
         */
        fun fromRoom(room: RoomInfo): NearbyRoomAdvertisement {
            return NearbyRoomAdvertisement(
                roomName = room.name,
                hostShortId = shortHostIdFor(room.host.peerId),
                hostName = room.host.name,
                sessionId = sessionIdFor(room),
                createdAtMillis = room.createdAtMillis,
            )
        }

        /**
         * Декодирует endpointName, если это короткая визитка комнаты BtRation актуального поколения рекламы.
         */
        fun decode(endpointName: String): NearbyRoomAdvertisement? {
            val parts = endpointName.split(SEPARATOR, limit = FIELD_COUNT)
            if (parts.size != FIELD_COUNT || parts[0] != FORMAT_PREFIX) {
                return null
            }
            val sessionId = parts[1].takeIf { value -> value.isNotBlank() } ?: return null
            val createdAtMillis = parts[2].toLongOrNull(CREATED_AT_RADIX) ?: return null
            val hostShortId = parts[3].takeIf { value -> value.isNotBlank() } ?: return null
            return NearbyRoomAdvertisement(
                hostShortId = hostShortId,
                roomName = parts[4],
                hostName = parts[5],
                sessionId = sessionId,
                createdAtMillis = createdAtMillis,
            )
        }

        /**
         * Возвращает true, если RoomId был создан из короткой Nearby-визитки и еще не подтвержден host-ом.
         */
        fun isAdvertisedRoomId(roomId: RoomId): Boolean {
            return roomId.value.startsWith(ADVERTISED_ROOM_ID_PREFIX)
        }

        /**
         * Возвращает true, если PeerId host-а был создан из короткой Nearby-визитки и еще не подтвержден host-ом.
         */
        fun isAdvertisedHostPeerId(peerId: PeerId): Boolean {
            return peerId.value.startsWith(ADVERTISED_HOST_PEER_ID_PREFIX)
        }

        /**
         * Создает короткий идентификатор поколения рекламы из RoomId и времени создания комнаты.
         */
        private fun sessionIdFor(room: RoomInfo): String {
            val time = room.createdAtMillis.toString(CREATED_AT_RADIX)
            val roomHash = Integer.toUnsignedString(room.roomId.value.hashCode(), CREATED_AT_RADIX)
            return "$time$roomHash".takeLast(MAX_SESSION_ID_CHARS)
        }

        /**
         * Создает короткий стабильный след host-а для показа и временной адресации endpoint-а до JOIN_ACCEPTED.
         */
        private fun shortHostIdFor(peerId: PeerId): String {
            return Integer.toUnsignedString(peerId.value.hashCode(), CREATED_AT_RADIX)
                .takeLast(MAX_HOST_SHORT_ID_CHARS)
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

        private const val MAX_SESSION_ID_CHARS = 18
        private const val MAX_HOST_SHORT_ID_CHARS = 6
    }
}
