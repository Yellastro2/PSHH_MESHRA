package com.yellastro.btration.voice

import android.util.Log
import com.yellastro.btration.data.nearby.NearbyEvent
import com.yellastro.btration.data.nearby.NearbyTransport
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.VoiceTransportControlInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Реализация VoiceTransport поверх Nearby BYTES payload: слушает Nearby voice events и отправляет BTVO frames через NearbyTransport.
 */
class NearbyVoiceTransport(
    private val nearbyTransport: NearbyTransport,
    externalScope: CoroutineScope,
) : VoiceTransport {
    private val _events = MutableSharedFlow<VoiceTransportEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /**
     * Нормализованные voice-события, которые выше не знают о типах NearbyEvent.
     */
    override val events: SharedFlow<VoiceTransportEvent> = _events.asSharedFlow()

    /**
     * Nearby BYTES не требует дополнительного media-plane handshake поверх signaling.
     */
    override val localControlInfo: VoiceTransportControlInfo? = null

    init {
        externalScope.launch {
            Log.i(TAG, "[init] Подписываем NearbyVoiceTransport на Nearby voice events")
            nearbyTransport.events.collect(::handleNearbyEvent)
        }
    }

    /**
     * Отправляет voice frame выбранным peerId через Nearby endpoint registry.
     */
    override fun sendFrameToPeers(peerIds: Set<PeerId>, frame: VoiceFrame) {
        nearbyTransport.sendVoiceFrameToPeers(peerIds, frame)
    }

    /**
     * Отмечает старт Nearby voice-сессии; реальная Nearby-сессия уже управляется комнатой.
     */
    override fun startSession(selfPeerId: PeerId, role: VoiceTransportSessionRole) {
        Log.i(TAG, "[startSession] Nearby voice transport готов role=$role selfPeerId=${selfPeerId.value}")
    }

    /**
     * Останавливает Nearby voice-сессию без сброса общего NearbyTransport, которым владеет RoomRuntime.
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
     * Nearby endpoint registry уже подтвержден signaling-соединением комнаты.
     */
    override fun isReadyForPeers(peerIds: Set<PeerId>): Boolean = true

    /**
     * Преобразует только voice-события Nearby в общий VoiceTransportEvent.
     */
    private fun handleNearbyEvent(event: NearbyEvent) {
        when (event) {
            is NearbyEvent.VoiceFrameReceived -> emitEvent(
                VoiceTransportEvent.FrameReceived(
                    transportPeerId = event.peerId,
                    frame = event.frame,
                    transportEndpointId = event.endpointId,
                ),
            )

            is NearbyEvent.VoiceFrameSendFailed -> emitEvent(
                VoiceTransportEvent.FrameSendFailed(
                    peerIds = event.peerIds,
                    cause = event.cause,
                ),
            )

            else -> Unit
        }
    }

    /**
     * Отправляет событие подписчикам без подвешивания callback-потока Nearby.
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
