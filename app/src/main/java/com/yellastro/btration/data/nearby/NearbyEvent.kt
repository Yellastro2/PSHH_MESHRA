package com.yellastro.btration.data.nearby

import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.model.WirePacket
import com.yellastro.btration.voice.VoiceFrame
import java.io.InputStream

/**
 * Событие низкого Nearby-слоя без бизнес-решений комнаты, включая wire payload, recovery, voice streams и voice frames.
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
     * Транспорт подтвердил, что ранее установленное соединение можно повторно использовать без requestConnection.
     */
    data class ConnectionReused(
        val endpointId: String,
    ) : NearbyEvent()

    /**
     * Транспорт принудительно сбросил полуподключенный endpoint и просит заново обнаружить комнату.
     */
    data class ConnectionRecoveryRequired(
        val endpointId: String,
        val cause: Throwable,
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
     * Транспорт получил входящий voice stream от Nearby endpoint.
     */
    data class StreamReceived(
        val endpointId: String,
        val peerId: PeerId?,
        val inputStream: InputStream,
    ) : NearbyEvent()

    /**
     * Транспорт получил входящий voice frame от Nearby endpoint.
     */
    data class VoiceFrameReceived(
        val endpointId: String,
        val peerId: PeerId?,
        val frame: VoiceFrame,
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

    /**
     * Nearby не смог отправить voice stream.
     */
    data class StreamSendFailed(
        val peerIds: Set<PeerId>,
        val cause: Throwable,
    ) : NearbyEvent()

    /**
     * Nearby не смог отправить voice frame.
     */
    data class VoiceFrameSendFailed(
        val peerIds: Set<PeerId>,
        val cause: Throwable,
    ) : NearbyEvent()
}
