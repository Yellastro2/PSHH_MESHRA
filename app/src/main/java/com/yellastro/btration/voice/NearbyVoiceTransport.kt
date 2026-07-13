package com.yellastro.btration.voice

import android.util.Log
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.VoiceTransportControlInfo
import com.yellastro.btration.domain.transport.NeighborTransport
import com.yellastro.btration.domain.transport.NeighborTransportEvent
import com.yellastro.btration.domain.transport.PeerLinkResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Реализация VoiceTransport поверх NeighborTransport BYTES payload: кодирует и декодирует только BTVO voice frames.
 */
class NearbyVoiceTransport(
    private val neighborTransport: NeighborTransport,
    private val peerLinkResolver: PeerLinkResolver,
    externalScope: CoroutineScope,
) : VoiceTransport {
    private val _events = MutableSharedFlow<VoiceTransportEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /**
     * Нормализованные voice-события для VoiceRuntime.
     */
    override val events: SharedFlow<VoiceTransportEvent> = _events.asSharedFlow()

    /**
     * Nearby BYTES не требует дополнительного media-plane handshake поверх signaling.
     */
    override val localControlInfo: VoiceTransportControlInfo? = null

    init {
        externalScope.launch {
            Log.i(TAG, "[init] Подписываем NearbyVoiceTransport на события NeighborTransport")
            neighborTransport.neighborEvents.collect(::handleNeighborEvent)
        }
    }

    /**
     * Отправляет voice frame выбранным peerId через linkId, которые ведет RoomTransport.
     */
    override fun sendFrameToPeers(peerIds: Set<PeerId>, frame: VoiceFrame) {
        val linkIds = peerIds.mapNotNull(peerLinkResolver::linkIdForPeer)
        if (linkIds.isEmpty()) {
            Log.w(TAG, "[sendFrameToPeers] Нельзя отправить voice frame, link-ы неизвестны peerCount=${peerIds.size} originPeerId=${frame.originPeerId.value}")
            emitEvent(
                VoiceTransportEvent.FrameSendFailed(
                    peerIds = peerIds,
                    cause = IllegalStateException("Link-и для voice frame неизвестны"),
                ),
            )
            return
        }
        neighborTransport.sendMessage(linkIds, VoiceFrameCodec.encode(frame)) { cause ->
            emitEvent(
                VoiceTransportEvent.FrameSendFailed(
                    peerIds = peerIds,
                    cause = cause,
                ),
            )
        }
    }

    /**
     * Отмечает старт Nearby voice-сессии и сразу сообщает готовность, потому что signaling link уже установлен.
     */
    override fun startSession(selfPeerId: PeerId, role: VoiceTransportSessionRole) {
        Log.i(TAG, "[startSession] Nearby voice transport готов role=$role selfPeerId=${selfPeerId.value}")
        emitEvent(VoiceTransportEvent.DirectAudioReady)
    }

    /**
     * Останавливает Nearby voice-сессию без сброса общего NeighborTransport, которым владеет RoomRuntime.
     */
    override fun stopSession() {
        Log.i(TAG, "[stopSession] Nearby voice transport остановлен без сброса signaling")
    }

    /**
     * Игнорирует служебную информацию других voice transports.
     */
    override fun handleControlInfo(fromPeerId: PeerId, info: VoiceTransportControlInfo) {
        Log.i(TAG, "[handleControlInfo] Nearby voice transport игнорирует info mode=${info.mode} fromPeerId=${fromPeerId.value}")
    }

    /**
     * Проверяет, есть ли linkId для каждого peerId.
     */
    override fun isReadyForPeers(peerIds: Set<PeerId>): Boolean {
        return peerIds.all { peerId -> peerLinkResolver.linkIdForPeer(peerId) != null }
    }

    /**
     * Преобразует только voice-сообщения NeighborTransport в общий VoiceTransportEvent.
     */
    private fun handleNeighborEvent(event: NeighborTransportEvent) {
        when (event) {
            is NeighborTransportEvent.MessageReceived -> handleMessageReceived(event)
            else -> Unit
        }
    }

    /**
     * Декодирует BTVO bytes и пропускает все остальные сообщения соседского транспорта.
     */
    private fun handleMessageReceived(event: NeighborTransportEvent.MessageReceived) {
        if (!VoiceFrameCodec.isVoiceFrame(event.bytes)) {
            return
        }
        val frame = runCatching { VoiceFrameCodec.decode(event.bytes) }
            .onFailure { cause ->
                Log.w(TAG, "[handleMessageReceived] Не удалось декодировать voice frame linkId=${event.linkId.value} bytes=${event.bytes.size}: ${cause.message}", cause)
            }
            .getOrNull()
            ?: return
        emitEvent(
            VoiceTransportEvent.FrameReceived(
                transportPeerId = peerLinkResolver.peerIdForLink(event.linkId),
                frame = frame,
                transportEndpointId = event.linkId.value,
            ),
        )
    }

    /**
     * Отправляет событие подписчикам без подвешивания callback-потока.
     */
    private fun emitEvent(event: VoiceTransportEvent) {
        if (!_events.tryEmit(event)) {
            Log.w(TAG, "[emitEvent] Буфер voice-событий переполнен type=${event.javaClass.simpleName}")
        }
    }

    private companion object {
        private const val TAG = "NearbyVoiceTransport"
        private const val EVENT_BUFFER_CAPACITY = 64
    }
}
