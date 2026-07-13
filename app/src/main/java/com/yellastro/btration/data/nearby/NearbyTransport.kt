package com.yellastro.btration.data.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Strategy
import com.yellastro.btration.data.wire.WireCodec
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.model.WirePacket
import com.yellastro.btration.domain.model.WirePacketType
import com.yellastro.btration.voice.VoiceFrame
import java.io.InputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Фасад Nearby Connections для RoomRuntime: связывает lifecycle, payload и endpoint registry в старый API.
 */
class NearbyTransport(
    context: Context,
    connectionsClient: ConnectionsClient,
    wireCodec: WireCodec,
    strategy: Strategy = Strategy.P2P_STAR,
    serviceId: String = DEFAULT_SERVICE_ID,
) {
    private val endpointRegistry = NearbyEndpointRegistry()
    private val _events = MutableSharedFlow<NearbyEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val payloadTransport = NearbyPayloadTransport(
        connectionsClient = connectionsClient,
        wireCodec = wireCodec,
        emitEvent = ::handlePayloadTransportEvent,
    )
    private val connectionLayer = NearbyConnectionLayer(
        context = context,
        connectionsClient = connectionsClient,
        strategy = strategy,
        serviceId = serviceId,
        payloadCallback = payloadTransport.payloadCallback,
        emitEvent = ::handleConnectionLayerEvent,
    )

    /**
     * Поток событий Nearby-слоя для RoomRuntime.
     */
    val events: SharedFlow<NearbyEvent> = _events.asSharedFlow()

    /**
     * Запускает поиск Nearby endpoint-ов с текущим serviceId.
     */
    fun startDiscovery() {
        connectionLayer.startDiscovery()
    }

    /**
     * Останавливает поиск Nearby endpoint-ов.
     */
    fun stopDiscovery() {
        connectionLayer.stopDiscovery()
    }

    /**
     * Запускает advertising комнаты после сброса старой рекламы, endpoint-ов и registry.
     */
    fun startAdvertising(room: RoomInfo) {
        stopAllEndpointsAndClearState(reason = "prepare_start_advertising")
        connectionLayer.startAdvertising(room)
    }

    /**
     * Останавливает advertising текущего устройства.
     */
    fun stopAdvertising() {
        connectionLayer.stopAdvertising()
    }

    /**
     * Полностью останавливает локальное Nearby-состояние: discovery, advertising, все endpoint-ы и registry.
     */
    fun stopAllEndpointsAndClearState(reason: String) {
        Log.i(TAG, "[stopAllEndpointsAndClearState] Полностью сбрасываем Nearby reason=$reason")
        connectionLayer.stopAllEndpoints(reason)
        endpointRegistry.clear()
    }

    /**
     * Запрашивает Nearby-соединение либо сообщает о пригодном для повторного использования активном endpoint.
     */
    fun connectToEndpoint(endpointId: String) {
        connectionLayer.connectToEndpoint(endpointId)
    }

    /**
     * Явно разрывает все активные Nearby-соединения без сброса discovery-связок.
     */
    fun disconnectAllPeers() {
        connectionLayer.disconnectAllPeers()
    }

    /**
     * Отправляет packet участнику по доменному PeerId.
     */
    fun sendToPeer(peerId: PeerId, packet: WirePacket) {
        val endpointId = endpointRegistry.getEndpointId(peerId)
        if (endpointId == null) {
            Log.w(TAG, "[sendToPeer] Нельзя отправить packet type=${packet.type}, неизвестен endpoint для peerId=${peerId.value}")
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
        Log.i(TAG, "[sendToPeer] Отправляем packet type=${packet.type} peerId=${peerId.value} endpointId=$endpointId roomId=${packet.roomId?.value}")
        payloadTransport.sendPacket(endpointId, peerId, packet) { cause ->
            emitEvent(NearbyEvent.SendFailed(endpointId, peerId, packet, cause))
        }
    }

    /**
     * Отправляет packet всем известным endpoint-ам.
     */
    fun broadcast(packet: WirePacket) {
        val endpointIds = endpointRegistry.getKnownEndpointIds()
        if (endpointIds.isEmpty()) {
            Log.i(TAG, "[broadcast] Некому отправлять packet type=${packet.type}, известных endpoint-ов нет")
            return
        }
        Log.i(TAG, "[broadcast] Рассылаем packet type=${packet.type} endpointCount=${endpointIds.size} roomId=${packet.roomId?.value}")
        payloadTransport.broadcastPacket(endpointIds.toList(), packet) { cause ->
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
     * Отправляет голосовой stream выбранным участникам одним Nearby STREAM payload с исходным PeerId в заголовке.
     */
    fun sendStreamToPeers(peerIds: Set<PeerId>, originPeerId: PeerId, inputStream: InputStream) {
        val endpointIds = peerIds
            .mapNotNull { peerId -> endpointRegistry.getEndpointId(peerId) }
            .distinct()
        if (endpointIds.isEmpty()) {
            Log.w(TAG, "[sendStreamToPeers] Нельзя отправить голосовой stream, endpoint-ы неизвестны peerCount=${peerIds.size}")
            runCatching { inputStream.close() }
            emitEvent(
                NearbyEvent.StreamSendFailed(
                    peerIds = peerIds,
                    cause = IllegalStateException("Endpoints for voice stream are unknown"),
                ),
            )
            return
        }

        payloadTransport.sendStreamToEndpoints(
            endpointIds = endpointIds,
            peerCount = peerIds.size,
            originPeerId = originPeerId,
            inputStream = inputStream,
        ) { cause ->
            emitEvent(NearbyEvent.StreamSendFailed(peerIds, cause))
        }
    }

    /**
     * Отправляет короткий голосовой frame выбранным участникам через Nearby BYTES payload.
     */
    fun sendVoiceFrameToPeers(peerIds: Set<PeerId>, frame: VoiceFrame) {
        val endpointIds = peerIds
            .mapNotNull { peerId -> endpointRegistry.getEndpointId(peerId) }
            .distinct()
        if (endpointIds.isEmpty()) {
            Log.w(TAG, "[sendVoiceFrameToPeers] Нельзя отправить voice frame, endpoint-ы неизвестны peerCount=${peerIds.size} originPeerId=${frame.originPeerId.value}")
            emitEvent(
                NearbyEvent.VoiceFrameSendFailed(
                    peerIds = peerIds,
                    cause = IllegalStateException("Endpoints for voice frame are unknown"),
                ),
            )
            return
        }

        payloadTransport.sendVoiceFrameToEndpoints(endpointIds, frame) { cause ->
            emitEvent(NearbyEvent.VoiceFrameSendFailed(peerIds, cause))
        }
    }

    /**
     * Переводит событие слоя соединений в публичное NearbyEvent и обновляет registry найденных endpoint-ов.
     */
    private fun handleConnectionLayerEvent(event: NearbyConnectionLayerEvent) {
        when (event) {
            is NearbyConnectionLayerEvent.ConnectionInitiated -> {
                emitEvent(NearbyEvent.ConnectionInitiated(event.endpointId, event.connectionInfo))
            }

            is NearbyConnectionLayerEvent.ConnectionAcceptFailed -> {
                emitEvent(NearbyEvent.ConnectionAcceptFailed(event.endpointId, event.cause))
            }

            is NearbyConnectionLayerEvent.ConnectionResult -> {
                emitEvent(NearbyEvent.ConnectionResult(event.endpointId, event.resolution))
            }

            is NearbyConnectionLayerEvent.ConnectionRecoveryRequired -> {
                endpointRegistry.removeEndpoint(event.endpointId)
                emitEvent(NearbyEvent.ConnectionRecoveryRequired(event.endpointId, event.cause))
            }

            is NearbyConnectionLayerEvent.Disconnected -> {
                val peerId = endpointRegistry.getPeerId(event.endpointId)
                Log.i(TAG, "[handleConnectionLayerEvent] Endpoint отключился endpointId=${event.endpointId} peerId=${peerId?.value}")
                emitEvent(NearbyEvent.Disconnected(event.endpointId, peerId))
            }

            is NearbyConnectionLayerEvent.EndpointFound -> {
                event.roomInfo?.let { roomInfo ->
                    endpointRegistry.bindRoom(event.endpointId, roomInfo.roomId)
                    endpointRegistry.bindPeer(event.endpointId, roomInfo.host.peerId)
                }
                emitEvent(NearbyEvent.EndpointFound(event.endpointId, event.endpointInfo, event.roomInfo))
            }

            is NearbyConnectionLayerEvent.EndpointLost -> {
                val roomId = endpointRegistry.getRoomId(event.endpointId)
                Log.i(TAG, "[handleConnectionLayerEvent] Потерян endpoint endpointId=${event.endpointId} roomId=${roomId?.value}")
                endpointRegistry.removeEndpoint(event.endpointId)
                emitEvent(NearbyEvent.EndpointLost(event.endpointId, roomId))
            }

            is NearbyConnectionLayerEvent.DiscoveryFailed -> {
                emitEvent(NearbyEvent.DiscoveryFailed(event.cause))
            }

            is NearbyConnectionLayerEvent.AdvertisingFailed -> {
                emitEvent(NearbyEvent.AdvertisingFailed(event.cause))
            }

            is NearbyConnectionLayerEvent.ConnectionReused -> {
                emitEvent(NearbyEvent.ConnectionReused(event.endpointId))
            }

            is NearbyConnectionLayerEvent.ConnectionRequestFailed -> {
                emitEvent(NearbyEvent.ConnectionRequestFailed(event.endpointId, event.cause))
            }
        }
    }

    /**
     * Переводит событие payload-слоя в публичное NearbyEvent и дополняет его PeerId из registry.
     */
    private fun handlePayloadTransportEvent(event: NearbyPayloadTransportEvent) {
        when (event) {
            is NearbyPayloadTransportEvent.PacketReceived -> {
                bindPacketMetadata(event.endpointId, event.packet)
                val peerId = endpointRegistry.getPeerId(event.endpointId)
                Log.i(
                    TAG,
                    "[handlePayloadTransportEvent] Получен packet endpointId=${event.endpointId} peerId=${peerId?.value} type=${event.packet.type} roomId=${event.packet.roomId?.value}",
                )
                emitEvent(NearbyEvent.PacketReceived(event.endpointId, peerId, event.packet))
            }

            is NearbyPayloadTransportEvent.VoiceFrameReceived -> {
                emitEvent(NearbyEvent.VoiceFrameReceived(event.endpointId, endpointRegistry.getPeerId(event.endpointId), event.frame))
            }

            is NearbyPayloadTransportEvent.StreamReceived -> {
                emitEvent(NearbyEvent.StreamReceived(event.endpointId, endpointRegistry.getPeerId(event.endpointId), event.inputStream))
            }

            is NearbyPayloadTransportEvent.PayloadDecodeFailed -> {
                emitEvent(NearbyEvent.PayloadDecodeFailed(event.endpointId, event.cause))
            }

            is NearbyPayloadTransportEvent.UnsupportedPayloadReceived -> {
                emitEvent(NearbyEvent.UnsupportedPayloadReceived(event.endpointId, event.payloadType))
            }

            is NearbyPayloadTransportEvent.PayloadTransferUpdated -> {
                emitEvent(NearbyEvent.PayloadTransferUpdated(event.endpointId, event.update))
            }
        }
    }

    /**
     * Обновляет registry только по данным прямого Nearby-соседа, не по автору relayed-сообщения.
     */
    private fun bindPacketMetadata(endpointId: String, packet: WirePacket) {
        packet.roomId?.let { endpointRegistry.bindRoom(endpointId, it) }
        packet.roomInfo?.let { roomInfo ->
            endpointRegistry.bindRoom(endpointId, roomInfo.roomId)
        }
        findDirectEndpointPeerId(packet)?.let { peerId ->
            bindDirectPeerIfStable(endpointId, peerId, packet)
        }
    }

    /**
     * Привязывает endpoint к прямому соседу, разрешая заменить только временный advertised-host после JOIN_ACCEPTED.
     */
    private fun bindDirectPeerIfStable(endpointId: String, peerId: PeerId, packet: WirePacket) {
        val existingPeerId = endpointRegistry.getPeerId(endpointId)
        if (existingPeerId != null && existingPeerId != peerId) {
            if (canReplaceAdvertisedHostPeer(existingPeerId, packet)) {
                endpointRegistry.bindPeer(endpointId, peerId)
                Log.i(
                    TAG,
                    "[bindDirectPeerIfStable] Временный host peer заменен реальным endpointId=$endpointId advertisedPeerId=${existingPeerId.value} realPeerId=${peerId.value} packetType=${packet.type}",
                )
                return
            }
            Log.w(
                TAG,
                "[bindDirectPeerIfStable] Не перезаписываем прямой endpoint endpointId=$endpointId existingPeerId=${existingPeerId.value} packetPeerId=${peerId.value} packetType=${packet.type}",
            )
            return
        }
        val bound = endpointRegistry.bindPeerIfEndpointFree(endpointId, peerId)
        Log.i(
            TAG,
            "[bindDirectPeerIfStable] Endpoint привязан к прямому peer endpointId=$endpointId peerId=${peerId.value} packetType=${packet.type} bound=$bound",
        )
    }

    /**
     * Проверяет, можно ли заменить временный host PeerId из рекламы на реальный PeerId из прямого handshake-пакета.
     */
    private fun canReplaceAdvertisedHostPeer(existingPeerId: PeerId, packet: WirePacket): Boolean {
        return NearbyRoomAdvertisement.isAdvertisedHostPeerId(existingPeerId) &&
            packet.type in REAL_HOST_ID_PACKET_TYPES
    }

    /**
     * Возвращает PeerId только если packet описывает прямого соседа, а не автора ретранслированного сообщения.
     */
    private fun findDirectEndpointPeerId(packet: WirePacket): PeerId? {
        return when (packet.type) {
            WirePacketType.JOIN_REQUEST -> packet.peer?.peerId ?: packet.sender?.peerId
            WirePacketType.JOIN_ACCEPTED,
            WirePacketType.JOIN_REJECTED,
            WirePacketType.MEMBER_LIST,
            WirePacketType.ROOM_CLOSED,
            WirePacketType.PING,
            WirePacketType.PONG,
            WirePacketType.ROOM_INFO,
            WirePacketType.VOICE_TRANSPORT_INFO,
            -> packet.sender?.peerId ?: packet.roomInfo?.host?.peerId

            WirePacketType.MEMBER_JOINED,
            WirePacketType.MEMBER_LEFT,
            WirePacketType.CHAT_MESSAGE,
            -> null
        }
    }

    /**
     * Публикует событие без подвешивания Nearby callback-потока.
     */
    private fun emitEvent(event: NearbyEvent) {
        val emitted = _events.tryEmit(event)
        if (!emitted) {
            Log.w(TAG, "[emitEvent] Не удалось опубликовать NearbyEvent type=${event.javaClass.simpleName}")
        }
    }

    private companion object {
        private const val TAG = "NearbyTransport"
        private const val DEFAULT_SERVICE_ID = "com.yellastro.btration.nearby.ROOM_V1"
        private const val EVENT_BUFFER_CAPACITY = 64
        private val REAL_HOST_ID_PACKET_TYPES = setOf(
            WirePacketType.JOIN_ACCEPTED,
            WirePacketType.JOIN_REJECTED,
            WirePacketType.MEMBER_LIST,
            WirePacketType.ROOM_CLOSED,
            WirePacketType.PING,
            WirePacketType.PONG,
            WirePacketType.ROOM_INFO,
            WirePacketType.VOICE_TRANSPORT_INFO,
        )
    }
}
