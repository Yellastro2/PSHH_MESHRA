package com.yellastro.btration.voice

import android.util.Log
import com.yellastro.btration.domain.model.PeerId
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Управляет per-room voice-профилем: capture/Opus encode, compact PTT session frames, decode и low-latency playback.
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
    private var activeOutgoingSessionId: Int? = null
    private var nextOutgoingSessionId = 0
    private var outgoingVoiceSequence = 0L
    @Volatile
    private var currentProfile = VoiceAudioProfile()
    private val activeFrameSessions = ConcurrentHashMap<PeerId, ActiveFrameSession>()
    private val frameSessionLock = Any()
    private val outgoingEncoderLock = Any()

    /**
     * Применяет неизменяемый voice-профиль новой комнаты и останавливает старые audio-сессии при реальной смене.
     */
    fun configure(profile: VoiceAudioProfile) {
        if (profile == currentProfile) {
            return
        }
        stopAll()
        currentProfile = profile
        Log.i(TAG, "[configure] Voice-профиль комнаты применен frameMs=${profile.frameDuration.millis}")
    }

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
        val profile = currentProfile
        val encoder = runCatching { OpusVoiceEncoder(profile) }
            .onFailure { cause ->
                Log.w(TAG, "[startTalking] Не удалось создать Opus encoder: ${cause.message}", cause)
            }
            .getOrNull()
            ?: return false
        Log.i(TAG, "[startTalking] Начинаем голосовую передачу targetCount=${targetPeerIds.size} frameMs=${profile.frameDuration.millis}")
        outgoingVoiceSequence = 0L
        activeOutgoingSessionId = nextOutgoingSessionId
        nextOutgoingSessionId = (nextOutgoingSessionId + 1) and UNSIGNED_SHORT_MASK
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
            voiceCapture.startFrames(profile) { pcmBytes ->
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
            activeOutgoingSessionId = null
            synchronized(outgoingEncoderLock) {
                if (activeOutgoingEncoder === encoder) {
                    activeOutgoingEncoder = null
                }
                encoder.close()
            }
            return false
        }
        Log.i(TAG, "[startTalking] Передача голоса запущена targetCount=${targetPeerIds.size} frameMs=${profile.frameDuration.millis} elapsedMs=${System.currentTimeMillis() - startedAtMillis}")
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
        activeOutgoingSessionId = null
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
            finishFrameSession(frame.originPeerId, expectedSessionId = frame.sessionId)
            return
        }
        if (frame.encodedBytes.isEmpty()) {
            return
        }

        val session = synchronized(frameSessionLock) {
            val existing = activeFrameSessions[frame.originPeerId]
            if (existing != null && existing.sessionId != frame.sessionId) {
                activeFrameSessions.remove(frame.originPeerId)
                existing.cancel()
                Log.i(
                    TAG,
                    "[playIncomingFrame] Входящая PTT-сессия заменена originPeerId=${frame.originPeerId.value} oldSessionId=${existing.sessionId} newSessionId=${frame.sessionId}",
                )
            }
            activeFrameSessions[frame.originPeerId]
                ?: startFrameSession(frame.originPeerId, frame.sessionId, onStarted, onFinished)
                    .also { newSession -> activeFrameSessions[frame.originPeerId] = newSession }
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
        finishFrameSession(originPeerId, expectedSessionId = null)
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
        val sequence = outgoingVoiceSequence
        outgoingVoiceSequence = (outgoingVoiceSequence + 1L) and UNSIGNED_SHORT_MASK.toLong()
        val sessionId = activeOutgoingSessionId
        if (sessionId == null) {
            Log.w(TAG, "[sendLocalVoiceFrame] Нет активного PTT session id sequence=$sequence")
            return
        }
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
                sessionId = sessionId,
                sequence = sequence,
                encodedBytes = encodedBytes,
                isFinal = isFinal,
            ),
        )
        if (sequence == FIRST_VOICE_FRAME_SEQUENCE || isFinal) {
            Log.i(
                TAG,
                "[sendLocalVoiceFrame] Opus voice frame отправлен originPeerId=${originPeerId.value} sessionId=$sessionId targetCount=${targetPeerIds.size} sequence=$sequence pcmBytes=${pcmBytes.size} encodedBytes=${encodedBytes.size} final=$isFinal",
            )
        }
    }

    /**
     * Создает короткую pipe/queue-сессию конкретного PTT session id и запускает player с room profile.
     */
    private fun startFrameSession(
        originPeerId: PeerId,
        sessionId: Int,
        onStarted: (PeerId) -> Unit,
        onFinished: (PeerId) -> Unit,
    ): ActiveFrameSession {
        val profile = currentProfile
        val inputStream = PipedInputStream(PcmVoiceConfig.pipeBufferBytes(profile))
        val outputStream = PipedOutputStream(inputStream)
        val session = ActiveFrameSession(
            originPeerId = originPeerId,
            sessionId = sessionId,
            outputStream = outputStream,
            decoder = OpusVoiceDecoder(),
            queueCapacity = encodedFrameQueueCapacity(profile),
            externalScope = externalScope,
        )
        onStarted(originPeerId)
        voicePlayer.play(originPeerId, inputStream, profile) { finishedPeerId ->
            if (activeFrameSessions.remove(finishedPeerId, session)) {
                onFinished(finishedPeerId)
            }
        }
        Log.i(TAG, "[startFrameSession] Входящая frame-сессия запущена originPeerId=${originPeerId.value} sessionId=$sessionId frameMs=${profile.frameDuration.millis}")
        return session
    }

    /**
     * Закрывает ожидаемую входящую PTT-сессию; поздний final старой session не закрывает новую.
     */
    private fun finishFrameSession(originPeerId: PeerId, expectedSessionId: Int?) {
        val session = synchronized(frameSessionLock) {
            val activeSession = activeFrameSessions[originPeerId]
            if (expectedSessionId != null && activeSession?.sessionId != expectedSessionId) {
                null
            } else {
                activeFrameSessions.remove(originPeerId)
            }
        }
        if (session == null) {
            Log.i(TAG, "[finishFrameSession] Входящая session уже отсутствует или заменена originPeerId=${originPeerId.value} expectedSessionId=$expectedSessionId")
            return
        }
        session.close()
        Log.i(TAG, "[finishFrameSession] Входящая frame-сессия завершена originPeerId=${originPeerId.value} sessionId=${session.sessionId}")
    }

    /**
     * Ограничивает очередь примерно 80 мс аудио независимо от выбранной длительности фрейма.
     */
    private fun encodedFrameQueueCapacity(profile: VoiceAudioProfile): Int {
        return (MAX_ENCODED_QUEUE_MILLIS / profile.frameDuration.millis).coerceAtLeast(MIN_ENCODED_QUEUE_FRAMES)
    }

    /**
     * Активная bounded queue/pipe-сессия одного участника и одного PTT session id.
     */
    private class ActiveFrameSession(
        private val originPeerId: PeerId,
        val sessionId: Int,
        private val outputStream: PipedOutputStream,
        private val decoder: OpusVoiceDecoder,
        queueCapacity: Int,
        externalScope: CoroutineScope,
    ) {
        private val encodedFrames = Channel<ByteArray>(
            capacity = queueCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
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
        private const val UNSIGNED_SHORT_MASK = 0xFFFF
        private const val MAX_ENCODED_QUEUE_MILLIS = 80
        private const val MIN_ENCODED_QUEUE_FRAMES = 2
    }
}
