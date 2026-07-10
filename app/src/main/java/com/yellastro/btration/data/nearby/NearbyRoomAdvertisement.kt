package com.yellastro.btration.data.nearby

import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomInfo

/**
 * Компактная визитка комнаты для Nearby endpointName, где полный WirePacket не помещается.
 */
data class NearbyRoomAdvertisement(
    val roomId: RoomId,
    val roomName: String,
    val hostPeerId: PeerId,
    val hostName: String,
) {
    /**
     * Кодирует визитку в короткую строку endpointName с запасом под лимит Nearby.
     */
    fun encode(): String {
        val cleanRoomName = sanitizeName(roomName)
        val cleanHostName = sanitizeName(hostName)
        val fixedBytes = utf8Size(FORMAT_PREFIX) +
            utf8Size(roomId.value) +
            utf8Size(hostPeerId.value) +
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
            roomId.value,
            hostPeerId.value,
            encodedRoomName,
            encodedHostName,
        ).joinToString(SEPARATOR)
    }

    /**
     * Превращает визитку обратно в RoomInfo для показа комнаты в лобби.
     */
    fun toRoomInfo(createdAtMillis: Long): RoomInfo {
        return RoomInfo(
            roomId = roomId,
            name = roomName.ifBlank { DEFAULT_ROOM_NAME },
            host = Peer(
                peerId = hostPeerId,
                name = hostName.ifBlank { DEFAULT_HOST_NAME },
            ),
            createdAtMillis = createdAtMillis,
        )
    }

    companion object {
        private const val FORMAT_PREFIX = "BTR1"
        private const val SEPARATOR = "|"
        private const val FIELD_COUNT = 5
        private const val SEPARATOR_COUNT = FIELD_COUNT - 1
        private const val SEPARATOR_BYTES = 1
        private const val MAX_ENDPOINT_NAME_BYTES = 120
        private const val ROOM_NAME_WEIGHT_NUMERATOR = 2
        private const val ROOM_NAME_WEIGHT_DENOMINATOR = 3
        private const val DEFAULT_ROOM_NAME = "Комната"
        private const val DEFAULT_HOST_NAME = "Хост"

        /**
         * Создает визитку из доменного описания комнаты.
         */
        fun fromRoom(room: RoomInfo): NearbyRoomAdvertisement {
            return NearbyRoomAdvertisement(
                roomId = room.roomId,
                roomName = room.name,
                hostPeerId = room.host.peerId,
                hostName = room.host.name,
            )
        }

        /**
         * Декодирует endpointName, если это визитка комнаты BtRation актуального формата.
         */
        fun decode(endpointName: String): NearbyRoomAdvertisement? {
            val parts = endpointName.split(SEPARATOR, limit = FIELD_COUNT)
            if (parts.size != FIELD_COUNT || parts[0] != FORMAT_PREFIX) {
                return null
            }
            val roomId = parts[1].takeIf { value -> value.isNotBlank() } ?: return null
            val hostPeerId = parts[2].takeIf { value -> value.isNotBlank() } ?: return null
            return NearbyRoomAdvertisement(
                roomId = RoomId(roomId),
                hostPeerId = PeerId(hostPeerId),
                roomName = parts[3],
                hostName = parts[4],
            )
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
