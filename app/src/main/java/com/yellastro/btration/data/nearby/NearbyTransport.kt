package com.yellastro.btration.data.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.Strategy
import com.yellastro.btration.domain.transport.NeighborAdvertisement
import com.yellastro.btration.domain.transport.NeighborCandidate
import com.yellastro.btration.domain.transport.NeighborCandidateId
import com.yellastro.btration.domain.transport.NeighborConnectionId
import com.yellastro.btration.domain.transport.NeighborLinkId
import com.yellastro.btration.domain.transport.NeighborPayloadTransferState
import com.yellastro.btration.domain.transport.NeighborTransport
import com.yellastro.btration.domain.transport.NeighborTransportEvent
import java.io.InputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Реализация NeighborTransport поверх Google Nearby Connections без знания форматов room/voice payload-ов.
 */
class NearbyTransport(
    context: Context,
    connectionsClient: ConnectionsClient,
    strategy: Strategy = Strategy.P2P_STAR,
    serviceId: String = DEFAULT_SERVICE_ID,
) : NeighborTransport {
    private val _neighborEvents = MutableSharedFlow<NeighborTransportEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val payloadTransport = NearbyPayloadTransport(
        connectionsClient = connectionsClient,
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
     * Поток событий общего соседского транспорта.
     */
    override val neighborEvents: SharedFlow<NeighborTransportEvent> = _neighborEvents.asSharedFlow()

    /**
     * Запускает поиск Nearby endpoint-ов с текущим serviceId.
     */
    override fun startDiscovery() {
        connectionLayer.startDiscovery()
    }

    /**
     * Останавливает поиск Nearby endpoint-ов.
     */
    override fun stopDiscovery() {
        connectionLayer.stopDiscovery()
    }

    /**
     * Запускает advertising с заранее подготовленной transport-визиткой.
     */
    override fun startAdvertising(advertisement: NeighborAdvertisement) {
        connectionLayer.startAdvertising(advertisement)
    }

    /**
     * Останавливает advertising текущего устройства.
     */
    override fun stopAdvertising() {
        connectionLayer.stopAdvertising()
    }

    /**
     * Запрашивает соединение с найденным Nearby endpoint-кандидатом.
     */
    override fun connect(candidateId: NeighborCandidateId) {
        connectionLayer.connectToEndpoint(candidateId.value)
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
        connectionLayer.disconnectEndpoint(linkId.value)
    }

    /**
     * Явно разрывает все активные Nearby-соединения без сброса discovery-связок.
     */
    override fun disconnectAll() {
        connectionLayer.disconnectAllPeers()
    }

    /**
     * Полностью останавливает Nearby transport.
     */
    override fun stopAll(reason: String) {
        connectionLayer.stopAllEndpoints(reason)
    }

    /**
     * Отправляет непрозрачное bytes-сообщение в Nearby link и возвращает ошибку вызывающему слою.
     */
    override fun sendMessage(linkId: NeighborLinkId, bytes: ByteArray, onFailure: (Throwable) -> Unit) {
        payloadTransport.sendMessageToEndpoint(linkId.value, bytes, onFailure)
    }

    /**
     * Отправляет непрозрачное bytes-сообщение в несколько Nearby link-ов и возвращает ошибку вызывающему слою.
     */
    override fun sendMessage(linkIds: Collection<NeighborLinkId>, bytes: ByteArray, onFailure: (Throwable) -> Unit) {
        val endpointIds = linkIds.map { linkId -> linkId.value }
        payloadTransport.sendMessageToEndpoints(endpointIds, bytes, onFailure)
    }

    /**
     * Отправляет непрозрачный stream в несколько Nearby link-ов и возвращает ошибку вызывающему слою.
     */
    override fun sendStream(linkIds: Collection<NeighborLinkId>, inputStream: InputStream, onFailure: (Throwable) -> Unit) {
        val endpointIds = linkIds.map { linkId -> linkId.value }
        payloadTransport.sendStreamToEndpoints(endpointIds, inputStream, onFailure)
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
                    emitEvent(
                        NeighborTransportEvent.LinkConnected(
                            linkId = linkId,
                            statusCode = event.resolution.status.statusCode,
                            reused = false,
                        ),
                    )
                } else {
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
            is NearbyConnectionLayerEvent.Disconnected -> emitEvent(
                NeighborTransportEvent.LinkDisconnected(NeighborLinkId(event.endpointId)),
            )
            is NearbyConnectionLayerEvent.EndpointFound -> emitEvent(
                NeighborTransportEvent.CandidateFound(
                    NeighborCandidate(
                        candidateId = NeighborCandidateId(event.endpointId),
                        endpointName = event.endpointInfo.endpointName,
                        serviceId = event.endpointInfo.serviceId,
                    ),
                ),
            )
            is NearbyConnectionLayerEvent.EndpointLost -> emitEvent(
                NeighborTransportEvent.CandidateLost(NeighborCandidateId(event.endpointId)),
            )
            is NearbyConnectionLayerEvent.DiscoveryFailed -> emitEvent(NeighborTransportEvent.DiscoveryFailed(event.cause))
            is NearbyConnectionLayerEvent.AdvertisingFailed -> emitEvent(NeighborTransportEvent.AdvertisingFailed(event.cause))
            is NearbyConnectionLayerEvent.ConnectionReused -> emitEvent(
                NeighborTransportEvent.LinkConnected(
                    linkId = NeighborLinkId(event.endpointId),
                    statusCode = STATUS_ALREADY_CONNECTED_REUSED,
                    reused = true,
                ),
            )
            is NearbyConnectionLayerEvent.ConnectionRequestFailed -> emitEvent(
                NeighborTransportEvent.ConnectionRequestFailed(
                    candidateId = NeighborCandidateId(event.endpointId),
                    cause = event.cause,
                ),
            )
        }
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
    }
}
