package com.yellastro.btration.data.nearby

import android.content.Context
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.EndpointInfo
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.yellastro.btration.data.wire.WireCodec
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.model.WirePacket
import com.yellastro.btration.domain.model.WirePacketType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Тонкая обертка над Nearby Connections: discovery, advertising, connection callbacks и byte payload.
 */
class NearbyTransport(
    private val context: Context,
    private val connectionsClient: ConnectionsClient,
    private val wireCodec: WireCodec,
    private val strategy: Strategy = Strategy.P2P_STAR,
    private val serviceId: String = DEFAULT_SERVICE_ID,
) {
    private val endpointRegistry = NearbyEndpointRegistry()
    private val _events = MutableSharedFlow<NearbyEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /**
     * Поток событий Nearby-слоя для RoomRuntime.
     */
    val events: SharedFlow<NearbyEvent> = _events.asSharedFlow()

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            emitEvent(NearbyEvent.ConnectionInitiated(endpointId, connectionInfo))
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { cause ->
                    emitEvent(NearbyEvent.ConnectionAcceptFailed(endpointId, cause))
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            emitEvent(NearbyEvent.ConnectionResult(endpointId, resolution))
        }

        override fun onDisconnected(endpointId: String) {
            val peerId = endpointRegistry.getPeerId(endpointId)
            endpointRegistry.removeEndpoint(endpointId)
            emitEvent(NearbyEvent.Disconnected(endpointId, peerId))
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, endpointInfo: EndpointInfo) {
            val roomInfo = decodeRoomInfo(endpointInfo)
            if (roomInfo != null) {
                endpointRegistry.bindRoom(endpointId, roomInfo.roomId)
                endpointRegistry.bindPeer(endpointId, roomInfo.host.peerId)
            }
            emitEvent(NearbyEvent.EndpointFound(endpointId, endpointInfo, roomInfo))
        }

        override fun onEndpointLost(endpointId: String) {
            val roomId = endpointRegistry.getRoomId(endpointId)
            endpointRegistry.removeEndpoint(endpointId)
            emitEvent(NearbyEvent.EndpointLost(endpointId, roomId))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) {
                emitEvent(NearbyEvent.UnsupportedPayloadReceived(endpointId, payload.type))
                return
            }

            val bytes = payload.asBytes()
            if (bytes == null) {
                emitEvent(
                    NearbyEvent.PayloadDecodeFailed(
                        endpointId,
                        IllegalArgumentException("Payload bytes are null"),
                    ),
                )
                return
            }

            val packet = runCatching { wireCodec.decode(bytes) }
                .onFailure { cause ->
                    emitEvent(NearbyEvent.PayloadDecodeFailed(endpointId, cause))
                }
                .getOrNull()
                ?: return

            bindPacketMetadata(endpointId, packet)
            emitEvent(NearbyEvent.PacketReceived(endpointId, endpointRegistry.getPeerId(endpointId), packet))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            emitEvent(NearbyEvent.PayloadTransferUpdated(endpointId, update))
        }
    }

    /**
     * Запускает поиск Nearby endpoint-ов с текущим serviceId.
     */
    fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnFailureListener { cause ->
                emitEvent(NearbyEvent.DiscoveryFailed(cause))
            }
    }

    /**
     * Останавливает поиск Nearby endpoint-ов.
     */
    fun stopDiscovery() {
        connectionsClient.stopDiscovery()
    }

    /**
     * Запускает advertising комнаты через endpointName с ROOM_INFO-пакетом.
     */
    fun startAdvertising(room: RoomInfo) {
        val endpointName = wireCodec.encode(
            WirePacket(
                type = WirePacketType.ROOM_INFO,
                roomId = room.roomId,
                roomInfo = room,
            ),
        ).decodeToString()
        val options = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        connectionsClient.startAdvertising(endpointName, serviceId, connectionLifecycleCallback, options)
            .addOnFailureListener { cause ->
                emitEvent(NearbyEvent.AdvertisingFailed(cause))
            }
    }

    /**
     * Останавливает advertising текущего устройства.
     */
    fun stopAdvertising() {
        connectionsClient.stopAdvertising()
    }

    /**
     * Запрашивает Nearby-соединение с найденным endpointId.
     */
    fun connectToEndpoint(endpointId: String) {
        connectionsClient.requestConnection(context.packageName, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { cause ->
                emitEvent(NearbyEvent.ConnectionRequestFailed(endpointId, cause))
            }
    }

    /**
     * Отправляет packet участнику по доменному PeerId.
     */
    fun sendToPeer(peerId: PeerId, packet: WirePacket) {
        val endpointId = endpointRegistry.getEndpointId(peerId)
        if (endpointId == null) {
            emitEvent(
                NearbyEvent.SendFailed(
                    endpointId = null,
                    peerId = peerId,
                    packet = packet,
                    cause = IllegalStateException("Endpoint for peer ${peerId.value} is unknown"),
                ),
            )
            return
        }
        sendToEndpoint(endpointId, peerId, packet)
    }

    /**
     * Отправляет packet всем известным endpoint-ам.
     */
    fun broadcast(packet: WirePacket) {
        val endpointIds = endpointRegistry.getKnownEndpointIds()
        if (endpointIds.isEmpty()) {
            return
        }
        connectionsClient.sendPayload(endpointIds.toList(), Payload.fromBytes(wireCodec.encode(packet)))
            .addOnFailureListener { cause ->
                emitEvent(
                    NearbyEvent.SendFailed(
                        endpointId = null,
                        peerId = null,
                        packet = packet,
                        cause = cause,
                    ),
                )
            }
    }

    /**
     * Отправляет packet напрямую в Nearby endpointId.
     */
    private fun sendToEndpoint(endpointId: String, peerId: PeerId?, packet: WirePacket) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(wireCodec.encode(packet)))
            .addOnFailureListener { cause ->
                emitEvent(NearbyEvent.SendFailed(endpointId, peerId, packet, cause))
            }
    }

    /**
     * Декодирует публичное описание комнаты из Nearby endpointName.
     */
    private fun decodeRoomInfo(endpointInfo: EndpointInfo): RoomInfo? {
        return runCatching {
            val packet = wireCodec.decode(endpointInfo.endpointName.encodeToByteArray())
            packet.roomInfo?.takeIf { packet.type == WirePacketType.ROOM_INFO }
        }.getOrNull()
    }

    /**
     * Обновляет registry по доменным идентификаторам, найденным внутри packet.
     */
    private fun bindPacketMetadata(endpointId: String, packet: WirePacket) {
        packet.roomId?.let { endpointRegistry.bindRoom(endpointId, it) }
        packet.roomInfo?.let { roomInfo ->
            endpointRegistry.bindRoom(endpointId, roomInfo.roomId)
            endpointRegistry.bindPeer(endpointId, roomInfo.host.peerId)
        }
        findPacketPeerId(packet)?.let { peerId ->
            endpointRegistry.bindPeer(endpointId, peerId)
        }
    }

    /**
     * Достаёт наиболее вероятный PeerId отправителя или участника из packet.
     */
    private fun findPacketPeerId(packet: WirePacket): PeerId? {
        return packet.sender?.peerId
            ?: packet.peer?.peerId
            ?: packet.message?.author?.peerId
            ?: packet.roomInfo?.host?.peerId
    }

    /**
     * Публикует событие без подвешивания Nearby callback-потока.
     */
    private fun emitEvent(event: NearbyEvent) {
        _events.tryEmit(event)
    }

    private companion object {
        private const val DEFAULT_SERVICE_ID = "com.yellastro.btration.nearby.ROOM_V1"
        private const val EVENT_BUFFER_CAPACITY = 64
    }
}
