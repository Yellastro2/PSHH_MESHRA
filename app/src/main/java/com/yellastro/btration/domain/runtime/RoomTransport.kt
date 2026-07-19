package com.yellastro.btration.domain.runtime

import android.util.Log
import com.yellastro.btration.data.nearby.NearbyRoomAdvertisement
import com.yellastro.btration.data.wire.WireCodec
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.model.RoomTransportMode
import com.yellastro.btration.domain.model.WirePacket
import com.yellastro.btration.domain.model.WirePacketType
import com.yellastro.btration.domain.transport.NeighborAdvertisement
import com.yellastro.btration.domain.transport.NeighborCandidateId
import com.yellastro.btration.domain.transport.NeighborDiscoveryMode
import com.yellastro.btration.domain.transport.NeighborLinkId
import com.yellastro.btration.domain.transport.NeighborTopology
import com.yellastro.btration.domain.transport.NeighborTransport
import com.yellastro.btration.domain.transport.NeighborTransportEvent
import com.yellastro.btration.domain.transport.PeerLinkResolver
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Протокол комнаты поверх topology-aware NeighborTransport: WirePacket, реклама режима комнаты и связи PeerId с linkId.
 */
class RoomTransport(
    private val neighborTransport: NeighborTransport,
    private val wireCodec: WireCodec,
    private val externalScope: CoroutineScope,
    private val shouldIgnoreMessage: (ByteArray) -> Boolean = { false },
    private val shouldAcceptConnection: (String) -> Boolean = { true },
) : PeerLinkResolver {
    private val endpointToPeer = mutableMapOf<String, PeerId>()
    private val peerToEndpoint = mutableMapOf<PeerId, String>()
    private val endpointToRoom = mutableMapOf<String, RoomId>()
    private val roomToEndpoints = mutableMapOf<RoomId, MutableSet<String>>()
    private val _events = MutableSharedFlow<RoomTransportEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /**
     * Поток событий протокола комнаты для RoomRuntime.
     */
    val events: SharedFlow<RoomTransportEvent> = _events.asSharedFlow()

    init {
        externalScope.launch {
            Log.i(TAG, "[init] Подписываем RoomTransport на события NeighborTransport")
            neighborTransport.neighborEvents.collect(::handleNeighborEvent)
        }
    }

    /**
     * Запускает чередующийся поиск Star/MESHRA для лобби либо фиксированный поиск для восстановления конкретной комнаты.
     */
    fun startDiscovery(roomTransportMode: RoomTransportMode? = null) {
        val discoveryMode = when (roomTransportMode) {
            null -> NeighborDiscoveryMode.ALTERNATING
            RoomTransportMode.NEARBY_STAR -> NeighborDiscoveryMode.STAR_ONLY
            RoomTransportMode.MESHRA -> NeighborDiscoveryMode.CLUSTER_ONLY
        }
        neighborTransport.startDiscovery(discoveryMode)
    }

    /**
     * Останавливает поиск комнат через соседский транспорт.
     */
    fun stopDiscovery() {
        neighborTransport.stopDiscovery()
    }

    /**
     * Запускает рекламу комнаты через короткую визитку endpointName и физическую topology режима комнаты.
     */
    fun startAdvertising(room: RoomInfo) {
        stopAllEndpointsAndClearState(reason = "prepare_start_advertising")
        neighborTransport.startAdvertising(
            advertisement = NeighborAdvertisement(NearbyRoomAdvertisement.fromRoom(room).encode()),
            topology = room.roomTransportMode.toNeighborTopology(),
        )
    }

    /**
     * Останавливает рекламу текущего устройства.
     */
    fun stopAdvertising() {
        neighborTransport.stopAdvertising()
    }

    /**
     * Проверяет, является ли RoomId временным идентификатором из публичной рекламы комнаты.
     */
    fun isAdvertisedRoomId(roomId: RoomId): Boolean {
        return NearbyRoomAdvertisement.isAdvertisedRoomId(roomId)
    }

    /**
     * Полностью сбрасывает соседский транспорт и room-level связи linkId с PeerId/RoomId.
     */
    fun stopAllEndpointsAndClearState(reason: String) {
        Log.i(TAG, "[stopAllEndpointsAndClearState] Полностью сбрасываем RoomTransport reason=$reason")
        neighborTransport.stopAll(reason)
        clearRegistry()
    }

    /**
     * Запрашивает соединение с endpoint-кандидатом в topology, объявленной режимом комнаты.
     */
    fun connectToEndpoint(endpointId: String, roomTransportMode: RoomTransportMode = RoomTransportMode.NEARBY_STAR) {
        neighborTransport.connect(
            candidateId = NeighborCandidateId(endpointId),
            topology = roomTransportMode.toNeighborTopology(),
        )
    }

    /**
     * Явно разрывает все активные соседские соединения.
     */
    fun disconnectAllPeers() {
        neighborTransport.disconnectAll()
    }

    /**
     * Отправляет wire packet участнику по доменному PeerId.
     */
    fun sendToPeer(peerId: PeerId, packet: WirePacket) {
        val endpointId = getEndpointId(peerId)
        if (endpointId == null) {
            Log.w(TAG, "[sendToPeer] Нельзя отправить packet type=${packet.type}, неизвестен endpoint для peerId=${peerId.value}")
            emitEvent(
                RoomTransportEvent.SendFailed(
                    endpointId = null,
                    peerId = peerId,
                    cause = IllegalStateException("Endpoint for peer ${peerId.value} is unknown"),
                ),
            )
            return
        }
        Log.i(TAG, "[sendToPeer] Отправляем packet type=${packet.type} peerId=${peerId.value} endpointId=$endpointId roomId=${packet.roomId?.value}")
        neighborTransport.sendMessage(NeighborLinkId(endpointId), wireCodec.encode(packet)) { cause ->
            emitEvent(RoomTransportEvent.SendFailed(endpointId = endpointId, peerId = peerId, cause = cause))
        }
    }

    /**
     * Отправляет wire packet всем известным endpoint-ам.
     */
    fun broadcast(packet: WirePacket) {
        val endpointIds = getKnownEndpointIds()
        if (endpointIds.isEmpty()) {
            Log.i(TAG, "[broadcast] Некому отправлять packet type=${packet.type}, известных endpoint-ов нет")
            return
        }
        Log.i(TAG, "[broadcast] Рассылаем packet type=${packet.type} endpointCount=${endpointIds.size} roomId=${packet.roomId?.value}")
        neighborTransport.sendMessage(
            linkIds = endpointIds.map(::NeighborLinkId),
            bytes = wireCodec.encode(packet),
        ) { cause ->
            emitEvent(RoomTransportEvent.SendFailed(endpointId = null, peerId = null, cause = cause))
        }
    }

    /**
     * Возвращает linkId прямого транспорта для доменного PeerId.
     */
    override fun linkIdForPeer(peerId: PeerId): NeighborLinkId? {
        return getEndpointId(peerId)?.let(::NeighborLinkId)
    }

    /**
     * Возвращает доменный PeerId, связанный с прямым linkId.
     */
    override fun peerIdForLink(linkId: NeighborLinkId): PeerId? {
        return getPeerId(linkId.value)
    }

    /**
     * Переводит события нижнего транспорта в события room protocol.
     */
    private fun handleNeighborEvent(event: NeighborTransportEvent) {
        when (event) {
            is NeighborTransportEvent.CandidateFound -> handleCandidateFound(event)
            is NeighborTransportEvent.CandidateLost -> handleCandidateLost(event)
            is NeighborTransportEvent.ConnectionInitiated -> {
                handleConnectionInitiated(event)
            }
            is NeighborTransportEvent.ConnectionAcceptFailed -> emitEvent(
                RoomTransportEvent.ConnectionAcceptFailed(event.connectionId.value, event.cause),
            )
            is NeighborTransportEvent.ConnectionRejectFailed -> Unit
            is NeighborTransportEvent.ConnectionRequestFailed -> emitEvent(
                RoomTransportEvent.ConnectionRequestFailed(event.candidateId.value, event.cause),
            )
            is NeighborTransportEvent.ConnectionRecoveryRequired -> {
                removeEndpoint(event.linkId.value)
                emitEvent(RoomTransportEvent.ConnectionRecoveryRequired(event.linkId.value, event.cause))
            }
            is NeighborTransportEvent.LinkConnected -> {
                if (event.reused) {
                    emitEvent(RoomTransportEvent.ConnectionReused(event.linkId.value))
                } else {
                    emitEvent(RoomTransportEvent.ConnectionResult(event.linkId.value, success = true, statusCode = event.statusCode))
                }
            }
            is NeighborTransportEvent.LinkConnectionFailed -> emitEvent(
                RoomTransportEvent.ConnectionResult(event.linkId.value, success = false, statusCode = event.statusCode),
            )
            is NeighborTransportEvent.LinkDisconnected -> {
                val peerId = getPeerId(event.linkId.value)
                Log.i(TAG, "[handleNeighborEvent] Endpoint отключился endpointId=${event.linkId.value} peerId=${peerId?.value}")
                emitEvent(RoomTransportEvent.Disconnected(event.linkId.value, peerId))
            }
            is NeighborTransportEvent.DiscoveryFailed -> emitEvent(RoomTransportEvent.DiscoveryFailed(event.cause))
            is NeighborTransportEvent.AdvertisingFailed -> emitEvent(RoomTransportEvent.AdvertisingFailed(event.cause))
            is NeighborTransportEvent.MessageReceived -> handleMessageReceived(event.linkId.value, event.bytes)
            is NeighborTransportEvent.StreamReceived -> emitEvent(
                RoomTransportEvent.StreamReceived(event.linkId.value, getPeerId(event.linkId.value), event.inputStream),
            )
            is NeighborTransportEvent.UnsupportedPayloadReceived -> Unit
            is NeighborTransportEvent.PayloadReadFailed -> emitEvent(
                RoomTransportEvent.PayloadDecodeFailed(event.linkId.value, event.cause),
            )
            is NeighborTransportEvent.PayloadTransferUpdated -> Unit
        }
    }

    /**
     * Принимает или отклоняет входящий connection request по endpointName до создания прямого link-а.
     */
    private fun handleConnectionInitiated(event: NeighborTransportEvent.ConnectionInitiated) {
        if (!shouldAcceptConnection(event.endpointName)) {
            Log.i(TAG, "[handleConnectionInitiated] Connection request отклонен политикой endpointName=${event.endpointName}")
            neighborTransport.rejectConnection(event.connectionId)
            return
        }
        neighborTransport.acceptConnection(event.connectionId)
        emitEvent(RoomTransportEvent.ConnectionInitiated(event.connectionId.value))
    }

    /**
     * Обрабатывает найденный endpoint как возможную комнату.
     */
    private fun handleCandidateFound(event: NeighborTransportEvent.CandidateFound) {
        val endpointId = event.candidate.candidateId.value
        val roomInfo = decodeRoomInfo(event.candidate.endpointName)
        roomInfo?.let { decodedRoomInfo ->
            val gatewayPeer = decodedRoomInfo.gateway ?: decodedRoomInfo.host
            Log.i(
                TAG,
                "[handleCandidateFound] Endpoint распознан как комната roomId=${decodedRoomInfo.roomId.value} roomName=${decodedRoomInfo.name} gatewayPeerId=${gatewayPeer.peerId.value}",
            )
            bindRoom(endpointId, decodedRoomInfo.roomId)
            bindPeer(endpointId, gatewayPeer.peerId)
        } ?: run {
            Log.w(TAG, "[handleCandidateFound] Endpoint не содержит корректную визитку комнаты endpointId=$endpointId")
        }
        emitEvent(RoomTransportEvent.EndpointFound(endpointId, roomInfo))
    }

    /**
     * Удаляет endpoint из room-level registry и сообщает runtime о потере комнаты.
     */
    private fun handleCandidateLost(event: NeighborTransportEvent.CandidateLost) {
        val endpointId = event.candidateId.value
        val roomId = getRoomId(endpointId)
        Log.i(TAG, "[handleCandidateLost] Потерян endpoint endpointId=$endpointId roomId=${roomId?.value}")
        removeEndpoint(endpointId)
        emitEvent(RoomTransportEvent.EndpointLost(endpointId, roomId))
    }

    /**
     * Декодирует входящее bytes-сообщение как WirePacket комнаты.
     */
    private fun handleMessageReceived(endpointId: String, bytes: ByteArray) {
        if (shouldIgnoreMessage(bytes)) {
            return
        }
        val packet = runCatching { wireCodec.decode(bytes) }
            .onFailure { cause ->
                Log.w(TAG, "[handleMessageReceived] Не удалось декодировать message endpointId=$endpointId bytes=${bytes.size}: ${cause.message}", cause)
                emitEvent(RoomTransportEvent.PayloadDecodeFailed(endpointId, cause))
            }
            .getOrNull()
            ?: return

        bindPacketMetadata(endpointId, packet)
        val peerId = getPeerId(endpointId)
        Log.i(
            TAG,
            "[handleMessageReceived] Получен packet endpointId=$endpointId peerId=${peerId?.value} type=${packet.type} roomId=${packet.roomId?.value}",
        )
        emitEvent(RoomTransportEvent.PacketReceived(endpointId, peerId, packet))
    }

    /**
     * Декодирует публичное описание комнаты из короткой endpointName-визитки.
     */
    private fun decodeRoomInfo(endpointName: String): RoomInfo? {
        return runCatching {
            NearbyRoomAdvertisement.decode(endpointName)
                ?.toRoomInfo()
        }.onFailure { cause ->
            Log.w(TAG, "[decodeRoomInfo] Не удалось декодировать endpointName как визитку комнаты: ${cause.message}", cause)
        }.getOrNull()
    }

    /**
     * Обновляет registry только по данным прямого соседа, не по автору relayed-сообщения.
     */
    private fun bindPacketMetadata(endpointId: String, packet: WirePacket) {
        packet.roomId?.let { bindRoom(endpointId, it) }
        packet.roomInfo?.let { roomInfo -> bindRoom(endpointId, roomInfo.roomId) }
        findDirectEndpointPeerId(packet)?.let { peerId ->
            bindDirectPeerIfStable(endpointId, peerId, packet)
        }
    }

    /**
     * Привязывает endpoint к прямому peer, разрешая заменить только временный advertised-host после JOIN_ACCEPTED.
     */
    private fun bindDirectPeerIfStable(endpointId: String, peerId: PeerId, packet: WirePacket) {
        val existingPeerId = getPeerId(endpointId)
        if (existingPeerId != null && existingPeerId != peerId) {
            if (canReplaceAdvertisedHostPeer(existingPeerId, packet)) {
                bindPeer(endpointId, peerId)
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
        val bound = bindPeerIfEndpointFree(endpointId, peerId)
        Log.i(
            TAG,
            "[bindDirectPeerIfStable] Endpoint привязан к прямому peer endpointId=$endpointId peerId=${peerId.value} packetType=${packet.type} bound=$bound",
        )
    }

    /**
     * Проверяет, можно ли заменить временный host PeerId из рекламы на реальный PeerId из handshake-пакета.
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
     * Связывает endpoint с PeerId, заменяя старую прямую связь при новом discovery/handshake.
     */
    private fun bindPeer(endpointId: String, peerId: PeerId) {
        endpointToPeer[endpointId]?.let(peerToEndpoint::remove)
        peerToEndpoint[peerId]?.let(endpointToPeer::remove)
        endpointToPeer[endpointId] = peerId
        peerToEndpoint[peerId] = endpointId
    }

    /**
     * Связывает endpoint с PeerId только если endpoint еще не закреплен за другим прямым соседом.
     */
    private fun bindPeerIfEndpointFree(endpointId: String, peerId: PeerId): Boolean {
        val existingPeerId = endpointToPeer[endpointId]
        if (existingPeerId != null) {
            return existingPeerId == peerId
        }
        peerToEndpoint[peerId]?.let(endpointToPeer::remove)
        endpointToPeer[endpointId] = peerId
        peerToEndpoint[peerId] = endpointId
        return true
    }

    /**
     * Связывает endpoint с найденной или активной комнатой.
     */
    private fun bindRoom(endpointId: String, roomId: RoomId) {
        endpointToRoom[endpointId]?.let { previousRoomId ->
            roomToEndpoints[previousRoomId]?.remove(endpointId)
            if (roomToEndpoints[previousRoomId].isNullOrEmpty()) {
                roomToEndpoints.remove(previousRoomId)
            }
        }
        endpointToRoom[endpointId] = roomId
        roomToEndpoints.getOrPut(roomId) { mutableSetOf() }.add(endpointId)
    }

    /**
     * Возвращает PeerId, связанный с endpoint.
     */
    private fun getPeerId(endpointId: String): PeerId? {
        return endpointToPeer[endpointId]
    }

    /**
     * Возвращает endpoint, связанный с PeerId.
     */
    private fun getEndpointId(peerId: PeerId): String? {
        return peerToEndpoint[peerId]
    }

    /**
     * Возвращает RoomId, связанный с endpoint.
     */
    private fun getRoomId(endpointId: String): RoomId? {
        return endpointToRoom[endpointId]
    }

    /**
     * Возвращает все endpointId, с которыми уже связаны room или peer данные.
     */
    private fun getKnownEndpointIds(): Set<String> {
        return endpointToPeer.keys + endpointToRoom.keys
    }

    /**
     * Удаляет все room-level связи для endpoint.
     */
    private fun removeEndpoint(endpointId: String) {
        endpointToPeer.remove(endpointId)?.let(peerToEndpoint::remove)
        endpointToRoom.remove(endpointId)?.let { roomId ->
            roomToEndpoints[roomId]?.remove(endpointId)
            if (roomToEndpoints[roomId].isNullOrEmpty()) {
                roomToEndpoints.remove(roomId)
            }
        }
    }

    /**
     * Полностью очищает room-level registry.
     */
    private fun clearRegistry() {
        endpointToPeer.clear()
        peerToEndpoint.clear()
        endpointToRoom.clear()
        roomToEndpoints.clear()
    }

    /**
     * Публикует событие RoomTransport без подвешивания callback-потока.
     */
    private fun emitEvent(event: RoomTransportEvent) {
        if (!_events.tryEmit(event)) {
            Log.w(TAG, "[emitEvent] Не удалось опубликовать RoomTransportEvent type=${event.javaClass.simpleName}")
        }
    }

    /**
     * Преобразует тип комнаты в физическую topology нижнего NeighborTransport.
     */
    private fun RoomTransportMode.toNeighborTopology(): NeighborTopology {
        return when (this) {
            RoomTransportMode.NEARBY_STAR -> NeighborTopology.STAR
            RoomTransportMode.MESHRA -> NeighborTopology.CLUSTER
        }
    }

    private companion object {
        private const val TAG = "RoomTransport"
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

/**
 * Событие room protocol поверх NeighborTransport.
 */
sealed class RoomTransportEvent {
    /**
     * Найдена комната в nearby discovery.
     */
    data class EndpointFound(val endpointId: String, val roomInfo: RoomInfo?) : RoomTransportEvent()

    /**
     * Найденный endpoint потерян.
     */
    data class EndpointLost(val endpointId: String, val roomId: RoomId?) : RoomTransportEvent()

    /**
     * Входящее соединение начато.
     */
    data class ConnectionInitiated(val endpointId: String) : RoomTransportEvent()

    /**
     * Попытка соединения завершилась результатом.
     */
    data class ConnectionResult(val endpointId: String, val success: Boolean, val statusCode: Int) : RoomTransportEvent()

    /**
     * Уже установленное соединение переиспользовано.
     */
    data class ConnectionReused(val endpointId: String) : RoomTransportEvent()

    /**
     * Требуется recovery после полуподключенного endpoint.
     */
    data class ConnectionRecoveryRequired(val endpointId: String, val cause: Throwable) : RoomTransportEvent()

    /**
     * Активный endpoint отключился.
     */
    data class Disconnected(val endpointId: String, val peerId: PeerId?) : RoomTransportEvent()

    /**
     * Входящее bytes-сообщение декодировано как WirePacket.
     */
    data class PacketReceived(val endpointId: String, val peerId: PeerId?, val packet: WirePacket) : RoomTransportEvent()

    /**
     * Входящий stream payload получен от endpoint.
     */
    data class StreamReceived(val endpointId: String, val peerId: PeerId?, val inputStream: InputStream) : RoomTransportEvent()

    /**
     * Advertising не удалось запустить.
     */
    data class AdvertisingFailed(val cause: Throwable) : RoomTransportEvent()

    /**
     * Discovery не удалось запустить.
     */
    data class DiscoveryFailed(val cause: Throwable) : RoomTransportEvent()

    /**
     * Request connection завершился ошибкой.
     */
    data class ConnectionRequestFailed(val endpointId: String, val cause: Throwable) : RoomTransportEvent()

    /**
     * Accept connection завершился ошибкой.
     */
    data class ConnectionAcceptFailed(val endpointId: String, val cause: Throwable) : RoomTransportEvent()

    /**
     * Payload не удалось декодировать как пакет комнаты.
     */
    data class PayloadDecodeFailed(val endpointId: String, val cause: Throwable) : RoomTransportEvent()

    /**
     * Отправка room packet завершилась ошибкой.
     */
    data class SendFailed(val endpointId: String?, val peerId: PeerId?, val cause: Throwable) : RoomTransportEvent()
}
