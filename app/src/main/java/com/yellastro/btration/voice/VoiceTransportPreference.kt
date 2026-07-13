package com.yellastro.btration.voice

/**
 * Пользовательская настройка желаемого voice transport: хранится в prefs и применяется при создании runtime.
 */
enum class VoiceTransportPreference(
    val prefValue: String,
    val shortName: String,
    val fullName: String,
    val isSelectable: Boolean,
    val transportMode: VoiceTransportMode?,
) {
    WIFI_DIRECT(
        prefValue = "wifi_direct",
        shortName = "Direct",
        fullName = "Wi-Fi Direct",
        isSelectable = true,
        transportMode = VoiceTransportMode.WIFI_DIRECT_UDP,
    ),
    NEARBY_CONNECT(
        prefValue = "nearby_connect",
        shortName = "Nearby",
        fullName = "Nearby Connect",
        isSelectable = true,
        transportMode = VoiceTransportMode.NEARBY_BYTES,
    ),
    WIFI_AWARE(
        prefValue = "wifi_aware",
        shortName = "Aware",
        fullName = "Wi-Fi Aware",
        isSelectable = false,
        transportMode = null,
    );

    companion object {
        /**
         * Возвращает настройку по сохраненному значению prefs или Wi-Fi Direct по умолчанию.
         */
        fun fromPrefValue(value: String?): VoiceTransportPreference {
            return values().firstOrNull { mode -> mode.prefValue == value } ?: WIFI_DIRECT
        }

        /**
         * Возвращает UI-настройку, которая соответствует transport mode активной комнаты.
         */
        fun fromTransportMode(mode: VoiceTransportMode): VoiceTransportPreference {
            return values().firstOrNull { preference -> preference.transportMode == mode } ?: WIFI_DIRECT
        }
    }
}
