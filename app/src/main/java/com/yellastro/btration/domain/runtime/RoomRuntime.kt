package com.yellastro.btration.domain.runtime

import android.util.Log
import com.yellastro.btration.data.nearby.NearbyEvent
import com.yellastro.btration.data.nearby.NearbyRequirementException
import com.yellastro.btration.data.nearby.NearbyTransport
import com.yellastro.btration.domain.model.ChatMessage
import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.model.WirePacket
import com.yellastro.btration.domain.model.WirePacketId
import com.yellastro.btration.domain.model.WirePacketType
import com.yellastro.btration.domain.util.IdGenerator
import com.yellastro.btration.repository.ProfileRepository
import com.yellastro.btration.voice.VoiceRuntime
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Рабочая машина комнаты: слушает NearbyTransport, ведет состояние комнаты, участников, чат и PTT-голос.
 */
class RoomRuntime(
    private val profileRepository: ProfileRepository,
    private val nearbyTransport: NearbyTransport,
    private val voiceRuntime: VoiceRuntime,
    private val idGenerator: IdGenerator,
    externalScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RoomRuntimeState>(RoomRuntimeState.Idle)
    private val _availableRooms = MutableStateFlow<List<RoomInfo>>(emptyList())
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    private val roomEndpoints = mutableMapOf<RoomId, String>()
    private val endpointRooms = mutableMapOf<String, RoomId>()
    private val seenPacketIds = mutableSetOf<WirePacketId>()

    /**
     * Текущее состояние runtime: idle/searching/hosting/joining/client/error.
     */
    val state: StateFlow<RoomRuntimeState> = _state.asStateFlow()

    /**
     * Список комнат, найденных через Nearby discovery.
     */
    val availableRooms: StateFlow<List<RoomInfo>> = _availableRooms.asStateFlow()

    /**
     * Сообщения текущей комнаты.
     */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /**
     * Возвращает PeerId локального пользователя для верхних слоев без доступа к ProfileRepository.
     */
    fun getSelfPeerId(): PeerId {
        return profileRepository.getOrCreatePeerId()
    }

    init {
        externalScope.launch {
            Log.i(TAG, "[init] Подписываем RoomRuntime на события NearbyTransport")
            nearbyTransport.events.collect(::handleNearbyEvent)
        }
    }

    /**
     * Запускает поиск Nearby-комнат, если пользователь сейчас не состоит в комнате.
     */
    suspend fun startSearch() {
        Log.i(TAG, "[startSearch] Команда поиска комнат currentState=${_state.value.javaClass.simpleName}")
        when (_state.value) {
            is RoomRuntimeState.Hosting,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Client,
            -> {
                Log.w(TAG, "[startSearch] Поиск отклонен, уже есть активная комната currentState=${_state.value.javaClass.simpleName}")
                setError("Нельзя искать комнаты, пока активна текущая комната")
            }

            RoomRuntimeState.Idle,
            is RoomRuntimeState.Error,
            RoomRuntimeState.Searching,
            -> {
                nearbyTransport.startDiscovery()
                _state.value = RoomRuntimeState.Searching
                Log.i(TAG, "[startSearch] Runtime переведен в Searching")
            }
        }
    }

    /**
     * Останавливает поиск Nearby-комнат и возвращает runtime в Idle, если он только искал комнаты.
     */
    suspend fun stopSearch() {
        Log.i(TAG, "[stopSearch] Останавливаем поиск currentState=${_state.value.javaClass.simpleName}")
        nearbyTransport.stopDiscovery()
        if (_state.value is RoomRuntimeState.Searching) {
            _state.value = RoomRuntimeState.Idle
            Log.i(TAG, "[stopSearch] Runtime переведен в Idle")
        }
    }

    /**
     * Создает локальную комнату и запускает Nearby advertising.
     */
    suspend fun createRoom(name: String) {
        Log.i(TAG, "[createRoom] Команда создать комнату name=$name currentState=${_state.value.javaClass.simpleName}")
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

        nearbyTransport.stopDiscovery()
        val self = profileRepository.getSelfPeer()
        val room = RoomInfo(
            roomId = idGenerator.newRoomId(),
            name = name.trim(),
            host = self,
            createdAtMillis = now(),
        )
        _messages.value = emptyList()
        _state.value = RoomRuntimeState.Hosting(room = room, members = listOf(self))
        Log.i(TAG, "[createRoom] Runtime переведен в Hosting roomId=${room.roomId.value} roomName=${room.name} hostPeerId=${self.peerId.value}")
        nearbyTransport.startAdvertising(room)
    }

    /**
     * Начинает подключение к найденной комнате по ее RoomId.
     */
    suspend fun joinRoom(roomId: RoomId) {
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

        _messages.value = emptyList()
        _state.value = RoomRuntimeState.Joining(room)
        Log.i(TAG, "[joinRoom] Runtime переведен в Joining roomId=${room.roomId.value} endpointId=$endpointId")
        nearbyTransport.connectToEndpoint(endpointId)
    }

    /**
     * Покидает текущую комнату или останавливает поиск.
     */
    suspend fun leaveRoom() {
        Log.i(TAG, "[leaveRoom] Команда выйти currentState=${_state.value.javaClass.simpleName}")
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> closeRoom()
            is RoomRuntimeState.Client -> {
                val self = profileRepository.getSelfPeer()
                nearbyTransport.sendToPeer(
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
     * Закрывает хостимую комнату и уведомляет всех участников.
     */
    suspend fun closeRoom() {
        Log.i(TAG, "[closeRoom] Команда закрыть комнату currentState=${_state.value.javaClass.simpleName}")
        val currentState = _state.value as? RoomRuntimeState.Hosting
        if (currentState == null) {
            Log.i(TAG, "[closeRoom] Активной host-комнаты нет, сбрасываем сессию")
            resetSession()
            return
        }

        val roomClosed = packet(
            type = WirePacketType.ROOM_CLOSED,
            roomId = currentState.room.roomId,
            sender = profileRepository.getSelfPeer(),
        )
        sendToMembers(currentState.members, roomClosed, excludedPeerIds = setOf(profileRepository.getOrCreatePeerId()))
        Log.i(TAG, "[closeRoom] ROOM_CLOSED разослан roomId=${currentState.room.roomId.value} memberCount=${currentState.members.size}")
        nearbyTransport.stopAdvertising()
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
                Log.i(TAG, "[sendMessage] Client отправляет сообщение hostPeerId=${currentState.room.host.peerId.value} roomId=${currentState.room.roomId.value} textLength=${cleanText.length}")
                val message = newChatMessage(currentState.room.roomId, self, cleanText)
                appendMessage(message)
                nearbyTransport.sendToPeer(
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
     * Начинает передачу микрофона в текущую комнату через voice runtime.
     */
    suspend fun startTalking() {
        Log.i(TAG, "[startTalking] Команда начать передачу голоса currentState=${_state.value.javaClass.simpleName}")
        val selfPeerId = profileRepository.getOrCreatePeerId()
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                val targetPeerIds = currentState.members
                    .map { peer -> peer.peerId }
                    .filterNot { peerId -> peerId == selfPeerId }
                    .toSet()
                voiceRuntime.startTalking(targetPeerIds)
            }

            is RoomRuntimeState.Client -> {
                voiceRuntime.startTalking(setOf(currentState.room.host.peerId))
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> {
                Log.w(TAG, "[startTalking] Передача голоса отклонена, нет активной комнаты")
                setError("Нельзя передавать голос вне активной комнаты")
            }
        }
    }

    /**
     * Останавливает передачу микрофона.
     */
    suspend fun stopTalking() {
        Log.i(TAG, "[stopTalking] Команда остановить передачу голоса")
        voiceRuntime.stopTalking()
    }

    /**
     * Обрабатывает события NearbyTransport и маршрутизирует их в доменную логику.
     */
    private fun handleNearbyEvent(event: NearbyEvent) {
        Log.i(TAG, "[handleNearbyEvent] Получено событие NearbyEvent type=${event.javaClass.simpleName} currentState=${_state.value.javaClass.simpleName}")
        when (event) {
            is NearbyEvent.EndpointFound -> handleEndpointFound(event)
            is NearbyEvent.EndpointLost -> handleEndpointLost(event)
            is NearbyEvent.ConnectionResult -> handleConnectionResult(event)
            is NearbyEvent.Disconnected -> handleDisconnected(event)
            is NearbyEvent.PacketReceived -> handlePacketReceived(event)
            is NearbyEvent.StreamReceived -> handleStreamReceived(event)
            is NearbyEvent.AdvertisingFailed -> setError(
                message = "Не удалось запустить комнату: ${event.cause.message.orEmpty()}",
                action = actionFor(event.cause),
            )
            is NearbyEvent.DiscoveryFailed -> setError(
                message = "Не удалось начать поиск комнат: ${event.cause.message.orEmpty()}",
                action = actionFor(event.cause),
            )
            is NearbyEvent.ConnectionRequestFailed -> setError("Не удалось подключиться к комнате: ${event.cause.message.orEmpty()}")
            is NearbyEvent.ConnectionAcceptFailed -> setError("Не удалось принять подключение: ${event.cause.message.orEmpty()}")
            is NearbyEvent.PayloadDecodeFailed -> setError("Не удалось прочитать пакет: ${event.cause.message.orEmpty()}")
            is NearbyEvent.SendFailed -> setError("Не удалось отправить пакет: ${event.cause.message.orEmpty()}")
            is NearbyEvent.StreamSendFailed -> setError("Не удалось отправить голос: ${event.cause.message.orEmpty()}")
            is NearbyEvent.ConnectionInitiated,
            is NearbyEvent.PayloadTransferUpdated,
            is NearbyEvent.UnsupportedPayloadReceived,
            -> Unit
        }
    }

    /**
     * Принимает входящий voice stream, если отправитель относится к текущей комнате.
     */
    private fun handleStreamReceived(event: NearbyEvent.StreamReceived) {
        val peerId = event.peerId
        if (peerId == null) {
            Log.w(TAG, "[handleStreamReceived] Голосовой stream без известного peerId endpointId=${event.endpointId}")
            closeInputStream(event.inputStream)
            return
        }

        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                if (currentState.members.none { peer -> peer.peerId == peerId }) {
                    Log.w(TAG, "[handleStreamReceived] Host игнорирует голосовой stream от неизвестного peerId=${peerId.value}")
                    closeInputStream(event.inputStream)
                    return
                }
                voiceRuntime.playIncoming(peerId, event.inputStream)
            }

            is RoomRuntimeState.Client -> {
                if (currentState.members.none { peer -> peer.peerId == peerId }) {
                    Log.w(TAG, "[handleStreamReceived] Client игнорирует голосовой stream от неизвестного peerId=${peerId.value}")
                    closeInputStream(event.inputStream)
                    return
                }
                voiceRuntime.playIncoming(peerId, event.inputStream)
            }

            RoomRuntimeState.Idle,
            RoomRuntimeState.Searching,
            is RoomRuntimeState.Joining,
            is RoomRuntimeState.Error,
            -> {
                Log.w(TAG, "[handleStreamReceived] Голосовой stream получен вне активной комнаты currentState=${_state.value.javaClass.simpleName}")
                closeInputStream(event.inputStream)
            }
        }
    }

    /**
     * Добавляет найденную комнату и связывает ее RoomId с Nearby endpointId.
     */
    private fun handleEndpointFound(event: NearbyEvent.EndpointFound) {
        val roomInfo = event.roomInfo
        if (roomInfo == null) {
            Log.w(TAG, "[handleEndpointFound] Endpoint найден, но визитка комнаты отсутствует endpointId=${event.endpointId}")
            return
        }
        roomEndpoints[roomInfo.roomId] = event.endpointId
        endpointRooms[event.endpointId] = roomInfo.roomId
        upsertAvailableRoom(roomInfo)
        Log.i(TAG, "[handleEndpointFound] Комната добавлена roomId=${roomInfo.roomId.value} roomName=${roomInfo.name} endpointId=${event.endpointId} availableCount=${_availableRooms.value.size}")
    }

    /**
     * Удаляет комнату из списка доступных при потере Nearby endpoint.
     */
    private fun handleEndpointLost(event: NearbyEvent.EndpointLost) {
        val roomId = event.roomId ?: endpointRooms[event.endpointId] ?: return
        roomEndpoints.remove(roomId)
        endpointRooms.remove(event.endpointId)
        _availableRooms.value = _availableRooms.value.filterNot { it.roomId == roomId }
        Log.i(TAG, "[handleEndpointLost] Комната удалена roomId=${roomId.value} endpointId=${event.endpointId} availableCount=${_availableRooms.value.size}")
    }

    /**
     * После успешного Nearby connection отправляет JOIN_REQUEST в найденную комнату.
     */
    private fun handleConnectionResult(event: NearbyEvent.ConnectionResult) {
        if (!event.resolution.status.isSuccess) {
            Log.w(TAG, "[handleConnectionResult] Соединение не установлено endpointId=${event.endpointId} statusCode=${event.resolution.status.statusCode}")
            setError("Подключение не установлено: ${event.resolution.status.statusCode}")
            return
        }

        val currentState = _state.value as? RoomRuntimeState.Joining ?: return
        val endpointId = roomEndpoints[currentState.room.roomId]
        if (endpointId != event.endpointId) {
            Log.w(TAG, "[handleConnectionResult] Успешное соединение не совпало с joining endpointId=${event.endpointId} expectedEndpointId=$endpointId")
            return
        }

        val self = profileRepository.getSelfPeer()
        Log.i(TAG, "[handleConnectionResult] Соединение установлено, отправляем JOIN_REQUEST roomId=${currentState.room.roomId.value} endpointId=${event.endpointId}")
        nearbyTransport.sendToPeer(
            currentState.room.host.peerId,
            packet(
                type = WirePacketType.JOIN_REQUEST,
                roomId = currentState.room.roomId,
                sender = self,
                peer = self,
            ),
        )
    }

    /**
     * Обновляет состояние при разрыве Nearby-соединения.
     */
    private fun handleDisconnected(event: NearbyEvent.Disconnected) {
        val peerId = event.peerId
        if (peerId == null) {
            Log.w(TAG, "[handleDisconnected] Endpoint отключился без известного peerId endpointId=${event.endpointId}")
            return
        }
        Log.i(TAG, "[handleDisconnected] Peer отключился endpointId=${event.endpointId} peerId=${peerId.value} currentState=${_state.value.javaClass.simpleName}")
        when (val currentState = _state.value) {
            is RoomRuntimeState.Hosting -> {
                val members = currentState.members.filterNot { it.peerId == peerId }
                _state.value = currentState.copy(members = members)
                Log.i(TAG, "[handleDisconnected] Host обновил список участников memberCount=${members.size}")
                broadcastMemberList(currentState.room, members)
            }

            is RoomRuntimeState.Client -> {
                if (peerId == currentState.room.host.peerId) {
                    Log.i(TAG, "[handleDisconnected] Отключился host, сбрасываем client-сессию")
                    resetSession()
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
     * Отбрасывает дубликаты packetId и отправляет пакет в обработчик по типу.
     */
    private fun handlePacketReceived(event: NearbyEvent.PacketReceived) {
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
            WirePacketType.PONG,
            WirePacketType.ROOM_INFO,
            -> Unit
        }
    }

    /**
     * На стороне host принимает JOIN_REQUEST и рассылает обновление участников.
     */
    private fun handleJoinRequest(event: NearbyEvent.PacketReceived) {
        val currentState = _state.value as? RoomRuntimeState.Hosting ?: return
        val joiningPeer = event.packet.peer ?: event.packet.sender
        if (joiningPeer == null || event.packet.roomId != currentState.room.roomId) {
            Log.w(TAG, "[handleJoinRequest] Некорректный JOIN_REQUEST roomId=${event.packet.roomId?.value} expectedRoomId=${currentState.room.roomId.value}")
            sendJoinRejected(event.packet.sender, currentState.room.roomId, "Некорректный запрос входа")
            return
        }

        val members = mergeMember(currentState.members, joiningPeer)
        _state.value = currentState.copy(members = members)
        Log.i(TAG, "[handleJoinRequest] Участник принят peerId=${joiningPeer.peerId.value} roomId=${currentState.room.roomId.value} memberCount=${members.size}")

        nearbyTransport.sendToPeer(
            joiningPeer.peerId,
            packet(
                type = WirePacketType.JOIN_ACCEPTED,
                roomId = currentState.room.roomId,
                sender = profileRepository.getSelfPeer(),
                roomInfo = currentState.room,
            ),
        )
        nearbyTransport.sendToPeer(
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
    }

    /**
     * Переводит client из Joining в Client после JOIN_ACCEPTED.
     */
    private fun handleJoinAccepted(packet: WirePacket) {
        val currentState = _state.value as? RoomRuntimeState.Joining ?: return
        val room = packet.roomInfo ?: currentState.room
        if (room.roomId != currentState.room.roomId) {
            Log.w(TAG, "[handleJoinAccepted] JOIN_ACCEPTED не для текущей комнаты packetRoomId=${room.roomId.value} currentRoomId=${currentState.room.roomId.value}")
            return
        }
        _state.value = RoomRuntimeState.Client(room = room, members = listOf(room.host, profileRepository.getSelfPeer()))
        Log.i(TAG, "[handleJoinAccepted] Runtime переведен в Client roomId=${room.roomId.value}")
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
        _state.value = currentState.copy(members = packet.members)
        Log.i(TAG, "[handleMemberList] Список участников обновлен memberCount=${packet.members.size}")
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
        nearbyTransport.sendToPeer(
            sender.peerId,
            packet(
                type = WirePacketType.PONG,
                roomId = packet.roomId,
                sender = profileRepository.getSelfPeer(),
            ),
        )
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
        nearbyTransport.sendToPeer(
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
                nearbyTransport.sendToPeer(peer.peerId, packet)
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
     * Добавляет или заменяет найденную комнату в списке availableRooms.
     */
    private fun upsertAvailableRoom(roomInfo: RoomInfo) {
        _availableRooms.value = (_availableRooms.value.filterNot { it.roomId == roomInfo.roomId } + roomInfo)
            .sortedBy { it.name.lowercase() }
        Log.i(TAG, "[upsertAvailableRoom] AvailableRooms обновлен roomId=${roomInfo.roomId.value} total=${_availableRooms.value.size}")
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
            reason = reason,
            ttl = DEFAULT_PACKET_TTL,
            sentAtMillis = now(),
        )
    }

    /**
     * Сбрасывает текущую комнатную сессию без очистки списка найденных комнат.
     */
    private fun resetSession() {
        Log.i(TAG, "[resetSession] Сбрасываем текущую сессию currentState=${_state.value.javaClass.simpleName}")
        voiceRuntime.stopAll()
        nearbyTransport.stopAdvertising()
        _messages.value = emptyList()
        _state.value = RoomRuntimeState.Idle
        Log.i(TAG, "[resetSession] Runtime переведен в Idle")
    }

    /**
     * Закрывает входящий stream, если runtime не может его использовать.
     */
    private fun closeInputStream(inputStream: InputStream) {
        runCatching { inputStream.close() }
    }

    /**
     * Переводит runtime в Error с человекочитаемым сообщением и optional UI-действием.
     */
    private fun setError(message: String, action: RoomRuntimeErrorAction? = null) {
        Log.w(TAG, "[setError] Runtime перешел в Error message=$message action=$action")
        voiceRuntime.stopAll()
        _state.value = RoomRuntimeState.Error(
            message = message.ifBlank { "Неизвестная ошибка" },
            action = action,
        )
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
     * Возвращает текущее системное время в миллисекундах.
     */
    private fun now(): Long {
        return System.currentTimeMillis()
    }

    private companion object {
        private const val TAG = "RoomRuntime"
        private const val DEFAULT_PACKET_TTL = 1
    }
}
