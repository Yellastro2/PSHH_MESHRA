package com.yellastro.btration.domain.mesh

import android.util.Log
import com.yellastro.btration.domain.model.ChatMessage
import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomTransportMode
import com.yellastro.btration.domain.transport.NeighborAdvertisement
import com.yellastro.btration.domain.transport.NeighborCandidateId
import com.yellastro.btration.domain.transport.NeighborLinkId
import com.yellastro.btration.domain.transport.NeighborTransport
import com.yellastro.btration.domain.transport.NeighborTransportEvent
import com.yellastro.btration.voice.VoiceFrame
import com.yellastro.btration.voice.VoiceTransportMode
import java.util.LinkedHashSet
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Текстовый mesh-слой поверх NeighborTransport: принимает room events, дедупит, flood-ит соседям и ведет live-мапу прямых PeerId.
 *
 * Runtime включает этот слой для MESHRA-комнат; Nearby Star продолжает работать через обычный RoomTransport.
 */
class MeshTransport(
    private val selfPeerId: PeerId,
    private val neighborTransport: NeighborTransport,
    private val codec: MeshCodec,
    private val externalScope: CoroutineScope,
    private val maxTtl: Int = DEFAULT_TTL,
    private val acceptIncomingConnections: Boolean = true,
) {
    private val activeLinks = mutableSetOf<NeighborLinkId>()
    private val pendingGatewayConnections = mutableSetOf<NeighborCandidateId>()
    private val pendingGatewayPeerIds = mutableMapOf<NeighborCandidateId, PeerId>()
    private val linkPeerIds = mutableMapOf<NeighborLinkId, PeerId>()
    private val seenEventIds = mutableSetOf<MeshRoomEventId>()
    private val seenVoiceFrameIds = LinkedHashSet<MeshVoiceFrameId>()
    private val roomSnapshots = mutableMapOf<RoomId, MeshRoomSnapshot>()
    private val advertisingLock = Any()
    private var advertisedEndpointName: String? = null
    private val _events = MutableSharedFlow<MeshTransportEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val _rooms = MutableStateFlow<Map<RoomId, MeshRoomSnapshot>>(emptyMap())
    private val _directPeerIds = MutableStateFlow<Set<PeerId>>(emptySet())

    /**
     * События mesh-слоя для будущего runtime: принятые room events, snapshot-ы и ошибки.
     */
    val events: SharedFlow<MeshTransportEvent> = _events.asSharedFlow()

    /**
     * Текущие snapshot-ы известных mesh-комнат, рассчитанные инкрементально по принятым событиям.
     */
    val rooms: StateFlow<Map<RoomId, MeshRoomSnapshot>> = _rooms.asStateFlow()

    /**
     * PeerId соседей, с которыми у локального устройства сейчас есть прямой mesh link.
     */
    val directPeerIds: StateFlow<Set<PeerId>> = _directPeerIds.asStateFlow()

    init {
        externalScope.launch {
            Log.i(TAG, "[init] Подписываем MeshTransport на события NeighborTransport")
            neighborTransport.neighborEvents.collect(::handleNeighborEvent)
        }
    }

    /**
     * Запускает поиск mesh gateway-ев через нижний соседский транспорт.
     */
    fun startDiscovery() {
        neighborTransport.startDiscovery()
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
        neighborTransport.startAdvertising(NeighborAdvertisement(endpointName))
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
        neighborTransport.connect(candidateId)
    }

    /**
     * Возвращает количество готовых mesh link-ов.
     */
    fun activeLinkCount(): Int {
        return activeLinks.size
    }

    /**
     * Возвращает количество готовых и уже запрошенных mesh link-ов.
     */
    fun linkOrPendingCount(): Int {
        return activeLinks.size + pendingGatewayConnections.size
    }

    /**
     * Возвращает true, если gateway уже подключен или ожидает завершения connection request.
     */
    fun hasLinkOrPending(candidateId: NeighborCandidateId): Boolean {
        return NeighborLinkId(candidateId.value) in activeLinks || candidateId in pendingGatewayConnections
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
        seenVoiceFrameIds.clear()
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
        seenEventIds.clear()
        seenVoiceFrameIds.clear()
        roomSnapshots.clear()
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
     * Публикует локальный ephemeral voice frame в MESHRA flood без сохранения в event-log комнаты.
     */
    fun publishVoiceFrame(roomId: RoomId, frame: VoiceFrame) {
        val meshVoiceFrame = MeshVoiceFrame(
            frameId = newVoiceFrameId(),
            originPeerId = frame.originPeerId,
            sequence = frame.sequence,
            encodedBytes = frame.encodedBytes,
            isFinal = frame.isFinal,
        )
        rememberVoiceFrameId(meshVoiceFrame.frameId)
        floodVoiceFrame(
            roomId = roomId,
            voiceFrame = meshVoiceFrame,
            incomingLinkId = null,
            ttl = VOICE_TTL,
        )
        if (frame.sequence == FIRST_VOICE_FRAME_SEQUENCE || frame.isFinal) {
            Log.i(TAG, "[publishVoiceFrame] Mesh voice frame опубликован roomId=${roomId.value} frameId=${meshVoiceFrame.frameId.value} originPeerId=${frame.originPeerId.value} sequence=${frame.sequence} final=${frame.isFinal}")
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
     * Переводит события нижнего транспорта в mesh-события, не трогая payload-ы других протоколов.
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
                pendingGatewayConnections.remove(candidateId)
                activeLinks.add(event.linkId)
                bindLinkPeer(event.linkId, pendingGatewayPeerIds.remove(candidateId), source = "pending_gateway")
                Log.i(TAG, "[handleNeighborEvent] Mesh link готов linkId=${event.linkId.value} activeLinkCount=${activeLinks.size}")
                sendPeerHello(event.linkId)
                sendKnownSnapshots(event.linkId)
                emitEvent(MeshTransportEvent.LinkConnected(event.linkId, reused = event.reused))
            }
            is NeighborTransportEvent.LinkDisconnected -> {
                val candidateId = NeighborCandidateId(event.linkId.value)
                pendingGatewayConnections.remove(candidateId)
                pendingGatewayPeerIds.remove(candidateId)
                activeLinks.remove(event.linkId)
                linkPeerIds.remove(event.linkId)
                publishDirectPeerIds()
                Log.i(TAG, "[handleNeighborEvent] Mesh link отключен linkId=${event.linkId.value} activeLinkCount=${activeLinks.size}")
                emitEvent(MeshTransportEvent.LinkDisconnected(event.linkId))
            }
            is NeighborTransportEvent.MessageReceived -> handleMessageReceived(event.linkId, event.bytes)
            else -> Unit
        }
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
     * Декодирует mesh envelope, принимает новые события и пересылает их дальше с уменьшенным ttl.
     */
    private fun handleMessageReceived(linkId: NeighborLinkId, bytes: ByteArray) {
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

        bindLinkPeer(linkId, envelope.previousHopPeerId, source = envelope.payloadKind.name)
        when (envelope.payloadKind) {
            MeshPayloadKind.ROOM_EVENT -> handleRoomEventEnvelope(linkId, envelope)
            MeshPayloadKind.ROOM_SNAPSHOT -> handleSnapshotEnvelope(linkId, envelope)
            MeshPayloadKind.PEER_HELLO -> handlePeerHelloEnvelope(linkId, envelope)
            MeshPayloadKind.VOICE_FRAME -> handleVoiceFrameEnvelope(linkId, envelope)
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
     * Принимает room event из envelope и flood-ит дальше, если ttl еще живой.
     */
    private fun handleRoomEventEnvelope(linkId: NeighborLinkId, envelope: MeshEnvelope) {
        val event = envelope.event
        if (event == null) {
            Log.w(TAG, "[handleRoomEventEnvelope] Mesh envelope без room event linkId=${linkId.value} roomId=${envelope.roomId?.value}")
            return
        }
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
        mergeSnapshot(snapshot)
        emitEvent(MeshTransportEvent.SnapshotReceived(snapshot, linkId))
        Log.i(TAG, "[handleSnapshotEnvelope] Snapshot принят roomId=${snapshot.roomId.value} eventCount=${snapshot.events.size} linkId=${linkId.value}")
    }

    /**
     * Принимает ephemeral voice frame, дедупит его, flood-ит дальше и отдает runtime для playback.
     */
    private fun handleVoiceFrameEnvelope(linkId: NeighborLinkId, envelope: MeshEnvelope) {
        val roomId = envelope.roomId
        val voiceFrame = envelope.voiceFrame
        if (roomId == null || voiceFrame == null) {
            Log.w(TAG, "[handleVoiceFrameEnvelope] Mesh voice envelope неполный linkId=${linkId.value} roomId=${roomId?.value}")
            return
        }
        if (!rememberVoiceFrameId(voiceFrame.frameId)) {
            return
        }
        val nextTtl = envelope.ttl - 1
        if (nextTtl >= 0) {
            floodVoiceFrame(
                roomId = roomId,
                voiceFrame = voiceFrame,
                incomingLinkId = linkId,
                ttl = nextTtl,
            )
        }
        emitEvent(
            MeshTransportEvent.VoiceFrameReceived(
                roomId = roomId,
                frame = voiceFrame.toVoiceFrame(),
                sourceLinkId = linkId,
                previousHopPeerId = envelope.previousHopPeerId,
            ),
        )
        if (voiceFrame.sequence == FIRST_VOICE_FRAME_SEQUENCE || voiceFrame.isFinal) {
            Log.i(TAG, "[handleVoiceFrameEnvelope] Mesh voice frame принят roomId=${roomId.value} frameId=${voiceFrame.frameId.value} originPeerId=${voiceFrame.originPeerId.value} sequence=${voiceFrame.sequence} final=${voiceFrame.isFinal}")
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
        val targetLinks = activeLinks.filterNot { linkId -> linkId == incomingLinkId }
        if (targetLinks.isEmpty()) {
            Log.i(TAG, "[floodEvent] Некому переслать mesh-событие eventId=${event.eventId.value} ttl=$ttl")
            return
        }
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
        Log.i(TAG, "[floodEvent] Mesh-событие переслано eventId=${event.eventId.value} targetCount=${targetLinks.size} ttl=$ttl")
    }

    /**
     * Отправляет ephemeral voice frame всем активным соседям, кроме link-а, с которого он пришел.
     */
    private fun floodVoiceFrame(
        roomId: RoomId,
        voiceFrame: MeshVoiceFrame,
        incomingLinkId: NeighborLinkId?,
        ttl: Int,
    ) {
        if (ttl < 0) {
            return
        }
        val targetLinks = activeLinks.filterNot { linkId -> linkId == incomingLinkId }
        if (targetLinks.isEmpty()) {
            return
        }
        val envelope = MeshEnvelope(
            roomId = roomId,
            previousHopPeerId = selfPeerId,
            ttl = ttl,
            payloadKind = MeshPayloadKind.VOICE_FRAME,
            voiceFrame = voiceFrame,
            sentAtMillis = now(),
        )
        neighborTransport.sendMessage(targetLinks, codec.encode(envelope), isRealtime = true) { cause ->
            emitEvent(MeshTransportEvent.SendFailed(roomId = roomId, eventId = null, cause = cause, isRealtime = true))
        }
        if (voiceFrame.sequence == FIRST_VOICE_FRAME_SEQUENCE || voiceFrame.isFinal) {
            Log.i(TAG, "[floodVoiceFrame] Mesh voice frame переслан frameId=${voiceFrame.frameId.value} targetCount=${targetLinks.size} ttl=$ttl final=${voiceFrame.isFinal}")
        }
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
        Log.i(TAG, "[bindLinkPeer] Mesh link привязан к peerId linkId=${linkId.value} peerId=${peerId.value} source=$source")
    }

    /**
     * Публикует immutable-набор PeerId, с которыми сейчас есть прямой mesh link.
     */
    private fun publishDirectPeerIds() {
        _directPeerIds.value = linkPeerIds.values.toSet()
    }

    /**
     * Запоминает voice frame id в ограниченном LRU-подобном cache и возвращает false для дублей.
     */
    private fun rememberVoiceFrameId(frameId: MeshVoiceFrameId): Boolean {
        if (!seenVoiceFrameIds.add(frameId)) {
            return false
        }
        while (seenVoiceFrameIds.size > MAX_SEEN_VOICE_FRAME_IDS) {
            val oldestFrameId = seenVoiceFrameIds.iterator().next()
            seenVoiceFrameIds.remove(oldestFrameId)
        }
        return true
    }

    /**
     * Преобразует mesh voice payload в обычный VoiceFrame для VoiceRuntime playback.
     */
    private fun MeshVoiceFrame.toVoiceFrame(): VoiceFrame {
        return VoiceFrame(
            originPeerId = originPeerId,
            sequence = sequence,
            encodedBytes = encodedBytes,
            isFinal = isFinal,
        )
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
     * Создает уникальный идентификатор ephemeral voice frame-а для dedup в mesh flood.
     */
    private fun newVoiceFrameId(): MeshVoiceFrameId {
        return MeshVoiceFrameId("mesh_voice_${UUID.randomUUID()}")
    }

    /**
     * Возвращает текущее системное время.
     */
    private fun now(): Long {
        return System.currentTimeMillis()
    }

    private companion object {
        private const val TAG = "MeshTransport"
        private const val DEFAULT_TTL = 16
        private const val VOICE_TTL = 8
        private const val MAX_SEEN_VOICE_FRAME_IDS = 4_096
        private const val EVENT_BUFFER_CAPACITY = 64
        private const val FIRST_VOICE_FRAME_SEQUENCE = 0L
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
