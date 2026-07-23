package com.yellastro.btration.voice

import android.util.Log
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.VoiceTransportControlInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * VoiceTransport-обертка, которая выбирает media-plane по meta host-а и переносит в delegate индекс участников.
 *
 * Делегат создается лениво: пока режим комнаты не применен и media-plane не стартует,
 * Wi-Fi Direct/Nearby реализация не должна регистрировать свои callbacks и трогать систему.
 */
class SwitchableVoiceTransport(
    initialMode: VoiceTransportMode,
    private val externalScope: CoroutineScope,
    private val createDelegate: (VoiceTransportMode) -> VoiceTransport,
) : VoiceTransport {
    private val _events = MutableSharedFlow<VoiceTransportEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val delegates = mutableMapOf<VoiceTransportMode, VoiceTransport>()

    private var currentMode = initialMode
    private var currentDelegate: VoiceTransport? = null
    private var currentRoomPeerIds: Set<PeerId> = emptySet()

    /**
     * События только от текущего выбранного voice transport delegate.
     */
    override val events: SharedFlow<VoiceTransportEvent> = _events.asSharedFlow()

    /**
     * Локальная служебная информация текущего delegate, если выбранный media-plane ее публикует.
     */
    override val localControlInfo: VoiceTransportControlInfo?
        get() = currentDelegate?.localControlInfo

    /**
     * Выбирает voice transport и передает актуальный peer-index уже созданному delegate без раннего создания нового.
     */
    fun setMode(mode: VoiceTransportMode, reason: String) {
        if (mode == currentMode) {
            Log.i(TAG, "[setMode] Voice transport уже выбран mode=$mode reason=$reason delegateCreated=${currentDelegate != null}")
            return
        }
        currentDelegate?.stopSession()
        currentMode = mode
        currentDelegate = delegates[mode]
        currentDelegate?.updateRoomPeers(currentRoomPeerIds)
        Log.i(TAG, "[setMode] Voice transport переключен mode=$mode reason=$reason delegateReused=${currentDelegate != null}")
    }

    /**
     * Запускает media-plane сессию в текущем выбранном delegate.
     */
    override fun startSession(selfPeerId: PeerId, role: VoiceTransportSessionRole) {
        Log.i(TAG, "[startSession] Запускаем выбранный voice transport mode=$currentMode role=$role selfPeerId=${selfPeerId.value}")
        activeDelegate().startSession(selfPeerId, role)
    }

    /**
     * Останавливает текущую media-plane сессию выбранного delegate.
     */
    override fun stopSession() {
        val delegate = currentDelegate
        if (delegate == null) {
            Log.i(TAG, "[stopSession] Активный voice transport еще не создан mode=$currentMode")
            return
        }
        delegate.stopSession()
    }

    /**
     * Передает transport control info только текущему выбранному delegate.
     */
    override fun handleControlInfo(fromPeerId: PeerId, info: VoiceTransportControlInfo) {
        activeDelegate().handleControlInfo(fromPeerId, info)
    }

    /**
     * Кэширует полный набор участников и обновляет уже созданный delegate без принудительной ленивой инициализации.
     */
    override fun updateRoomPeers(peerIds: Set<PeerId>) {
        currentRoomPeerIds = peerIds.toSet()
        currentDelegate?.updateRoomPeers(currentRoomPeerIds)
        Log.i(TAG, "[updateRoomPeers] Индекс участников voice transport обновлен peerCount=${currentRoomPeerIds.size} mode=$currentMode")
    }

    /**
     * Проверяет готовность участников в текущем выбранном delegate.
     */
    override fun isReadyForPeers(peerIds: Set<PeerId>): Boolean {
        return currentDelegate?.isReadyForPeers(peerIds) == true
    }

    /**
     * Отправляет voice frame через текущий выбранный delegate.
     */
    override fun sendFrameToPeers(peerIds: Set<PeerId>, frame: VoiceFrame) {
        val delegate = currentDelegate
        if (delegate == null) {
            Log.w(TAG, "[sendFrameToPeers] Нельзя отправить voice frame: delegate еще не создан mode=$currentMode peerCount=${peerIds.size}")
            return
        }
        delegate.sendFrameToPeers(peerIds, frame)
    }

    /**
     * Создает delegate лениво, передает ему индекс участников и подписывает общий events-flow.
     */
    private fun delegateFor(mode: VoiceTransportMode): VoiceTransport {
        return delegates.getOrPut(mode) {
            createDelegate(mode).also { delegate ->
                delegate.updateRoomPeers(currentRoomPeerIds)
                externalScope.launch {
                    delegate.events.collect { event ->
                        if (delegate === currentDelegate) {
                            emitEvent(event)
                        } else {
                            Log.i(TAG, "[delegateFor] Событие старого voice transport проигнорировано mode=$mode type=${event.javaClass.simpleName}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Возвращает активный delegate, создавая его только для уже выбранного режима комнаты.
     */
    private fun activeDelegate(): VoiceTransport {
        val delegate = currentDelegate
        if (delegate != null) {
            return delegate
        }
        return delegateFor(currentMode).also { createdDelegate ->
            currentDelegate = createdDelegate
            Log.i(TAG, "[activeDelegate] Voice transport delegate создан mode=$currentMode")
        }
    }

    /**
     * Публикует событие текущего delegate наружу без подвешивания transport callbacks.
     */
    private fun emitEvent(event: VoiceTransportEvent) {
        if (!_events.tryEmit(event)) {
            Log.w(TAG, "[emitEvent] Буфер switchable voice events переполнен type=${event.javaClass.simpleName}")
        }
    }

    private companion object {
        private const val TAG = "SwitchableVoiceTransport"
        private const val EVENT_BUFFER_CAPACITY = 64
    }
}
