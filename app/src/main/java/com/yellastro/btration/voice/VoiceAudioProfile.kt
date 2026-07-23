package com.yellastro.btration.voice

import kotlinx.serialization.Serializable

/**
 * Поддержанная длительность одного Opus-фрейма голоса и ее представление в prefs/UI.
 */
@Serializable
enum class VoiceFrameDuration(
    val millis: Int,
    val prefValue: String,
    val fullName: String,
) {
    MS_10(
        millis = 10,
        prefValue = "10",
        fullName = "10 мс",
    ),
    MS_20(
        millis = 20,
        prefValue = "20",
        fullName = "20 мс",
    ),
    MS_40(
        millis = 40,
        prefValue = "40",
        fullName = "40 мс",
    );

    companion object {
        /**
         * Читает поддержанную длительность из prefs или возвращает переданное значение по умолчанию.
         */
        fun fromPrefValue(value: String?, default: VoiceFrameDuration): VoiceFrameDuration {
            return values().firstOrNull { duration -> duration.prefValue == value } ?: default
        }
    }
}

/**
 * Неизменяемый на время жизни комнаты профиль кодирования голоса, который host передает участникам в meta комнаты.
 */
@Serializable
data class VoiceAudioProfile(
    val frameDuration: VoiceFrameDuration = VoiceFrameDuration.MS_40,
)
