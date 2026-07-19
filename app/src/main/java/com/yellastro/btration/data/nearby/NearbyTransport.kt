package com.yellastro.btration.data.nearby

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Strategy
import com.yellastro.btration.domain.transport.NeighborAdvertisement
import com.yellastro.btration.domain.transport.NeighborCandidate
import com.yellastro.btration.domain.transport.NeighborCandidateId
import com.yellastro.btration.domain.transport.NeighborConnectionId
import com.yellastro.btration.domain.transport.NeighborDiscoveryMode
import com.yellastro.btration.domain.transport.NeighborLinkId
import com.yellastro.btration.domain.transport.NeighborPayloadTransferState
import com.yellastro.btration.domain.transport.NeighborTopology
import com.yellastro.btration.domain.transport.NeighborTransport
import com.yellastro.btration.domain.transport.NeighborTransportEvent
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Реализация NeighborTransport поверх Google Nearby Connections с динамическими Star/Cluster topology.
 *
 * Лобби чередует discovery-фазы; room layer выбирает P2P_STAR для обычного Star signaling и P2P_CLUSTER для MESHRA
 * либо Star-комнаты с отдельным raw Wi-Fi Direct media-plane.
 */
class NearbyTransport(
    context: Context,
    connectionsClient: ConnectionsClient,
    serviceId: String = DEFAULT_SERVICE_ID,
) : NeighborTransport {
    private val _neighborEvents = MutableSharedFlow<NeighborTransportEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val discoveryLock = Any()
    private val discoveryHandler = Handler(Looper.getMainLooper())
    private val candidateTopologies = ConcurrentHashMap<String, NeighborTopology>()
    private val activeDiscoveryCandidateIds = ConcurrentHashMap.newKeySet<String>()
    private val intentionallyStoppedCandidateIds = ConcurrentHashMap.newKeySet<String>()
    private val connectedCandidateIds = ConcurrentHashMap.newKeySet<String>()
    private var discoveryMode: NeighborDiscoveryMode? = null
    private var activeDiscoveryTopology: NeighborTopology? = null
    private var discoveryStartRunnable: Runnable? = null
    private var discoverySwitchRunnable: Runnable? = null
    private var pendingConnectionTimeoutRunnable: Runnable? = null
    private var pendingConnectionCandidateId: String? = null
    private var pendingConnectionTopology: NeighborTopology? = null
    private val payloadTransport = NearbyPayloadTransport(
        connectionsClient = connectionsClient,
        emitEvent = ::handlePayloadTransportEvent,
    )
    private val connectionLayer = NearbyConnectionLayer(
        context = context,
        connectionsClient = connectionsClient,
        serviceId = serviceId,
        payloadCallback = payloadTransport.payloadCallback,
        emitEvent = ::handleConnectionLayerEvent,
    )

    /**
     * Поток событий общего соседского транспорта.
     */
    override val neighborEvents: SharedFlow<NeighborTransportEvent> = _neighborEvents.asSharedFlow()

    /**
     * Запускает фиксированный либо чередующийся Star/Cluster discovery-режим.
     */
    override fun startDiscovery(mode: NeighborDiscoveryMode) {
        synchronized(discoveryLock) {
            if (discoveryMode == mode && (activeDiscoveryTopology != null || discoveryStartRunnable != null)) {
                Log.i(TAG, "[startDiscovery] Discovery-режим уже активен mode=$mode topology=$activeDiscoveryTopology")
                return
            }
            discoveryMode = mode
            clearPendingConnectionLocked()
            switchDiscoveryTopologyLocked(firstTopology(mode), reason = "start_$mode")
        }
    }

    /**
     * Останавливает поиск Nearby endpoint-ов.
     */
    override fun stopDiscovery() {
        synchronized(discoveryLock) {
            discoveryMode = null
            cancelDiscoveryRunnablesLocked()
            clearPendingConnectionLocked()
            intentionallyStoppedCandidateIds.addAll(activeDiscoveryCandidateIds)
            activeDiscoveryCandidateIds.clear()
            activeDiscoveryTopology = null
            connectionLayer.stopDiscovery()
        }
    }

    /**
     * Запускает advertising в Star или Cluster topology, выбранной режимом комнаты.
     */
    override fun startAdvertising(advertisement: NeighborAdvertisement, topology: NeighborTopology) {
        connectionLayer.startAdvertising(advertisement, topology.toNearbyStrategy())
    }

    /**
     * Останавливает advertising текущего устройства.
     */
    override fun stopAdvertising() {
        connectionLayer.stopAdvertising()
    }

    /**
     * Фиксирует topology выбранной комнаты и подключается сразу либо после повторного обнаружения endpoint-а в нужной фазе.
     */
    override fun connect(candidateId: NeighborCandidateId, topology: NeighborTopology) {
        var shouldConnectImmediately = false
        synchronized(discoveryLock) {
            discoveryMode = topology.fixedDiscoveryMode()
            cancelDiscoveryRunnablesLocked()
            clearPendingConnectionLocked()
            val candidateAlreadyConnected = candidateId.value in connectedCandidateIds
            val candidateWasFoundInActivePhase = activeDiscoveryTopology == topology &&
                candidateId.value in activeDiscoveryCandidateIds
            if (candidateAlreadyConnected || candidateWasFoundInActivePhase) {
                shouldConnectImmediately = true
                Log.i(TAG, "[connect] Endpoint подключается без повторного discovery candidateId=${candidateId.value} topology=$topology alreadyConnected=$candidateAlreadyConnected")
            } else {
                pendingConnectionCandidateId = candidateId.value
                pendingConnectionTopology = topology
                schedulePendingConnectionTimeoutLocked(candidateId.value, topology)
                switchDiscoveryTopologyLocked(topology, reason = "connect_wait_candidate")
                Log.i(TAG, "[connect] Ждем повторного обнаружения endpoint-а в нужной topology candidateId=${candidateId.value} topology=$topology lastFoundTopology=${candidateTopologies[candidateId.value]}")
            }
        }
        if (shouldConnectImmediately) {
            connectionLayer.connectToEndpoint(candidateId.value)
            if (topology == NeighborTopology.STAR) {
                stopDiscovery()
            }
        }
    }

    /**
     * Принимает входящее Nearby-соединение.
     */
    override fun acceptConnection(connectionId: NeighborConnectionId) {
        connectionLayer.acceptConnection(connectionId.value)
    }

    /**
     * Отклоняет входящее Nearby-соединение.
     */
    override fun rejectConnection(connectionId: NeighborConnectionId) {
        connectionLayer.rejectConnection(connectionId.value)
    }

    /**
     * Разрывает конкретный Nearby link.
     */
    override fun disconnect(linkId: NeighborLinkId) {
        connectedCandidateIds.remove(linkId.value)
        connectionLayer.disconnectEndpoint(linkId.value)
    }

    /**
     * Явно разрывает все активные Nearby-соединения без сброса discovery-связок.
     */
    override fun disconnectAll() {
        connectedCandidateIds.clear()
        connectionLayer.disconnectAllPeers()
    }

    /**
     * Полностью останавливает Nearby transport.
     */
    override fun stopAll(reason: String) {
        synchronized(discoveryLock) {
            discoveryMode = null
            cancelDiscoveryRunnablesLocked()
            clearPendingConnectionLocked()
            candidateTopologies.clear()
            activeDiscoveryCandidateIds.clear()
            intentionallyStoppedCandidateIds.clear()
            connectedCandidateIds.clear()
            activeDiscoveryTopology = null
        }
        connectionLayer.stopAllEndpoints(reason)
    }

    /**
     * Отправляет непрозрачное bytes-сообщение в Nearby link и возвращает ошибку вызывающему слою.
     *
     * Realtime bytes логируются агрегированно на payload-слое, чтобы голос не спамил logcat.
     */
    override fun sendMessage(
        linkId: NeighborLinkId,
        bytes: ByteArray,
        isRealtime: Boolean,
        onFailure: (Throwable) -> Unit,
    ) {
        payloadTransport.sendMessageToEndpoint(linkId.value, bytes, isRealtime, onFailure)
    }

    /**
     * Отправляет непрозрачное bytes-сообщение в несколько Nearby link-ов и возвращает ошибку вызывающему слою.
     *
     * Realtime bytes логируются агрегированно на payload-слое, чтобы голос не спамил logcat.
     */
    override fun sendMessage(
        linkIds: Collection<NeighborLinkId>,
        bytes: ByteArray,
        isRealtime: Boolean,
        onFailure: (Throwable) -> Unit,
    ) {
        val endpointIds = linkIds.map { linkId -> linkId.value }
        payloadTransport.sendMessageToEndpoints(endpointIds, bytes, isRealtime, onFailure)
    }

    /**
     * Отправляет непрозрачный stream в несколько Nearby link-ов и возвращает ошибку вызывающему слою.
     */
    override fun sendStream(linkIds: Collection<NeighborLinkId>, inputStream: InputStream, onFailure: (Throwable) -> Unit) {
        val endpointIds = linkIds.map { linkId -> linkId.value }
        payloadTransport.sendStreamToEndpoints(endpointIds, inputStream, onFailure)
    }

    /**
     * Останавливает текущую discovery-фазу и после короткого cooldown запускает нужную topology.
     * Вызывать только внутри discoveryLock.
     */
    private fun switchDiscoveryTopologyLocked(topology: NeighborTopology, reason: String) {
        cancelDiscoveryRunnablesLocked()
        intentionallyStoppedCandidateIds.addAll(activeDiscoveryCandidateIds)
        activeDiscoveryCandidateIds.clear()
        activeDiscoveryTopology = null
        connectionLayer.stopDiscovery()
        val startRunnable = Runnable {
            synchronized(discoveryLock) {
                discoveryStartRunnable = null
                val mode = discoveryMode ?: return@synchronized
                if (!mode.includes(topology)) {
                    return@synchronized
                }
                activeDiscoveryTopology = topology
                connectionLayer.startDiscovery(topology.toNearbyStrategy())
                scheduleNextAlternatingPhaseLocked(topology)
                Log.i(TAG, "[switchDiscoveryTopologyLocked] Discovery-фаза запущена topology=$topology mode=$mode reason=$reason")
            }
        }
        discoveryStartRunnable = startRunnable
        discoveryHandler.postDelayed(startRunnable, DISCOVERY_RESTART_COOLDOWN_MILLIS)
    }

    /**
     * Планирует следующую Star/Cluster фазу, если лобби работает в чередующемся режиме.
     * Вызывать только внутри discoveryLock.
     */
    private fun scheduleNextAlternatingPhaseLocked(currentTopology: NeighborTopology) {
        if (discoveryMode != NeighborDiscoveryMode.ALTERNATING) {
            return
        }
        val switchRunnable = Runnable {
            synchronized(discoveryLock) {
                discoverySwitchRunnable = null
                if (discoveryMode != NeighborDiscoveryMode.ALTERNATING || activeDiscoveryTopology != currentTopology) {
                    return@synchronized
                }
                switchDiscoveryTopologyLocked(currentTopology.other(), reason = "alternating_phase")
            }
        }
        discoverySwitchRunnable = switchRunnable
        discoveryHandler.postDelayed(switchRunnable, DISCOVERY_PHASE_MILLIS)
    }

    /**
     * Ограничивает ожидание повторного обнаружения endpoint-а перед connect, чтобы Joining не зависал бесконечно.
     * Вызывать только внутри discoveryLock.
     */
    private fun schedulePendingConnectionTimeoutLocked(candidateId: String, topology: NeighborTopology) {
        val timeoutRunnable = Runnable {
            val shouldFail = synchronized(discoveryLock) {
                if (pendingConnectionCandidateId != candidateId || pendingConnectionTopology != topology) {
                    false
                } else {
                    pendingConnectionCandidateId = null
                    pendingConnectionTopology = null
                    pendingConnectionTimeoutRunnable = null
                    true
                }
            }
            if (shouldFail) {
                val cause = IllegalStateException("Endpoint не найден повторно в topology=$topology")
                Log.w(TAG, "[schedulePendingConnectionTimeoutLocked] Истекло ожидание endpoint-а candidateId=$candidateId topology=$topology")
                emitEvent(NeighborTransportEvent.ConnectionRequestFailed(NeighborCandidateId(candidateId), cause))
            }
        }
        pendingConnectionTimeoutRunnable = timeoutRunnable
        discoveryHandler.postDelayed(timeoutRunnable, PENDING_CONNECTION_TIMEOUT_MILLIS)
    }

    /**
     * Отменяет runnable-ы запуска и смены discovery-фаз, сохраняя отдельный connect-timeout.
     * Вызывать только внутри discoveryLock.
     */
    private fun cancelDiscoveryRunnablesLocked() {
        discoveryStartRunnable?.let(discoveryHandler::removeCallbacks)
        discoverySwitchRunnable?.let(discoveryHandler::removeCallbacks)
        discoveryStartRunnable = null
        discoverySwitchRunnable = null
    }

    /**
     * Очищает ожидающий connect и отменяет его timeout.
     * Вызывать только внутри discoveryLock.
     */
    private fun clearPendingConnectionLocked() {
        pendingConnectionTimeoutRunnable?.let(discoveryHandler::removeCallbacks)
        pendingConnectionTimeoutRunnable = null
        pendingConnectionCandidateId = null
        pendingConnectionTopology = null
    }

    /**
     * Возвращает первую topology discovery-режима; общее лобби начинает со Star для быстрого восстановления обычных комнат.
     */
    private fun firstTopology(mode: NeighborDiscoveryMode): NeighborTopology {
        return when (mode) {
            NeighborDiscoveryMode.STAR_ONLY,
            NeighborDiscoveryMode.ALTERNATING,
            -> NeighborTopology.STAR
            NeighborDiscoveryMode.CLUSTER_ONLY -> NeighborTopology.CLUSTER
        }
    }

    /**
     * Проверяет, разрешена ли topology текущим discovery-режимом.
     */
    private fun NeighborDiscoveryMode.includes(topology: NeighborTopology): Boolean {
        return this == NeighborDiscoveryMode.ALTERNATING ||
            (this == NeighborDiscoveryMode.STAR_ONLY && topology == NeighborTopology.STAR) ||
            (this == NeighborDiscoveryMode.CLUSTER_ONLY && topology == NeighborTopology.CLUSTER)
    }

    /**
     * Возвращает противоположную topology для следующей фазы общего lobby discovery.
     */
    private fun NeighborTopology.other(): NeighborTopology {
        return when (this) {
            NeighborTopology.STAR -> NeighborTopology.CLUSTER
            NeighborTopology.CLUSTER -> NeighborTopology.STAR
        }
    }

    /**
     * Преобразует доменную topology в Google Nearby Strategy только на границе SDK.
     */
    private fun NeighborTopology.toNearbyStrategy(): Strategy {
        return when (this) {
            NeighborTopology.STAR -> Strategy.P2P_STAR
            NeighborTopology.CLUSTER -> Strategy.P2P_CLUSTER
        }
    }

    /**
     * Возвращает фиксированный discovery-режим для подключения к комнате выбранной topology.
     */
    private fun NeighborTopology.fixedDiscoveryMode(): NeighborDiscoveryMode {
        return when (this) {
            NeighborTopology.STAR -> NeighborDiscoveryMode.STAR_ONLY
            NeighborTopology.CLUSTER -> NeighborDiscoveryMode.CLUSTER_ONLY
        }
    }

    /**
     * Переводит lifecycle-события Nearby в транспортно-нейтральные события.
     */
    private fun handleConnectionLayerEvent(event: NearbyConnectionLayerEvent) {
        when (event) {
            is NearbyConnectionLayerEvent.ConnectionInitiated -> emitEvent(
                NeighborTransportEvent.ConnectionInitiated(
                    connectionId = NeighborConnectionId(event.endpointId),
                    endpointName = event.connectionInfo.endpointName,
                ),
            )
            is NearbyConnectionLayerEvent.ConnectionAcceptFailed -> emitEvent(
                NeighborTransportEvent.ConnectionAcceptFailed(
                    connectionId = NeighborConnectionId(event.endpointId),
                    cause = event.cause,
                ),
            )
            is NearbyConnectionLayerEvent.ConnectionRejectFailed -> emitEvent(
                NeighborTransportEvent.ConnectionRejectFailed(
                    connectionId = NeighborConnectionId(event.endpointId),
                    cause = event.cause,
                ),
            )
            is NearbyConnectionLayerEvent.ConnectionResult -> {
                val linkId = NeighborLinkId(event.endpointId)
                if (event.resolution.status.isSuccess) {
                    connectedCandidateIds.add(event.endpointId)
                    emitEvent(
                        NeighborTransportEvent.LinkConnected(
                            linkId = linkId,
                            statusCode = event.resolution.status.statusCode,
                            reused = false,
                        ),
                    )
                } else {
                    connectedCandidateIds.remove(event.endpointId)
                    emitEvent(
                        NeighborTransportEvent.LinkConnectionFailed(
                            linkId = linkId,
                            statusCode = event.resolution.status.statusCode,
                        ),
                    )
                }
            }
            is NearbyConnectionLayerEvent.ConnectionRecoveryRequired -> emitEvent(
                NeighborTransportEvent.ConnectionRecoveryRequired(
                    linkId = NeighborLinkId(event.endpointId),
                    cause = event.cause,
                ),
            )
            is NearbyConnectionLayerEvent.Disconnected -> {
                connectedCandidateIds.remove(event.endpointId)
                emitEvent(NeighborTransportEvent.LinkDisconnected(NeighborLinkId(event.endpointId)))
            }
            is NearbyConnectionLayerEvent.EndpointFound -> handleEndpointFound(event)
            is NearbyConnectionLayerEvent.EndpointLost -> handleEndpointLost(event)
            is NearbyConnectionLayerEvent.DiscoveryFailed -> emitEvent(NeighborTransportEvent.DiscoveryFailed(event.cause))
            is NearbyConnectionLayerEvent.AdvertisingFailed -> emitEvent(NeighborTransportEvent.AdvertisingFailed(event.cause))
            is NearbyConnectionLayerEvent.ConnectionReused -> {
                connectedCandidateIds.add(event.endpointId)
                emitEvent(
                    NeighborTransportEvent.LinkConnected(
                        linkId = NeighborLinkId(event.endpointId),
                        statusCode = STATUS_ALREADY_CONNECTED_REUSED,
                        reused = true,
                    ),
                )
            }
            is NearbyConnectionLayerEvent.ConnectionRequestFailed -> emitEvent(
                NeighborTransportEvent.ConnectionRequestFailed(
                    candidateId = NeighborCandidateId(event.endpointId),
                    cause = event.cause,
                ),
            )
        }
    }

    /**
     * Запоминает topology найденного endpoint-а, публикует кандидата и завершает отложенный connect при совпадении.
     */
    private fun handleEndpointFound(event: NearbyConnectionLayerEvent.EndpointFound) {
        var shouldConnect = false
        val topology = synchronized(discoveryLock) {
            val currentTopology = activeDiscoveryTopology
            if (currentTopology == null) {
                Log.w(TAG, "[handleEndpointFound] Endpoint найден вне активной discovery-фазы endpointId=${event.endpointId}")
                return@synchronized null
            }
            candidateTopologies[event.endpointId] = currentTopology
            activeDiscoveryCandidateIds.add(event.endpointId)
            intentionallyStoppedCandidateIds.remove(event.endpointId)
            if (pendingConnectionCandidateId == event.endpointId && pendingConnectionTopology == currentTopology) {
                clearPendingConnectionLocked()
                shouldConnect = true
            }
            currentTopology
        } ?: return
        emitEvent(
            NeighborTransportEvent.CandidateFound(
                NeighborCandidate(
                    candidateId = NeighborCandidateId(event.endpointId),
                    endpointName = event.endpointInfo.endpointName,
                    serviceId = event.endpointInfo.serviceId,
                ),
            ),
        )
        Log.i(TAG, "[handleEndpointFound] Endpoint привязан к discovery topology endpointId=${event.endpointId} topology=$topology pendingConnect=$shouldConnect")
        if (shouldConnect) {
            connectionLayer.connectToEndpoint(event.endpointId)
            if (topology == NeighborTopology.STAR) {
                stopDiscovery()
            }
        }
    }

    /**
     * Подавляет искусственный EndpointLost при смене фаз; реальную потерю в фиксированном режиме публикует наверх.
     */
    private fun handleEndpointLost(event: NearbyConnectionLayerEvent.EndpointLost) {
        val shouldSuppress = synchronized(discoveryLock) {
            activeDiscoveryCandidateIds.remove(event.endpointId)
            intentionallyStoppedCandidateIds.remove(event.endpointId) || discoveryMode == NeighborDiscoveryMode.ALTERNATING
        }
        if (shouldSuppress) {
            Log.i(TAG, "[handleEndpointLost] EndpointLost подавлен при смене discovery-фазы endpointId=${event.endpointId}")
            return
        }
        candidateTopologies.remove(event.endpointId)
        emitEvent(NeighborTransportEvent.CandidateLost(NeighborCandidateId(event.endpointId)))
    }

    /**
     * Переводит payload-события Nearby в транспортно-нейтральные события.
     */
    private fun handlePayloadTransportEvent(event: NearbyPayloadTransportEvent) {
        when (event) {
            is NearbyPayloadTransportEvent.MessageReceived -> emitEvent(
                NeighborTransportEvent.MessageReceived(
                    linkId = NeighborLinkId(event.endpointId),
                    bytes = event.bytes,
                ),
            )
            is NearbyPayloadTransportEvent.StreamReceived -> emitEvent(
                NeighborTransportEvent.StreamReceived(
                    linkId = NeighborLinkId(event.endpointId),
                    inputStream = event.inputStream,
                ),
            )
            is NearbyPayloadTransportEvent.PayloadReadFailed -> emitEvent(
                NeighborTransportEvent.PayloadReadFailed(
                    linkId = NeighborLinkId(event.endpointId),
                    cause = event.cause,
                ),
            )
            is NearbyPayloadTransportEvent.UnsupportedPayloadReceived -> emitEvent(
                NeighborTransportEvent.UnsupportedPayloadReceived(
                    linkId = NeighborLinkId(event.endpointId),
                    payloadType = event.payloadType,
                ),
            )
            is NearbyPayloadTransportEvent.PayloadTransferUpdated -> emitEvent(
                NeighborTransportEvent.PayloadTransferUpdated(
                    linkId = NeighborLinkId(event.endpointId),
                    state = NeighborPayloadTransferState(
                        payloadId = event.update.payloadId,
                        status = event.update.status,
                        bytesTransferred = event.update.bytesTransferred,
                        totalBytes = event.update.totalBytes,
                    ),
                ),
            )
        }
    }

    /**
     * Публикует транспортно-нейтральное событие без подвешивания Nearby callback-потока.
     */
    private fun emitEvent(event: NeighborTransportEvent) {
        val emitted = _neighborEvents.tryEmit(event)
        if (!emitted) {
            Log.w(TAG, "[emitEvent] Не удалось опубликовать NeighborTransportEvent type=${event.javaClass.simpleName}")
        }
    }

    private companion object {
        private const val TAG = "NearbyTransport"
        private const val DEFAULT_SERVICE_ID = "com.yellastro.btration.nearby.ROOM_V1"
        private const val EVENT_BUFFER_CAPACITY = 64
        private const val STATUS_ALREADY_CONNECTED_REUSED = 0
        private const val DISCOVERY_RESTART_COOLDOWN_MILLIS = 250L
        private const val DISCOVERY_PHASE_MILLIS = 4_000L
        private const val PENDING_CONNECTION_TIMEOUT_MILLIS = 12_000L
    }
}
