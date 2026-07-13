package com.yellastro.btration.repository

import android.content.SharedPreferences
import com.yellastro.btration.voice.VoiceTransportPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Хранит пользовательскую настройку выбранного voice transport в SharedPreferences.
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

    private companion object {
        private const val KEY_VOICE_TRANSPORT_PREFERENCE = "voice_transport_preference"
    }
}
