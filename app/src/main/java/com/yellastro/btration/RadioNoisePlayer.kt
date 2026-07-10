package com.yellastro.btration

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.abs
import kotlin.random.Random

class RadioNoisePlayer(
    private val sampleRate: Int = 16_000,
    private val volume: Float = 0.12f,
) {

    @Volatile
    private var isRunning = false

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    fun start() {
        if (isRunning) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        if (minBufferSize <= 0) {
            throw IllegalStateException(
                "AudioTrack buffer size error: $minBufferSize"
            )
        }

        val bufferSize = minBufferSize * 2

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.setVolume(volume.coerceIn(0f, 1f))

        audioTrack = track
        isRunning = true

        playbackThread = Thread({
            generateAndPlay(track, bufferSize / 2)
        }, "RadioNoiseThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        isRunning = false

        playbackThread?.interrupt()

        try {
            playbackThread?.join(300)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        playbackThread = null

        audioTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
            }

            runCatching {
                track.flush()
                track.release()
            }
        }

        audioTrack = null
    }

    private fun generateAndPlay(
        track: AudioTrack,
        sampleCount: Int,
    ) {
        val buffer = ShortArray(sampleCount)

        var previousWhite = 0.0
        var filteredNoise = 0.0
        var envelope = 0.75

        track.play()

        while (isRunning && !Thread.currentThread().isInterrupted) {
            for (i in buffer.indices) {
                /*
                 * Белый шум.
                 */
                val white = Random.nextDouble(-1.0, 1.0)

                /*
                 * Убираем глухой низкочастотный гул.
                 * Получается более сухое радиошипение.
                 */
                val highPass = white - previousWhite * 0.94
                previousWhite = white

                /*
                 * Немного сглаживаем самые резкие цифровые пики.
                 */
                filteredNoise += 0.35 * (highPass - filteredNoise)

                /*
                 * Медленное случайное плавание громкости.
                 */
                envelope += Random.nextDouble(-0.002, 0.002)
                envelope = envelope.coerceIn(0.55, 1.0)

                /*
                 * Редкие короткие щелчки и потрескивания.
                 */
                val crackle = when {
                    Random.nextInt(12_000) == 0 ->
                        Random.nextDouble(-1.0, 1.0) * 1.8

                    Random.nextInt(3_500) == 0 ->
                        Random.nextDouble(-1.0, 1.0) * 0.45

                    else -> 0.0
                }

                /*
                 * Небольшое ограничение сигнала создаёт характер
                 * дешёвой аналоговой рации.
                 */
                var sample = (filteredNoise * envelope + crackle) * 10_000.0

                if (abs(sample) > 7_000.0) {
                    sample = if (sample > 0) 7_000.0 else -7_000.0
                }

                buffer[i] = sample
                    .toInt()
                    .coerceIn(
                        Short.MIN_VALUE.toInt(),
                        Short.MAX_VALUE.toInt(),
                    )
                    .toShort()
            }

            val written = track.write(
                buffer,
                0,
                buffer.size,
                AudioTrack.WRITE_BLOCKING,
            )

            if (written < 0) {
                break
            }
        }
    }
}