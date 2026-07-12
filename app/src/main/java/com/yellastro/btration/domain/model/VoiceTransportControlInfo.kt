package com.yellastro.btration.domain.model

import kotlinx.serialization.Serializable

/**
 * Служебная информация media-plane, которую участники обменивают через signaling-транспорт комнаты.
 *
 * Для Wi-Fi Direct поле peerId совпадает с RoomInfo.host.peerId у host-а, wifiDirectDeviceAddress остается только
 * диагностикой Android API, а p2pAddress и udpPort используются для резервного двустороннего UDP-punch через Nearby.
 */
@Serializable
data class VoiceTransportControlInfo(
    val mode: String,
    val peerId: PeerId,
    val wifiDirectDeviceAddress: String? = null,
    val p2pAddress: String? = null,
    val udpPort: Int? = null,
    val isGroupOwner: Boolean = false,
    val sentAtMillis: Long = 0L,
)
