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
     * Начинает передачу микрофона выбранным участникам через Nearby STREAM и возвращает true при реальном старте.
     */
    fun startTalking(targetPeerIds: Set<PeerId>): Boolean {
        if (isTalking) {
            Log.i(TAG, "[startTalking] Передача голоса уже активна")
            return true
        }
        if (targetPeerIds.isEmpty()) {
            Log.i(TAG, "[startTalking] Некому передавать голос, участники отсутствуют")
            return false
        }

        val startedAtMillis = System.currentTimeMillis()
        Log.i(TAG, "[startTalking] Начинаем запуск голосовой передачи targetCount=${targetPeerIds.size}")
        val inputStream = runCatching { voiceCapture.start() }
            .onFailure { cause ->
                Log.w(TAG, "[startTalking] Не удалось запустить захват микрофона: ${cause.message}", cause)
            }
            .getOrNull()
            ?: return false
        isTalking = true
        nearbyTransport.sendStreamToPeers(targetPeerIds, inputStream)
        Log.i(TAG, "[startTalking] Передача голоса запущена targetCount=${targetPeerIds.size} elapsedMs=${System.currentTimeMillis() - startedAtMillis}")
        return true
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
     * Проигрывает входящий голосовой stream от участника комнаты и сообщает о завершении воспроизведения.
     */
    fun playIncoming(peerId: PeerId, inputStream: InputStream, onFinished: (PeerId) -> Unit) {
        Log.i(TAG, "[playIncoming] Запускаем входящий голосовой stream peerId=${peerId.value}")
        voicePlayer.play(peerId, inputStream, onFinished)
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
