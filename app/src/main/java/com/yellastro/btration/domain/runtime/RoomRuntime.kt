package com.yellastro.btration.domain.runtime

import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.yellastro.btration.data.nearby.NearbyRequirementException
import com.yellastro.btration.domain.mesh.MeshRoomAdvertisement
import com.yellastro.btration.domain.mesh.MeshRoomSnapshot
import com.yellastro.btration.domain.mesh.MeshTransport
import com.yellastro.btration.domain.mesh.MeshTransportEvent
import com.yellastro.btration.domain.model.ChatMessage
import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.model.RoomTransportMode
import com.yellastro.btration.domain.model.WirePacket
import com.yellastro.btration.domain.model.WirePacketId
import com.yellastro.btration.domain.model.WirePacketType
import com.yellastro.btration.domain.model.VoiceTransportControlInfo
import com.yellastro.btration.domain.transport.NeighborCandidateId
import com.yellastro.btration.domain.util.IdGenerator
import com.yellastro.btration.repository.ProfileRepository
import com.yellastro.btration.repository.VoiceSettingsRepository
import com.yellastro.btration.voice.SwitchableVoiceTransport
import com.yellastro.btration.voice.VoiceTransportEvent
import com.yellastro.btration.voice.VoiceRuntime
import com.yellastro.btration.voice.VoiceTransportMode
import com.yellastro.btration.voice.VoiceTransportSessionRole
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Рабочая машина комнаты: ведет discovery, Nearby Star/MESHRA room transport, direct mesh-connect статусы, выбор voice transport, чат, PTT и статус media-plane.
 */
class RoomRuntime(
    private val profileRepository: ProfileRepository,
    private val roomTransport: RoomTransport,
    private val meshTransport: MeshTransport,
    private val voiceTransport: SwitchableVoiceTransport,
    private val voiceRuntime: VoiceRuntime,
    private val voiceSettingsRepository: VoiceSettingsRepository,
    private val idGenerator: IdGenerator,
    private val externalScope: CoroutineScope,
    private val shouldUseMeshGateway: (PeerId) -> Boolean = { true },
) {
    private val _state = MutableStateFlow<RoomRuntimeState>(RoomRuntimeState.Idle)
    private val _availableRooms = MutableStateFlow<List<RoomInfo>>(emptyList())
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _talkingPeerIds = MutableStateFlow<Set<PeerId>>(emptySet())
    private val _notices = MutableSharedFlow<RoomRuntimeNotice>(replay = 1, extraBufferCapacity = NOTICE_BUFFER_CAPACITY)

    private val roomEndpoints = mutableMapOf<RoomId, String>()
    private val endpointRooms = mutableMapOf<String, RoomId>()
    private val realRoomToAdvertisedRoom = mutableMapOf<RoomId, RoomInfo>()
    private val seenPacketIds = mutableSetOf<WirePacketId>()
    private var roomIdsSeenInDiscoveryCycle: MutableSet<RoomId>? = null
    private val discoveryCycleMutex = Mutex()
    private var connectionRecoveryJob: Job? = null
    private var recoveringRoomId: RoomId? = null
    private var recoveryReconnectRequested = false
    private var connectionRecoveryAttemptCount = 0
    private var noticeId = 0L
    private val talkingPeerTimeoutJobs = ConcurrentHashMap<PeerId, Job>()

    /**
     * Текущее состояние runtime: idle/searching/hosting/joining/client/error.
     */
    val state: StateFlow<RoomRuntimeState> = _state.asStateFlow()

    /**
     * Список комнат, найденных через room discovery.
     */
    val availableRooms: StateFlow<List<RoomInfo>> = _availableRooms.asStateFlow()

    /**
     * Сообщения текущей комнаты.
     */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /**
     * PeerId участников, чьи голосовые frames сейчас передаются или доигрываются локально.
     */
    val talkingPeerIds: StateFlow<Set<PeerId>> = _talkingPeerIds.asStateFlow()

    /**
     * PeerId участников, с которыми сейчас есть прямой mesh link; в Nearby Star не используется.
     */
    val directMeshPeerIds: StateFlow<Set<PeerId>> = meshTransport.directPeerIds

    /**
     * Одноразовые уведомления для snackbar, которые не обязаны менять состояние комнаты.
     */
    val notices: SharedFlow<RoomRuntimeNotice> = _notices.asSharedFlow()

    /**
     * Возвращает PeerId локального пользователя для верхних слоев без доступа к ProfileRepository.
     */
    fun getSelfPeerId(): PeerId {
        return profileRepository.getOrCreatePeerId()
    }

    init {
        externalScope.launch {
            Log.i(TAG, "[init] Подписываем RoomRuntime на события RoomTransport")
            roomTransport.events.collect(::handleRoomTransportEvent)
        }
        externalScope.launch {
            Log.i(TAG, "[init] Подписываем RoomRuntime на события MeshTransport")
            meshTransport.events.collect(::handleMeshTransportEvent)
        }
        externalScope.launch {
            Log.i(TAG, "[init] Подписываем RoomRuntime на события VoiceTransport")
            voiceTransport.events.collect(::handleVoiceTransportEvent)
        }
    }

    /**
     * Запускает новый поиск комнат без очистки ранее показанного списка до завершения первого цикла.
     */
    suspend fun startSearch() {
        discoveryCycleMutex.withLock {
            Log.i(TAG, "[startSearch] Команда поиска комнат currentState=${_state.value.javaClass.simpleName}")
            when (_state.value) {
                is RoomRuntimeState.Hosting,
                is RoomRuntimeState.Joining,
                is RoomRuntimeState.Client,
                -> {
                    Log.i(TAG, "[startSearch] Поиск пропущен, уже есть активная комната currentState=${_state.value.javaClass.simpleName}")
                }

                RoomRuntimeState.Idle,
                is RoomRuntimeState.Error,
                -> {
                    startDiscoveryCycle(reconcilePreviousCycle = false)
                }

                RoomRuntimeState.Searching -> startDiscoveryCycle(reconcilePreviousCycle = true)
            }
        }
    }

    /**
     * Завершает текущий десятисекундный снимок комнат и запускает следующий, только пока runtime ищет комнаты.
     */
    suspend fun refreshSearch() {
        discoveryCycleMutex.withLock {
            if (_state.value !is RoomRuntimeState.Searching) {
                Log.i(TAG, "[refreshSearch] Обновление discovery пропущено currentState=${_state.value.javaClass.simpleName}")
                return@withLock
            }
            Log.i(TAG, "[refreshSearch] Завершаем текущий discovery-цикл и запускаем следующий")
            startDiscoveryCycle(reconcilePreviousCycle = true)
        }
    }

    /**
     * Останавливает поиск комнат и возвращает runtime в Idle, если он только искал комнаты.
     */
    suspend fun stopSearch() {
        discoveryCycleMutex.withLock {
            val currentState = _state.value
            Log.i(TAG, "[stopSearch] Останавливаем поиск currentState=${currentState.javaClass.simpleName}")
            roomTransport.stopDiscovery()
            if (isActiveMeshState(currentState)) {
                Log.i(TAG, "[stopSearch] Mesh discovery оставлен активным для healing")
            } else {
                meshTransport.stopDiscovery()
            }
            roomIdsSeenInDiscoveryCycle = null
            if (currentState is RoomRuntimeState.Searching) {
                _state.value = RoomRuntimeState.Idle
                Log.i(TAG, "[stopSearch] Runtime переведен в Idle")
            }
        }
    }

    /**
     * Создает локальную комнату, фиксирует выбранный room transport и запускает room advertising.
     */
    suspend fun createRoom(name: String, roomTransportMode: RoomTransportMode) {
        Log.i(TAG, "[createRoom] Команда создать комнату name=$name roomTransportMode=$roomTransportMode currentState=${_state.value.javaClass.simpleName}")
        when (_state.value) {
            is RoomRuntimeState.Hosting -> {
                Log.w(TAG, "[createRoom] Создание отклонено, устройство уже хостит комнату")
                setError("Нельзя создать вторую комнату на этом устройстве")
                return
            }

            is RoomRuntimeState.Client,
            is RoomRuntimeState.Joining,
            -> {
                Log.w(TAG, "[createRoom] Создание отклонено, пользователь уже в процессе комнаты currentState=${_state.value.javaClass.simpleName}")
                setError("Сначала выйдите из текущей комнаты")
                return
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Error,
            -> Unit
        }

        if (roomTransportMode == RoomTransportMode.MESHRA) {
            createMeshRoom(name)
            return
        }

        roomTransport.stopDiscovery()
        val self = profileRepository.getSelfPeer()
        val voiceTransportMode = preferredVoiceTransportMode()
        activateRoomTransportMode(roomTransportMode, reason = "create_room")
        voiceTransport.setMode(voiceTransportMode, reason = "create_room")
        val room = RoomInfo(
            roomId = idGenerator.newRoomId(),
            name = name.trim(),
            host = self,
            createdAtMillis = now(),
            roomTransportMode = roomTransportMode,
            voiceTransportMode = voiceTransportMode,
        )
        _messages.value = emptyList()
        _state.value = RoomRuntimeState.Hosting(room = room, members = listOf(self))
        voiceTransport.startSession(self.peerId, VoiceTransportSessionRole.HOST)
        Log.i(TAG, "[createRoom] Runtime переведен в Hosting roomId=${room.roomId.value} roomName=${room.name} hostPeerId=${self.peerId.value} roomTransportMode=${room.roomTransportMode} voiceTransportMode=$voiceTransportMode")
        roomTransport.startAdvertising(room)
    }

    /**
     * Создает MESHRA-комнату: локально публикует MEMBER_JOINED, запускает mesh-рекламу gateway и не поднимает voice media-plane.
     */
    private fun createMeshRoom(name: String) {
        roomTransport.stopDiscovery()
        meshTransport.stopDiscovery()
        val self = profileRepository.getSelfPeer()
        val voiceTransportMode = preferredVoiceTransportMode()
        val room = RoomInfo(
            roomId = idGenerator.newRoomId(),
            name = name.trim(),
            host = self,
            createdAtMillis = now(),
            roomTransportMode = RoomTransportMode.MESHRA,
            voiceTransportMode = voiceTransportMode,
            isDirectAudioReady = false,
        )
        activateRoomTransportMode(room.roomTransportMode, reason = "create_mesh_room")
        voiceRuntime.stopAll()
        voiceTransport.stopSession()
        _messages.value = emptyList()
        _state.value = RoomRuntimeState.Hosting(
            room = room,
            members = listOf(self),
            directAudioStatus = DirectAudioStatus.Unavailable(MESH_VOICE_UNAVAILABLE_MESSAGE),
        )
        meshTransport.publishMemberJoined(
            roomId = room.roomId,
            roomName = room.name,
            knownHost = room.host,
            member = self,
            roomTransportMode = room.roomTransportMode,
            voiceTransportMode = room.voiceTransportMode,
            isDirectAudioReady = room.isDirectAudioReady,
        )
        advertiseMeshSnapshot(room.roomId, self)
        startMeshHealingDiscovery(room.roomId, reason = "create_mesh_room")
        Log.i(TAG, "[createMeshRoom] Runtime переведен в Hosting MESHRA roomId=${room.roomId.value} roomName=${room.name} hostPeerId=${self.peerId.value}")
    }

    /**
     * Атомарно останавливает discovery refresh и начинает подключение к найденной комнате по ее RoomId.
     */
    suspend fun joinRoom(roomId: RoomId) {
        discoveryCycleMutex.withLock {
            joinRoomLocked(roomId)
        }
    }

    /**
     * Выполняет вход под discovery mutex, чтобы endpoint не сменился между выбором комнаты и requestConnection.
     */
    private fun joinRoomLocked(roomId: RoomId) {
        Log.i(TAG, "[joinRoom] Команда войти в комнату roomId=${roomId.value} currentState=${_state.value.javaClass.simpleName}")
        when (_state.value) {
            is RoomRuntimeState.Hosting,
            is RoomRuntimeState.Client,
            is RoomRuntimeState.Joining,
            -> {
                Log.w(TAG, "[joinRoom] Вход отклонен, уже есть активная комната currentState=${_state.value.javaClass.simpleName}")
                setError("Сначала выйдите из текущей комнаты")
                return
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Error,
            -> Unit
        }

        val room = _availableRooms.value.firstOrNull { it.roomId == roomId }
        val endpointId = roomEndpoints[roomId]
        if (room == null || endpointId == null) {
            Log.w(TAG, "[joinRoom] Комната не найдена в runtime roomId=${roomId.value} hasRoom=${room != null} endpointId=$endpointId")
            setError("Комната больше не найдена поблизости")
            return
        }

        if (room.roomTransportMode == RoomTransportMode.MESHRA) {
            joinMeshRoom(room, endpointId)
            return
        }

        clearConnectionRecovery()
        roomTransport.stopDiscovery()
        roomIdsSeenInDiscoveryCycle = null
        _messages.value = emptyList()
        _state.value = RoomRuntimeState.Joining(room)
        Log.i(TAG, "[joinRoom] Runtime переведен в Joining roomId=${room.roomId.value} endpointId=$endpointId")
        roomTransport.connectToEndpoint(endpointId)
    }

    /**
     * Начинает вход в MESHRA-комнату через выбранный gateway endpoint и ждет snapshot от соседнего узла.
     */
    private fun joinMeshRoom(room: RoomInfo, endpointId: String) {
        clearConnectionRecovery()
        roomTransport.stopDiscovery()
        meshTransport.stopDiscovery()
        roomIdsSeenInDiscoveryCycle = null
        _messages.value = emptyList()
        _state.value = RoomRuntimeState.Joining(
            room = room,
            directAudioStatus = DirectAudioStatus.Unavailable(MESH_VOICE_UNAVAILABLE_MESSAGE),
        )
        Log.i(TAG, "[joinMeshRoom] Runtime переведен в Joining MESHRA roomId=${room.roomId.value} endpointId=$endpointId")
        meshTransport.connectToGateway(
            candidateId = NeighborCandidateId(endpointId),
            gatewayPeerId = (room.gateway ?: room.host).peerId,
        )
    }

    /**
     * Покидает текущую комнату или останавливает поиск.
     */
    suspend fun leaveRoom() {
        Log.i(TAG, "[leaveRoom] Команда выйти currentState=${_state.value.javaClass.simpleName}")
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                if (currentState.room.roomTransportMode == RoomTransportMode.MESHRA) {
                    leaveMeshRoomLocally(currentState.room, role = "host")
                    return
                }
                closeRoom()
            }
            is RoomRuntimeState.Client -> {
                if (currentState.room.roomTransportMode == RoomTransportMode.MESHRA) {
                    leaveMeshRoomLocally(currentState.room, role = "client")
                    return
                }
                val self = profileRepository.getSelfPeer()
                roomTransport.sendToPeer(
                    currentState.room.host.peerId,
                    packet(
                        type = WirePacketType.MEMBER_LEFT,
                        roomId = currentState.room.roomId,
                        sender = self,
                        peer = self,
                    ),
                )
                Log.i(TAG, "[leaveRoom] Клиент отправил MEMBER_LEFT hostPeerId=${currentState.room.host.peerId.value}")
                resetSession()
            }

            is RoomRuntimeState.Joining -> resetSession()
            RoomRuntimeState.Searching -> stopSearch()
            RoomRuntimeState.Idle,
            is RoomRuntimeState.Error,
            -> resetSession()
        }
    }

    /**
     * Закрывает хостимую Nearby Star комнату или локально выходит из MESHRA-комнаты без удаления общей mesh-сети.
     */
    suspend fun closeRoom() {
        Log.i(TAG, "[closeRoom] Команда закрыть комнату currentState=${_state.value.javaClass.simpleName}")
        val currentState = _state.value as? RoomRuntimeState.Hosting
        if (currentState == null) {
            Log.i(TAG, "[closeRoom] Активной host-комнаты нет, сбрасываем сессию")
            resetSession()
            return
        }

        if (currentState.room.roomTransportMode == RoomTransportMode.MESHRA) {
            leaveMeshRoomLocally(currentState.room, role = "host_close_command")
            return
        }

        val roomClosed = packet(
            type = WirePacketType.ROOM_CLOSED,
            roomId = currentState.room.roomId,
            sender = profileRepository.getSelfPeer(),
        )
        sendToMembers(currentState.members, roomClosed, excludedPeerIds = setOf(profileRepository.getOrCreatePeerId()))
        Log.i(TAG, "[closeRoom] ROOM_CLOSED разослан roomId=${currentState.room.roomId.value} memberCount=${currentState.members.size}")
        resetSession()
    }

    /**
     * Публикует штатный выход локального peer-а из MESHRA-комнаты и сбрасывает только локальную сессию.
     *
     * В MESHRA создатель комнаты не является владельцем жизни комнаты: его выход должен быть обычным
     * `MEMBER_LEFT`, без `ROOM_CLOSED` и без удаления event-log/snapshot-а на остальных устройствах.
     */
    private fun leaveMeshRoomLocally(room: RoomInfo, role: String) {
        publishMeshMemberLeft(room, profileRepository.getSelfPeer())
        Log.i(TAG, "[leaveMeshRoomLocally] Локальный peer вышел из MESHRA-комнаты без ROOM_CLOSED role=$role roomId=${room.roomId.value}")
        resetSession()
    }

    /**
     * Отправляет текстовое сообщение в текущую комнату.
     */
    suspend fun sendMessage(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            Log.i(TAG, "[sendMessage] Пустое сообщение проигнорировано")
            return
        }

        val self = profileRepository.getSelfPeer()
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                if (currentState.room.roomTransportMode == RoomTransportMode.MESHRA) {
                    sendMeshMessage(currentState.room, self, cleanText)
                    return
                }
                Log.i(TAG, "[sendMessage] Host отправляет сообщение roomId=${currentState.room.roomId.value} textLength=${cleanText.length}")
                val message = newChatMessage(currentState.room.roomId, self, cleanText)
                appendMessage(message)
                val chatPacket = packet(
                    type = WirePacketType.CHAT_MESSAGE,
                    roomId = currentState.room.roomId,
                    sender = self,
                    message = message,
                )
                sendToMembers(currentState.members, chatPacket, excludedPeerIds = setOf(self.peerId))
            }

            is RoomRuntimeState.Client -> {
                if (currentState.room.roomTransportMode == RoomTransportMode.MESHRA) {
                    sendMeshMessage(currentState.room, self, cleanText)
                    return
                }
                Log.i(TAG, "[sendMessage] Client отправляет сообщение hostPeerId=${currentState.room.host.peerId.value} roomId=${currentState.room.roomId.value} textLength=${cleanText.length}")
                val message = newChatMessage(currentState.room.roomId, self, cleanText)
                appendMessage(message)
                roomTransport.sendToPeer(
                    currentState.room.host.peerId,
                    packet(
                        type = WirePacketType.CHAT_MESSAGE,
                        roomId = currentState.room.roomId,
                        sender = self,
                        message = message,
                    ),
                )
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> {
                Log.w(TAG, "[sendMessage] Сообщение отклонено, нет активной комнаты currentState=${_state.value.javaClass.simpleName}")
                setError("Нельзя отправить сообщение вне активной комнаты")
            }
        }
    }

    /**
     * Начинает передачу готовым адресатам и фиксирует UI-статус, если выбранный voice transport не установлен.
     */
    suspend fun startTalking(): Boolean {
        Log.i(TAG, "[startTalking] Команда начать передачу голоса currentState=${_state.value.javaClass.simpleName}")
        val selfPeerId = profileRepository.getOrCreatePeerId()
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                val targetPeerIds = currentState.members
                    .map { peer -> peer.peerId }
                    .filterNot { peerId -> peerId == selfPeerId }
                    .toSet()
                val readyPeerIds = targetPeerIds.filterTo(mutableSetOf()) { peerId ->
                    voiceTransport.isReadyForPeers(setOf(peerId))
                }
                if (readyPeerIds.isEmpty()) {
                    val message = voiceTransportUnavailableMessage(currentState.room)
                    Log.w(TAG, "[startTalking] Нет участников с готовым voice transport targetCount=${targetPeerIds.size} mode=${currentState.room.voiceTransportMode}")
                    markDirectAudioUnavailable(message)
                    emitNotice(message)
                    return false
                }
                val skippedPeerCount = targetPeerIds.size - readyPeerIds.size
                if (skippedPeerCount > 0) {
                    Log.w(TAG, "[startTalking] Неготовые участники исключены из текущей передачи readyCount=${readyPeerIds.size} skippedCount=$skippedPeerCount")
                }
                if (voiceRuntime.startTalking(selfPeerId, readyPeerIds)) {
                    addTalkingPeer(selfPeerId)
                    return true
                }
                return false
            }

            is RoomRuntimeState.Client -> {
                val targetPeerIds = setOf(currentState.room.host.peerId)
                if (!voiceTransport.isReadyForPeers(targetPeerIds)) {
                    val message = voiceTransportUnavailableMessage(currentState.room)
                    Log.w(TAG, "[startTalking] Voice transport с хостом еще не установлен mode=${currentState.room.voiceTransportMode}")
                    markDirectAudioUnavailable(message)
                    emitNotice(message)
                    return false
                }
                if (voiceRuntime.startTalking(selfPeerId, targetPeerIds)) {
                    addTalkingPeer(selfPeerId)
                    return true
                }
                return false
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> {
                Log.w(TAG, "[startTalking] Передача голоса отклонена, нет активной комнаты")
                setError("Нельзя передавать голос вне активной комнаты")
                return false
            }
        }
    }

    /**
     * Останавливает передачу микрофона.
     */
    suspend fun stopTalking() {
        Log.i(TAG, "[stopTalking] Команда остановить передачу голоса")
        voiceRuntime.stopTalking()
        removeTalkingPeer(profileRepository.getOrCreatePeerId())
    }

    /**
     * Обрабатывает события room transport и маршрутизирует их в доменную логику комнаты.
     */
    private fun handleRoomTransportEvent(event: RoomTransportEvent) {
        Log.i(TAG, "[handleRoomTransportEvent] Получено событие RoomTransportEvent type=${event.javaClass.simpleName} currentState=${_state.value.javaClass.simpleName}")
        when (event) {
            is RoomTransportEvent.EndpointFound -> handleEndpointFound(event)
            is RoomTransportEvent.EndpointLost -> handleEndpointLost(event)
            is RoomTransportEvent.ConnectionResult -> handleConnectionResult(event)
            is RoomTransportEvent.ConnectionReused -> handleConnectionReady(event.endpointId, reused = true)
            is RoomTransportEvent.ConnectionRecoveryRequired -> handleConnectionRecoveryRequired(event)
            is RoomTransportEvent.Disconnected -> handleDisconnected(event)
            is RoomTransportEvent.PacketReceived -> handlePacketReceived(event)
            is RoomTransportEvent.StreamReceived -> handleStreamReceived(event)
            is RoomTransportEvent.AdvertisingFailed -> {
                val message = nearbyFailureMessage("запустить комнату", event.cause)
                emitNotice(message)
                setError(
                    message = message,
                    action = actionFor(event.cause),
                )
            }
            is RoomTransportEvent.DiscoveryFailed -> {
                if (isActiveMeshState(_state.value)) {
                    Log.w(TAG, "[handleRoomTransportEvent] Ошибка healing discovery в активной MESHRA-комнате не роняет комнату: ${event.cause.message}", event.cause)
                    return
                }
                val message = nearbyFailureMessage("начать поиск комнат", event.cause)
                emitNotice(message)
                setError(
                    message = message,
                    action = actionFor(event.cause),
                )
            }
            is RoomTransportEvent.ConnectionRequestFailed -> {
                if (isActiveMeshState(_state.value)) {
                    Log.w(TAG, "[handleRoomTransportEvent] Ошибка connection request в активной MESHRA-комнате не роняет комнату: ${event.cause.message}", event.cause)
                    return
                }
                val message = nearbyFailureMessage("подключиться к комнате", event.cause)
                emitNotice(message)
                setError(message)
            }
            is RoomTransportEvent.ConnectionAcceptFailed -> {
                val message = nearbyFailureMessage("принять подключение", event.cause)
                emitNotice(message)
                setError(message)
            }
            is RoomTransportEvent.PayloadDecodeFailed -> setError("Не удалось прочитать пакет: ${event.cause.message.orEmpty()}")
            is RoomTransportEvent.SendFailed -> setError("Не удалось отправить пакет: ${event.cause.message.orEmpty()}")
            is RoomTransportEvent.ConnectionInitiated -> Unit
        }
    }

    /**
     * Обрабатывает события MESHRA-транспорта и синхронизирует snapshot комнаты с runtime-состоянием.
     */
    private fun handleMeshTransportEvent(event: MeshTransportEvent) {
        Log.i(TAG, "[handleMeshTransportEvent] Получено событие MeshTransportEvent type=${event.javaClass.simpleName} currentState=${_state.value.javaClass.simpleName}")
        when (event) {
            is MeshTransportEvent.GatewayFound -> handleMeshGatewayFound(event)
            is MeshTransportEvent.GatewayLost -> handleMeshGatewayLost(event)
            is MeshTransportEvent.LinkConnected -> handleMeshLinkConnected(event)
            is MeshTransportEvent.LinkDisconnected -> handleMeshLinkDisconnected(event)
            is MeshTransportEvent.EventAccepted -> handleMeshRoomUpdated(event.event.roomId)
            is MeshTransportEvent.SnapshotReceived -> handleMeshSnapshotReceived(event.snapshot)
            is MeshTransportEvent.DecodeFailed -> {
                if (isCurrentStateMesh()) {
                    setError("Не удалось прочитать mesh-пакет: ${event.cause.message.orEmpty()}")
                } else {
                    Log.i(TAG, "[handleMeshTransportEvent] Ошибка decode mesh-пакета проигнорирована вне MESHRA-состояния: ${event.cause.message}")
                }
            }
            is MeshTransportEvent.SendFailed -> {
                if (event.roomId == null) {
                    Log.w(TAG, "[handleMeshTransportEvent] Служебный mesh-пакет не отправлен, комнату не роняем: ${event.cause.message}", event.cause)
                    return
                }
                setError("Не удалось отправить mesh-пакет: ${event.cause.message.orEmpty()}")
            }
            is MeshTransportEvent.TransportFailed -> {
                if (!isCurrentStateMesh()) {
                    Log.i(TAG, "[handleMeshTransportEvent] Ошибка MeshTransport проигнорирована вне MESHRA-состояния: ${event.cause.message}")
                    return
                }
                if (_state.value !is RoomRuntimeState.Joining) {
                    Log.w(TAG, "[handleMeshTransportEvent] Ошибка MeshTransport в активной MESHRA-комнате не роняет комнату: ${event.cause.message}", event.cause)
                    return
                }
                val message = nearbyFailureMessage("выполнить mesh-операцию", event.cause)
                emitNotice(message)
                setError(
                    message = message,
                    action = actionFor(event.cause),
                )
            }
        }
    }

    /**
     * Обрабатывает события VoiceTransport и переводит media-plane готовность/ошибки в состояние комнаты.
     */
    private fun handleVoiceTransportEvent(event: VoiceTransportEvent) {
        when (event) {
            is VoiceTransportEvent.LocalControlInfoChanged -> handleLocalVoiceTransportInfoChanged(event.info)
            VoiceTransportEvent.DirectAudioReady -> handleDirectAudioReady()
            is VoiceTransportEvent.FrameReceived -> handleVoiceFrameReceived(event)
            is VoiceTransportEvent.TransportUnavailable -> {
                markDirectAudioUnavailable(event.message)
                emitNotice(event.message)
            }
            is VoiceTransportEvent.FrameSendFailed -> Log.w(
                TAG,
                "[handleVoiceTransportEvent] Voice frame не отправлен, не роняем комнату: ${event.cause.message}",
                event.cause,
            )
        }
    }

    /**
     * Помечает активную комнату готовой к выбранному voice transport и рассылает обновленный RoomInfo с host-а.
     */
    private fun handleDirectAudioReady() {
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                if (currentState.room.isDirectAudioReady && currentState.directAudioStatus == DirectAudioStatus.Ready) {
                    Log.i(TAG, "[handleDirectAudioReady] Voice transport уже помечен готовым roomId=${currentState.room.roomId.value} mode=${currentState.room.voiceTransportMode}")
                    return
                }
                val updatedRoom = currentState.room.copy(isDirectAudioReady = true)
                _state.value = currentState.copy(
                    room = updatedRoom,
                    directAudioStatus = DirectAudioStatus.Ready,
                )
                emitNotice(voiceTransportReadyMessage(updatedRoom))
                Log.i(TAG, "[handleDirectAudioReady] Host-комната готова к voice transport roomId=${updatedRoom.roomId.value} mode=${updatedRoom.voiceTransportMode} memberCount=${currentState.members.size}")
                broadcastMemberList(updatedRoom, currentState.members)
                voiceTransport.localControlInfo?.let { info ->
                    sendVoiceTransportInfoToMembers(updatedRoom, currentState.members, info)
                }
            }

            is RoomRuntimeState.Client -> {
                _state.value = currentState.copy(directAudioStatus = DirectAudioStatus.Ready)
                emitNotice(voiceTransportReadyMessage(currentState.room))
                Log.i(TAG, "[handleDirectAudioReady] Client media-plane сообщил готовность roomId=${currentState.room.roomId.value} mode=${currentState.room.voiceTransportMode}")
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> Log.i(TAG, "[handleDirectAudioReady] Voice transport готов вне активной комнаты currentState=${currentState.javaClass.simpleName}")
        }
    }

    /**
     * Формирует snackbar о готовности выбранного транспорта комнаты.
     */
    private fun voiceTransportReadyMessage(room: RoomInfo): String {
        return "${voiceTransportDisplayName(room.voiceTransportMode)} аудио готово"
    }

    /**
     * Формирует snackbar о недоступности выбранного транспорта комнаты.
     */
    private fun voiceTransportUnavailableMessage(room: RoomInfo): String {
        return "${voiceTransportDisplayName(room.voiceTransportMode)} аудиоканал не установлен"
    }

    /**
     * Возвращает короткое пользовательское имя media-plane режима комнаты.
     */
    private fun voiceTransportDisplayName(mode: VoiceTransportMode): String {
        return when (mode) {
            VoiceTransportMode.NEARBY_BYTES -> "Nearby"
            VoiceTransportMode.WIFI_DIRECT_UDP -> "Direct"
        }
    }

    /**
     * Сохраняет ошибку direct-аудио в активном состоянии, не ломая комнату и не затирая уже готовый канал.
     */
    private fun markDirectAudioUnavailable(message: String) {
        val unavailable = DirectAudioStatus.Unavailable(message.ifBlank { DIRECT_AUDIO_UNAVAILABLE_MESSAGE })
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                if (currentState.directAudioStatus == DirectAudioStatus.Ready) {
                    Log.i(TAG, "[markDirectAudioUnavailable] Host уже имеет готовый direct-аудиоканал, оставляем Ready")
                    return
                }
                _state.value = currentState.copy(directAudioStatus = unavailable)
                Log.w(TAG, "[markDirectAudioUnavailable] Host direct-аудио не установлено message=${unavailable.message}")
            }

            is RoomRuntimeState.Joining -> {
                _state.value = currentState.copy(directAudioStatus = unavailable)
                Log.w(TAG, "[markDirectAudioUnavailable] Joining direct-аудио не установлено message=${unavailable.message}")
            }

            is RoomRuntimeState.Client -> {
                if (currentState.directAudioStatus == DirectAudioStatus.Ready) {
                    Log.i(TAG, "[markDirectAudioUnavailable] Client уже имеет готовый direct-аудиоканал, оставляем Ready")
                    return
                }
                _state.value = currentState.copy(directAudioStatus = unavailable)
                Log.w(TAG, "[markDirectAudioUnavailable] Client direct-аудио не установлено message=${unavailable.message}")
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Error,
            -> Log.i(TAG, "[markDirectAudioUnavailable] Ошибка direct-аудио вне активной комнаты currentState=${currentState.javaClass.simpleName}")
        }
    }

    /**
     * Рассылает локальную voice transport info через room signaling, когда transport уже готов к подключению соседей.
     */
    private fun handleLocalVoiceTransportInfoChanged(info: VoiceTransportControlInfo) {
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                Log.i(TAG, "[handleLocalVoiceTransportInfoChanged] Host рассылает voice info для запуска handshake memberCount=${currentState.members.size}")
                sendToMembers(
                    currentState.members,
                    packet(
                        type = WirePacketType.VOICE_TRANSPORT_INFO,
                        roomId = currentState.room.roomId,
                        sender = profileRepository.getSelfPeer(),
                        voiceTransportInfo = info,
                    ),
                    excludedPeerIds = setOf(profileRepository.getOrCreatePeerId()),
                )
            }

            is RoomRuntimeState.Client -> {
                Log.i(TAG, "[handleLocalVoiceTransportInfoChanged] Client отправляет voice info hostPeerId=${currentState.room.host.peerId.value}")
                roomTransport.sendToPeer(
                    currentState.room.host.peerId,
                    packet(
                        type = WirePacketType.VOICE_TRANSPORT_INFO,
                        roomId = currentState.room.roomId,
                        sender = profileRepository.getSelfPeer(),
                        voiceTransportInfo = info,
                    ),
                )
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> Log.i(TAG, "[handleLocalVoiceTransportInfoChanged] Voice info пока не отправлена currentState=${currentState.javaClass.simpleName}")
        }
    }

    /**
     * Закрывает legacy voice stream, потому что актуальный media-plane работает через VoiceTransport frames.
     */
    private fun handleStreamReceived(event: RoomTransportEvent.StreamReceived) {
        Log.i(TAG, "[handleStreamReceived] Legacy voice stream закрыт endpointId=${event.endpointId} peerId=${event.peerId?.value}")
        closeInputStream(event.inputStream)
    }

    /**
     * Передает входящий voice frame в VoiceRuntime после проверки прямого соседа транспорта.
     */
    private fun handleVoiceFrameReceived(event: VoiceTransportEvent.FrameReceived) {
        val peerId = event.transportPeerId
        if (peerId == null) {
            Log.w(TAG, "[handleVoiceFrameReceived] Voice frame без известного peerId endpointId=${event.transportEndpointId}")
            return
        }

        if (_state.value !is RoomRuntimeState.Hosting && _state.value !is RoomRuntimeState.Client) {
            Log.w(TAG, "[handleVoiceFrameReceived] Voice frame получен вне активной комнаты currentState=${_state.value.javaClass.simpleName}")
            return
        }
        if (event.frame.isFinal) {
            cancelTalkingPeerTimeout(event.frame.originPeerId)
        } else {
            addTalkingPeer(event.frame.originPeerId)
            scheduleTalkingPeerTimeout(event.frame.originPeerId)
        }

        voiceRuntime.playIncomingFrame(
            directPeerId = peerId,
            frame = event.frame,
            resolveRelayTargets = ::resolveVoiceRelayTargets,
            onStarted = ::addTalkingPeer,
            onFinished = ::removeTalkingPeer,
        )
    }

    /**
     * Проверяет заявленного автора voice frame и возвращает адресатов host relay либо null при недопустимом маршруте.
     */
    private fun resolveVoiceRelayTargets(directPeerId: PeerId, originPeerId: PeerId): Set<PeerId>? {
        return when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                val originIsDirectMember = originPeerId == directPeerId &&
                    currentState.members.any { peer -> peer.peerId == originPeerId }
                if (!originIsDirectMember) {
                    Log.w(TAG, "[resolveVoiceRelayTargets] Host отклонил автора directPeerId=${directPeerId.value} originPeerId=${originPeerId.value}")
                    null
                } else {
                    val selfPeerId = profileRepository.getOrCreatePeerId()
                    currentState.members
                        .asSequence()
                        .map { peer -> peer.peerId }
                        .filterNot { peerId -> peerId == selfPeerId || peerId == originPeerId }
                        .toSet()
                }
            }

            is RoomRuntimeState.Client -> {
                val streamCameFromHost = directPeerId == currentState.room.host.peerId
                val originIsMember = currentState.members.any { peer -> peer.peerId == originPeerId }
                if (!streamCameFromHost || !originIsMember) {
                    Log.w(TAG, "[resolveVoiceRelayTargets] Client отклонил маршрут directPeerId=${directPeerId.value} originPeerId=${originPeerId.value}")
                    null
                } else {
                    emptySet()
                }
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> null
        }
    }

    /**
     * Добавляет найденную комнату и связывает ее RoomId с transport endpointId.
     */
    private fun handleEndpointFound(event: RoomTransportEvent.EndpointFound) {
        val roomInfo = event.roomInfo?.copy(discoveryEndpointId = event.endpointId)
        if (roomInfo == null) {
            Log.w(TAG, "[handleEndpointFound] Endpoint найден, но визитка комнаты отсутствует endpointId=${event.endpointId}")
            return
        }
        roomIdsSeenInDiscoveryCycle?.add(roomInfo.roomId)
        roomEndpoints[roomInfo.roomId] = event.endpointId
        endpointRooms[event.endpointId] = roomInfo.roomId
        upsertAvailableRoom(roomInfo)
        Log.i(TAG, "[handleEndpointFound] Комната добавлена roomId=${roomInfo.roomId.value} roomName=${roomInfo.name} endpointId=${event.endpointId} availableCount=${_availableRooms.value.size}")
        reconnectAfterRecoveryIfNeeded(roomInfo.roomId, event.endpointId)
    }

    /**
     * Добавляет найденный mesh gateway как MESHRA-комнату в общий список лобби и дедупит дубли по roomToken.
     */
    private fun handleMeshGatewayFound(event: MeshTransportEvent.GatewayFound) {
        val endpointId = event.candidateId.value
        val roomInfo = event.advertisement.toRoomInfo().copy(discoveryEndpointId = endpointId)
        roomIdsSeenInDiscoveryCycle?.add(roomInfo.roomId)
        roomEndpoints[roomInfo.roomId] = endpointId
        endpointRooms[endpointId] = roomInfo.roomId
        upsertAvailableRoom(roomInfo)
        Log.i(
            TAG,
            "[handleMeshGatewayFound] Mesh gateway добавлен roomId=${roomInfo.roomId.value} roomName=${roomInfo.name} endpointId=$endpointId memberCount=${event.advertisement.memberCount}",
        )
        reconnectAfterRecoveryIfNeeded(roomInfo.roomId, endpointId)
        connectToMeshGatewayForHealingIfNeeded(roomInfo, endpointId)
    }

    /**
     * Удаляет mesh gateway из списка лобби, если discovery потерял соответствующий endpoint.
     */
    private fun handleMeshGatewayLost(event: MeshTransportEvent.GatewayLost) {
        val endpointId = event.candidateId.value
        val roomId = endpointRooms[endpointId] ?: return
        roomEndpoints.remove(roomId)
        endpointRooms.remove(endpointId)
        _availableRooms.value = _availableRooms.value.filterNot { roomInfo -> roomInfo.roomId == roomId }
        Log.i(TAG, "[handleMeshGatewayLost] Mesh gateway удален roomId=${roomId.value} endpointId=$endpointId")
    }

    /**
     * Подтверждает готовность mesh-link-а; вход в комнату завершается после получения snapshot-а.
     */
    private fun handleMeshLinkConnected(event: MeshTransportEvent.LinkConnected) {
        val joiningState = _state.value as? RoomRuntimeState.Joining ?: return
        if (joiningState.room.roomTransportMode != RoomTransportMode.MESHRA) {
            return
        }
        val expectedEndpointId = roomEndpoints[joiningState.room.roomId]
        if (expectedEndpointId != event.linkId.value) {
            Log.w(TAG, "[handleMeshLinkConnected] Mesh link не совпал с выбранным gateway linkId=${event.linkId.value} expectedEndpointId=$expectedEndpointId")
            return
        }
        Log.i(TAG, "[handleMeshLinkConnected] Mesh link готов, ждем snapshot roomId=${joiningState.room.roomId.value} linkId=${event.linkId.value} reused=${event.reused}")
    }

    /**
     * Запускает mesh discovery после разрыва link-а, чтобы активная MESHRA-комната могла добрать соседей.
     */
    private fun handleMeshLinkDisconnected(event: MeshTransportEvent.LinkDisconnected) {
        Log.i(TAG, "[handleMeshLinkDisconnected] Mesh link отключен linkId=${event.linkId.value} activeLinkCount=${meshTransport.activeLinkCount()}")
        val roomId = activeMeshRoomId() ?: return
        startMeshHealingDiscovery(roomId, reason = "link_disconnected")
        connectToKnownMeshGatewaysForHealing(roomId)
    }

    /**
     * Обрабатывает полученный snapshot: завершает mesh-вход или синхронизирует уже активную комнату.
     */
    private fun handleMeshSnapshotReceived(snapshot: MeshRoomSnapshot) {
        completeMeshJoinIfNeeded(snapshot)
        handleMeshRoomUpdated(snapshot.roomId)
    }

    /**
     * Завершает вход в MESHRA-комнату после snapshot-а от gateway и публикует локальное MEMBER_JOINED.
     */
    private fun completeMeshJoinIfNeeded(snapshot: MeshRoomSnapshot) {
        val currentState = _state.value as? RoomRuntimeState.Joining ?: return
        if (!isJoiningSameMeshRoom(currentState.room, snapshot)) {
            return
        }
        val self = profileRepository.getSelfPeer()
        val room = roomInfoFromMeshSnapshot(snapshot)
        val members = mergeMember(snapshot.members, self)
        _messages.value = snapshot.messages
        _state.value = RoomRuntimeState.Client(
            room = room,
            members = members,
            directAudioStatus = DirectAudioStatus.Unavailable(MESH_VOICE_UNAVAILABLE_MESSAGE),
        )
        clearConnectionRecovery()
        activateRoomTransportMode(room.roomTransportMode, reason = "mesh_snapshot_received")
        meshTransport.publishMemberJoined(
            roomId = room.roomId,
            roomName = room.name,
            knownHost = room.host,
            member = self,
            roomTransportMode = room.roomTransportMode,
            voiceTransportMode = room.voiceTransportMode,
            isDirectAudioReady = room.isDirectAudioReady,
        )
        advertiseMeshSnapshot(room.roomId, self)
        startMeshHealingDiscovery(room.roomId, reason = "mesh_join_completed")
        connectToKnownMeshGatewaysForHealing(room.roomId)
        Log.i(TAG, "[completeMeshJoinIfNeeded] Mesh-вход завершен roomId=${room.roomId.value} memberCount=${members.size}")
    }

    /**
     * Синхронизирует runtime-состояние с актуальным mesh snapshot-ом и перезапускает gateway-рекламу активной комнаты.
     */
    private fun handleMeshRoomUpdated(roomId: RoomId) {
        val snapshot = meshTransport.rooms.value[roomId] ?: return
        val room = roomInfoFromMeshSnapshot(snapshot)
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                if (currentState.room.roomTransportMode != RoomTransportMode.MESHRA || currentState.room.roomId != roomId) {
                    return
                }
                _messages.value = snapshot.messages
                _state.value = currentState.copy(
                    room = room,
                    members = snapshot.members,
                    directAudioStatus = DirectAudioStatus.Unavailable(MESH_VOICE_UNAVAILABLE_MESSAGE),
                )
                advertiseMeshSnapshot(roomId, profileRepository.getSelfPeer())
                connectToKnownMeshGatewaysForHealing(roomId)
                Log.i(TAG, "[handleMeshRoomUpdated] Host MESHRA snapshot применен roomId=${roomId.value} memberCount=${snapshot.members.size} messageCount=${snapshot.messages.size}")
            }

            is RoomRuntimeState.Client -> {
                if (currentState.room.roomTransportMode != RoomTransportMode.MESHRA || currentState.room.roomId != roomId) {
                    return
                }
                _messages.value = snapshot.messages
                _state.value = currentState.copy(
                    room = room,
                    members = snapshot.members,
                    directAudioStatus = DirectAudioStatus.Unavailable(MESH_VOICE_UNAVAILABLE_MESSAGE),
                )
                advertiseMeshSnapshot(roomId, profileRepository.getSelfPeer())
                connectToKnownMeshGatewaysForHealing(roomId)
                Log.i(TAG, "[handleMeshRoomUpdated] Client MESHRA snapshot применен roomId=${roomId.value} memberCount=${snapshot.members.size} messageCount=${snapshot.messages.size}")
            }

            is RoomRuntimeState.Joining -> completeMeshJoinIfNeeded(snapshot)
            RoomRuntimeState.Searching -> upsertAvailableRoom(room)
            RoomRuntimeState.Idle,
            is RoomRuntimeState.Error,
            -> Unit
        }
    }

    /**
     * Подключается к найденному gateway той же MESHRA-комнаты, если активных/pending link-ов меньше целевого числа.
     */
    private fun connectToMeshGatewayForHealingIfNeeded(roomInfo: RoomInfo, endpointId: String) {
        val activeMeshState = activeMeshStateInfo() ?: return
        if (!isSameMeshDiscoveryRoom(roomInfo, activeMeshState.room.roomId)) {
            return
        }
        val candidateId = NeighborCandidateId(endpointId)
        if (meshTransport.hasLinkOrPending(candidateId)) {
            return
        }
        val gatewayPeerId = (roomInfo.gateway ?: roomInfo.host).peerId
        if (!shouldUseMeshGateway(gatewayPeerId)) {
            Log.i(TAG, "[connectToMeshGatewayForHealingIfNeeded] Gateway пропущен ignore-list endpointId=$endpointId gatewayPeerId=${gatewayPeerId.value}")
            return
        }
        val knownMemberCount = maxOf(activeMeshState.memberCount, roomInfo.memberCount ?: 0)
        val targetLinkCount = desiredMeshLinkCount(knownMemberCount)
        val currentLinkOrPendingCount = meshTransport.linkOrPendingCount()
        if (targetLinkCount <= 0 || currentLinkOrPendingCount >= targetLinkCount || currentLinkOrPendingCount >= MESH_MAX_LINKS) {
            Log.i(
                TAG,
                "[connectToMeshGatewayForHealingIfNeeded] Mesh link не нужен endpointId=$endpointId current=$currentLinkOrPendingCount target=$targetLinkCount knownMembers=$knownMemberCount",
            )
            return
        }
        Log.i(
            TAG,
            "[connectToMeshGatewayForHealingIfNeeded] Добираем mesh link endpointId=$endpointId current=$currentLinkOrPendingCount target=$targetLinkCount knownMembers=$knownMemberCount",
        )
        meshTransport.connectToGateway(candidateId = candidateId, gatewayPeerId = gatewayPeerId)
    }

    /**
     * Пробует добрать mesh link-и из уже известных discovery-кандидатов текущей комнаты.
     */
    private fun connectToKnownMeshGatewaysForHealing(roomId: RoomId) {
        _availableRooms.value
            .filter { roomInfo -> roomInfo.discoveryEndpointId != null && isSameMeshDiscoveryRoom(roomInfo, roomId) }
            .forEach { roomInfo ->
                connectToMeshGatewayForHealingIfNeeded(
                    roomInfo = roomInfo,
                    endpointId = roomInfo.discoveryEndpointId.orEmpty(),
                )
            }
    }

    /**
     * Возвращает целевое количество активных mesh link-ов для текущего размера комнаты.
     */
    private fun desiredMeshLinkCount(memberCount: Int): Int {
        return (memberCount - 1)
            .coerceAtLeast(0)
            .coerceAtMost(MESH_MIN_LINKS)
            .coerceAtMost(MESH_MAX_LINKS)
    }

    /**
     * Запускает discovery активной MESHRA-комнаты для фонового добора соседей.
     */
    private fun startMeshHealingDiscovery(roomId: RoomId, reason: String) {
        meshTransport.startDiscovery()
        Log.i(TAG, "[startMeshHealingDiscovery] Mesh discovery оставлен активным для healing roomId=${roomId.value} reason=$reason")
    }

    /**
     * Возвращает true, если найденная discovery-комната относится к текущей активной MESHRA-комнате.
     */
    private fun isSameMeshDiscoveryRoom(discoveryRoom: RoomInfo, activeRoomId: RoomId): Boolean {
        return discoveryRoom.roomTransportMode == RoomTransportMode.MESHRA &&
            (discoveryRoom.roomId == activeRoomId || MeshRoomAdvertisement.matchesAdvertisedRoomId(discoveryRoom.roomId, activeRoomId))
    }

    /**
     * Возвращает RoomId активной MESHRA-комнаты, если runtime сейчас находится в ней.
     */
    private fun activeMeshRoomId(): RoomId? {
        return activeMeshStateInfo()?.room?.roomId
    }

    /**
     * Возвращает true для Hosting/Client/Joining MESHRA-состояний, где mesh discovery нужен не только лобби.
     */
    private fun isActiveMeshState(state: RoomRuntimeState): Boolean {
        return when (state) {
            is RoomRuntimeState.Hosting -> state.room.roomTransportMode == RoomTransportMode.MESHRA
            is RoomRuntimeState.Client -> state.room.roomTransportMode == RoomTransportMode.MESHRA
            is RoomRuntimeState.Joining -> state.room.roomTransportMode == RoomTransportMode.MESHRA
            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Error,
            -> false
        }
    }

    /**
     * Возвращает комнату и известное количество участников для активного MESHRA-состояния.
     */
    private fun activeMeshStateInfo(): ActiveMeshStateInfo? {
        return when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                if (currentState.room.roomTransportMode == RoomTransportMode.MESHRA) {
                    ActiveMeshStateInfo(currentState.room, currentState.members.size)
                } else {
                    null
                }
            }
            is RoomRuntimeState.Client -> {
                if (currentState.room.roomTransportMode == RoomTransportMode.MESHRA) {
                    ActiveMeshStateInfo(currentState.room, currentState.members.size)
                } else {
                    null
                }
            }
            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> null
        }
    }

    /**
     * После свежего обнаружения восстанавливаемой комнаты останавливает recovery discovery и повторяет connection один раз.
     */
    private fun reconnectAfterRecoveryIfNeeded(roomId: RoomId, endpointId: String) {
        val currentState = _state.value as? RoomRuntimeState.Joining ?: return
        if (recoveringRoomId != roomId || currentState.room.roomId != roomId || recoveryReconnectRequested) {
            return
        }
        recoveryReconnectRequested = true
        connectionRecoveryJob?.cancel()
        connectionRecoveryJob = null
        roomTransport.stopDiscovery()
        if (currentState.room.roomTransportMode == RoomTransportMode.MESHRA) {
            val gatewayPeerId = (_availableRooms.value.firstOrNull { roomInfo -> roomInfo.roomId == roomId }?.gateway ?: currentState.room.gateway ?: currentState.room.host).peerId
            Log.i(TAG, "[reconnectAfterRecoveryIfNeeded] Mesh gateway найден заново, повторяем connection roomId=${roomId.value} endpointId=$endpointId")
            meshTransport.connectToGateway(
                candidateId = NeighborCandidateId(endpointId),
                gatewayPeerId = gatewayPeerId,
            )
        } else {
            Log.i(TAG, "[reconnectAfterRecoveryIfNeeded] Комната найдена заново, повторяем connection roomId=${roomId.value} endpointId=$endpointId")
            roomTransport.connectToEndpoint(endpointId)
        }
    }

    /**
     * Удаляет комнату из списка доступных при потере transport endpoint.
     */
    private fun handleEndpointLost(event: RoomTransportEvent.EndpointLost) {
        val roomId = event.roomId ?: endpointRooms[event.endpointId] ?: return
        roomEndpoints.remove(roomId)
        endpointRooms.remove(event.endpointId)
        _availableRooms.value = _availableRooms.value.filterNot { it.roomId == roomId }
        Log.i(TAG, "[handleEndpointLost] Комната удалена roomId=${roomId.value} endpointId=${event.endpointId} availableCount=${_availableRooms.value.size}")
    }

    /**
     * Сверяет комнаты прошлого цикла, перезапускает room discovery и открывает новый набор увиденных RoomId.
     */
    private fun startDiscoveryCycle(reconcilePreviousCycle: Boolean) {
        roomTransport.stopDiscovery()
        if (reconcilePreviousCycle) {
            reconcileAvailableRoomsWithDiscoveryCycle()
        }
        roomIdsSeenInDiscoveryCycle = ConcurrentHashMap.newKeySet()
        roomTransport.startDiscovery()
        _state.value = RoomRuntimeState.Searching
        Log.i(
            TAG,
            "[startDiscoveryCycle] Новый discovery-цикл запущен reconcilePreviousCycle=$reconcilePreviousCycle availableCount=${_availableRooms.value.size}",
        )
    }

    /**
     * Удаляет комнаты, которые не были повторно найдены в завершившемся discovery-цикле.
     */
    private fun reconcileAvailableRoomsWithDiscoveryCycle() {
        val seenRoomIds = roomIdsSeenInDiscoveryCycle ?: return
        val missingRoomIds = _availableRooms.value
            .asSequence()
            .map { roomInfo -> roomInfo.roomId }
            .filterNot(seenRoomIds::contains)
            .toSet()
        if (missingRoomIds.isEmpty()) {
            Log.i(TAG, "[reconcileAvailableRoomsWithDiscoveryCycle] Расхождений не найдено seenCount=${seenRoomIds.size}")
            return
        }

        missingRoomIds.forEach { roomId ->
            roomEndpoints.remove(roomId)?.let { endpointId -> endpointRooms.remove(endpointId) }
        }
        _availableRooms.value = _availableRooms.value.filterNot { roomInfo -> roomInfo.roomId in missingRoomIds }
        Log.i(
            TAG,
            "[reconcileAvailableRoomsWithDiscoveryCycle] Пропавшие комнаты удалены missingCount=${missingRoomIds.size} availableCount=${_availableRooms.value.size}",
        )
    }

    /**
     * Проверяет результат нового room connection и передает готовый endpoint в общий обработчик входа.
     */
    private fun handleConnectionResult(event: RoomTransportEvent.ConnectionResult) {
        if ((_state.value as? RoomRuntimeState.Joining)?.room?.roomTransportMode == RoomTransportMode.MESHRA) {
            Log.i(TAG, "[handleConnectionResult] RoomTransport connection result проигнорирован для MESHRA endpointId=${event.endpointId}")
            return
        }
        if (!event.success) {
            Log.w(TAG, "[handleConnectionResult] Соединение не установлено endpointId=${event.endpointId} statusCode=${event.statusCode}")
            if (_state.value is RoomRuntimeState.Joining) {
                setError("Подключение не установлено: ${event.statusCode}")
            }
            return
        }

        handleConnectionReady(event.endpointId, reused = false)
    }

    /**
     * Отправляет JOIN_REQUEST через новый или переиспользованный endpoint текущей Joining-комнаты без реального RoomId.
     */
    private fun handleConnectionReady(endpointId: String, reused: Boolean) {
        val currentState = _state.value as? RoomRuntimeState.Joining ?: return
        if (currentState.room.roomTransportMode == RoomTransportMode.MESHRA) {
            Log.i(TAG, "[handleConnectionReady] RoomTransport JOIN_REQUEST пропущен для MESHRA endpointId=$endpointId reused=$reused")
            return
        }
        val expectedEndpointId = roomEndpoints[currentState.room.roomId]
        if (expectedEndpointId != endpointId) {
            Log.w(TAG, "[handleConnectionReady] Готовое соединение не совпало с joining endpointId=$endpointId expectedEndpointId=$expectedEndpointId")
            return
        }

        val self = profileRepository.getSelfPeer()
        Log.i(TAG, "[handleConnectionReady] Соединение готово, отправляем JOIN_REQUEST roomId=${currentState.room.roomId.value} endpointId=$endpointId reused=$reused")
        roomTransport.sendToPeer(
            currentState.room.host.peerId,
            packet(
                type = WirePacketType.JOIN_REQUEST,
                sender = self,
                peer = self,
            ),
        )
    }

    /**
     * Выполняет один clean-discovery retry после 8007/8009/8012 либо завершает Joining ошибкой после повторного сбоя.
     */
    private fun handleConnectionRecoveryRequired(event: RoomTransportEvent.ConnectionRecoveryRequired) {
        val currentState = _state.value as? RoomRuntimeState.Joining ?: return
        if (connectionRecoveryAttemptCount >= MAX_CONNECTION_RECOVERY_ATTEMPTS) {
            Log.w(TAG, "[handleConnectionRecoveryRequired] Recovery уже использован endpointId=${event.endpointId}: ${event.cause.message}")
            setError("Не удалось восстановить подключение к комнате: ${event.cause.message.orEmpty()}")
            return
        }

        connectionRecoveryAttemptCount += 1
        recoveringRoomId = currentState.room.roomId
        recoveryReconnectRequested = false
        removeAvailableRoom(currentState.room.roomId, reason = "connection_recovery")
        roomEndpoints.remove(currentState.room.roomId)
        endpointRooms.remove(event.endpointId)
        connectionRecoveryJob?.cancel()
        connectionRecoveryJob = externalScope.launch {
            Log.i(TAG, "[handleConnectionRecoveryRequired] Ждем cooldown перед clean discovery roomId=${currentState.room.roomId.value} attempt=$connectionRecoveryAttemptCount")
            delay(CONNECTION_RECOVERY_COOLDOWN_MILLIS)
            val joiningState = _state.value as? RoomRuntimeState.Joining
            if (joiningState?.room?.roomId != currentState.room.roomId || recoveringRoomId != currentState.room.roomId) {
                return@launch
            }
            roomTransport.startDiscovery()
            Log.i(TAG, "[handleConnectionRecoveryRequired] Clean discovery запущен roomId=${currentState.room.roomId.value}")
            delay(CONNECTION_RECOVERY_DISCOVERY_TIMEOUT_MILLIS)
            if (_state.value is RoomRuntimeState.Joining && recoveringRoomId == currentState.room.roomId && !recoveryReconnectRequested) {
                setError("Комната не найдена повторно после сброса соединения")
            }
        }
    }

    /**
     * Обновляет состояние при разрыве room-соединения.
     */
    private fun handleDisconnected(event: RoomTransportEvent.Disconnected) {
        val peerId = event.peerId
        if (peerId == null) {
            Log.w(TAG, "[handleDisconnected] Endpoint отключился без известного peerId endpointId=${event.endpointId}")
            return
        }
        Log.i(TAG, "[handleDisconnected] Peer отключился endpointId=${event.endpointId} peerId=${peerId.value} currentState=${_state.value.javaClass.simpleName}")
        cancelTalkingPeerTimeout(peerId)
        removeTalkingPeer(peerId)
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                val members = currentState.members.filterNot { it.peerId == peerId }
                _state.value = currentState.copy(members = members)
                Log.i(TAG, "[handleDisconnected] Host обновил список участников memberCount=${members.size}")
                broadcastMemberList(currentState.room, members)
            }

            is RoomRuntimeState.Client -> {
                if (peerId == currentState.room.host.peerId) {
                    Log.i(TAG, "[handleDisconnected] Отключился host, запускаем переподключение client-сессии")
                    beginClientReconnectAfterHostDisconnect(currentState)
                }
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> Unit
        }
    }

    /**
     * Переводит client обратно в Joining и запускает clean discovery той же advertised-комнаты после обрыва host-а.
     */
    private fun beginClientReconnectAfterHostDisconnect(currentState: RoomRuntimeState.Client) {
        clearConnectionRecovery()
        voiceRuntime.stopAll()
        voiceTransport.stopSession()
        _talkingPeerIds.value = emptySet()
        val reconnectRoom = realRoomToAdvertisedRoom[currentState.room.roomId] ?: currentState.room
        val oldEndpointId = roomEndpoints.remove(currentState.room.roomId)
        if (oldEndpointId != null) {
            endpointRooms.remove(oldEndpointId)
        }
        _state.value = RoomRuntimeState.Joining(reconnectRoom)
        recoveringRoomId = reconnectRoom.roomId
        recoveryReconnectRequested = false
        connectionRecoveryAttemptCount = 0
        roomTransport.stopAllEndpointsAndClearState(reason = "client_host_disconnect_reconnect")
        connectionRecoveryJob = externalScope.launch {
            Log.i(TAG, "[beginClientReconnectAfterHostDisconnect] Запускаем discovery для переподключения roomId=${reconnectRoom.roomId.value}")
            roomTransport.startDiscovery()
            delay(CLIENT_RECONNECT_DISCOVERY_TIMEOUT_MILLIS)
            if (_state.value is RoomRuntimeState.Joining && recoveringRoomId == reconnectRoom.roomId && !recoveryReconnectRequested) {
                setError("Не удалось переподключиться к комнате")
            }
        }
    }

    /**
     * Отбрасывает дубликаты packetId и отправляет пакет в обработчик по типу.
     */
    private fun handlePacketReceived(event: RoomTransportEvent.PacketReceived) {
        val packetId = event.packet.packetId
        if (packetId != null && !seenPacketIds.add(packetId)) {
            Log.i(TAG, "[handlePacketReceived] Дубликат packet проигнорирован packetId=${packetId.value} type=${event.packet.type}")
            return
        }

        Log.i(TAG, "[handlePacketReceived] Обрабатываем packet type=${event.packet.type} roomId=${event.packet.roomId?.value} peerId=${event.peerId?.value}")
        when (event.packet.type) {
            WirePacketType.JOIN_REQUEST -> handleJoinRequest(event)
            WirePacketType.JOIN_ACCEPTED -> handleJoinAccepted(event.packet)
            WirePacketType.JOIN_REJECTED -> handleJoinRejected(event.packet)
            WirePacketType.MEMBER_LIST -> handleMemberList(event.packet)
            WirePacketType.MEMBER_JOINED -> handleMemberJoined(event.packet)
            WirePacketType.MEMBER_LEFT -> handleMemberLeft(event.packet)
            WirePacketType.CHAT_MESSAGE -> handleChatMessage(event.packet)
            WirePacketType.ROOM_CLOSED -> handleRoomClosed()
            WirePacketType.PING -> handlePing(event.packet)
            WirePacketType.VOICE_TRANSPORT_INFO -> handleVoiceTransportInfo(event.packet)
            WirePacketType.PONG,
            WirePacketType.ROOM_INFO,
            -> Unit
        }
    }

    /**
     * На стороне host принимает JOIN_REQUEST по прямому endpoint-у и рассылает реальный RoomInfo участнику.
     */
    private fun handleJoinRequest(event: RoomTransportEvent.PacketReceived) {
        val currentState = _state.value as? RoomRuntimeState.Hosting ?: return
        val joiningPeer = event.packet.peer ?: event.packet.sender
        val packetRoomId = event.packet.roomId
        if (joiningPeer == null || (packetRoomId != null && packetRoomId != currentState.room.roomId)) {
            Log.w(TAG, "[handleJoinRequest] Некорректный JOIN_REQUEST roomId=${event.packet.roomId?.value} expectedRoomId=${currentState.room.roomId.value}")
            sendJoinRejected(event.packet.sender, currentState.room.roomId, "Некорректный запрос входа")
            return
        }

        val members = mergeMember(currentState.members, joiningPeer)
        _state.value = currentState.copy(members = members)
        Log.i(TAG, "[handleJoinRequest] Участник принят peerId=${joiningPeer.peerId.value} roomId=${currentState.room.roomId.value} memberCount=${members.size}")

        roomTransport.sendToPeer(
            joiningPeer.peerId,
            packet(
                type = WirePacketType.JOIN_ACCEPTED,
                roomId = currentState.room.roomId,
                sender = profileRepository.getSelfPeer(),
                roomInfo = currentState.room,
            ),
        )
        roomTransport.sendToPeer(
            joiningPeer.peerId,
            packet(
                type = WirePacketType.MEMBER_LIST,
                roomId = currentState.room.roomId,
                sender = profileRepository.getSelfPeer(),
                members = members,
            ),
        )
        sendToMembers(
            members,
            packet(
                type = WirePacketType.MEMBER_JOINED,
                roomId = currentState.room.roomId,
                sender = profileRepository.getSelfPeer(),
                peer = joiningPeer,
            ),
            excludedPeerIds = setOf(profileRepository.getOrCreatePeerId(), joiningPeer.peerId),
        )
        voiceTransport.localControlInfo?.let { info ->
            sendVoiceTransportInfoTo(joiningPeer.peerId, currentState.room.roomId, info)
        }
    }

    /**
     * Завершает вход по JOIN_ACCEPTED: заменяет временную комнату из рекламы реальным RoomInfo, сохраняет участников и запускает client media-plane.
     */
    private fun handleJoinAccepted(packet: WirePacket) {
        val currentState = _state.value as? RoomRuntimeState.Joining ?: return
        val room = packet.roomInfo ?: currentState.room
        val joiningWasAdvertised = roomTransport.isAdvertisedRoomId(currentState.room.roomId)
        if (!joiningWasAdvertised && room.roomId != currentState.room.roomId) {
            Log.w(TAG, "[handleJoinAccepted] JOIN_ACCEPTED не для текущей комнаты packetRoomId=${room.roomId.value} currentRoomId=${currentState.room.roomId.value}")
            return
        }
        replaceAdvertisedRoomMappingIfNeeded(advertisedRoom = currentState.room, realRoom = room)
        _state.value = RoomRuntimeState.Client(room = room, members = listOf(room.host, profileRepository.getSelfPeer()))
        clearConnectionRecovery()
        activateRoomTransportMode(room.roomTransportMode, reason = "join_accepted")
        voiceTransport.setMode(room.voiceTransportMode, reason = "join_accepted")
        voiceTransport.startSession(profileRepository.getOrCreatePeerId(), VoiceTransportSessionRole.CLIENT)
        packet.voiceTransportInfo?.let { info ->
            voiceTransport.handleControlInfo(room.host.peerId, info)
        }
        voiceTransport.localControlInfo?.let { info ->
            sendVoiceTransportInfoTo(room.host.peerId, room.roomId, info)
        }
        Log.i(TAG, "[handleJoinAccepted] Runtime переведен в Client roomId=${room.roomId.value} roomTransportMode=${room.roomTransportMode} voiceTransportMode=${room.voiceTransportMode}")
    }

    /**
     * Переносит endpoint mapping с временного RoomId из рекламы на настоящий RoomId, полученный от host-а.
     */
    private fun replaceAdvertisedRoomMappingIfNeeded(advertisedRoom: RoomInfo, realRoom: RoomInfo) {
        if (!roomTransport.isAdvertisedRoomId(advertisedRoom.roomId) || advertisedRoom.roomId == realRoom.roomId) {
            return
        }
        val endpointId = roomEndpoints.remove(advertisedRoom.roomId) ?: return
        endpointRooms[endpointId] = realRoom.roomId
        roomEndpoints[realRoom.roomId] = endpointId
        realRoomToAdvertisedRoom[realRoom.roomId] = advertisedRoom
        _availableRooms.value = _availableRooms.value.filterNot { roomInfo -> roomInfo.roomId == advertisedRoom.roomId }
        Log.i(
            TAG,
            "[replaceAdvertisedRoomMappingIfNeeded] Временная комната заменена реальной advertisedRoomId=${advertisedRoom.roomId.value} realRoomId=${realRoom.roomId.value} endpointId=$endpointId",
        )
    }

    /**
     * Завершает попытку входа ошибкой после JOIN_REJECTED.
     */
    private fun handleJoinRejected(packet: WirePacket) {
        val currentState = _state.value
        if (currentState is RoomRuntimeState.Joining) {
            Log.w(TAG, "[handleJoinRejected] Вход отклонен reason=${packet.reason}")
            setError(packet.reason ?: "Вход в комнату отклонен")
        }
    }

    /**
     * Заменяет список участников на стороне client.
     */
    private fun handleMemberList(packet: WirePacket) {
        val currentState = _state.value as? RoomRuntimeState.Client ?: return
        if (packet.roomId != currentState.room.roomId) {
            Log.w(TAG, "[handleMemberList] MEMBER_LIST не для текущей комнаты packetRoomId=${packet.roomId?.value} currentRoomId=${currentState.room.roomId.value}")
            return
        }
        val updatedRoom = packet.roomInfo ?: currentState.room
        _state.value = currentState.copy(room = updatedRoom, members = packet.members)
        Log.i(
            TAG,
            "[handleMemberList] Список участников обновлен memberCount=${packet.members.size} directAudioReady=${updatedRoom.isDirectAudioReady}",
        )
    }

    /**
     * Добавляет нового участника на стороне client.
     */
    private fun handleMemberJoined(packet: WirePacket) {
        val currentState = _state.value as? RoomRuntimeState.Client ?: return
        val peer = packet.peer ?: return
        if (packet.roomId != currentState.room.roomId) {
            Log.w(TAG, "[handleMemberJoined] MEMBER_JOINED не для текущей комнаты packetRoomId=${packet.roomId?.value} currentRoomId=${currentState.room.roomId.value}")
            return
        }
        _state.value = currentState.copy(members = mergeMember(currentState.members, peer))
        Log.i(TAG, "[handleMemberJoined] Участник добавлен peerId=${peer.peerId.value}")
    }

    /**
     * Убирает участника у host или client и при необходимости рассылает новый MEMBER_LIST.
     */
    private fun handleMemberLeft(packet: WirePacket) {
        val peerId = packet.peer?.peerId ?: packet.sender?.peerId ?: return
        removeTalkingPeer(peerId)
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                if (packet.roomId != currentState.room.roomId) {
                    Log.w(TAG, "[handleMemberLeft] MEMBER_LEFT не для host-комнаты packetRoomId=${packet.roomId?.value} currentRoomId=${currentState.room.roomId.value}")
                    return
                }
                val members = currentState.members.filterNot { it.peerId == peerId }
                _state.value = currentState.copy(members = members)
                Log.i(TAG, "[handleMemberLeft] Host убрал участника peerId=${peerId.value} memberCount=${members.size}")
                broadcastMemberList(currentState.room, members)
            }

            is RoomRuntimeState.Client -> {
                if (packet.roomId != currentState.room.roomId) {
                    Log.w(TAG, "[handleMemberLeft] MEMBER_LEFT не для client-комнаты packetRoomId=${packet.roomId?.value} currentRoomId=${currentState.room.roomId.value}")
                    return
                }
                _state.value = currentState.copy(members = currentState.members.filterNot { it.peerId == peerId })
                Log.i(TAG, "[handleMemberLeft] Client убрал участника peerId=${peerId.value}")
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> Unit
        }
    }

    /**
     * Обрабатывает CHAT_MESSAGE: host добавляет и ретранслирует, client добавляет полученное.
     */
    private fun handleChatMessage(packet: WirePacket) {
        val message = packet.message ?: return
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                if (message.roomId != currentState.room.roomId) {
                    Log.w(TAG, "[handleChatMessage] CHAT_MESSAGE не для host-комнаты messageRoomId=${message.roomId.value} currentRoomId=${currentState.room.roomId.value}")
                    return
                }
                Log.i(TAG, "[handleChatMessage] Host принял CHAT_MESSAGE messageId=${message.messageId.value} authorPeerId=${message.author.peerId.value}")
                appendMessage(message)
                sendToMembers(
                    currentState.members,
                    packet,
                    excludedPeerIds = setOf(profileRepository.getOrCreatePeerId(), message.author.peerId),
                )
            }

            is RoomRuntimeState.Client -> {
                if (message.roomId != currentState.room.roomId || message.author.peerId == profileRepository.getOrCreatePeerId()) {
                    Log.i(TAG, "[handleChatMessage] Client проигнорировал CHAT_MESSAGE messageId=${message.messageId.value} messageRoomId=${message.roomId.value}")
                    return
                }
                Log.i(TAG, "[handleChatMessage] Client добавляет CHAT_MESSAGE messageId=${message.messageId.value} authorPeerId=${message.author.peerId.value}")
                appendMessage(message)
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> Unit
        }
    }

    /**
     * Закрывает текущую client-сессию после ROOM_CLOSED от host.
     */
    private fun handleRoomClosed() {
        if (_state.value is RoomRuntimeState.Client || _state.value is RoomRuntimeState.Joining) {
            Log.i(TAG, "[handleRoomClosed] Host закрыл комнату, сбрасываем сессию")
            resetSession()
        }
    }

    /**
     * Отвечает PONG на PING, если известен отправитель.
     */
    private fun handlePing(packet: WirePacket) {
        val sender = packet.sender ?: return
        Log.i(TAG, "[handlePing] Отвечаем PONG peerId=${sender.peerId.value} roomId=${packet.roomId?.value}")
        roomTransport.sendToPeer(
            sender.peerId,
            packet(
                type = WirePacketType.PONG,
                roomId = packet.roomId,
                sender = profileRepository.getSelfPeer(),
            ),
        )
    }

    /**
     * Передает служебную voice transport info текущему media-plane.
     */
    private fun handleVoiceTransportInfo(packet: WirePacket) {
        val info = packet.voiceTransportInfo
        val senderPeerId = packet.sender?.peerId ?: info?.peerId
        if (info == null || senderPeerId == null) {
            Log.w(TAG, "[handleVoiceTransportInfo] Некорректный VOICE_TRANSPORT_INFO senderPeerId=${senderPeerId?.value}")
            return
        }
        if (!isPacketForCurrentRoom(packet.roomId)) {
            Log.w(TAG, "[handleVoiceTransportInfo] VOICE_TRANSPORT_INFO не для текущей комнаты roomId=${packet.roomId?.value}")
            return
        }
        voiceTransport.handleControlInfo(senderPeerId, info)
        Log.i(TAG, "[handleVoiceTransportInfo] Voice transport info передан media-plane fromPeerId=${senderPeerId.value} mode=${info.mode}")
    }

    /**
     * Отправляет JOIN_REJECTED, если host может определить адресата.
     */
    private fun sendJoinRejected(peer: Peer?, roomId: RoomId?, reason: String) {
        if (peer == null) {
            Log.w(TAG, "[sendJoinRejected] Не можем отправить JOIN_REJECTED, peer неизвестен reason=$reason")
            return
        }
        Log.i(TAG, "[sendJoinRejected] Отправляем JOIN_REJECTED peerId=${peer.peerId.value} reason=$reason")
        roomTransport.sendToPeer(
            peer.peerId,
            packet(
                type = WirePacketType.JOIN_REJECTED,
                roomId = roomId,
                sender = profileRepository.getSelfPeer(),
                reason = reason,
            ),
        )
    }

    /**
     * Отправляет voice transport info одному участнику через room signaling.
     */
    private fun sendVoiceTransportInfoTo(peerId: PeerId, roomId: RoomId, info: VoiceTransportControlInfo) {
        Log.i(TAG, "[sendVoiceTransportInfoTo] Отправляем voice info peerId=${peerId.value} mode=${info.mode}")
        roomTransport.sendToPeer(
            peerId,
            packet(
                type = WirePacketType.VOICE_TRANSPORT_INFO,
                roomId = roomId,
                sender = profileRepository.getSelfPeer(),
                voiceTransportInfo = info,
            ),
        )
    }

    /**
     * Рассылает актуальную voice transport info всем гостям host-комнаты после изменения готовности direct-аудио.
     */
    private fun sendVoiceTransportInfoToMembers(room: RoomInfo, members: List<Peer>, info: VoiceTransportControlInfo) {
        val selfPeerId = profileRepository.getOrCreatePeerId()
        val targetMembers = members.filterNot { peer -> peer.peerId == selfPeerId }
        Log.i(
            TAG,
            "[sendVoiceTransportInfoToMembers] Рассылаем voice info участникам roomId=${room.roomId.value} targetCount=${targetMembers.size} mode=${info.mode}",
        )
        targetMembers.forEach { peer ->
            sendVoiceTransportInfoTo(peer.peerId, room.roomId, info)
        }
    }

    /**
     * Рассылает актуальный список участников всем client-участникам комнаты.
     */
    private fun broadcastMemberList(room: RoomInfo, members: List<Peer>) {
        Log.i(TAG, "[broadcastMemberList] Рассылаем MEMBER_LIST roomId=${room.roomId.value} memberCount=${members.size}")
        sendToMembers(
            members,
            packet(
                type = WirePacketType.MEMBER_LIST,
                roomId = room.roomId,
                sender = profileRepository.getSelfPeer(),
                roomInfo = room,
                members = members,
            ),
            excludedPeerIds = setOf(profileRepository.getOrCreatePeerId()),
        )
    }

    /**
     * Отправляет пакет выбранным участникам, исключая указанные PeerId.
     */
    private fun sendToMembers(members: List<Peer>, packet: WirePacket, excludedPeerIds: Set<PeerId>) {
        val targetCount = members.count { it.peerId !in excludedPeerIds }
        Log.i(TAG, "[sendToMembers] Отправляем packet type=${packet.type} targetCount=$targetCount excludedCount=${excludedPeerIds.size}")
        members
            .asSequence()
            .filterNot { it.peerId in excludedPeerIds }
            .forEach { peer ->
                roomTransport.sendToPeer(peer.peerId, packet)
            }
    }

    /**
     * Добавляет или заменяет участника по PeerId.
     */
    private fun mergeMember(members: List<Peer>, peer: Peer): List<Peer> {
        return (members.filterNot { it.peerId == peer.peerId } + peer)
    }

    /**
     * Создает новое сообщение чата от указанного автора.
     */
    private fun newChatMessage(roomId: RoomId, author: Peer, text: String): ChatMessage {
        return ChatMessage(
            messageId = idGenerator.newMessageId(),
            roomId = roomId,
            author = author,
            text = text,
            createdAtMillis = now(),
        )
    }

    /**
     * Добавляет сообщение в чат, если такого messageId еще нет.
     */
    private fun appendMessage(message: ChatMessage) {
        if (_messages.value.any { it.messageId == message.messageId }) {
            Log.i(TAG, "[appendMessage] Сообщение уже есть, пропускаем messageId=${message.messageId.value}")
            return
        }
        _messages.value = _messages.value + message
        Log.i(TAG, "[appendMessage] Сообщение добавлено messageId=${message.messageId.value} totalMessages=${_messages.value.size}")
    }

    /**
     * Публикует текстовое сообщение как MESHRA event и локально добавляет его без ожидания обратного события.
     */
    private fun sendMeshMessage(room: RoomInfo, self: Peer, text: String) {
        val message = newChatMessage(room.roomId, self, text)
        appendMessage(message)
        meshTransport.publishChatMessage(
            roomId = room.roomId,
            roomName = room.name,
            knownHost = room.host,
            message = message,
            roomTransportMode = room.roomTransportMode,
            voiceTransportMode = room.voiceTransportMode,
            isDirectAudioReady = room.isDirectAudioReady,
        )
        Log.i(TAG, "[sendMeshMessage] MESHRA-сообщение опубликовано roomId=${room.roomId.value} messageId=${message.messageId.value} textLength=${text.length}")
    }

    /**
     * Публикует выход участника из MESHRA-комнаты через общий mesh event-log.
     */
    private fun publishMeshMemberLeft(room: RoomInfo, peer: Peer) {
        meshTransport.publishMemberLeft(
            roomId = room.roomId,
            roomName = room.name,
            knownHost = room.host,
            member = peer,
            roomTransportMode = room.roomTransportMode,
            voiceTransportMode = room.voiceTransportMode,
            isDirectAudioReady = room.isDirectAudioReady,
        )
        Log.i(TAG, "[publishMeshMemberLeft] MESHRA MEMBER_LEFT опубликован roomId=${room.roomId.value} peerId=${peer.peerId.value}")
    }

    /**
     * Перезапускает mesh-рекламу текущего snapshot-а, чтобы новые гости видели этот peer как gateway.
     */
    private fun advertiseMeshSnapshot(roomId: RoomId, gateway: Peer) {
        val snapshot = meshTransport.rooms.value[roomId]
        if (snapshot == null) {
            Log.w(TAG, "[advertiseMeshSnapshot] Mesh snapshot пока неизвестен roomId=${roomId.value}")
            return
        }
        meshTransport.startAdvertising(snapshot, gateway)
    }

    /**
     * Проверяет, что snapshot отвечает той MESHRA-комнате, в которую runtime сейчас входит.
     */
    private fun isJoiningSameMeshRoom(joiningRoom: RoomInfo, snapshot: MeshRoomSnapshot): Boolean {
        return joiningRoom.roomTransportMode == RoomTransportMode.MESHRA &&
            (joiningRoom.roomId == snapshot.roomId || MeshRoomAdvertisement.matchesAdvertisedRoomId(joiningRoom.roomId, snapshot.roomId))
    }

    /**
     * Преобразует mesh snapshot в обычное RoomInfo, чтобы верхний слой не зависел от конкретного транспорта комнаты.
     */
    private fun roomInfoFromMeshSnapshot(snapshot: MeshRoomSnapshot): RoomInfo {
        return RoomInfo(
            roomId = snapshot.roomId,
            name = snapshot.roomName,
            host = snapshot.knownHost,
            createdAtMillis = snapshot.updatedAtMillis,
            roomTransportMode = snapshot.roomTransportMode,
            voiceTransportMode = snapshot.voiceTransportMode,
            isDirectAudioReady = snapshot.isDirectAudioReady,
        )
    }

    /**
     * Возвращает true, если текущее runtime-состояние относится к MESHRA-комнате.
     */
    private fun isCurrentStateMesh(): Boolean {
        return when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> currentState.room.roomTransportMode == RoomTransportMode.MESHRA
            is RoomRuntimeState.Joining -> currentState.room.roomTransportMode == RoomTransportMode.MESHRA
            is RoomRuntimeState.Client -> currentState.room.roomTransportMode == RoomTransportMode.MESHRA
            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Error,
            -> false
        }
    }

    /**
     * Добавляет участника в набор говорящих, если его voice frames активны.
     */
    private fun addTalkingPeer(peerId: PeerId) {
        if (peerId in _talkingPeerIds.value) {
            return
        }
        _talkingPeerIds.value = _talkingPeerIds.value + peerId
        Log.i(TAG, "[addTalkingPeer] Участник помечен говорящим peerId=${peerId.value} talkingCount=${_talkingPeerIds.value.size}")
    }

    /**
     * Убирает участника из набора говорящих после остановки передачи или завершения входящих frames.
     */
    private fun removeTalkingPeer(peerId: PeerId) {
        if (peerId !in _talkingPeerIds.value) {
            return
        }
        _talkingPeerIds.value = _talkingPeerIds.value - peerId
        Log.i(TAG, "[removeTalkingPeer] Участник больше не говорит peerId=${peerId.value} talkingCount=${_talkingPeerIds.value.size}")
    }

    /**
     * Перезапускает fallback-таймер говорящего участника на случай потери final voice frame в UDP media-plane.
     */
    private fun scheduleTalkingPeerTimeout(peerId: PeerId) {
        talkingPeerTimeoutJobs.remove(peerId)?.cancel()
        talkingPeerTimeoutJobs[peerId] = externalScope.launch {
            delay(TALKING_PEER_TIMEOUT_MILLIS)
            talkingPeerTimeoutJobs.remove(peerId)
            voiceRuntime.finishIncomingFrameSession(peerId)
            removeTalkingPeer(peerId)
            Log.i(TAG, "[scheduleTalkingPeerTimeout] Индикатор эфира погашен по таймауту peerId=${peerId.value}")
        }
    }

    /**
     * Отменяет fallback-таймер говорящего участника, если его поток завершился штатно или peer отключился.
     */
    private fun cancelTalkingPeerTimeout(peerId: PeerId) {
        talkingPeerTimeoutJobs.remove(peerId)?.cancel()
    }

    /**
     * Отменяет все fallback-таймеры говорящих участников при сбросе комнаты.
     */
    private fun clearTalkingPeerTimeouts() {
        talkingPeerTimeoutJobs.values.forEach { job -> job.cancel() }
        talkingPeerTimeoutJobs.clear()
    }

    /**
     * Добавляет или заменяет найденную комнату в списке availableRooms.
     */
    private fun upsertAvailableRoom(roomInfo: RoomInfo) {
        _availableRooms.value = (_availableRooms.value.filterNot { it.roomId == roomInfo.roomId } + roomInfo)
            .sortedBy { it.name.lowercase() }
        Log.i(TAG, "[upsertAvailableRoom] AvailableRooms обновлен roomId=${roomInfo.roomId.value} total=${_availableRooms.value.size}")
    }

    /**
     * Удаляет найденную комнату и связанные endpoint mapping по причине stale/recovery.
     */
    private fun removeAvailableRoom(roomId: RoomId, reason: String) {
        roomEndpoints.remove(roomId)?.let { endpointId -> endpointRooms.remove(endpointId) }
        _availableRooms.value = _availableRooms.value.filterNot { roomInfo -> roomInfo.roomId == roomId }
        Log.i(TAG, "[removeAvailableRoom] Комната удалена из availableRooms roomId=${roomId.value} reason=$reason availableCount=${_availableRooms.value.size}")
    }

    /**
     * Создает wire-пакет с packetId, ttl и временем отправки.
     */
    private fun packet(
        type: WirePacketType,
        roomId: RoomId? = null,
        sender: Peer? = null,
        roomInfo: RoomInfo? = null,
        peer: Peer? = null,
        members: List<Peer> = emptyList(),
        message: ChatMessage? = null,
        voiceTransportInfo: VoiceTransportControlInfo? = null,
        reason: String? = null,
    ): WirePacket {
        return WirePacket(
            type = type,
            packetId = idGenerator.newWirePacketId(),
            roomId = roomId,
            sender = sender,
            roomInfo = roomInfo,
            peer = peer,
            members = members,
            message = message,
            voiceTransportInfo = voiceTransportInfo,
            reason = reason,
            ttl = DEFAULT_PACKET_TTL,
            sentAtMillis = now(),
        )
    }

    /**
     * Проверяет, относится ли packet к текущей комнате, если в нем есть roomId.
     */
    private fun isPacketForCurrentRoom(roomId: RoomId?): Boolean {
        val currentRoomId = when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> currentState.room.roomId
            is RoomRuntimeState.Joining -> currentState.room.roomId
            is RoomRuntimeState.Client -> currentState.room.roomId
            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Error,
            -> null
        }
        return roomId == null || currentRoomId == null || roomId == currentRoomId
    }

    /**
     * Сначала переводит runtime в Idle, затем закрывает voice и полностью сбрасывает локальное transport-состояние.
     */
    private fun resetSession() {
        Log.i(TAG, "[resetSession] Сбрасываем текущую сессию currentState=${_state.value.javaClass.simpleName}")
        clearConnectionRecovery()
        _state.value = RoomRuntimeState.Idle
        voiceRuntime.stopAll()
        voiceTransport.stopSession()
        _messages.value = emptyList()
        _talkingPeerIds.value = emptySet()
        clearTalkingPeerTimeouts()
        roomTransport.stopAllEndpointsAndClearState(reason = "runtime_reset_session")
        meshTransport.stopAll(reason = "runtime_reset_session")
        realRoomToAdvertisedRoom.clear()
        Log.i(TAG, "[resetSession] Runtime переведен в Idle")
    }

    /**
     * Закрывает входящий stream, если runtime не может его использовать.
     */
    private fun closeInputStream(inputStream: InputStream) {
        runCatching { inputStream.close() }
    }

    /**
     * Переводит runtime в Error до отключения voice и room transport, чтобы поздние callbacks не оживили старую сессию.
     */
    private fun setError(message: String, action: RoomRuntimeErrorAction? = null) {
        Log.w(TAG, "[setError] Runtime перешел в Error message=$message action=$action")
        clearConnectionRecovery()
        _state.value = RoomRuntimeState.Error(
            message = message.ifBlank { "Неизвестная ошибка" },
            action = action,
        )
        voiceRuntime.stopAll()
        voiceTransport.stopSession()
        _talkingPeerIds.value = emptySet()
        clearTalkingPeerTimeouts()
        roomTransport.stopAllEndpointsAndClearState(reason = "runtime_error")
        meshTransport.stopAll(reason = "runtime_error")
        realRoomToAdvertisedRoom.clear()
    }

    /**
     * Отменяет отложенный recovery и сбрасывает счетчик автоматического connection retry.
     */
    private fun clearConnectionRecovery() {
        connectionRecoveryJob?.cancel()
        connectionRecoveryJob = null
        recoveringRoomId = null
        recoveryReconnectRequested = false
        connectionRecoveryAttemptCount = 0
    }

    /**
     * Подбирает UI-действие для известных ошибок системных требований Nearby.
     */
    private fun actionFor(cause: Throwable): RoomRuntimeErrorAction? {
        return when {
            cause is NearbyRequirementException.LocationDisabled -> RoomRuntimeErrorAction.OPEN_LOCATION_SETTINGS
            else -> null
        }
    }

    /**
     * Возвращает выбранный host-ом voice transport mode из prefs с Wi-Fi Direct как безопасным default.
     */
    private fun preferredVoiceTransportMode(): VoiceTransportMode {
        return voiceSettingsRepository.voiceTransportPreference.value.transportMode
            ?: VoiceTransportMode.WIFI_DIRECT_UDP
    }

    /**
     * Фиксирует выбранный room transport для текущей комнаты; реальное переключение delegate будет добавлено при включении MESHRA.
     */
    private fun activateRoomTransportMode(mode: RoomTransportMode, reason: String) {
        Log.i(TAG, "[activateRoomTransportMode] Выбран room transport mode=$mode reason=$reason")
    }

    /**
     * Формирует пользовательское сообщение для ошибок Nearby API и отдельно помечает ApiException как недоступность Nearby.
     */
    private fun nearbyFailureMessage(operation: String, cause: Throwable): String {
        return if (cause is ApiException) {
            "Nearby Connections не поддерживается или недоступен на этом устройстве"
        } else {
            "Не удалось $operation: ${cause.message.orEmpty()}"
        }
    }

    /**
     * Отправляет одноразовое уведомление в UI snackbar без изменения состояния комнаты.
     */
    private fun emitNotice(message: String) {
        noticeId += 1L
        val notice = RoomRuntimeNotice(id = noticeId, message = message)
        if (!_notices.tryEmit(notice)) {
            Log.w(TAG, "[emitNotice] Не удалось отправить snackbar-уведомление message=$message")
        }
    }

    /**
     * Возвращает текущее системное время в миллисекундах.
     */
    private fun now(): Long {
        return System.currentTimeMillis()
    }

    /**
     * Короткое описание активной MESHRA-комнаты для расчета healing-связности.
     */
    private data class ActiveMeshStateInfo(
        val room: RoomInfo,
        val memberCount: Int,
    )

    private companion object {
        private const val TAG = "RoomRuntime"
        private const val DEFAULT_PACKET_TTL = 1
        private const val MAX_CONNECTION_RECOVERY_ATTEMPTS = 3
        private const val CONNECTION_RECOVERY_COOLDOWN_MILLIS = 750L
        private const val CONNECTION_RECOVERY_DISCOVERY_TIMEOUT_MILLIS = 15_000L
        private const val CLIENT_RECONNECT_DISCOVERY_TIMEOUT_MILLIS = 20_000L
        private const val NOTICE_BUFFER_CAPACITY = 8
        private const val TALKING_PEER_TIMEOUT_MILLIS = 1_500L
        private const val MESH_MIN_LINKS = 3
        private const val MESH_MAX_LINKS = 64
        private const val DIRECT_AUDIO_UNAVAILABLE_MESSAGE = "Прямой аудиоканал не установлен"
        private const val MESH_VOICE_UNAVAILABLE_MESSAGE = "Голос в MESHRA-комнатах пока не поддерживается"
    }
}
