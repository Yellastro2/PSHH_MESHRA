package com.yellastro.btration.voice

import android.util.Log
import com.yellastro.btration.domain.model.PeerId
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Управляет MVP-голосом комнаты: кодирует локальный PCM в Opus frames, отдает их VoiceTransport и декодирует входящий Opus для playback.
 */
class VoiceRuntime(
    private val voiceTransport: VoiceTransport,
    private val voiceCapture: PcmVoiceCapture,
    private val voicePlayer: PcmVoicePlayer,
    private val externalScope: CoroutineScope,
) {
    private fun interface VoiceFrameSender {
        /**
         * Отправляет уже закодированный voice frame выбранным адресатам.
         */
        fun send(targetPeerIds: Set<PeerId>, frame: VoiceFrame)
    }

    private var isTalking = false
    private var activeTalkingOriginPeerId: PeerId? = null
    private var activeTalkingTargetPeerIds: Set<PeerId> = emptySet()
    private var activeFrameSender: VoiceFrameSender? = null
    private var activeOutgoingEncoder: OpusVoiceEncoder? = null
    private var outgoingVoiceSequence = 0L
    private val activeFrameSessions = ConcurrentHashMap<PeerId, ActiveFrameSession>()
    private val frameSessionLock = Any()
    private val outgoingEncoderLock = Any()

    /**
     * Начинает передачу микрофона от исходного участника выбранным адресатам через текущий VoiceTransport.
     */
    fun startTalking(originPeerId: PeerId, targetPeerIds: Set<PeerId>): Boolean {
        return startTalking(
            originPeerId = originPeerId,
            targetPeerIds = targetPeerIds,
            sendFrame = { peerIds, frame -> voiceTransport.sendFrameToPeers(peerIds, frame) },
        )
    }

    /**
     * Начинает передачу микрофона и отдает закодированные Opus frames наружу через переданный sender.
     */
    fun startTalking(
        originPeerId: PeerId,
        targetPeerIds: Set<PeerId>,
        sendFrame: (targetPeerIds: Set<PeerId>, frame: VoiceFrame) -> Unit,
    ): Boolean {
        if (isTalking) {
            Log.i(TAG, "[startTalking] Передача голоса уже активна")
            return true
        }
        if (targetPeerIds.isEmpty()) {
            Log.i(TAG, "[startTalking] Некому передавать голос, участники отсутствуют")
            return false
        }

        val startedAtMillis = System.currentTimeMillis()
        val encoder = runCatching { OpusVoiceEncoder() }
            .onFailure { cause ->
                Log.w(TAG, "[startTalking] Не удалось создать Opus encoder: ${cause.message}", cause)
            }
            .getOrNull()
            ?: return false
        Log.i(TAG, "[startTalking] Начинаем запуск голосовой передачи opus-frame-mode targetCount=${targetPeerIds.size}")
        outgoingVoiceSequence = 0L
        activeTalkingOriginPeerId = originPeerId
        activeTalkingTargetPeerIds = targetPeerIds
        activeFrameSender = object : VoiceFrameSender {
            /**
             * Передает frame в callback, выбранный RoomRuntime для текущего transport режима.
             */
            override fun send(targetPeerIds: Set<PeerId>, frame: VoiceFrame) {
                sendFrame(targetPeerIds, frame)
            }
        }
        synchronized(outgoingEncoderLock) {
            activeOutgoingEncoder?.close()
            activeOutgoingEncoder = encoder
        }
        isTalking = true
        val started = runCatching {
            voiceCapture.startFrames { pcmBytes ->
                sendLocalVoiceFrame(originPeerId, targetPeerIds, pcmBytes, isFinal = false)
            }
        }
            .onFailure { cause ->
                Log.w(TAG, "[startTalking] Не удалось запустить захват микрофона: ${cause.message}", cause)
            }
            .isSuccess
        if (!started) {
            isTalking = false
            activeTalkingOriginPeerId = null
            activeTalkingTargetPeerIds = emptySet()
            activeFrameSender = null
            synchronized(outgoingEncoderLock) {
                if (activeOutgoingEncoder === encoder) {
                    activeOutgoingEncoder = null
                }
                encoder.close()
            }
            return false
        }
        Log.i(TAG, "[startTalking] Передача голоса opus-frame-mode запущена targetCount=${targetPeerIds.size} elapsedMs=${System.currentTimeMillis() - startedAtMillis}")
        return true
    }

    /**
     * Останавливает локальную передачу микрофона.
     */
    fun stopTalking() {
        if (!isTalking) {
            return
        }
        val originPeerId = activeTalkingOriginPeerId
        val targetPeerIds = activeTalkingTargetPeerIds
        val frameSender = activeFrameSender
        voiceCapture.stop()
        isTalking = false
        if (originPeerId != null && targetPeerIds.isNotEmpty() && frameSender != null) {
            sendLocalVoiceFrame(originPeerId, targetPeerIds, pcmBytes = ByteArray(0), isFinal = true, frameSender = frameSender)
        }
        synchronized(outgoingEncoderLock) {
            activeOutgoingEncoder?.close()
            activeOutgoingEncoder = null
        }
        activeTalkingOriginPeerId = null
        activeTalkingTargetPeerIds = emptySet()
        activeFrameSender = null
        Log.i(TAG, "[stopTalking] Передача голоса opus-frame-mode остановлена")
    }

    /**
     * Принимает короткий Opus voice frame, проверяет маршрут, пишет декодированный PCM в player и при необходимости ретранслирует frame без decode/re-encode.
     */
    fun playIncomingFrame(
        directPeerId: PeerId,
        frame: VoiceFrame,
        resolveRelayTargets: (directPeerId: PeerId, originPeerId: PeerId) -> Set<PeerId>?,
        onStarted: (PeerId) -> Unit,
        onFinished: (PeerId) -> Unit,
    ) {
        val relayTargetPeerIds = resolveRelayTargets(directPeerId, frame.originPeerId)
        if (relayTargetPeerIds == null) {
            Log.w(
                TAG,
                "[playIncomingFrame] Voice frame отклонен directPeerId=${directPeerId.value} originPeerId=${frame.originPeerId.value} sequence=${frame.sequence}",
            )
            return
        }

        if (relayTargetPeerIds.isNotEmpty()) {
            voiceTransport.sendFrameToPeers(relayTargetPeerIds, frame)
        }

        if (frame.isFinal) {
            finishFrameSession(frame.originPeerId)
            return
        }
        if (frame.encodedBytes.isEmpty()) {
            return
        }

        val session = synchronized(frameSessionLock) {
            activeFrameSessions[frame.originPeerId]
                ?: startFrameSession(frame.originPeerId, onStarted, onFinished)
                    .also { session -> activeFrameSessions[frame.originPeerId] = session }
        }
        if (!session.enqueue(frame.encodedBytes)) {
            synchronized(frameSessionLock) {
                if (activeFrameSessions[frame.originPeerId] === session) {
                    activeFrameSessions.remove(frame.originPeerId)
                }
            }
        }
    }

    /**
     * Останавливает локальную передачу и входящие Opus frame-сессии.
     */
    fun stopAll() {
        stopTalking()
        synchronized(frameSessionLock) {
            activeFrameSessions.values.forEach { frameSession -> frameSession.cancel() }
            activeFrameSessions.clear()
        }
        synchronized(outgoingEncoderLock) {
            activeOutgoingEncoder?.close()
            activeOutgoingEncoder = null
        }
        voicePlayer.stopAll()
        Log.i(TAG, "[stopAll] Все голосовые frame-сессии остановлены")
    }

    /**
     * Завершает входящую frame-сессию участника, если final-frame потерялся в UDP media-plane.
     */
    fun finishIncomingFrameSession(originPeerId: PeerId) {
        finishFrameSession(originPeerId)
    }

    /**
     * Кодирует локальный PCM-фрейм в Opus, отправляет адресатам и двигает sequence локальной передачи.
     */
    private fun sendLocalVoiceFrame(
        originPeerId: PeerId,
        targetPeerIds: Set<PeerId>,
        pcmBytes: ByteArray,
        isFinal: Boolean,
    ) {
        val frameSender = activeFrameSender
        if (frameSender == null) {
            Log.w(TAG, "[sendLocalVoiceFrame] Нет активного отправителя voice frame")
            return
        }
        sendLocalVoiceFrame(originPeerId, targetPeerIds, pcmBytes, isFinal, frameSender)
    }

    /**
     * Кодирует локальный PCM-фрейм в Opus, передает его наружу через sender и двигает sequence локальной передачи.
     */
    private fun sendLocalVoiceFrame(
        originPeerId: PeerId,
        targetPeerIds: Set<PeerId>,
        pcmBytes: ByteArray,
        isFinal: Boolean,
        frameSender: VoiceFrameSender,
    ) {
        val sequence = outgoingVoiceSequence++
        val encodedBytes = if (isFinal) {
            ByteArray(0)
        } else {
            synchronized(outgoingEncoderLock) {
                activeOutgoingEncoder?.encode(pcmBytes)
            } ?: run {
                Log.w(TAG, "[sendLocalVoiceFrame] Нет активного Opus encoder для voice frame sequence=$sequence")
                return
            }
        }
        frameSender.send(
            targetPeerIds,
            VoiceFrame(
                originPeerId = originPeerId,
                sequence = sequence,
                encodedBytes = encodedBytes,
                isFinal = isFinal,
            ),
        )
        if (sequence == FIRST_VOICE_FRAME_SEQUENCE || isFinal) {
            Log.i(
                TAG,
                "[sendLocalVoiceFrame] Opus voice frame отправлен originPeerId=${originPeerId.value} targetCount=${targetPeerIds.size} sequence=$sequence pcmBytes=${pcmBytes.size} encodedBytes=${encodedBytes.size} final=$isFinal",
            )
        }
    }

    /**
     * Создает pipe-сессию для входящих Opus voice frames одного originPeerId и запускает AudioTrack player.
     */
    private fun startFrameSession(
        originPeerId: PeerId,
        onStarted: (PeerId) -> Unit,
        onFinished: (PeerId) -> Unit,
    ): ActiveFrameSession {
        val inputStream = PipedInputStream(PcmVoiceConfig.PIPE_BUFFER_BYTES)
        val outputStream = PipedOutputStream(inputStream)
        val session = ActiveFrameSession(
            originPeerId = originPeerId,
            outputStream = outputStream,
            decoder = OpusVoiceDecoder(),
            externalScope = externalScope,
        )
        onStarted(originPeerId)
        voicePlayer.play(originPeerId, inputStream) { finishedPeerId ->
            activeFrameSessions.remove(finishedPeerId)
            onFinished(finishedPeerId)
        }
        Log.i(TAG, "[startFrameSession] Входящая frame-сессия запущена originPeerId=${originPeerId.value}")
        return session
    }

    /**
     * Закрывает входящую Opus frame-сессию, чтобы player доиграл PCM-хвост и вызвал onFinished.
     */
    private fun finishFrameSession(originPeerId: PeerId) {
        synchronized(frameSessionLock) {
            activeFrameSessions.remove(originPeerId)
        }?.close()
        Log.i(TAG, "[finishFrameSession] Входящая frame-сессия завершена originPeerId=${originPeerId.value}")
    }

    /**
     * Активная pipe-сессия входящих Opus voice frames одного участника.
     */
    private class ActiveFrameSession(
        private val originPeerId: PeerId,
        private val outputStream: PipedOutputStream,
        private val decoder: OpusVoiceDecoder,
        externalScope: CoroutineScope,
    ) {
        private val encodedFrames = Channel<ByteArray>(Channel.BUFFERED)
        private val job = externalScope.launch(Dispatchers.IO) {
            runCatching {
                for (encodedBytes in encodedFrames) {
                    val pcmBytes = decoder.decode(encodedBytes)
                    outputStream.write(pcmBytes)
                }
            }.onFailure { cause ->
                if (cause !is CancellationException) {
                    Log.w(TAG, "[processFrames] Входящая Opus frame-сессия оборвалась originPeerId=${originPeerId.value}: ${cause.message}", cause)
                }
            }
            runCatching { decoder.close() }
            runCatching { outputStream.close() }
        }

        /**
         * Ставит Opus frame в очередь последовательного decode/write и возвращает false, если session уже закрыта.
         */
        fun enqueue(encodedBytes: ByteArray): Boolean {
            val result = encodedFrames.trySend(encodedBytes)
            if (result.isFailure) {
                Log.w(TAG, "[enqueue] Не удалось поставить Opus voice frame в очередь originPeerId=${originPeerId.value}")
                close()
                return false
            }
            return true
        }

        /**
         * Закрывает очередь, позволяя decoder доработать уже принятые frames, а player увидеть EOF и доиграть хвост.
         */
        fun close() {
            encodedFrames.close()
        }

        /**
         * Принудительно останавливает decode/write без ожидания хвоста.
         */
        fun cancel() {
            job.cancel()
            encodedFrames.close()
            runCatching { decoder.close() }
            runCatching { outputStream.close() }
        }
    }

    private companion object {
        private const val TAG = "VoiceRuntime"
        private const val FIRST_VOICE_FRAME_SEQUENCE = 0L
    }
}
