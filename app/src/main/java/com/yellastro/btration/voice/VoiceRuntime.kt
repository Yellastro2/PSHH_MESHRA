package com.yellastro.btration.voice

import android.util.Log
import com.yellastro.btration.data.nearby.NearbyTransport
import com.yellastro.btration.domain.model.PeerId
import java.io.InputStream

/**
 * Управляет MVP-голосом комнаты: стартует локальный PCM stream и проигрывает входящие streams.
 */
class VoiceRuntime(
    private val nearbyTransport: NearbyTransport,
    private val voiceCapture: PcmVoiceCapture,
    private val voicePlayer: PcmVoicePlayer,
) {
    private var isTalking = false

    /**
     * Начинает передачу микрофона выбранным участникам через Nearby STREAM.
     */
    fun startTalking(targetPeerIds: Set<PeerId>) {
        if (isTalking) {
            Log.i(TAG, "[startTalking] Передача голоса уже активна")
            return
        }
        if (targetPeerIds.isEmpty()) {
            Log.i(TAG, "[startTalking] Некому передавать голос, участники отсутствуют")
            return
        }

        val inputStream = runCatching { voiceCapture.start() }
            .onFailure { cause ->
                Log.w(TAG, "[startTalking] Не удалось запустить захват микрофона: ${cause.message}", cause)
            }
            .getOrNull()
            ?: return
        isTalking = true
        nearbyTransport.sendStreamToPeers(targetPeerIds, inputStream)
        Log.i(TAG, "[startTalking] Передача голоса запущена targetCount=${targetPeerIds.size}")
    }

    /**
     * Останавливает локальную передачу микрофона.
     */
    fun stopTalking() {
        if (!isTalking) {
            return
        }
        voiceCapture.stop()
        isTalking = false
        Log.i(TAG, "[stopTalking] Передача голоса остановлена")
    }

    /**
     * Проигрывает входящий голосовой stream от участника комнаты.
     */
    fun playIncoming(peerId: PeerId, inputStream: InputStream) {
        Log.i(TAG, "[playIncoming] Запускаем входящий голосовой stream peerId=${peerId.value}")
        voicePlayer.play(peerId, inputStream)
    }

    /**
     * Останавливает локальную передачу и все входящие голосовые streams.
     */
    fun stopAll() {
        stopTalking()
        voicePlayer.stopAll()
        Log.i(TAG, "[stopAll] Все голосовые streams остановлены")
    }

    private companion object {
        private const val TAG = "VoiceRuntime"
    }
}
