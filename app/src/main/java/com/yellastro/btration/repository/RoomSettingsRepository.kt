package com.yellastro.btration.repository

import android.content.SharedPreferences
import com.yellastro.btration.domain.model.RoomTransportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Хранит пользовательскую настройку типа комнаты для следующих диалогов создания комнаты.
 */
class RoomSettingsRepository(
    private val prefs: SharedPreferences,
) {
    private val _roomTransportMode = MutableStateFlow(readRoomTransportMode())

    /**
     * Текущий сохраненный тип транспорта комнаты для предзаполнения диалога создания.
     */
    val roomTransportMode: StateFlow<RoomTransportMode> = _roomTransportMode.asStateFlow()

    /**
     * Сохраняет выбранный тип транспорта комнаты.
     */
    fun setRoomTransportMode(mode: RoomTransportMode) {
        prefs.edit().putString(KEY_ROOM_TRANSPORT_MODE, mode.prefValue).apply()
        _roomTransportMode.value = mode
    }

    /**
     * Читает сохраненный тип транспорта комнаты, используя MESHRA как default.
     */
    private fun readRoomTransportMode(): RoomTransportMode {
        return RoomTransportMode.fromPrefValue(
            prefs.getString(KEY_ROOM_TRANSPORT_MODE, null),
        )
    }

    private companion object {
        private const val KEY_ROOM_TRANSPORT_MODE = "room_transport_mode"
    }
}
