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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Управляет MVP-голосом комнаты: отправляет локальный PCM, проигрывает входящий и ретранслирует его через host.
 */
class VoiceRuntime(
    private val nearbyTransport: NearbyTransport,
    private val voiceCapture: PcmVoiceCapture,
    private val voicePlayer: PcmVoicePlayer,
    private val externalScope: CoroutineScope,
) {
    private var isTalking = false
    private val incomingStreamIds = AtomicLong(0L)
    private val activeIncomingStreams = ConcurrentHashMap<Long, ActiveIncomingStream>()

    /**
     * Начинает передачу микрофона от исходного участника выбранным адресатам и возвращает true при реальном старте.
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
        Log.i(TAG, "[startTalking] Начинаем запуск голосовой передачи targetCount=${targetPeerIds.size}")
        val inputStream = runCatching { voiceCapture.start() }
            .onFailure { cause ->
                Log.w(TAG, "[startTalking] Не удалось запустить захват микрофона: ${cause.message}", cause)
            }
            .getOrNull()
            ?: return false
        isTalking = true
        nearbyTransport.sendStreamToPeers(targetPeerIds, originPeerId, inputStream)
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
     * Останавливает локальную передачу и все входящие голосовые streams.
     */
    fun stopAll() {
        stopTalking()
        activeIncomingStreams.values.forEach { activeStream -> activeStream.close() }
        activeIncomingStreams.clear()
        voicePlayer.stopAll()
        Log.i(TAG, "[stopAll] Все голосовые streams остановлены")
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

    private companion object {
        private const val TAG = "VoiceRuntime"
    }
}
