package com.yellastro.btration.domain.model

import kotlinx.serialization.Serializable

/**
 * Тип транспорта комнаты: прямой Nearby star или будущий mesh-режим MESHRA.
 */
@Serializable
enum class RoomTransportMode(
    val prefValue: String,
    val shortName: String,
    val fullName: String,
) {
    NEARBY_STAR(
        prefValue = "nearby_star",
        shortName = "Nearby Star",
        fullName = "Nearby Star",
    ),
    MESHRA(
        prefValue = "meshra",
        shortName = "MESHRA",
        fullName = "MESHRA",
    );

    companion object {
        /**
         * Возвращает режим транспорта комнаты из prefs или MESHRA по умолчанию.
         */
        fun fromPrefValue(value: String?): RoomTransportMode {
            return values().firstOrNull { mode -> mode.prefValue == value } ?: MESHRA
        }
    }
}
