package com.yellastro.btration.voice

import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.VoiceTransportControlInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * VoiceTransport-заглушка для устройств, где выбранный media-plane не поддерживается системно.
 */
class UnavailableVoiceTransport(
    private val unavailableMessage: String,
) : VoiceTransport {
    private val _events = MutableSharedFlow<VoiceTransportEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /**
     * События недоступности транспорта, которые RoomRuntime превратит в snackbar.
     */
    override val events: SharedFlow<VoiceTransportEvent> = _events.asSharedFlow()

    /**
     * Недоступный transport не имеет служебной информации для signaling.
     */
    override val localControlInfo: VoiceTransportControlInfo? = null

    /**
     * Сообщает UI, что выбранный voice transport не поддерживается.
     */
    override fun startSession(selfPeerId: PeerId, role: VoiceTransportSessionRole) {
        emitUnavailable()
    }

    /**
     * Остановка заглушки не требует действий.
     */
    override fun stopSession() = Unit

    /**
     * Служебная информация других транспортов игнорируется, потому что локальный transport недоступен.
     */
    override fun handleControlInfo(fromPeerId: PeerId, info: VoiceTransportControlInfo) = Unit

    /**
     * Сообщает UI, что голосовые frames нельзя отправить выбранным transport-ом.
     */
    override fun sendFrameToPeers(peerIds: Set<PeerId>, frame: VoiceFrame) {
        emitUnavailable()
        _events.tryEmit(
            VoiceTransportEvent.FrameSendFailed(
                peerIds = peerIds,
                cause = IllegalStateException(unavailableMessage),
            ),
        )
    }

    /**
     * Публикует событие недоступности без подвешивания вызывающего потока.
     */
    private fun emitUnavailable() {
        _events.tryEmit(VoiceTransportEvent.TransportUnavailable(unavailableMessage))
    }

    private companion object {
        private const val EVENT_BUFFER_CAPACITY = 16
    }
}
