package com.yellastro.btration.data.nearby

import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.voice.VoiceTransportMode

/**
 * Компактная визитка комнаты для Nearby endpointName: данные для лобби, подключения и выбора voice transport.
 */
data class NearbyRoomAdvertisement(
    val roomName: String,
    val hostShortId: String,
    val hostName: String,
    val sessionId: String,
    val createdAtMillis: Long,
    val voiceTransportMode: VoiceTransportMode,
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
            utf8Size(voiceTransportToken(voiceTransportMode)) +
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
            voiceTransportToken(voiceTransportMode),
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
            voiceTransportMode = voiceTransportMode,
        )
    }

    companion object {
        private const val FORMAT_PREFIX = "BTR4"
        private const val LEGACY_FORMAT_PREFIX = "BTR3"
        private const val SEPARATOR = "|"
        private const val FIELD_COUNT = 7
        private const val LEGACY_FIELD_COUNT = 6
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
                voiceTransportMode = room.voiceTransportMode,
            )
        }

        /**
         * Декодирует endpointName, если это короткая визитка комнаты BtRation актуального поколения рекламы.
         */
        fun decode(endpointName: String): NearbyRoomAdvertisement? {
            val parts = endpointName.split(SEPARATOR, limit = FIELD_COUNT)
            if (parts.isEmpty()) {
                return null
            }
            val isCurrentFormat = parts.size == FIELD_COUNT && parts[0] == FORMAT_PREFIX
            val isLegacyFormat = parts.size == LEGACY_FIELD_COUNT && parts[0] == LEGACY_FORMAT_PREFIX
            if (!isCurrentFormat && !isLegacyFormat) {
                return null
            }
            val sessionId = parts[1].takeIf { value -> value.isNotBlank() } ?: return null
            val createdAtMillis = parts[2].toLongOrNull(CREATED_AT_RADIX) ?: return null
            val hostShortId = parts[3].takeIf { value -> value.isNotBlank() } ?: return null
            val voiceTransportMode = if (isCurrentFormat) {
                voiceTransportModeForToken(parts[4])
            } else {
                VoiceTransportMode.WIFI_DIRECT_UDP
            }
            val roomNameIndex = if (isCurrentFormat) 5 else 4
            val hostNameIndex = if (isCurrentFormat) 6 else 5
            return NearbyRoomAdvertisement(
                hostShortId = hostShortId,
                roomName = parts[roomNameIndex],
                hostName = parts[hostNameIndex],
                sessionId = sessionId,
                createdAtMillis = createdAtMillis,
                voiceTransportMode = voiceTransportMode,
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
         * Кодирует voice transport комнаты в компактный token для Nearby endpointName.
         */
        private fun voiceTransportToken(mode: VoiceTransportMode): String {
            return when (mode) {
                VoiceTransportMode.WIFI_DIRECT_UDP -> "d"
                VoiceTransportMode.NEARBY_BYTES -> "n"
            }
        }

        /**
         * Декодирует компактный token voice transport, оставляя Wi-Fi Direct безопасным default.
         */
        private fun voiceTransportModeForToken(token: String): VoiceTransportMode {
            return when (token) {
                "n" -> VoiceTransportMode.NEARBY_BYTES
                else -> VoiceTransportMode.WIFI_DIRECT_UDP
            }
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
