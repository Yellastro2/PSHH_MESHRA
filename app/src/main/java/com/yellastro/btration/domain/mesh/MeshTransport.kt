package com.yellastro.btration.domain.mesh

import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.yellastro.btration.domain.model.ChatMessage
import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomTransportMode
import com.yellastro.btration.domain.transport.NeighborAdvertisement
import com.yellastro.btration.domain.transport.NeighborCandidateId
import com.yellastro.btration.domain.transport.NeighborDiscoveryMode
import com.yellastro.btration.domain.transport.NeighborLinkId
import com.yellastro.btration.domain.transport.NeighborTopology
import com.yellastro.btration.domain.transport.NeighborTransport
import com.yellastro.btration.domain.transport.NeighborTransportEvent
import com.yellastro.btration.voice.VoiceFrame
import com.yellastro.btration.voice.VoiceTransportMode
import java.util.LinkedHashSet
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Mesh-слой поверх Cluster topology NeighborTransport: flood-ит room events/voice и меряет здоровье только тех
 * прямых link-ов, которые явно подтверждены как MESHRA-соединения.
 *
 * Runtime включает этот слой для MESHRA-комнат; Nearby Star продолжает работать через обычный RoomTransport.
 */
class MeshTransport(
    private val selfPeerId: PeerId,
    private val neighborTransport: NeighborTransport,
    private val codec: MeshCodec,
    private val voicePacketCodec: MeshVoicePacketCodec,
    private val externalScope: CoroutineScope,
    private val maxTtl: Int = DEFAULT_TTL,
    private val acceptIncomingConnections: Boolean = true,
) {
    private val activeLinks = mutableSetOf<NeighborLinkId>()
    private val pendingGatewayConnections = mutableSetOf<NeighborCandidateId>()
    private val pendingGatewayPeerIds = mutableMapOf<NeighborCandidateId, PeerId>()
    private val linkPeerIds = mutableMapOf<NeighborLinkId, PeerId>()
    private val linkRoomIds = mutableMapOf<NeighborLinkId, RoomId>()
    private val ignoredNonMeshPayloadLinks = mutableSetOf<NeighborLinkId>()
    private val seenEventIds = mutableSetOf<MeshRoomEventId>()
    private val seenVoiceFrameKeys = LinkedHashSet<Long>()
    private val roomSnapshots = mutableMapOf<RoomId, MeshRoomSnapshot>()
    private val roomVoicePeerIndexes = mutableMapOf<RoomId, Map<Int, PeerId?>>()
    private val advertisingLock = Any()
    private val voiceSessionLock = Any()
    private var advertisedEndpointName: String? = null
    private var nextLocalVoiceSessionId = 0
    private var activeLocalVoiceSessionId: Int? = null
    private val _events = MutableSharedFlow<MeshTransportEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val _rooms = MutableStateFlow<Map<RoomId, MeshRoomSnapshot>>(emptyMap())
    private val _directPeerIds = MutableStateFlow<Set<PeerId>>(emptySet())
    private val _linkHealth = MutableStateFlow<Map<NeighborLinkId, MeshLinkHealth>>(emptyMap())
    private val _peerConnectionStates = MutableStateFlow<Map<PeerId, MeshPeerConnectionState>>(emptyMap())
    private val heartbeatCodec = MeshHeartbeatCodec()
    private val linkHealthTracker = MeshLinkHealthTracker(
        heartbeatTimeoutMillis = HEARTBEAT_TIMEOUT_MILLIS,
        heartbeatLostMillis = HEARTBEAT_LOST_MILLIS,
        lossWindowSize = HEARTBEAT_LOSS_WINDOW_SIZE,
    )
    private val lastLoggedLinkStatuses = mutableMapOf<NeighborLinkId, MeshLinkStatus>()
    private val lastLoggedLinkMetricMillis = mutableMapOf<NeighborLinkId, Long>()
    private var heartbeatJob: Job? = null

    /**
     * События mesh-слоя для будущего runtime: принятые room events, snapshot-ы и ошибки.
     */
    val events: SharedFlow<MeshTransportEvent> = _events.asSharedFlow()

    /**
     * Текущие snapshot-ы известных mesh-комнат, рассчитанные инкрементально по принятым событиям.
     */
    val rooms: StateFlow<Map<RoomId, MeshRoomSnapshot>> = _rooms.asStateFlow()

    /**
     * PeerId соседей, с которыми у локального устройства сейчас есть прямой mesh link без LOST heartbeat-статуса.
     */
    val directPeerIds: StateFlow<Set<PeerId>> = _directPeerIds.asStateFlow()

    /**
     * Текущее диагностическое состояние прямых mesh link-ов: статус, RTT и потери heartbeat.
     */
    val linkHealth: StateFlow<Map<NeighborLinkId, MeshLinkHealth>> = _linkHealth.asStateFlow()

    /**
     * Текущее состояние прямых mesh-соседей по PeerId: бинарный статус для UI и ping/loss для диагностики.
     */
    val peerConnectionStates: StateFlow<Map<PeerId, MeshPeerConnectionState>> = _peerConnectionStates.asStateFlow()

    init {
        externalScope.launch {
            Log.i(TAG, "[init] Подписываем MeshTransport на события NeighborTransport")
            neighborTransport.neighborEvents.collect(::handleNeighborEvent)
        }
    }

    /**
     * Запускает поиск mesh gateway-ев только в Cluster topology.
     */
    fun startDiscovery() {
        neighborTransport.startDiscovery(NeighborDiscoveryMode.CLUSTER_ONLY)
    }

    /**
     * Останавливает поиск mesh gateway-ев через нижний соседский транспорт.
     */
    fun stopDiscovery() {
        neighborTransport.stopDiscovery()
    }

    /**
     * Начинает рекламировать текущий peer как gateway и пропускает повторный старт, пока нижний transport уже advertising.
     */
    fun startAdvertising(snapshot: MeshRoomSnapshot, gateway: Peer) {
        val advertisement = MeshRoomAdvertisement.fromSnapshot(snapshot, gateway)
        val endpointName = advertisement.encode()
        val shouldStart = synchronized(advertisingLock) {
            if (advertisedEndpointName != null) {
                false
            } else {
                advertisedEndpointName = endpointName
                true
            }
        }
        if (!shouldStart) {
            Log.i(TAG, "[startAdvertising] Mesh-реклама уже запущена, повторный старт пропущен roomId=${snapshot.roomId.value} gatewayPeerId=${gateway.peerId.value}")
            return
        }
        neighborTransport.startAdvertising(
            advertisement = NeighborAdvertisement(endpointName),
            topology = NeighborTopology.CLUSTER,
        )
        Log.i(TAG, "[startAdvertising] Mesh-реклама запущена roomId=${snapshot.roomId.value} gatewayPeerId=${gateway.peerId.value}")
    }

    /**
     * Останавливает mesh-рекламу текущего gateway.
     */
    fun stopAdvertising() {
        synchronized(advertisingLock) {
            advertisedEndpointName = null
        }
        neighborTransport.stopAdvertising()
    }

    /**
     * Запрашивает соединение с mesh gateway, найденным через discovery, и заранее запоминает PeerId gateway-а.
     */
    fun connectToGateway(candidateId: NeighborCandidateId, gatewayPeerId: PeerId? = null) {
        if (hasLinkOrPending(candidateId)) {
            Log.i(TAG, "[connectToGateway] Mesh gateway уже подключен или ожидает connection candidateId=${candidateId.value}")
            return
        }
        pendingGatewayConnections.add(candidateId)
        if (gatewayPeerId != null) {
            pendingGatewayPeerIds[candidateId] = gatewayPeerId
        }
        neighborTransport.connect(candidateId, NeighborTopology.CLUSTER)
    }

    /**
     * Возвращает количество готовых mesh link-ов, которые не помечены heartbeat-ом как LOST и пригодны для payload-ов.
     */
    fun activeLinkCount(): Int {
        return usablePayloadLinkCount()
    }

    /**
     * Возвращает количество пригодных для payload-ов и уже запрошенных mesh link-ов.
     */
    fun linkOrPendingCount(): Int {
        return usablePayloadLinkCount() + pendingGatewayConnections.size
    }

    /**
     * Возвращает true, если gateway уже подключен пригодным link-ом или ожидает завершения connection request.
     */
    fun hasLinkOrPending(candidateId: NeighborCandidateId): Boolean {
        return isPayloadLinkUsable(NeighborLinkId(candidateId.value)) || candidateId in pendingGatewayConnections
    }

    /**
     * Разрывает все активные соседские link-и mesh-слоя.
     */
    fun disconnectAll() {
        neighborTransport.disconnectAll()
        activeLinks.clear()
        pendingGatewayConnections.clear()
        pendingGatewayPeerIds.clear()
        linkPeerIds.clear()
        linkRoomIds.clear()
        ignoredNonMeshPayloadLinks.clear()
        seenVoiceFrameKeys.clear()
        clearLocalVoiceSession()
        clearLinkHealth()
        publishDirectPeerIds()
        Log.i(TAG, "[disconnectAll] Все mesh link-и разорваны")
    }

    /**
     * Полностью останавливает нижний транспорт и очищает локальные mesh-связи.
     */
    fun stopAll(reason: String) {
        neighborTransport.stopAll(reason)
        activeLinks.clear()
        pendingGatewayConnections.clear()
        pendingGatewayPeerIds.clear()
        linkPeerIds.clear()
        linkRoomIds.clear()
        ignoredNonMeshPayloadLinks.clear()
        seenEventIds.clear()
        seenVoiceFrameKeys.clear()
        clearLocalVoiceSession()
        roomSnapshots.clear()
        roomVoicePeerIndexes.clear()
        clearLinkHealth()
        synchronized(advertisingLock) {
            advertisedEndpointName = null
        }
        publishRooms()
        publishDirectPeerIds()
        Log.i(TAG, "[stopAll] MeshTransport остановлен и очищен reason=$reason")
    }

    /**
     * Создает событие вступления участника и flood-ит его всем текущим соседям.
     */
    fun publishMemberJoined(
        roomId: RoomId,
        roomName: String,
        knownHost: Peer,
        member: Peer,
        roomTransportMode: RoomTransportMode = RoomTransportMode.MESHRA,
        voiceTransportMode: VoiceTransportMode = VoiceTransportMode.WIFI_DIRECT_UDP,
        isDirectAudioReady: Boolean = false,
    ) {
        publishEvent(
            MeshRoomEvent(
                eventId = newEventId(),
                roomId = roomId,
                roomName = roomName,
                knownHost = knownHost,
                roomTransportMode = roomTransportMode,
                voiceTransportMode = voiceTransportMode,
                isDirectAudioReady = isDirectAudioReady,
                authorPeerId = selfPeerId,
                type = MeshRoomEventType.MEMBER_JOINED,
                peer = member,
                createdAtMillis = now(),
            ),
        )
    }

    /**
     * Создает событие штатного выхода участника и flood-ит его всем текущим соседям.
     */
    fun publishMemberLeft(
        roomId: RoomId,
        roomName: String,
        knownHost: Peer,
        member: Peer,
        roomTransportMode: RoomTransportMode = RoomTransportMode.MESHRA,
        voiceTransportMode: VoiceTransportMode = VoiceTransportMode.WIFI_DIRECT_UDP,
        isDirectAudioReady: Boolean = false,
    ) {
        publishEvent(
            MeshRoomEvent(
                eventId = newEventId(),
                roomId = roomId,
                roomName = roomName,
                knownHost = knownHost,
                roomTransportMode = roomTransportMode,
                voiceTransportMode = voiceTransportMode,
                isDirectAudioReady = isDirectAudioReady,
                authorPeerId = selfPeerId,
                type = MeshRoomEventType.MEMBER_LEFT,
                peer = member,
                createdAtMillis = now(),
            ),
        )
    }

    /**
     * Создает событие разрыва участника и flood-ит его всем текущим соседям.
     */
    fun publishPeerDisconnected(
        roomId: RoomId,
        roomName: String,
        knownHost: Peer,
        peer: Peer,
        roomTransportMode: RoomTransportMode = RoomTransportMode.MESHRA,
        voiceTransportMode: VoiceTransportMode = VoiceTransportMode.WIFI_DIRECT_UDP,
        isDirectAudioReady: Boolean = false,
    ) {
        publishEvent(
            MeshRoomEvent(
                eventId = newEventId(),
                roomId = roomId,
                roomName = roomName,
                knownHost = knownHost,
                roomTransportMode = roomTransportMode,
                voiceTransportMode = voiceTransportMode,
                isDirectAudioReady = isDirectAudioReady,
                authorPeerId = selfPeerId,
                type = MeshRoomEventType.PEER_DISCONNECTED,
                peer = peer,
                createdAtMillis = now(),
            ),
        )
    }

    /**
     * Создает событие текстового сообщения и flood-ит его всем текущим соседям.
     */
    fun publishChatMessage(
        roomId: RoomId,
        roomName: String,
        knownHost: Peer,
        message: ChatMessage,
        roomTransportMode: RoomTransportMode = RoomTransportMode.MESHRA,
        voiceTransportMode: VoiceTransportMode = VoiceTransportMode.WIFI_DIRECT_UDP,
        isDirectAudioReady: Boolean = false,
    ) {
        publishEvent(
            MeshRoomEvent(
                eventId = newEventId(),
                roomId = roomId,
                roomName = roomName,
                knownHost = knownHost,
                roomTransportMode = roomTransportMode,
                voiceTransportMode = voiceTransportMode,
                isDirectAudioReady = isDirectAudioReady,
                authorPeerId = selfPeerId,
                type = MeshRoomEventType.CHAT_MESSAGE,
                message = message,
                createdAtMillis = message.createdAtMillis,
            ),
        )
    }

    /**
     * Публикует локальный Opus frame в компактном MESHRA voice-пакете без JSON и event-log комнаты.
     */
    fun publishVoiceFrame(roomId: RoomId, frame: VoiceFrame) {
        val sequence = frame.sequence.toInt()
        if (frame.sequence !in UNSIGNED_SHORT_LONG_RANGE || sequence !in UNSIGNED_SHORT_RANGE) {
            Log.w(TAG, "[publishVoiceFrame] Voice frame отброшен: sequence не помещается в UInt16 roomId=${roomId.value} sequence=${frame.sequence}")
            return
        }
        val originNodeId = MeshVoiceNodeId.fromPeerIdValue(frame.originPeerId.value)
        val resolvedOriginPeerId = resolveOriginPeerId(roomId, originNodeId)
        if (resolvedOriginPeerId != frame.originPeerId) {
            Log.e(TAG, "[publishVoiceFrame] Voice frame отброшен: короткий origin node id не разрешился однозначно roomId=${roomId.value} originPeerId=${frame.originPeerId.value} originNodeId=$originNodeId")
            return
        }
        val sessionId = localVoiceSessionId(frame)
        val packet = MeshVoicePacket(
            originNodeId = originNodeId,
            pttSessionId = sessionId,
            sequence = sequence,
            encodedBytes = frame.encodedBytes,
            isFinal = frame.isFinal,
            ttl = VOICE_TTL,
        )
        rememberVoiceFrame(packet)
        floodVoicePacket(
            roomId = roomId,
            packet = packet,
            incomingLinkId = null,
        )
        if (frame.sequence == FIRST_VOICE_FRAME_SEQUENCE || frame.isFinal) {
            Log.i(TAG, "[publishVoiceFrame] Компактный mesh voice frame опубликован roomId=${roomId.value} originNodeId=${packet.originNodeId} sessionId=${packet.pttSessionId} sequence=${packet.sequence} final=${packet.isFinal} packetBytes=${packet.encodedBytes.size + MeshVoicePacketCodec.HEADER_SIZE}")
        }
    }

    /**
     * Принимает локально созданное событие комнаты, сохраняет его и отправляет всем соседям.
     */
    fun publishEvent(event: MeshRoomEvent) {
        if (!acceptEvent(event, sourceLinkId = null)) {
            return
        }
        floodEvent(event = event, incomingLinkId = null, ttl = maxTtl)
    }

    /**
     * Отправляет текущий snapshot комнаты одному соседу, например после будущего JOIN через mesh gateway.
     */
    fun sendSnapshot(roomId: RoomId, linkId: NeighborLinkId) {
        val snapshot = roomSnapshots[roomId]
        if (snapshot == null) {
            Log.w(TAG, "[sendSnapshot] Snapshot комнаты неизвестен roomId=${roomId.value} linkId=${linkId.value}")
            return
        }
        if (!isPayloadLinkUsable(linkId)) {
            Log.w(TAG, "[sendSnapshot] Snapshot не отправлен в LOST mesh link roomId=${roomId.value} linkId=${linkId.value}")
            return
        }
        bindLinkRoom(linkId, roomId, source = "outgoing_snapshot")
        val envelope = MeshEnvelope(
            roomId = roomId,
            previousHopPeerId = selfPeerId,
            ttl = 0,
            payloadKind = MeshPayloadKind.ROOM_SNAPSHOT,
            snapshot = snapshot,
            sentAtMillis = now(),
        )
        neighborTransport.sendMessage(linkId, codec.encode(envelope)) { cause ->
            emitEvent(MeshTransportEvent.SendFailed(roomId = roomId, eventId = null, cause = cause))
        }
    }

    /**
     * Переводит события нижнего транспорта в mesh-события и кормит heartbeat tracker Nearby lifecycle-статусами.
     */
    private fun handleNeighborEvent(event: NeighborTransportEvent) {
        when (event) {
            is NeighborTransportEvent.CandidateFound -> handleCandidateFound(event)
            is NeighborTransportEvent.CandidateLost -> emitEvent(MeshTransportEvent.GatewayLost(event.candidateId))
            is NeighborTransportEvent.ConnectionInitiated -> {
                if (acceptIncomingConnections) {
                    neighborTransport.acceptConnection(event.connectionId)
                    Log.i(TAG, "[handleNeighborEvent] Принят входящий mesh connection request connectionId=${event.connectionId.value}")
                } else {
                    Log.i(TAG, "[handleNeighborEvent] MeshTransport не принимает request напрямую, accept выполняет общий room lifecycle connectionId=${event.connectionId.value}")
                }
            }
            is NeighborTransportEvent.ConnectionAcceptFailed -> emitEvent(MeshTransportEvent.TransportFailed(event.cause))
            is NeighborTransportEvent.ConnectionRequestFailed -> {
                pendingGatewayConnections.remove(event.candidateId)
                pendingGatewayPeerIds.remove(event.candidateId)
                emitEvent(MeshTransportEvent.TransportFailed(event.cause))
            }
            is NeighborTransportEvent.ConnectionRecoveryRequired -> {
                val candidateId = NeighborCandidateId(event.linkId.value)
                pendingGatewayConnections.remove(candidateId)
                pendingGatewayPeerIds.remove(candidateId)
            }
            is NeighborTransportEvent.LinkConnectionFailed -> {
                val candidateId = NeighborCandidateId(event.linkId.value)
                pendingGatewayConnections.remove(candidateId)
                pendingGatewayPeerIds.remove(candidateId)
                emitEvent(
                    MeshTransportEvent.TransportFailed(
                        IllegalStateException("Mesh link connection failed statusCode=${event.statusCode}"),
                    ),
                )
            }
            is NeighborTransportEvent.LinkConnected -> {
                val candidateId = NeighborCandidateId(event.linkId.value)
                val wasPendingMeshGateway = pendingGatewayConnections.remove(candidateId)
                val gatewayPeerId = pendingGatewayPeerIds.remove(candidateId)
                if (!wasPendingMeshGateway) {
                    Log.i(TAG, "[handleNeighborEvent] Nearby link не активирован как mesh без pending gateway linkId=${event.linkId.value}")
                    return
                }
                activateMeshLink(
                    linkId = event.linkId,
                    peerId = gatewayPeerId,
                    source = "pending_gateway",
                    reused = event.reused,
                )
            }
            is NeighborTransportEvent.LinkDisconnected -> {
                val candidateId = NeighborCandidateId(event.linkId.value)
                pendingGatewayConnections.remove(candidateId)
                pendingGatewayPeerIds.remove(candidateId)
                ignoredNonMeshPayloadLinks.remove(event.linkId)
                if (!activeLinks.remove(event.linkId)) {
                    Log.i(TAG, "[handleNeighborEvent] Отключен неактивный для mesh Nearby link linkId=${event.linkId.value}")
                    return
                }
                publishLinkHealth(linkHealthTracker.onLinkDisconnected(event.linkId, heartbeatNow()))
                linkPeerIds.remove(event.linkId)
                linkRoomIds.remove(event.linkId)
                publishDirectPeerIds()
                stopHeartbeatLoopIfIdle()
                Log.i(TAG, "[handleNeighborEvent] Mesh link отключен linkId=${event.linkId.value} activeLinkCount=${activeLinks.size}")
                emitEvent(MeshTransportEvent.LinkDisconnected(event.linkId))
            }
            is NeighborTransportEvent.MessageReceived -> handleMessageReceived(event.linkId, event.bytes)
            else -> Unit
        }
    }

    /**
     * Активирует подтвержденный mesh link, включает heartbeat и отправляет первичные mesh payload-ы соседу.
     */
    private fun activateMeshLink(
        linkId: NeighborLinkId,
        peerId: PeerId?,
        source: String,
        reused: Boolean = false,
    ) {
        val wasAdded = activeLinks.add(linkId)
        ignoredNonMeshPayloadLinks.remove(linkId)
        bindLinkPeer(linkId, peerId, source = source)
        if (!wasAdded) {
            return
        }
        publishLinkHealth(linkHealthTracker.onLinkConnected(linkId, heartbeatNow()))
        Log.i(TAG, "[activateMeshLink] Mesh link активирован linkId=${linkId.value} activeLinkCount=${activeLinks.size} source=$source")
        startHeartbeatLoopIfNeeded()
        sendPeerHello(linkId)
        sendHeartbeatPing(linkId)
        sendKnownSnapshots(linkId)
        emitEvent(MeshTransportEvent.LinkConnected(linkId, reused = reused))
    }

    /**
     * Декодирует найденного transport-кандидата как mesh gateway, если endpointName содержит mesh-визитку.
     */
    private fun handleCandidateFound(event: NeighborTransportEvent.CandidateFound) {
        val advertisement = MeshRoomAdvertisement.decode(event.candidate.endpointName)
        if (advertisement == null) {
            return
        }
        emitEvent(MeshTransportEvent.GatewayFound(event.candidate.candidateId, advertisement))
        Log.i(
            TAG,
            "[handleCandidateFound] Найден mesh gateway roomToken=${advertisement.roomToken} gateway=${advertisement.gatewayName} memberCount=${advertisement.memberCount}",
        )
    }

    /**
     * Отправляет новому соседу все известные snapshot-ы, чтобы он мог быстро войти через текущий gateway.
     */
    private fun sendKnownSnapshots(linkId: NeighborLinkId) {
        roomSnapshots.keys.forEach { roomId -> sendSnapshot(roomId, linkId) }
        Log.i(TAG, "[sendKnownSnapshots] Snapshot-ы отправлены новому mesh link linkId=${linkId.value} roomCount=${roomSnapshots.size}")
    }

    /**
     * Отправляет соседу служебный hello, чтобы обе стороны быстро связали физический link с PeerId.
     */
    private fun sendPeerHello(linkId: NeighborLinkId) {
        val envelope = MeshEnvelope(
            previousHopPeerId = selfPeerId,
            ttl = 0,
            payloadKind = MeshPayloadKind.PEER_HELLO,
            sentAtMillis = now(),
        )
        neighborTransport.sendMessage(linkId, codec.encode(envelope)) { cause ->
            emitEvent(MeshTransportEvent.SendFailed(roomId = null, eventId = null, cause = cause))
        }
        Log.i(TAG, "[sendPeerHello] Mesh hello отправлен linkId=${linkId.value} peerId=${selfPeerId.value}")
    }

    /**
     * Разделяет heartbeat, компактный бинарный голос и JSON control envelope, затем передает payload соответствующему обработчику.
     */
    private fun handleMessageReceived(linkId: NeighborLinkId, bytes: ByteArray) {
        if (heartbeatCodec.isHeartbeatPacket(bytes)) {
            if (linkId !in activeLinks) {
                if (ignoredNonMeshPayloadLinks.add(linkId)) {
                    Log.i(TAG, "[handleMessageReceived] Mesh heartbeat проигнорирован на неактивном mesh link linkId=${linkId.value}")
                }
                return
            }
            handleHeartbeatPacket(linkId, bytes)
            return
        }
        if (voicePacketCodec.isVoicePacket(bytes)) {
            if (linkId !in activeLinks) {
                if (ignoredNonMeshPayloadLinks.add(linkId)) {
                    Log.i(TAG, "[handleMessageReceived] Mesh voice проигнорирован на неактивном mesh link linkId=${linkId.value}")
                }
                return
            }
            handleVoicePacket(linkId, bytes)
            return
        }
        if (!codec.isMeshEnvelope(bytes)) {
            return
        }
        val envelope = runCatching { codec.decode(bytes) }
            .onFailure { cause ->
                Log.w(TAG, "[handleMessageReceived] Не удалось декодировать mesh envelope linkId=${linkId.value}: ${cause.message}", cause)
                emitEvent(MeshTransportEvent.DecodeFailed(linkId, cause))
            }
            .getOrNull()
            ?: return

        activateMeshLink(
            linkId = linkId,
            peerId = envelope.previousHopPeerId,
            source = "incoming_${envelope.payloadKind.name}",
        )
        bindLinkPeer(linkId, envelope.previousHopPeerId, source = envelope.payloadKind.name)
        when (envelope.payloadKind) {
            MeshPayloadKind.ROOM_EVENT -> handleRoomEventEnvelope(linkId, envelope)
            MeshPayloadKind.ROOM_SNAPSHOT -> handleSnapshotEnvelope(linkId, envelope)
            MeshPayloadKind.PEER_HELLO -> handlePeerHelloEnvelope(linkId, envelope)
        }
    }

    /**
     * Принимает служебный hello; основная работа уже сделана через previousHopPeerId в bindLinkPeer.
     */
    private fun handlePeerHelloEnvelope(linkId: NeighborLinkId, envelope: MeshEnvelope) {
        val peerId = envelope.previousHopPeerId
        if (peerId == null) {
            Log.w(TAG, "[handlePeerHelloEnvelope] Mesh hello без peerId linkId=${linkId.value}")
            return
        }
        Log.i(TAG, "[handlePeerHelloEnvelope] Mesh hello принят linkId=${linkId.value} peerId=${peerId.value}")
    }

    /**
     * Принимает четырехбайтовый heartbeat: на ping отвечает pong, по pong обновляет RTT и потери.
     */
    private fun handleHeartbeatPacket(linkId: NeighborLinkId, bytes: ByteArray) {
        val packet = heartbeatCodec.decode(bytes)
        when (packet.kind) {
            MeshHeartbeatKind.PING -> {
                publishLinkHealth(linkHealthTracker.onPingReceived(linkId, heartbeatNow()))
                val response = heartbeatCodec.encode(MeshHeartbeatPacket(kind = MeshHeartbeatKind.PONG, sequence = packet.sequence))
                neighborTransport.sendMessage(linkId, response, isRealtime = true) { cause ->
                    logHeartbeatSendFailure(functionName = "handleHeartbeatPacket", direction = "pong", linkId = linkId, cause = cause)
                }
            }
            MeshHeartbeatKind.PONG -> {
                linkHealthTracker.onPongReceived(linkId, packet.sequence, heartbeatNow())?.let(::publishLinkHealth)
            }
        }
    }

    /**
     * Запускает общий heartbeat loop, если появился хотя бы один активный mesh link.
     */
    private fun startHeartbeatLoopIfNeeded() {
        if (heartbeatJob?.isActive == true) {
            return
        }
        heartbeatJob = externalScope.launch {
            Log.i(TAG, "[startHeartbeatLoopIfNeeded] Mesh heartbeat loop запущен")
            while (true) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                tickHeartbeat()
            }
        }
    }

    /**
     * Останавливает heartbeat loop, когда активных mesh link-ов больше нет.
     */
    private fun stopHeartbeatLoopIfIdle() {
        if (activeLinks.isNotEmpty()) {
            return
        }
        heartbeatJob?.cancel()
        heartbeatJob = null
        Log.i(TAG, "[stopHeartbeatLoopIfIdle] Mesh heartbeat loop остановлен")
    }

    /**
     * Отправляет очередной heartbeat по всем активным link-ам и закрывает просроченные замеры.
     */
    private fun tickHeartbeat() {
        val nowMillis = heartbeatNow()
        linkHealthTracker.collectTimedOut(nowMillis).forEach(::publishLinkHealth)
        activeLinks.toList().forEach(::sendHeartbeatPing)
    }

    /**
     * Отправляет один четырехбайтовый ping в link и учитывает ошибку отправки как потерю.
     */
    private fun sendHeartbeatPing(linkId: NeighborLinkId) {
        val packet = linkHealthTracker.preparePing(linkId, heartbeatNow()) ?: return
        neighborTransport.sendMessage(linkId, heartbeatCodec.encode(packet), isRealtime = true) { cause ->
            linkHealthTracker.onHeartbeatSendFailed(linkId, heartbeatNow())?.let(::publishLinkHealth)
            logHeartbeatSendFailure(functionName = "sendHeartbeatPing", direction = "ping", linkId = linkId, cause = cause)
        }
    }

    /**
     * Логирует ошибку heartbeat-отправки без stack trace, если link уже штатно закрывается или потерян.
     */
    private fun logHeartbeatSendFailure(functionName: String, direction: String, linkId: NeighborLinkId, cause: Throwable) {
        val message = "[$functionName] Не удалось отправить mesh heartbeat $direction linkId=${linkId.value}: ${cause.message}"
        if (isHeartbeatShutdownFailure(linkId, cause)) {
            Log.i(TAG, "$message; штатно игнорируем stack trace для закрытого heartbeat link-а")
            return
        }
        Log.w(TAG, message, cause)
    }

    /**
     * Возвращает true для ожидаемых Nearby-ошибок heartbeat-а при выходе из комнаты или закрытии endpoint-а.
     */
    private fun isHeartbeatShutdownFailure(linkId: NeighborLinkId, cause: Throwable): Boolean {
        val apiException = cause as? ApiException
        return linkId !in activeLinks ||
            apiException?.statusCode == ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN ||
            cause.message?.contains("SOCKET_CLOSED", ignoreCase = true) == true
    }

    /**
     * Публикует новый health snapshot link-а в StateFlow, статус логирует по смене, а RTT/loss — редко.
     */
    private fun publishLinkHealth(health: MeshLinkHealth) {
        _linkHealth.value = linkHealthTracker.snapshot().associateBy { linkHealth -> linkHealth.linkId }
        publishDirectPeerIds()
        val peerId = linkPeerIds[health.linkId]
        val previousStatus = lastLoggedLinkStatuses.put(health.linkId, health.status)
        if (previousStatus != health.status) {
            emitEvent(MeshTransportEvent.LinkHealthChanged(health = health, peerId = peerId))
            Log.i(
                TAG,
                "[publishLinkHealth] Mesh link health изменился linkId=${health.linkId.value} peerId=${peerId?.value} status=${health.status} rttMillis=${health.rttMillis} lossPercent=${health.lossPercent} missedInRow=${health.missedInRow}",
            )
        }
        val nowMillis = heartbeatNow()
        val lastMetricMillis = lastLoggedLinkMetricMillis[health.linkId] ?: 0L
        if (health.rttMillis != null && (lastMetricMillis == 0L || nowMillis - lastMetricMillis >= HEARTBEAT_METRIC_LOG_INTERVAL_MILLIS)) {
            lastLoggedLinkMetricMillis[health.linkId] = nowMillis
            Log.i(
                TAG,
                "[publishLinkHealth] Mesh heartbeat метрика linkId=${health.linkId.value} peerId=${peerId?.value} status=${health.status} rttMillis=${health.rttMillis} lossPercent=${health.lossPercent} missedInRow=${health.missedInRow} sent=${health.sentCount} pong=${health.receivedPongCount}",
            )
        }
    }

    /**
     * Полностью очищает health tracker и выключает heartbeat loop при сбросе mesh-сессии.
     */
    private fun clearLinkHealth() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        linkHealthTracker.clear()
        lastLoggedLinkStatuses.clear()
        lastLoggedLinkMetricMillis.clear()
        _linkHealth.value = emptyMap()
        _peerConnectionStates.value = emptyMap()
    }

    /**
     * Принимает room event из envelope и flood-ит дальше, если ttl еще живой.
     */
    private fun handleRoomEventEnvelope(linkId: NeighborLinkId, envelope: MeshEnvelope) {
        val event = envelope.event
        if (event == null) {
            Log.w(TAG, "[handleRoomEventEnvelope] Mesh envelope без room event linkId=${linkId.value} roomId=${envelope.roomId?.value}")
            return
        }
        bindLinkRoom(linkId, event.roomId, source = "incoming_event")
        if (!acceptEvent(event, sourceLinkId = linkId)) {
            return
        }
        val nextTtl = envelope.ttl - 1
        if (nextTtl >= 0) {
            floodEvent(event = event, incomingLinkId = linkId, ttl = nextTtl)
        }
    }

    /**
     * Принимает snapshot комнаты без дальнейшего flooding-а и добавляет события snapshot-а в локальный log.
     */
    private fun handleSnapshotEnvelope(linkId: NeighborLinkId, envelope: MeshEnvelope) {
        val snapshot = envelope.snapshot
        if (snapshot == null) {
            Log.w(TAG, "[handleSnapshotEnvelope] Mesh envelope без snapshot linkId=${linkId.value} roomId=${envelope.roomId?.value}")
            return
        }
        bindLinkRoom(linkId, snapshot.roomId, source = "incoming_snapshot")
        mergeSnapshot(snapshot)
        emitEvent(MeshTransportEvent.SnapshotReceived(snapshot, linkId))
        Log.i(TAG, "[handleSnapshotEnvelope] Snapshot принят roomId=${snapshot.roomId.value} eventCount=${snapshot.events.size} linkId=${linkId.value}")
    }

    /**
     * Декодирует компактный voice-пакет, восстанавливает room/PeerId из состояния линка, дедупит и ретранслирует его.
     */
    private fun handleVoicePacket(linkId: NeighborLinkId, bytes: ByteArray) {
        val packet = runCatching { voicePacketCodec.decode(bytes) }
            .onFailure { cause ->
                Log.w(TAG, "[handleVoicePacket] Не удалось декодировать компактный mesh voice-пакет linkId=${linkId.value}: ${cause.message}", cause)
                emitEvent(MeshTransportEvent.DecodeFailed(linkId, cause))
            }
            .getOrNull()
            ?: return
        val roomId = linkRoomIds[linkId]
        if (roomId == null) {
            Log.w(TAG, "[handleVoicePacket] Voice-пакет отброшен: link еще не привязан к комнате linkId=${linkId.value}")
            return
        }
        val originPeerId = resolveOriginPeerId(roomId, packet.originNodeId)
        if (originPeerId == null) {
            return
        }
        if (!rememberVoiceFrame(packet)) {
            return
        }
        val nextTtl = packet.ttl - 1
        if (nextTtl >= 0) {
            floodVoicePacket(
                roomId = roomId,
                packet = packet.copy(ttl = nextTtl),
                incomingLinkId = linkId,
            )
        }
        emitEvent(
            MeshTransportEvent.VoiceFrameReceived(
                roomId = roomId,
                frame = VoiceFrame(
                    originPeerId = originPeerId,
                    sequence = packet.sequence.toLong(),
                    encodedBytes = packet.encodedBytes,
                    isFinal = packet.isFinal,
                ),
                sourceLinkId = linkId,
                previousHopPeerId = linkPeerIds[linkId],
            ),
        )
        if (packet.sequence == FIRST_VOICE_FRAME_SEQUENCE.toInt() || packet.isFinal) {
            Log.i(TAG, "[handleVoicePacket] Компактный mesh voice frame принят roomId=${roomId.value} originNodeId=${packet.originNodeId} sessionId=${packet.pttSessionId} sequence=${packet.sequence} final=${packet.isFinal} packetBytes=${bytes.size}")
        }
    }

    /**
     * Сохраняет новое событие и инкрементально применяет его к snapshot-у комнаты.
     */
    private fun acceptEvent(event: MeshRoomEvent, sourceLinkId: NeighborLinkId?): Boolean {
        if (!isValidEvent(event)) {
            Log.w(TAG, "[acceptEvent] Некорректное mesh-событие отброшено eventId=${event.eventId.value} type=${event.type}")
            return false
        }
        if (!seenEventIds.add(event.eventId)) {
            Log.i(TAG, "[acceptEvent] Дубликат mesh-события проигнорирован eventId=${event.eventId.value} type=${event.type}")
            return false
        }
        applyEventToSnapshot(event)
        emitEvent(MeshTransportEvent.EventAccepted(event, sourceLinkId))
        Log.i(TAG, "[acceptEvent] Mesh-событие принято eventId=${event.eventId.value} type=${event.type} roomId=${event.roomId.value}")
        return true
    }

    /**
     * Проверяет минимальную целостность события для текстового mesh MVP.
     */
    private fun isValidEvent(event: MeshRoomEvent): Boolean {
        return when (event.type) {
            MeshRoomEventType.MEMBER_JOINED,
            MeshRoomEventType.MEMBER_LEFT,
            MeshRoomEventType.PEER_DISCONNECTED,
            -> event.peer != null

            MeshRoomEventType.CHAT_MESSAGE -> event.message != null
        }
    }

    /**
     * Отправляет событие всем активным соседям, кроме линка, с которого оно только что пришло.
     */
    private fun floodEvent(event: MeshRoomEvent, incomingLinkId: NeighborLinkId?, ttl: Int) {
        if (ttl < 0) {
            return
        }
        val lostTargetCount = activeLinks.count { linkId -> linkId != incomingLinkId && !isPayloadLinkUsable(linkId) }
        val targetLinks = activeLinks.filter { linkId -> linkId != incomingLinkId && isPayloadLinkUsable(linkId) }
        if (targetLinks.isEmpty()) {
            Log.i(TAG, "[floodEvent] Некому переслать mesh-событие eventId=${event.eventId.value} ttl=$ttl skippedLostCount=$lostTargetCount")
            return
        }
        targetLinks.forEach { linkId -> bindLinkRoom(linkId, event.roomId, source = "outgoing_event") }
        val envelope = MeshEnvelope(
            roomId = event.roomId,
            previousHopPeerId = selfPeerId,
            ttl = ttl,
            payloadKind = MeshPayloadKind.ROOM_EVENT,
            event = event,
            sentAtMillis = now(),
        )
        neighborTransport.sendMessage(targetLinks, codec.encode(envelope)) { cause ->
            emitEvent(MeshTransportEvent.SendFailed(roomId = event.roomId, eventId = event.eventId, cause = cause))
        }
        Log.i(TAG, "[floodEvent] Mesh-событие переслано eventId=${event.eventId.value} targetCount=${targetLinks.size} skippedLostCount=$lostTargetCount ttl=$ttl")
    }

    /**
     * Кодирует один компактный voice-пакет и отправляет его соседям этой комнаты, кроме входящего link-а.
     */
    private fun floodVoicePacket(
        roomId: RoomId,
        packet: MeshVoicePacket,
        incomingLinkId: NeighborLinkId?,
    ) {
        if (packet.ttl < 0) {
            return
        }
        val candidateLinks = activeLinks.filter { linkId -> linkId != incomingLinkId && linkRoomIds[linkId] == roomId }
        val targetLinks = candidateLinks.filter(::isPayloadLinkUsable)
        val lostTargetCount = candidateLinks.size - targetLinks.size
        if (targetLinks.isEmpty()) {
            if (packet.sequence == FIRST_VOICE_FRAME_SEQUENCE.toInt() || packet.isFinal) {
                Log.i(TAG, "[floodVoicePacket] Mesh voice frame не переслан: пригодных link-ов нет originNodeId=${packet.originNodeId} sessionId=${packet.pttSessionId} sequence=${packet.sequence} skippedLostCount=$lostTargetCount ttl=${packet.ttl} final=${packet.isFinal}")
            }
            return
        }
        val bytes = voicePacketCodec.encode(packet)
        neighborTransport.sendMessage(targetLinks, bytes, isRealtime = true) { cause ->
            emitEvent(MeshTransportEvent.SendFailed(roomId = roomId, eventId = null, cause = cause, isRealtime = true))
        }
        if (packet.sequence == FIRST_VOICE_FRAME_SEQUENCE.toInt() || packet.isFinal) {
            Log.i(TAG, "[floodVoicePacket] Компактный mesh voice frame переслан originNodeId=${packet.originNodeId} sessionId=${packet.pttSessionId} sequence=${packet.sequence} targetCount=${targetLinks.size} skippedLostCount=$lostTargetCount ttl=${packet.ttl} final=${packet.isFinal} packetBytes=${bytes.size}")
        }
    }

    /**
     * Возвращает количество link-ов, в которые можно отправлять обычные mesh payload-ы без heartbeat LOST.
     */
    private fun usablePayloadLinkCount(): Int {
        return activeLinks.count(::isPayloadLinkUsable)
    }

    /**
     * Проверяет, можно ли использовать link для room/event/voice payload-ов; heartbeat продолжает ходить отдельно.
     */
    private fun isPayloadLinkUsable(linkId: NeighborLinkId): Boolean {
        val health = linkHealthTracker.healthFor(linkId)
        return linkId in activeLinks && health?.status != MeshLinkStatus.LOST
    }

    /**
     * Инкрементально применяет событие к snapshot-у комнаты.
     */
    private fun applyEventToSnapshot(event: MeshRoomEvent) {
        val current = roomSnapshots[event.roomId] ?: MeshRoomSnapshot(
            roomId = event.roomId,
            roomName = event.roomName,
            knownHost = event.knownHost,
            roomTransportMode = event.roomTransportMode,
            voiceTransportMode = event.voiceTransportMode,
            isDirectAudioReady = event.isDirectAudioReady,
            members = listOf(event.knownHost),
            updatedAtMillis = event.createdAtMillis,
        )
        val nextMembers = applyMemberEvent(current.members, event)
        val nextMessages = applyMessageEvent(current.messages, event)
        val nextEvents = (current.events + event)
            .distinctBy { roomEvent -> roomEvent.eventId }
            .sortedWith(compareBy<MeshRoomEvent> { roomEvent -> roomEvent.createdAtMillis }.thenBy { roomEvent -> roomEvent.eventId.value })
        val nextSnapshot = current.copy(
            roomName = event.roomName,
            knownHost = event.knownHost,
            roomTransportMode = event.roomTransportMode,
            voiceTransportMode = event.voiceTransportMode,
            isDirectAudioReady = event.isDirectAudioReady,
            members = nextMembers,
            messages = nextMessages,
            events = nextEvents,
            updatedAtMillis = maxOf(current.updatedAtMillis, event.createdAtMillis),
        )
        roomSnapshots[event.roomId] = nextSnapshot
        if (nextMembers != current.members || event.roomId !in roomVoicePeerIndexes) {
            rebuildVoicePeerIndex(event.roomId)
        }
        publishRooms()
    }

    /**
     * Применяет member-событие к списку участников snapshot-а.
     */
    private fun applyMemberEvent(members: List<Peer>, event: MeshRoomEvent): List<Peer> {
        val peer = event.peer ?: return members
        return when (event.type) {
            MeshRoomEventType.MEMBER_JOINED -> upsertPeer(members, peer)
            MeshRoomEventType.MEMBER_LEFT,
            MeshRoomEventType.PEER_DISCONNECTED,
            -> members.filterNot { member -> member.peerId == peer.peerId }

            MeshRoomEventType.CHAT_MESSAGE -> members
        }
    }

    /**
     * Применяет chat-событие к списку сообщений snapshot-а.
     */
    private fun applyMessageEvent(messages: List<ChatMessage>, event: MeshRoomEvent): List<ChatMessage> {
        val message = event.message ?: return messages
        if (event.type != MeshRoomEventType.CHAT_MESSAGE) {
            return messages
        }
        return (messages.filterNot { existing -> existing.messageId == message.messageId } + message)
            .sortedWith(compareBy<ChatMessage> { chatMessage -> chatMessage.createdAtMillis }.thenBy { chatMessage -> chatMessage.messageId.value })
    }

    /**
     * Объединяет принятый snapshot с локальным состоянием, добавляя его события через общий dedup-путь.
     */
    private fun mergeSnapshot(snapshot: MeshRoomSnapshot) {
        val current = roomSnapshots[snapshot.roomId]
        if (current == null || snapshot.updatedAtMillis > current.updatedAtMillis) {
            roomSnapshots[snapshot.roomId] = snapshot
            rebuildVoicePeerIndex(snapshot.roomId)
            snapshot.events.forEach { event -> seenEventIds.add(event.eventId) }
        }
        snapshot.events.forEach { event -> acceptEvent(event, sourceLinkId = null) }
        publishRooms()
    }

    /**
     * Добавляет или заменяет участника по PeerId.
     */
    private fun upsertPeer(members: List<Peer>, peer: Peer): List<Peer> {
        return (members.filterNot { member -> member.peerId == peer.peerId } + peer)
            .sortedBy { member -> member.name.lowercase() }
    }

    /**
     * Публикует immutable-копию snapshot-ов наружу.
     */
    private fun publishRooms() {
        _rooms.value = roomSnapshots.toMap()
    }

    /**
     * Привязывает физический mesh link к PeerId соседа и обновляет live-набор прямых peer-ов для UI.
     */
    private fun bindLinkPeer(linkId: NeighborLinkId, peerId: PeerId?, source: String) {
        if (peerId == null || peerId == selfPeerId) {
            return
        }
        val previousPeerId = linkPeerIds.put(linkId, peerId)
        if (previousPeerId == peerId) {
            return
        }
        publishDirectPeerIds()
        linkHealthTracker.healthFor(linkId)?.let(::publishLinkHealth)
        Log.i(TAG, "[bindLinkPeer] Mesh link привязан к peerId linkId=${linkId.value} peerId=${peerId.value} source=$source")
    }

    /**
     * Привязывает физический link к единственной активной комнате, контекст которой не повторяется в voice-пакетах.
     */
    private fun bindLinkRoom(linkId: NeighborLinkId, roomId: RoomId, source: String): Boolean {
        val previousRoomId = linkRoomIds[linkId]
        if (previousRoomId == roomId) {
            return true
        }
        if (previousRoomId != null) {
            Log.w(TAG, "[bindLinkRoom] Link уже привязан к другой комнате linkId=${linkId.value} previousRoomId=${previousRoomId.value} requestedRoomId=${roomId.value} source=$source")
            return false
        }
        linkRoomIds[linkId] = roomId
        Log.i(TAG, "[bindLinkRoom] Mesh link привязан к комнате linkId=${linkId.value} roomId=${roomId.value} source=$source")
        return true
    }

    /**
     * Публикует PeerId и ping-состояния прямых mesh-соседей, исключая LOST из старого direct-набора.
     */
    private fun publishDirectPeerIds() {
        val states = linkPeerIds.mapNotNull { (linkId, peerId) ->
            val health = linkHealthTracker.healthFor(linkId) ?: return@mapNotNull null
            MeshPeerConnectionState(
                peerId = peerId,
                status = health.status,
                rttMillis = health.rttMillis,
                lossPercent = health.lossPercent,
                missedInRow = health.missedInRow,
            )
        }
            .groupBy { state -> state.peerId }
            .mapValues { (_, peerStates) ->
                peerStates.minWith(Comparator { left, right -> comparePeerConnectionState(left, right) })
            }
        _peerConnectionStates.value = states
        _directPeerIds.value = states
            .filterValues { state -> state.status != MeshLinkStatus.LOST }
            .keys
    }

    /**
     * Сравнивает несколько link-ов одного peer-а и выбирает самый полезный для UI ping/status.
     */
    private fun comparePeerConnectionState(left: MeshPeerConnectionState, right: MeshPeerConnectionState): Int {
        val statusCompare = left.status.uiRank().compareTo(right.status.uiRank())
        if (statusCompare != 0) {
            return statusCompare
        }
        val leftRtt = left.rttMillis ?: Long.MAX_VALUE
        val rightRtt = right.rttMillis ?: Long.MAX_VALUE
        return leftRtt.compareTo(rightRtt)
    }

    /**
     * Возвращает приоритет статуса для выбора лучшего link-а к одному peer-у.
     */
    private fun MeshLinkStatus.uiRank(): Int {
        return when (this) {
            MeshLinkStatus.CONNECTED -> 0
            MeshLinkStatus.SUSPECT -> 1
            MeshLinkStatus.LOST -> 2
        }
    }

    /**
     * Запоминает составной ключ origin/session/sequence в ограниченном LRU-подобном cache и возвращает false для дублей.
     */
    private fun rememberVoiceFrame(packet: MeshVoicePacket): Boolean {
        val frameKey = voiceFrameKey(packet)
        if (!seenVoiceFrameKeys.add(frameKey)) {
            return false
        }
        while (seenVoiceFrameKeys.size > MAX_SEEN_VOICE_FRAME_KEYS) {
            val oldestFrameKey = seenVoiceFrameKeys.iterator().next()
            seenVoiceFrameKeys.remove(oldestFrameKey)
        }
        return true
    }

    /**
     * Упаковывает три UInt16 поля voice-пакета в один Long без дополнительных объектов на каждый frame.
     */
    private fun voiceFrameKey(packet: MeshVoicePacket): Long {
        return (packet.originNodeId.toLong() shl ORIGIN_KEY_SHIFT) or
            (packet.pttSessionId.toLong() shl SESSION_KEY_SHIFT) or
            packet.sequence.toLong()
    }

    /**
     * Восстанавливает полный PeerId по заранее построенному короткому индексу участников комнаты.
     */
    private fun resolveOriginPeerId(roomId: RoomId, originNodeId: Int): PeerId? {
        if (roomId !in roomVoicePeerIndexes) {
            rebuildVoicePeerIndex(roomId)
        }
        val index = roomVoicePeerIndexes[roomId]
        if (index == null || originNodeId !in index) {
            Log.w(TAG, "[resolveOriginPeerId] Короткий origin node id неизвестен roomId=${roomId.value} originNodeId=$originNodeId")
            return null
        }
        return index[originNodeId]
    }

    /**
     * Перестраивает вынесенную из voice-пакетов таблицу UInt16 node id -> PeerId и помечает коллизии значением null.
     */
    private fun rebuildVoicePeerIndex(roomId: RoomId) {
        val snapshot = roomSnapshots[roomId]
        if (snapshot == null) {
            roomVoicePeerIndexes.remove(roomId)
            Log.w(TAG, "[rebuildVoicePeerIndex] Нельзя построить voice-индекс без snapshot roomId=${roomId.value}")
            return
        }
        val peerIds = buildSet {
            add(selfPeerId)
            add(snapshot.knownHost.peerId)
            snapshot.members.forEach { member -> add(member.peerId) }
        }
        val index = mutableMapOf<Int, PeerId?>()
        peerIds.forEach { peerId ->
            val nodeId = MeshVoiceNodeId.fromPeerIdValue(peerId.value)
            val previousPeerId = index[nodeId]
            if (nodeId !in index) {
                index[nodeId] = peerId
            } else if (previousPeerId != peerId) {
                index[nodeId] = null
                Log.e(TAG, "[rebuildVoicePeerIndex] Обнаружена коллизия короткого voice node id roomId=${roomId.value} nodeId=$nodeId firstPeerId=${previousPeerId?.value} secondPeerId=${peerId.value}")
            }
        }
        roomVoicePeerIndexes[roomId] = index
    }

    /**
     * Назначает один UInt16 session id всем frame-ам текущего PTT и освобождает его после final frame-а.
     */
    private fun localVoiceSessionId(frame: VoiceFrame): Int {
        return synchronized(voiceSessionLock) {
            if (frame.sequence == FIRST_VOICE_FRAME_SEQUENCE || activeLocalVoiceSessionId == null) {
                activeLocalVoiceSessionId = nextLocalVoiceSessionId
                nextLocalVoiceSessionId = (nextLocalVoiceSessionId + 1) and UNSIGNED_SHORT_MASK
            }
            val sessionId = requireNotNull(activeLocalVoiceSessionId)
            if (frame.isFinal) {
                activeLocalVoiceSessionId = null
            }
            sessionId
        }
    }

    /**
     * Сбрасывает локальную PTT-сессию при остановке transport-а, не откатывая счетчик session id.
     */
    private fun clearLocalVoiceSession() {
        synchronized(voiceSessionLock) {
            activeLocalVoiceSessionId = null
        }
    }

    /**
     * Публикует событие mesh-слоя без подвешивания callback-потока.
     */
    private fun emitEvent(event: MeshTransportEvent) {
        if (!_events.tryEmit(event)) {
            Log.w(TAG, "[emitEvent] Не удалось опубликовать MeshTransportEvent type=${event.javaClass.simpleName}")
        }
    }

    /**
     * Создает новый идентификатор события mesh-log.
     */
    private fun newEventId(): MeshRoomEventId {
        return MeshRoomEventId("mesh_event_${UUID.randomUUID()}")
    }

    /**
     * Возвращает текущее системное время.
     */
    private fun now(): Long {
        return System.currentTimeMillis()
    }

    /**
     * Возвращает монотонное время для RTT и timeout-ов heartbeat, не зависящее от перевода часов.
     */
    private fun heartbeatNow(): Long {
        return SystemClock.elapsedRealtime()
    }

    private companion object {
        private const val TAG = "MeshTransport"
        private const val DEFAULT_TTL = 16
        private const val VOICE_TTL = 8
        private const val HEARTBEAT_INTERVAL_MILLIS = 1_000L
        private const val HEARTBEAT_TIMEOUT_MILLIS = 2_500L
        private const val HEARTBEAT_LOST_MILLIS = 10_000L
        private const val HEARTBEAT_LOSS_WINDOW_SIZE = 16
        private const val HEARTBEAT_METRIC_LOG_INTERVAL_MILLIS = 10_000L
        private const val MAX_SEEN_VOICE_FRAME_KEYS = 4_096
        private const val EVENT_BUFFER_CAPACITY = 64
        private const val FIRST_VOICE_FRAME_SEQUENCE = 0L
        private const val ORIGIN_KEY_SHIFT = 32
        private const val SESSION_KEY_SHIFT = 16
        private const val UNSIGNED_SHORT_MASK = 0xFFFF
        private val UNSIGNED_SHORT_RANGE = 0..UNSIGNED_SHORT_MASK
        private val UNSIGNED_SHORT_LONG_RANGE = 0L..UNSIGNED_SHORT_MASK.toLong()
    }
}

/**
 * События mesh-слоя для будущей интеграции с runtime/UI.
 */
sealed class MeshTransportEvent {
    /**
     * Соседский mesh link успешно установлен или переиспользован.
     */
    data class LinkConnected(val linkId: NeighborLinkId, val reused: Boolean) : MeshTransportEvent()

    /**
     * Соседский mesh link отключился.
     */
    data class LinkDisconnected(val linkId: NeighborLinkId) : MeshTransportEvent()

    /**
     * Диагностический статус прямого mesh link-а изменился; актуальные RTT/loss лежат в health.
     */
    data class LinkHealthChanged(
        val health: MeshLinkHealth,
        val peerId: PeerId?,
    ) : MeshTransportEvent()

    /**
     * Discovery нашел соседний gateway в mesh-комнату.
     */
    data class GatewayFound(
        val candidateId: NeighborCandidateId,
        val advertisement: MeshRoomAdvertisement,
    ) : MeshTransportEvent()

    /**
     * Discovery потерял ранее найденный gateway.
     */
    data class GatewayLost(val candidateId: NeighborCandidateId) : MeshTransportEvent()

    /**
     * Новое room event принято в локальный log и применено к snapshot-у.
     */
    data class EventAccepted(val event: MeshRoomEvent, val sourceLinkId: NeighborLinkId?) : MeshTransportEvent()

    /**
     * Snapshot комнаты получен от соседнего mesh-узла.
     */
    data class SnapshotReceived(val snapshot: MeshRoomSnapshot, val sourceLinkId: NeighborLinkId) : MeshTransportEvent()

    /**
     * Ephemeral voice frame принят из MESHRA flood и готов к локальному playback.
     */
    data class VoiceFrameReceived(
        val roomId: RoomId,
        val frame: VoiceFrame,
        val sourceLinkId: NeighborLinkId,
        val previousHopPeerId: PeerId?,
    ) : MeshTransportEvent()

    /**
     * Входящий mesh payload не удалось декодировать.
     */
    data class DecodeFailed(val linkId: NeighborLinkId, val cause: Throwable) : MeshTransportEvent()

    /**
     * Отправка mesh payload-а завершилась ошибкой; roomId null означает служебный payload вне комнаты.
     */
    data class SendFailed(
        val roomId: RoomId?,
        val eventId: MeshRoomEventId?,
        val cause: Throwable,
        val isRealtime: Boolean = false,
    ) : MeshTransportEvent()

    /**
     * Нижний транспорт сообщил ошибку connection lifecycle.
     */
    data class TransportFailed(val cause: Throwable) : MeshTransportEvent()
}
