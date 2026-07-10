package com.yellastro.btration.data.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
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
            Log.i(TAG, "[onConnectionInitiated] Получен запрос соединения endpointId=$endpointId endpointName=${connectionInfo.endpointName}")
            emitEvent(NearbyEvent.ConnectionInitiated(endpointId, connectionInfo))
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { cause ->
                    Log.w(TAG, "[onConnectionInitiated] Не удалось принять соединение endpointId=$endpointId: ${cause.message}", cause)
                    emitEvent(NearbyEvent.ConnectionAcceptFailed(endpointId, cause))
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            Log.i(
                TAG,
                "[onConnectionResult] Результат соединения endpointId=$endpointId statusCode=${resolution.status.statusCode} success=${resolution.status.isSuccess}",
            )
            emitEvent(NearbyEvent.ConnectionResult(endpointId, resolution))
        }

        override fun onDisconnected(endpointId: String) {
            val peerId = endpointRegistry.getPeerId(endpointId)
            Log.i(TAG, "[onDisconnected] Nearby endpoint отключился endpointId=$endpointId peerId=${peerId?.value}")
            endpointRegistry.removeEndpoint(endpointId)
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
            if (payload.type != Payload.Type.BYTES) {
                Log.w(TAG, "[onPayloadReceived] Получен неподдерживаемый payload endpointId=$endpointId type=${payload.type}")
                emitEvent(NearbyEvent.UnsupportedPayloadReceived(endpointId, payload.type))
                return
            }

            val bytes = payload.asBytes()
            if (bytes == null) {
                Log.w(TAG, "[onPayloadReceived] Payload bytes пустые endpointId=$endpointId")
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
                    Log.w(TAG, "[onPayloadReceived] Не удалось декодировать payload endpointId=$endpointId bytes=${bytes.size}: ${cause.message}", cause)
                    emitEvent(NearbyEvent.PayloadDecodeFailed(endpointId, cause))
                }
                .getOrNull()
                ?: return

            bindPacketMetadata(endpointId, packet)
            Log.i(
                TAG,
                "[onPayloadReceived] Получен packet endpointId=$endpointId peerId=${endpointRegistry.getPeerId(endpointId)?.value} type=${packet.type} roomId=${packet.roomId?.value}",
            )
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
     * Запускает advertising комнаты через короткую endpointName-визитку.
     */
    fun startAdvertising(room: RoomInfo) {
        Log.i(
            TAG,
            "[startAdvertising] Запускаем advertising roomId=${room.roomId.value} roomName=${room.name} hostPeerId=${room.host.peerId.value} serviceId=$serviceId strategy=$strategy",
        )
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
     * Запрашивает Nearby-соединение с найденным endpointId.
     */
    fun connectToEndpoint(endpointId: String) {
        Log.i(TAG, "[connectToEndpoint] Запрашиваем соединение endpointId=$endpointId")
        connectionsClient.requestConnection(context.packageName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Log.i(TAG, "[connectToEndpoint] Запрос соединения отправлен endpointId=$endpointId")
            }
            .addOnFailureListener { cause ->
                Log.w(TAG, "[connectToEndpoint] Не удалось запросить соединение endpointId=$endpointId: ${cause.message}", cause)
                emitEvent(NearbyEvent.ConnectionRequestFailed(endpointId, cause))
            }
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
     * Декодирует публичное описание комнаты из компактной Nearby endpointName-визитки.
     */
    private fun decodeRoomInfo(endpointInfo: DiscoveredEndpointInfo): RoomInfo? {
        return runCatching {
            NearbyRoomAdvertisement.decode(endpointInfo.endpointName)
                ?.toRoomInfo(createdAtMillis = System.currentTimeMillis())
        }.onFailure { cause ->
            Log.w(TAG, "[decodeRoomInfo] Не удалось декодировать endpointName как визитку комнаты: ${cause.message}", cause)
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
        val emitted = _events.tryEmit(event)
        if (!emitted) {
            Log.w(TAG, "[emitEvent] Не удалось опубликовать NearbyEvent type=${event.javaClass.simpleName}")
        }
    }

    private companion object {
        private const val TAG = "NearbyTransport"
        private const val DEFAULT_SERVICE_ID = "com.yellastro.btration.nearby.ROOM_V1"
        private const val EVENT_BUFFER_CAPACITY = 64
    }
}
