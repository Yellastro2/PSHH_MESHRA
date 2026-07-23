package com.yellastro.btration.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.yellastro.btration.domain.model.PeerId
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Проигрывает PCM16 голос через low-latency AudioTrack порциями и буферами текущего room voice-профиля.
 */
class PcmVoicePlayer(
    private val externalScope: CoroutineScope,
) {
    private val activePlayers = ConcurrentHashMap<PeerId, ActivePlayer>()

    /**
     * Запускает воспроизведение участника с room-profile и не дает завершению старого player-а удалить новый.
     */
    fun play(
        peerId: PeerId,
        inputStream: InputStream,
        profile: VoiceAudioProfile,
        onFinished: (PeerId) -> Unit,
    ) {
        stop(peerId)
        val minBufferSize = AudioTrack.getMinBufferSize(
            PcmVoiceConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            PcmVoiceConfig.AUDIO_FORMAT,
        )
        val frameBytes = PcmVoiceConfig.frameBytes(profile)
        val playChunkBytes = frameBytes * PLAY_CHUNK_FRAME_COUNT
        val playBufferSize = maxOf(minBufferSize, frameBytes * PLAY_BUFFER_FRAME_COUNT)
        val audioTrackBuilder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PcmVoiceConfig.SAMPLE_RATE_HZ)
                    .setEncoding(PcmVoiceConfig.AUDIO_FORMAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(playBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioTrackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val audioTrack = audioTrackBuilder.build()

        lateinit var activePlayer: ActivePlayer
        val job = externalScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val buffer = ByteArray(playChunkBytes)
            val playStartedAtMillis = System.currentTimeMillis()
            var firstReadLogged = false
            var firstWriteLogged = false
            var reachedEndOfStream = false
            var writtenFrames = 0L
            runCatching {
                audioTrack.play()
                Log.i(TAG, "[play] Воспроизведение голосового stream запущено usage=MEDIA peerId=${peerId.value} frameMs=${profile.frameDuration.millis} bufferBytes=$playBufferSize readChunkBytes=$playChunkBytes")
                while (isActive) {
                    val readBytes = inputStream.read(buffer)
                    if (readBytes < 0) {
                        reachedEndOfStream = true
                        break
                    }
                    if (readBytes > 0) {
                        if (!firstReadLogged) {
                            firstReadLogged = true
                            Log.i(
                                TAG,
                                "[play] Первый read из входящего stream peerId=${peerId.value} readBytes=$readBytes elapsedMs=${System.currentTimeMillis() - playStartedAtMillis}",
                            )
                        }
                        val writtenBytes = audioTrack.write(buffer, 0, readBytes)
                        if (writtenBytes > 0) {
                            writtenFrames += writtenBytes / PcmVoiceConfig.BYTES_PER_SAMPLE
                            if (!firstWriteLogged) {
                                firstWriteLogged = true
                                Log.i(
                                    TAG,
                                    "[play] Первый write в AudioTrack peerId=${peerId.value} writeBytes=$writtenBytes elapsedMs=${System.currentTimeMillis() - playStartedAtMillis}",
                                )
                            }
                        } else {
                            Log.w(TAG, "[play] AudioTrack не принял аудио peerId=${peerId.value} writtenBytes=$writtenBytes")
                        }
                    }
                }
            }.onFailure { cause ->
                Log.w(TAG, "[play] Воспроизведение голосового stream оборвалось peerId=${peerId.value}: ${cause.message}", cause)
            }
            if (reachedEndOfStream) {
                drainPlaybackTail(audioTrack, peerId, writtenFrames)
            }
            releaseAudioTrack(audioTrack)
            runCatching { inputStream.close() }
            activePlayers.remove(peerId, activePlayer)
            Log.i(TAG, "[play] Воспроизведение голосового stream остановлено peerId=${peerId.value}")
            onFinished(peerId)
        }
        activePlayer = ActivePlayer(inputStream, audioTrack, job)
        activePlayers[peerId] = activePlayer
        job.start()
    }

    /**
     * Останавливает воспроизведение конкретного участника.
     */
    fun stop(peerId: PeerId) {
        activePlayers.remove(peerId)?.close()
    }

    /**
     * Останавливает все входящие голосовые streams.
     */
    fun stopAll() {
        activePlayers.keys.toList().forEach(::stop)
    }

    /**
     * Освобождает AudioTrack без проброса ошибок Android audio stack.
     */
    private fun releaseAudioTrack(audioTrack: AudioTrack) {
        runCatching { audioTrack.stop() }
        runCatching { audioTrack.release() }
    }

    /**
     * Даёт AudioTrack доиграть PCM-байты, которые уже записаны, но ещё не вышли в динамик после EOF stream.
     */
    private suspend fun drainPlaybackTail(audioTrack: AudioTrack, peerId: PeerId, writtenFrames: Long) {
        val playedFrames = audioTrack.playbackHeadPosition.toLong()
        val remainingFrames = (writtenFrames - playedFrames).coerceAtLeast(0L)
        val remainingMs = remainingFrames * 1_000L / PcmVoiceConfig.SAMPLE_RATE_HZ
        val waitMs = (remainingMs + PLAYBACK_DRAIN_MARGIN_MS).coerceAtMost(MAX_PLAYBACK_DRAIN_WAIT_MS)
        if (waitMs <= 0L) {
            return
        }
        Log.i(
            TAG,
            "[drainPlaybackTail] Доигрываем хвост голосового stream peerId=${peerId.value} writtenFrames=$writtenFrames playedFrames=$playedFrames remainingMs=$remainingMs waitMs=$waitMs",
        )
        delay(waitMs)
    }

    /**
     * Активное воспроизведение одного входящего голосового stream.
     */
    private inner class ActivePlayer(
        private val inputStream: InputStream,
        private val audioTrack: AudioTrack,
        private val job: Job,
    ) : Closeable {
        /**
         * Останавливает coroutine, закрывает input stream и освобождает AudioTrack.
         */
        override fun close() {
            Log.i(TAG, "[close] Останавливаем воспроизведение голосового stream")
            job.cancel()
            runCatching { inputStream.close() }
            releaseAudioTrack(audioTrack)
        }
    }

    private companion object {
        private const val TAG = "PcmVoicePlayer"
        private const val PLAY_BUFFER_FRAME_COUNT = 4
        private const val PLAY_CHUNK_FRAME_COUNT = 1
        private const val PLAYBACK_DRAIN_MARGIN_MS = 60L
        private const val MAX_PLAYBACK_DRAIN_WAIT_MS = 600L
    }
}
