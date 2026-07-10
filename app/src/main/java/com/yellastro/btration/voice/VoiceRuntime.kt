package com.yellastro.btration.voice

import android.util.Log
import com.yellastro.btration.data.nearby.NearbyTransport
import com.yellastro.btration.domain.model.PeerId
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Управляет MVP-голосом комнаты: кодирует локальный PCM в Opus BYTES-фреймы, проигрывает входящий Opus и ретранслирует его через host.
 */
class VoiceRuntime(
    private val nearbyTransport: NearbyTransport,
    private val voiceCapture: PcmVoiceCapture,
    private val voicePlayer: PcmVoicePlayer,
    private val externalScope: CoroutineScope,
) {
    private var isTalking = false
    private var activeTalkingOriginPeerId: PeerId? = null
    private var activeTalkingTargetPeerIds: Set<PeerId> = emptySet()
    private var activeOutgoingEncoder: OpusVoiceEncoder? = null
    private var outgoingVoiceSequence = 0L
    private val incomingStreamIds = AtomicLong(0L)
    private val activeIncomingStreams = ConcurrentHashMap<Long, ActiveIncomingStream>()
    private val activeFrameSessions = ConcurrentHashMap<PeerId, ActiveFrameSession>()
    private val frameSessionLock = Any()
    private val outgoingEncoderLock = Any()

    /**
     * Начинает передачу микрофона от исходного участника выбранным адресатам через короткие Nearby BYTES-фреймы.
     */
    fun startTalking(originPeerId: PeerId, targetPeerIds: Set<PeerId>): Boolean {
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
        voiceCapture.stop()
        isTalking = false
        if (originPeerId != null && targetPeerIds.isNotEmpty()) {
            sendLocalVoiceFrame(originPeerId, targetPeerIds, pcmBytes = ByteArray(0), isFinal = true)
        }
        synchronized(outgoingEncoderLock) {
            activeOutgoingEncoder?.close()
            activeOutgoingEncoder = null
        }
        activeTalkingOriginPeerId = null
        activeTalkingTargetPeerIds = emptySet()
        Log.i(TAG, "[stopTalking] Передача голоса opus-frame-mode остановлена")
    }

    /**
     * Читает исходного автора stream, проверяет маршрут через callback, локально играет PCM и при необходимости ретранслирует его.
     */
    fun playIncoming(
        directPeerId: PeerId,
        inputStream: InputStream,
        resolveRelayTargets: (directPeerId: PeerId, originPeerId: PeerId) -> Set<PeerId>?,
        onStarted: (PeerId) -> Unit,
        onFinished: (PeerId) -> Unit,
    ) {
        val incomingStreamId = incomingStreamIds.incrementAndGet()
        val activeIncomingStream = ActiveIncomingStream(inputStream)
        val job = externalScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var inputOwnedByJob = true
            var startedOriginPeerId: PeerId? = null
            var completionOwnedByPlayer = false
            try {
                val originPeerId = VoiceStreamCodec.readOriginPeerId(inputStream)
                val relayTargetPeerIds = resolveRelayTargets(directPeerId, originPeerId)
                if (relayTargetPeerIds == null) {
                    Log.w(
                        TAG,
                        "[playIncoming] Голосовой stream отклонен directPeerId=${directPeerId.value} originPeerId=${originPeerId.value}",
                    )
                    return@launch
                }

                Log.i(
                    TAG,
                    "[playIncoming] Голосовой stream принят directPeerId=${directPeerId.value} originPeerId=${originPeerId.value} relayTargetCount=${relayTargetPeerIds.size}",
                )
                onStarted(originPeerId)
                startedOriginPeerId = originPeerId
                if (relayTargetPeerIds.isEmpty()) {
                    voicePlayer.play(originPeerId, inputStream, onFinished)
                    completionOwnedByPlayer = true
                    inputOwnedByJob = false
                } else {
                    relayAndPlay(
                        originPeerId = originPeerId,
                        sourceInputStream = inputStream,
                        relayTargetPeerIds = relayTargetPeerIds,
                        activeIncomingStream = activeIncomingStream,
                        onFinished = onFinished,
                        onPlayerStarted = { completionOwnedByPlayer = true },
                    )
                }
            } catch (cause: Throwable) {
                if (isActive) {
                    Log.w(TAG, "[playIncoming] Не удалось обработать входящий голосовой stream: ${cause.message}", cause)
                }
                if (!completionOwnedByPlayer) {
                    startedOriginPeerId?.let(onFinished)
                }
            } finally {
                activeIncomingStreams.remove(incomingStreamId)
                if (inputOwnedByJob) {
                    runCatching { inputStream.close() }
                }
            }
        }
        activeIncomingStream.attachJob(job)
        activeIncomingStreams[incomingStreamId] = activeIncomingStream
        job.start()
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
            nearbyTransport.sendVoiceFrameToPeers(relayTargetPeerIds, frame)
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
     * Останавливает локальную передачу, старые входящие streams и новые Opus frame-сессии.
     */
    fun stopAll() {
        stopTalking()
        activeIncomingStreams.values.forEach { activeStream -> activeStream.close() }
        activeIncomingStreams.clear()
        synchronized(frameSessionLock) {
            activeFrameSessions.values.forEach { frameSession -> frameSession.cancel() }
            activeFrameSessions.clear()
        }
        synchronized(outgoingEncoderLock) {
            activeOutgoingEncoder?.close()
            activeOutgoingEncoder = null
        }
        voicePlayer.stopAll()
        Log.i(TAG, "[stopAll] Все голосовые streams остановлены")
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
        nearbyTransport.sendVoiceFrameToPeers(
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
     * Разделяет входящий PCM на локальное воспроизведение и новый Nearby stream для остальных участников.
     */
    private suspend fun relayAndPlay(
        originPeerId: PeerId,
        sourceInputStream: InputStream,
        relayTargetPeerIds: Set<PeerId>,
        activeIncomingStream: ActiveIncomingStream,
        onFinished: (PeerId) -> Unit,
        onPlayerStarted: () -> Unit,
    ) {
        val localInputStream = PipedInputStream(PcmVoiceConfig.PIPE_BUFFER_BYTES)
        val localOutputStream = PipedOutputStream(localInputStream)
        val relayInputStream = PipedInputStream(PcmVoiceConfig.PIPE_BUFFER_BYTES)
        val relayOutputStream = PipedOutputStream(relayInputStream)
        activeIncomingStream.registerBranch(localInputStream)
        activeIncomingStream.registerBranch(relayInputStream)
        var localBranchOpen = true
        var relayBranchOpen = true

        try {
            voicePlayer.play(originPeerId, localInputStream, onFinished)
            onPlayerStarted()
            nearbyTransport.sendStreamToPeers(relayTargetPeerIds, originPeerId, relayInputStream)
            Log.i(
                TAG,
                "[relayAndPlay] Host начал ретрансляцию originPeerId=${originPeerId.value} relayTargetCount=${relayTargetPeerIds.size}",
            )

            val buffer = ByteArray(PcmVoiceConfig.FRAME_BYTES)
            while (currentCoroutineContext().isActive && (localBranchOpen || relayBranchOpen)) {
                val readBytes = sourceInputStream.read(buffer)
                if (readBytes < 0) {
                    break
                }
                if (readBytes == 0) {
                    continue
                }
                if (localBranchOpen) {
                    localBranchOpen = writeBranch(localOutputStream, buffer, readBytes, "локальное воспроизведение")
                }
                if (relayBranchOpen) {
                    relayBranchOpen = writeBranch(relayOutputStream, buffer, readBytes, "ретрансляция")
                }
            }
        } finally {
            runCatching { sourceInputStream.close() }
            runCatching { localOutputStream.close() }
            runCatching { relayOutputStream.close() }
            Log.i(TAG, "[relayAndPlay] Host завершил ретрансляцию originPeerId=${originPeerId.value}")
        }
    }

    /**
     * Пишет один PCM-фрагмент в ветку tee и отключает только эту ветку при ошибке потребителя.
     */
    private fun writeBranch(outputStream: PipedOutputStream, buffer: ByteArray, byteCount: Int, branchName: String): Boolean {
        return runCatching {
            outputStream.write(buffer, 0, byteCount)
            true
        }.onFailure { cause ->
            Log.w(TAG, "[writeBranch] Ветка $branchName закрыта: ${cause.message}")
            runCatching { outputStream.close() }
        }.getOrDefault(false)
    }

    /**
     * Активная задача чтения входящего stream, которую можно остановить закрытием источника.
     */
    private class ActiveIncomingStream(
        private val inputStream: InputStream,
    ) {
        private val branchInputStreams = CopyOnWriteArrayList<InputStream>()
        private var job: Job? = null

        /**
         * Привязывает coroutine обработки после ее создания в ленивом состоянии.
         */
        fun attachJob(job: Job) {
            this.job = job
        }

        /**
         * Регистрирует вход pipe-ветки, чтобы stopAll мог разблокировать соответствующий writer.
         */
        fun registerBranch(inputStream: InputStream) {
            branchInputStreams += inputStream
        }

        /**
         * Останавливает обработку и закрывает источник с pipe-ветками для разблокировки read/write.
         */
        fun close() {
            job?.cancel()
            runCatching { inputStream.close() }
            branchInputStreams.forEach { branchInputStream -> runCatching { branchInputStream.close() } }
            branchInputStreams.clear()
        }
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
