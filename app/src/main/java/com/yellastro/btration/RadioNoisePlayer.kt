package com.yellastro.btration

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Проигрывает фоновый радиошум через небольшой media stream без аудиофокуса и генерации PCM в рабочем цикле.
 */
class RadioNoisePlayer(
    private val externalScope: CoroutineScope,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE_HZ,
    private val volume: Float = DEFAULT_VOLUME,
) {
    private val noiseSamples by lazy(::generateNoiseSamples)
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    /**
     * Запускает радиошум, если он еще не воспроизводится.
     */
    @Synchronized
    fun start() {
        if (audioTrack != null) {
            return
        }

        val track = buildAudioTrack()
        val samples = noiseSamples
        val job = externalScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var sampleOffset = 0
            try {
                while (isActive) {
                    val samplesToWrite = minOf(NOISE_WRITE_CHUNK_SAMPLES, samples.size - sampleOffset)
                    val writtenSamples = track.write(
                        samples,
                        sampleOffset,
                        samplesToWrite,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    check(writtenSamples > 0) {
                        "AudioTrack радиошума вернул ошибку записи: $writtenSamples"
                    }
                    sampleOffset = (sampleOffset + writtenSamples) % samples.size
                }
            } catch (cause: Throwable) {
                if (cause !is CancellationException && isActive) {
                    Log.w(TAG, "[start] Воспроизведение радиошума оборвалось: ${cause.message}", cause)
                }
            } finally {
                clearPlayback(track)
                releaseAudioTrack(track)
            }
        }

        try {
            track.setVolume(volume.coerceIn(0f, 1f))
            track.play()
            audioTrack = track
            playbackJob = job
            job.start()
        } catch (cause: Throwable) {
            job.cancel()
            releaseAudioTrack(track)
            throw cause
        }
        Log.i(
            TAG,
            "[start] Радиошум запущен usage=MEDIA mode=STREAM sampleRate=$sampleRate bufferBytes=${track.bufferSizeInFrames * Short.SIZE_BYTES} volume=$volume",
        )
    }

    /**
     * Останавливает радиошум и освобождает только принадлежащий ему AudioTrack.
     */
    @Synchronized
    fun stop() {
        val track = audioTrack ?: return
        audioTrack = null
        playbackJob?.cancel()
        playbackJob = null
        releaseAudioTrack(track)
        Log.i(TAG, "[stop] Радиошум остановлен")
    }

    /**
     * Создает потоковый media-track с небольшим системно допустимым буфером.
     */
    private fun buildAudioTrack(): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferSize > 0) {
            "Не удалось определить минимальный буфер AudioTrack радиошума: $minBufferSize"
        }
        val bufferSize = maxOf(minBufferSize, NOISE_WRITE_CHUNK_SAMPLES * Short.SIZE_BYTES * BUFFER_CHUNK_COUNT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Не удалось инициализировать AudioTrack радиошума bufferBytes=$bufferSize")
        }
        return track
    }

    /**
     * Убирает завершившееся воспроизведение из текущего состояния, не задевая новый track.
     */
    @Synchronized
    private fun clearPlayback(track: AudioTrack) {
        if (audioTrack === track) {
            audioTrack = null
            playbackJob = null
        }
    }

    /**
     * Безопасно останавливает и освобождает AudioTrack радиошума.
     */
    private fun releaseAudioTrack(track: AudioTrack) {
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
        }
        runCatching { track.release() }
    }

    /**
     * Один раз создает длинный неповторяющийся на слух PCM-фрагмент сухого радиошипения с редкими щелчками.
     */
    private fun generateNoiseSamples(): ShortArray {
        val random = Random(NOISE_RANDOM_SEED)
        val samples = ShortArray(sampleRate * NOISE_DURATION_SECONDS)
        var previousWhite = 0.0
        var smoothedHighPass = 0.0
        var envelope = 0.75

        for (index in samples.indices) {
            val white = random.nextDouble(-1.0, 1.0)
            val highPass = white - previousWhite * HIGH_PASS_FEEDBACK
            previousWhite = white
            smoothedHighPass += SMOOTHING_FACTOR * (highPass - smoothedHighPass)
            envelope = (envelope + random.nextDouble(-ENVELOPE_STEP, ENVELOPE_STEP))
                .coerceIn(MIN_ENVELOPE, MAX_ENVELOPE)

            val crackle = when {
                random.nextInt(12_000) == 0 -> random.nextDouble(-1.0, 1.0) * 1.8
                random.nextInt(3_500) == 0 -> random.nextDouble(-1.0, 1.0) * 0.45
                else -> 0.0
            }
            var sample = (smoothedHighPass * envelope + crackle) * SAMPLE_GAIN
            if (abs(sample) > SAMPLE_LIMIT) {
                sample = if (sample > 0) SAMPLE_LIMIT else -SAMPLE_LIMIT
            }
            samples[index] = sample.toInt().toShort()
        }
        return samples
    }

    private companion object {
        private const val TAG = "RadioNoisePlayer"
        private const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        private const val DEFAULT_VOLUME = 0.10f
        private const val NOISE_DURATION_SECONDS = 4
        private const val NOISE_RANDOM_SEED = 0x425452
        private const val NOISE_FRAME_MILLIS = 20
        private const val NOISE_WRITE_CHUNK_SAMPLES = DEFAULT_SAMPLE_RATE_HZ * NOISE_FRAME_MILLIS / 1_000
        private const val BUFFER_CHUNK_COUNT = 4
        private const val HIGH_PASS_FEEDBACK = 0.94
        private const val SMOOTHING_FACTOR = 0.35
        private const val ENVELOPE_STEP = 0.002
        private const val MIN_ENVELOPE = 0.55
        private const val MAX_ENVELOPE = 1.0
        private const val SAMPLE_GAIN = 26_000.0
        private const val SAMPLE_LIMIT = 18_000.0
    }
}
