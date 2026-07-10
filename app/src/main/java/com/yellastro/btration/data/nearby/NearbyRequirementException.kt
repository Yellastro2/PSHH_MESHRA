package com.yellastro.btration.data.nearby

/**
 * Ошибка предварительной проверки системных требований Nearby перед вызовом Google API.
 */
sealed class NearbyRequirementException(message: String) : IllegalStateException(message) {
    /**
     * Системная геолокация выключена, из-за чего Nearby discovery/advertising может падать как missing location permission.
     */
    data object LocationDisabled : NearbyRequirementException(
        "Для Nearby нужно включить геолокацию устройства",
    )
}
