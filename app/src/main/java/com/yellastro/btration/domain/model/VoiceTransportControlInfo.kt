package com.yellastro.btration.domain.model

import kotlinx.serialization.Serializable

/**
 * Служебная информация media-plane, которую участники обменивают через signaling-транспорт комнаты.
 *
 * Для Wi-Fi Direct поле peerId совпадает с RoomInfo.host.peerId у host-а, а wifiDirectDeviceAddress остается только
 * диагностикой локального Android API: connect выполняется по WifiP2pDevice из DNS-SD discovery, а не по этому полю.
 */
@Serializable
data class VoiceTransportControlInfo(
    val mode: String,
    val peerId: PeerId,
    val wifiDirectDeviceAddress: String? = null,
    val udpPort: Int? = null,
    val isGroupOwner: Boolean = false,
    val sentAtMillis: Long = 0L,
)
