package com.yellastro.btration.data.nearby

import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.model.WirePacket

/**
 * Событие низкого Nearby-слоя без бизнес-решений комнаты.
 */
sealed class NearbyEvent {
    /**
     * Nearby обнаружил endpoint и, если получилось, публичное описание комнаты.
     */
    data class EndpointFound(
        val endpointId: String,
        val endpointInfo: DiscoveredEndpointInfo,
        val roomInfo: RoomInfo?,
    ) : NearbyEvent()

    /**
     * Nearby сообщил, что endpoint больше не виден.
     */
    data class EndpointLost(
        val endpointId: String,
        val roomId: RoomId?,
    ) : NearbyEvent()

    /**
     * Nearby инициировал соединение, которое транспорт автоматически принимает.
     */
    data class ConnectionInitiated(
        val endpointId: String,
        val connectionInfo: ConnectionInfo,
    ) : NearbyEvent()

    /**
     * Nearby завершил попытку соединения.
     */
    data class ConnectionResult(
        val endpointId: String,
        val resolution: ConnectionResolution,
    ) : NearbyEvent()

    /**
     * Nearby сообщил о разрыве соединения.
     */
    data class Disconnected(
        val endpointId: String,
        val peerId: PeerId?,
    ) : NearbyEvent()

    /**
     * Транспорт получил и успешно декодировал wire-пакет.
     */
    data class PacketReceived(
        val endpointId: String,
        val peerId: PeerId?,
        val packet: WirePacket,
    ) : NearbyEvent()

    /**
     * Nearby обновил состояние передачи payload.
     */
    data class PayloadTransferUpdated(
        val endpointId: String,
        val update: PayloadTransferUpdate,
    ) : NearbyEvent()

    /**
     * Транспорт не смог декодировать payload как wire-пакет.
     */
    data class PayloadDecodeFailed(
        val endpointId: String,
        val cause: Throwable,
    ) : NearbyEvent()

    /**
     * Транспорт получил payload неподдерживаемого типа.
     */
    data class UnsupportedPayloadReceived(
        val endpointId: String,
        val payloadType: Int,
    ) : NearbyEvent()

    /**
     * Nearby не смог запустить advertising.
     */
    data class AdvertisingFailed(
        val cause: Throwable,
    ) : NearbyEvent()

    /**
     * Nearby не смог запустить discovery.
     */
    data class DiscoveryFailed(
        val cause: Throwable,
    ) : NearbyEvent()

    /**
     * Nearby не смог запросить соединение с endpoint.
     */
    data class ConnectionRequestFailed(
        val endpointId: String,
        val cause: Throwable,
    ) : NearbyEvent()

    /**
     * Nearby не смог принять входящее соединение.
     */
    data class ConnectionAcceptFailed(
        val endpointId: String,
        val cause: Throwable,
    ) : NearbyEvent()

    /**
     * Nearby не смог отправить пакет.
     */
    data class SendFailed(
        val endpointId: String?,
        val peerId: PeerId?,
        val packet: WirePacket,
        val cause: Throwable,
    ) : NearbyEvent()
}
