package com.yellastro.btration.repository

import android.content.SharedPreferences
import com.yellastro.btration.domain.model.RoomTransportMode
import com.yellastro.btration.voice.VoiceFrameDuration
import com.yellastro.btration.voice.VoiceTransportPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Хранит voice transport и отдельную длительность Opus-фрейма для каждого типа создаваемой комнаты.
 */
class VoiceSettingsRepository(
    private val prefs: SharedPreferences,
) {
    private val _voiceTransportPreference = MutableStateFlow(readVoiceTransportPreference())

    /**
     * Текущая сохраненная настройка voice transport для ViewModel и UI.
     */
    val voiceTransportPreference: StateFlow<VoiceTransportPreference> = _voiceTransportPreference.asStateFlow()

    /**
     * Сохраняет выбранный voice transport, если режим уже доступен пользователю.
     */
    fun setVoiceTransportPreference(preference: VoiceTransportPreference): Boolean {
        if (!preference.isSelectable) {
            return false
        }
        prefs.edit().putString(KEY_VOICE_TRANSPORT_PREFERENCE, preference.prefValue).apply()
        _voiceTransportPreference.value = preference
        return true
    }

    /**
     * Сохраняет длительность voice frame отдельно для Nearby Star или MESHRA-комнат.
     */
    fun setVoiceFrameDuration(roomTransportMode: RoomTransportMode, duration: VoiceFrameDuration) {
        prefs.edit()
            .putString(frameDurationPreferenceKey(roomTransportMode), duration.prefValue)
            .apply()
    }

    /**
     * Возвращает сохраненную длительность voice frame с отдельным безопасным default для каждого типа комнаты.
     */
    fun voiceFrameDuration(roomTransportMode: RoomTransportMode): VoiceFrameDuration {
        val default = when (roomTransportMode) {
            RoomTransportMode.NEARBY_STAR -> VoiceFrameDuration.MS_10
            RoomTransportMode.MESHRA -> VoiceFrameDuration.MS_20
        }
        return VoiceFrameDuration.fromPrefValue(
            value = prefs.getString(frameDurationPreferenceKey(roomTransportMode), null),
            default = default,
        )
    }

    /**
     * Читает сохраненный voice transport из prefs и откатывает неподдержанные значения к Wi-Fi Direct.
     */
    private fun readVoiceTransportPreference(): VoiceTransportPreference {
        val preference = VoiceTransportPreference.fromPrefValue(
            prefs.getString(KEY_VOICE_TRANSPORT_PREFERENCE, null),
        )
        return if (preference.isSelectable) {
            preference
        } else {
            VoiceTransportPreference.WIFI_DIRECT
        }
    }

    /**
     * Выбирает независимый SharedPreferences key для длительности фрейма указанного типа комнаты.
     */
    private fun frameDurationPreferenceKey(roomTransportMode: RoomTransportMode): String {
        return when (roomTransportMode) {
            RoomTransportMode.NEARBY_STAR -> KEY_VOICE_FRAME_DURATION_NEARBY_STAR
            RoomTransportMode.MESHRA -> KEY_VOICE_FRAME_DURATION_MESHRA
        }
    }

    private companion object {
        private const val KEY_VOICE_TRANSPORT_PREFERENCE = "voice_transport_preference"
        private const val KEY_VOICE_FRAME_DURATION_NEARBY_STAR = "voice_frame_duration_nearby_star"
        private const val KEY_VOICE_FRAME_DURATION_MESHRA = "voice_frame_duration_meshra"
    }
}
