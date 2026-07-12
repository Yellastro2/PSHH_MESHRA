package com.yellastro.btration.data.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.yellastro.btration.data.wire.WireCodec
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomInfo
import com.yellastro.btration.domain.model.WirePacket
import com.yellastro.btration.domain.model.WirePacketType
import com.yellastro.btration.voice.VoiceFrame
import com.yellastro.btration.voice.VoiceFrameCodec
import com.yellastro.btration.voice.VoiceStreamCodec
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Тонкая обертка над Nearby Connections с короткой рекламой комнат, прямыми endpoint-ами, cleanup, payload, Opus voice frames и legacy voice stream.
 */
class NearbyTransport(
    private val context: Context,
    private val connectionsClient: ConnectionsClient,
    private val wireCodec: WireCodec,
    private val strategy: Strategy = Strategy.P2P_STAR,
    private val serviceId: String = DEFAULT_SERVICE_ID,
) {
    private val endpointRegistry = NearbyEndpointRegistry()
    private val connectedEndpointIds = ConcurrentHashMap.newKeySet<String>()
    private val _events = MutableSharedFlow<NearbyEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /**
     * Поток событий Nearby-слоя для RoomRuntime.
     */
    val events: SharedFlow<NearbyEvent> = _events.asSharedFlow()

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.i(TAG, "[onConnectionInitiated] Получен запрос соединения endpointId=$endpointId endpointName=${connectionInfo.endpointName}")
            emitEvent(NearbyEvent.ConnectionInitiated(endpointId, connectionInfo))
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { cause ->
                    Log.w(TAG, "[onConnectionInitiated] Не удалось принять соединение endpointId=$endpointId: ${cause.message}", cause)
                    emitEvent(NearbyEvent.ConnectionAcceptFailed(endpointId, cause))
                }
        }

        /**
         * Запоминает успешный handshake либо запускает recovery для полуподключенного endpoint.
         */
        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            Log.i(
                TAG,
                "[onConnectionResult] Результат соединения endpointId=$endpointId statusCode=${resolution.status.statusCode} success=${resolution.status.isSuccess}",
            )
            if (!resolution.status.isSuccess && resolution.status.statusCode in RECOVERABLE_CONNECTION_STATUS_CODES) {
                val cause = ApiException(resolution.status)
                Log.w(TAG, "[onConnectionResult] Сбрасываем полуподключенный endpoint после statusCode=${resolution.status.statusCode} endpointId=$endpointId", cause)
                disconnectEndpoint(endpointId, forgetRegistryEntry = true)
                emitEvent(NearbyEvent.ConnectionRecoveryRequired(endpointId, cause))
                return
            }
            if (resolution.status.isSuccess) {
                connectedEndpointIds.add(endpointId)
            }
            emitEvent(NearbyEvent.ConnectionResult(endpointId, resolution))
        }

        /**
         * Снимает только состояние active connection, сохраняя discovery-связки для повторного входа.
         */
        override fun onDisconnected(endpointId: String) {
            val peerId = endpointRegistry.getPeerId(endpointId)
            Log.i(TAG, "[onDisconnected] Nearby endpoint отключился endpointId=$endpointId peerId=${peerId?.value}")
            connectedEndpointIds.remove(endpointId)
            emitEvent(NearbyEvent.Disconnected(endpointId, peerId))
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, endpointInfo: DiscoveredEndpointInfo) {
            Log.i(
                TAG,
                "[onEndpointFound] Найден endpoint endpointId=$endpointId serviceId=${endpointInfo.serviceId} endpointNameLength=${endpointInfo.endpointName.length}",
            )
            val roomInfo = decodeRoomInfo(endpointInfo)
            if (roomInfo != null) {
                Log.i(
                    TAG,
                    "[onEndpointFound] Endpoint распознан как комната roomId=${roomInfo.roomId.value} roomName=${roomInfo.name} hostPeerId=${roomInfo.host.peerId.value}",
                )
                endpointRegistry.bindRoom(endpointId, roomInfo.roomId)
                endpointRegistry.bindPeer(endpointId, roomInfo.host.peerId)
            } else {
                Log.w(TAG, "[onEndpointFound] Endpoint не содержит корректную визитку комнаты endpointId=$endpointId")
            }
            emitEvent(NearbyEvent.EndpointFound(endpointId, endpointInfo, roomInfo))
        }

        override fun onEndpointLost(endpointId: String) {
            val roomId = endpointRegistry.getRoomId(endpointId)
            Log.i(TAG, "[onEndpointLost] Потерян endpoint endpointId=$endpointId roomId=${roomId?.value}")
            endpointRegistry.removeEndpoint(endpointId)
            emitEvent(NearbyEvent.EndpointLost(endpointId, roomId))
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> handleBytesPayload(endpointId, payload)
                Payload.Type.STREAM -> handleStreamPayload(endpointId, payload)
                else -> {
                    Log.w(TAG, "[onPayloadReceived] Получен неподдерживаемый payload endpointId=$endpointId type=${payload.type}")
                    emitEvent(NearbyEvent.UnsupportedPayloadReceived(endpointId, payload.type))
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            emitEvent(NearbyEvent.PayloadTransferUpdated(endpointId, update))
        }
    }

    /**
     * Обрабатывает bytes payload как wire-пакет протокола комнаты.
     */
    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        val bytes = payload.asBytes()
        if (bytes == null) {
            Log.w(TAG, "[handleBytesPayload] Payload bytes пустые endpointId=$endpointId")
            emitEvent(
                NearbyEvent.PayloadDecodeFailed(
                    endpointId,
                    IllegalArgumentException("Payload bytes are null"),
                ),
            )
            return
        }

        if (VoiceFrameCodec.isVoiceFrame(bytes)) {
            handleVoiceFramePayload(endpointId, bytes)
            return
        }

        val packet = runCatching { wireCodec.decode(bytes) }
            .onFailure { cause ->
                Log.w(TAG, "[handleBytesPayload] Не удалось декодировать payload endpointId=$endpointId bytes=${bytes.size}: ${cause.message}", cause)
                emitEvent(NearbyEvent.PayloadDecodeFailed(endpointId, cause))
            }
            .getOrNull()
            ?: return

        bindPacketMetadata(endpointId, packet)
        Log.i(
            TAG,
            "[handleBytesPayload] Получен packet endpointId=$endpointId peerId=${endpointRegistry.getPeerId(endpointId)?.value} type=${packet.type} roomId=${packet.roomId?.value}",
        )
        emitEvent(NearbyEvent.PacketReceived(endpointId, endpointRegistry.getPeerId(endpointId), packet))
    }

    /**
     * Обрабатывает bytes payload как короткий голосовой frame.
     */
    private fun handleVoiceFramePayload(endpointId: String, bytes: ByteArray) {
        val frame = runCatching { VoiceFrameCodec.decode(bytes) }
            .onFailure { cause ->
                Log.w(TAG, "[handleVoiceFramePayload] Не удалось декодировать voice frame endpointId=$endpointId bytes=${bytes.size}: ${cause.message}", cause)
                emitEvent(NearbyEvent.PayloadDecodeFailed(endpointId, cause))
            }
            .getOrNull()
            ?: return
        val peerId = endpointRegistry.getPeerId(endpointId)
        if (frame.sequence == FIRST_VOICE_FRAME_SEQUENCE || frame.isFinal) {
            Log.i(
                TAG,
                "[handleVoiceFramePayload] Получен voice frame endpointId=$endpointId peerId=${peerId?.value} originPeerId=${frame.originPeerId.value} sequence=${frame.sequence} encodedBytes=${frame.encodedBytes.size} final=${frame.isFinal}",
            )
        }
        emitEvent(NearbyEvent.VoiceFrameReceived(endpointId, peerId, frame))
    }

    /**
     * Обрабатывает stream payload как входящий голосовой поток.
     */
    private fun handleStreamPayload(endpointId: String, payload: Payload) {
        val receivedAtMillis = System.currentTimeMillis()
        val inputStream = payload.asStream()?.asInputStream()
        if (inputStream == null) {
            Log.w(TAG, "[handleStreamPayload] Stream payload без InputStream endpointId=$endpointId")
            emitEvent(
                NearbyEvent.PayloadDecodeFailed(
                    endpointId,
                    IllegalArgumentException("Payload stream input is null"),
                ),
            )
            return
        }
        val peerId = endpointRegistry.getPeerId(endpointId)
        Log.i(TAG, "[handleStreamPayload] Получен голосовой stream endpointId=$endpointId peerId=${peerId?.value} receivedAtMs=$receivedAtMillis")
        emitEvent(NearbyEvent.StreamReceived(endpointId, peerId, inputStream))
    }

    /**
     * Запускает поиск Nearby endpoint-ов с текущим serviceId.
     */
    fun startDiscovery() {
        Log.i(TAG, "[startDiscovery] Запускаем discovery serviceId=$serviceId strategy=$strategy")
        val missingPermissions = missingPermissionsForDiscovery()
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "[startDiscovery] Discovery не стартует, нет permissions: ${missingPermissions.joinToString()}")
            emitEvent(
                NearbyEvent.DiscoveryFailed(
                    SecurityException("Нет Nearby permissions для discovery: ${missingPermissions.joinToString()}"),
                ),
            )
            return
        }

        val locationRequirementError = locationRequirementErrorOrNull()
        if (locationRequirementError != null) {
            Log.w(TAG, "[startDiscovery] Discovery не стартует, не выполнено системное требование: ${locationRequirementError.message}")
            emitEvent(NearbyEvent.DiscoveryFailed(locationRequirementError))
            return
        }

        val options = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                Log.i(TAG, "[startDiscovery] Nearby discovery запущен")
            }
            .addOnFailureListener { cause ->
                Log.w(TAG, "[startDiscovery] Nearby не запустил discovery: ${cause.message}", cause)
                emitEvent(NearbyEvent.DiscoveryFailed(cause))
            }
    }

    /**
     * Останавливает поиск Nearby endpoint-ов.
     */
    fun stopDiscovery() {
        Log.i(TAG, "[stopDiscovery] Останавливаем discovery")
        connectionsClient.stopDiscovery()
    }

    /**
     * Запускает advertising комнаты через короткую endpointName-визитку после сброса старой рекламы и endpoint-ов.
     */
    fun startAdvertising(room: RoomInfo) {
        Log.i(
            TAG,
            "[startAdvertising] Запускаем advertising roomId=${room.roomId.value} roomName=${room.name} hostPeerId=${room.host.peerId.value} serviceId=$serviceId strategy=$strategy",
        )
        stopAllEndpointsAndClearState(reason = "prepare_start_advertising")
        val missingPermissions = missingPermissionsForAdvertising()
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "[startAdvertising] Advertising не стартует, нет permissions: ${missingPermissions.joinToString()}")
            emitEvent(
                NearbyEvent.AdvertisingFailed(
                    SecurityException("Нет Nearby permissions для advertising: ${missingPermissions.joinToString()}"),
                ),
            )
            return
        }

        val locationRequirementError = locationRequirementErrorOrNull()
        if (locationRequirementError != null) {
            Log.w(TAG, "[startAdvertising] Advertising не стартует, не выполнено системное требование: ${locationRequirementError.message}")
            emitEvent(NearbyEvent.AdvertisingFailed(locationRequirementError))
            return
        }

        val endpointName = NearbyRoomAdvertisement.fromRoom(room).encode()
        Log.i(
            TAG,
            "[startAdvertising] Визитка комнаты закодирована в endpointName chars=${endpointName.length} bytes=${endpointName.encodeToByteArray().size}",
        )
        val options = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        connectionsClient.startAdvertising(endpointName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                Log.i(TAG, "[startAdvertising] Nearby advertising запущен roomId=${room.roomId.value}")
            }
            .addOnFailureListener { cause ->
                Log.w(TAG, "[startAdvertising] Nearby не запустил advertising roomId=${room.roomId.value}: ${cause.message}", cause)
                emitEvent(NearbyEvent.AdvertisingFailed(cause))
            }
    }

    /**
     * Останавливает advertising текущего устройства.
     */
    fun stopAdvertising() {
        Log.i(TAG, "[stopAdvertising] Останавливаем advertising")
        connectionsClient.stopAdvertising()
    }

    /**
     * Полностью останавливает локальное Nearby-состояние: discovery, advertising, все endpoint-ы и registry.
     */
    fun stopAllEndpointsAndClearState(reason: String) {
        Log.i(TAG, "[stopAllEndpointsAndClearState] Полностью сбрасываем Nearby reason=$reason")
        connectionsClient.stopDiscovery()
        connectionsClient.stopAdvertising()
        connectionsClient.stopAllEndpoints()
        connectedEndpointIds.clear()
        endpointRegistry.clear()
    }

    /**
     * Запрашивает Nearby-соединение либо сообщает о пригодном для повторного использования активном endpoint.
     */
    fun connectToEndpoint(endpointId: String) {
        if (endpointId in connectedEndpointIds) {
            Log.i(TAG, "[connectToEndpoint] Endpoint уже подключен, переиспользуем endpointId=$endpointId")
            emitEvent(NearbyEvent.ConnectionReused(endpointId))
            return
        }
        Log.i(TAG, "[connectToEndpoint] Запрашиваем соединение endpointId=$endpointId")
        connectionsClient.requestConnection(context.packageName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Log.i(TAG, "[connectToEndpoint] Запрос соединения отправлен endpointId=$endpointId")
            }
            .addOnFailureListener { cause ->
                if (cause is ApiException && cause.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                    connectedEndpointIds.add(endpointId)
                    Log.i(TAG, "[connectToEndpoint] Nearby подтвердил существующее соединение endpointId=$endpointId")
                    emitEvent(NearbyEvent.ConnectionReused(endpointId))
                    return@addOnFailureListener
                }
                if (cause is ApiException && cause.statusCode in RECOVERABLE_CONNECTION_STATUS_CODES) {
                    Log.w(TAG, "[connectToEndpoint] Сбрасываем полуподключенный endpoint после statusCode=${cause.statusCode} endpointId=$endpointId", cause)
                    disconnectEndpoint(endpointId, forgetRegistryEntry = true)
                    emitEvent(NearbyEvent.ConnectionRecoveryRequired(endpointId, cause))
                    return@addOnFailureListener
                }
                Log.w(TAG, "[connectToEndpoint] Не удалось запросить соединение endpointId=$endpointId: ${cause.message}", cause)
                emitEvent(NearbyEvent.ConnectionRequestFailed(endpointId, cause))
            }
    }

    /**
     * Явно разрывает все активные Nearby-соединения и очищает транспортный признак connected без сброса discovery-связок.
     */
    fun disconnectAllPeers() {
        val endpointIds = connectedEndpointIds.toList()
        if (endpointIds.isEmpty()) {
            Log.i(TAG, "[disconnectAllPeers] Активных Nearby-соединений нет")
            return
        }
        endpointIds.forEach { endpointId ->
            disconnectEndpoint(endpointId, forgetRegistryEntry = false)
        }
    }

    /**
     * Безусловно отключает endpoint и при recovery удаляет его доменные связи, даже если handshake не завершился.
     */
    private fun disconnectEndpoint(endpointId: String, forgetRegistryEntry: Boolean) {
        connectedEndpointIds.remove(endpointId)
        connectionsClient.disconnectFromEndpoint(endpointId)
        if (forgetRegistryEntry) {
            endpointRegistry.removeEndpoint(endpointId)
        }
        Log.i(TAG, "[disconnectEndpoint] Endpoint отключен endpointId=$endpointId forgetRegistryEntry=$forgetRegistryEntry")
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
        sendToEndpoint(endpointId, peerId, packet)
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
        connectionsClient.sendPayload(endpointIds.toList(), Payload.fromBytes(wireCodec.encode(packet)))
            .addOnSuccessListener {
                Log.i(TAG, "[broadcast] Packet type=${packet.type} передан Nearby endpointCount=${endpointIds.size}")
            }
            .addOnFailureListener { cause ->
                Log.w(TAG, "[broadcast] Не удалось отправить broadcast packet type=${packet.type}: ${cause.message}", cause)
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

        val sendStartedAtMillis = System.currentTimeMillis()
        Log.i(TAG, "[sendStreamToPeers] Вызываем sendPayload для голосового stream originPeerId=${originPeerId.value} endpointCount=${endpointIds.size} peerCount=${peerIds.size} startedAtMs=$sendStartedAtMillis")
        val loggingInputStream = NearbyStreamLoggingInputStream(
            delegate = VoiceStreamCodec.frame(originPeerId, inputStream),
            endpointCount = endpointIds.size,
            peerCount = peerIds.size,
            sendStartedAtMillis = sendStartedAtMillis,
        )
        connectionsClient.sendPayload(endpointIds, Payload.fromStream(loggingInputStream))
            .addOnSuccessListener {
                Log.i(
                    TAG,
                    "[sendStreamToPeers] Голосовой stream принят Nearby endpointCount=${endpointIds.size} elapsedMs=${System.currentTimeMillis() - sendStartedAtMillis}",
                )
            }
            .addOnFailureListener { cause ->
                Log.w(TAG, "[sendStreamToPeers] Не удалось отправить голосовой stream: ${cause.message}", cause)
                runCatching { loggingInputStream.close() }
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

        val bytes = VoiceFrameCodec.encode(frame)
        connectionsClient.sendPayload(endpointIds, Payload.fromBytes(bytes))
            .addOnSuccessListener {
                if (frame.sequence == FIRST_VOICE_FRAME_SEQUENCE || frame.isFinal) {
                    Log.i(
                        TAG,
                        "[sendVoiceFrameToPeers] Voice frame передан Nearby originPeerId=${frame.originPeerId.value} sequence=${frame.sequence} endpointCount=${endpointIds.size} final=${frame.isFinal}",
                    )
                }
            }
            .addOnFailureListener { cause ->
                Log.w(
                    TAG,
                    "[sendVoiceFrameToPeers] Не удалось отправить voice frame originPeerId=${frame.originPeerId.value} sequence=${frame.sequence} final=${frame.isFinal}: ${cause.message}",
                    cause,
                )
                emitEvent(NearbyEvent.VoiceFrameSendFailed(peerIds, cause))
            }
    }

    /**
     * Возвращает permissions, без которых discovery заведомо упадет в Nearby API.
     */
    private fun missingPermissionsForDiscovery(): List<String> {
        return requiredBaseNearbyPermissions()
            .plus(requiredDiscoveryPermissions())
            .filterNot(::isPermissionGranted)
            .distinct()
    }

    /**
     * Возвращает permissions, без которых advertising заведомо упадет в Nearby API.
     */
    private fun missingPermissionsForAdvertising(): List<String> {
        return requiredBaseNearbyPermissions()
            .plus(requiredAdvertisingPermissions())
            .filterNot(::isPermissionGranted)
            .distinct()
    }

    /**
     * Возвращает общие permissions Nearby Connections для текущего Android.
     */
    private fun requiredBaseNearbyPermissions(): List<String> {
        return buildList {
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.CHANGE_WIFI_STATE)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
    }

    /**
     * Возвращает permissions, специфичные для discovery.
     */
    private fun requiredDiscoveryPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }
    }

    /**
     * Возвращает permissions, специфичные для advertising.
     */
    private fun requiredAdvertisingPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyList()
        }
    }

    /**
     * Проверяет, считает ли Android permission выданным этому приложению.
     */
    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Возвращает ошибку системной геолокации, если permissions есть, но Location toggle выключен.
     */
    private fun locationRequirementErrorOrNull(): NearbyRequirementException? {
        val locationPermissionsGranted = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).all(::isPermissionGranted)
        if (!locationPermissionsGranted) {
            return null
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (LocationManagerCompat.isLocationEnabled(locationManager)) {
            null
        } else {
            NearbyRequirementException.LocationDisabled
        }
    }

    /**
     * Отправляет packet напрямую в Nearby endpointId.
     */
    private fun sendToEndpoint(endpointId: String, peerId: PeerId?, packet: WirePacket) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(wireCodec.encode(packet)))
            .addOnSuccessListener {
                Log.i(TAG, "[sendToEndpoint] Packet type=${packet.type} передан Nearby endpointId=$endpointId peerId=${peerId?.value}")
            }
            .addOnFailureListener { cause ->
                Log.w(TAG, "[sendToEndpoint] Не удалось отправить packet type=${packet.type} endpointId=$endpointId peerId=${peerId?.value}: ${cause.message}", cause)
                emitEvent(NearbyEvent.SendFailed(endpointId, peerId, packet, cause))
            }
    }

    /**
     * Декодирует публичное описание комнаты из короткой Nearby endpointName-визитки актуального поколения.
     */
    private fun decodeRoomInfo(endpointInfo: DiscoveredEndpointInfo): RoomInfo? {
        return runCatching {
            NearbyRoomAdvertisement.decode(endpointInfo.endpointName)
                ?.toRoomInfo()
        }.onFailure { cause ->
            Log.w(TAG, "[decodeRoomInfo] Не удалось декодировать endpointName как визитку комнаты: ${cause.message}", cause)
        }.getOrNull()
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

    /**
     * Оборачивает голосовой stream, ограничивает размер одного чтения Nearby и логирует первый реальный read.
     */
    private class NearbyStreamLoggingInputStream(
        delegate: InputStream,
        private val endpointCount: Int,
        private val peerCount: Int,
        private val sendStartedAtMillis: Long,
    ) : FilterInputStream(delegate) {
        private var firstReadLogged = false

        /**
         * Читает один байт и отмечает первый успешный read со стороны Nearby.
         */
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                logFirstRead(readBytes = 1)
            } else {
                logEndBeforeFirstRead()
            }
            return value
        }

        /**
         * Читает порцию байтов и отмечает первый успешный read со стороны Nearby.
         */
        override fun read(buffer: ByteArray, byteOffset: Int, byteCount: Int): Int {
            val limitedByteCount = minOf(byteCount, STREAM_READ_LIMIT_BYTES)
            val readBytes = super.read(buffer, byteOffset, limitedByteCount)
            if (readBytes > 0) {
                logFirstRead(
                    readBytes = readBytes,
                    requestedBytes = byteCount,
                    limitedBytes = limitedByteCount,
                )
            } else if (readBytes < 0) {
                logEndBeforeFirstRead()
            }
            return readBytes
        }

        /**
         * Логирует первый момент, когда Nearby забрал из stream хотя бы один байт.
         */
        private fun logFirstRead(readBytes: Int, requestedBytes: Int = 1, limitedBytes: Int = 1) {
            if (firstReadLogged) {
                return
            }
            firstReadLogged = true
            Log.i(
                TAG,
                "[read] Nearby впервые прочитал голосовой stream readBytes=$readBytes requestedBytes=$requestedBytes limitedBytes=$limitedBytes endpointCount=$endpointCount peerCount=$peerCount elapsedMs=${System.currentTimeMillis() - sendStartedAtMillis}",
            )
        }

        /**
         * Логирует ситуацию, когда stream закончился до первого полезного чтения Nearby.
         */
        private fun logEndBeforeFirstRead() {
            if (firstReadLogged) {
                return
            }
            firstReadLogged = true
            Log.w(
                TAG,
                "[read] Голосовой stream закончился до первого чтения Nearby endpointCount=$endpointCount peerCount=$peerCount elapsedMs=${System.currentTimeMillis() - sendStartedAtMillis}",
            )
        }
    }

    private companion object {
        private const val TAG = "NearbyTransport"
        private const val DEFAULT_SERVICE_ID = "com.yellastro.btration.nearby.ROOM_V1"
        private const val EVENT_BUFFER_CAPACITY = 64
        private const val STREAM_READ_LIMIT_BYTES = 320
        private const val FIRST_VOICE_FRAME_SEQUENCE = 0L
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
        private val RECOVERABLE_CONNECTION_STATUS_CODES = setOf(
            ConnectionsStatusCodes.STATUS_RADIO_ERROR,
            ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN,
            ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL,
            ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR,
        )
    }
}
