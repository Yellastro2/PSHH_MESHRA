package com.yellastro.btration.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.yellastro.btration.domain.model.PeerId
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Проигрывает входящие PCM16 голосовые streams через AudioTrack в media-routing для громкой связи.
 */
class PcmVoicePlayer(
    private val externalScope: CoroutineScope,
) {
    private val activePlayers = ConcurrentHashMap<PeerId, ActivePlayer>()

    /**
     * Запускает воспроизведение входящего stream от указанного участника.
     */
    fun play(peerId: PeerId, inputStream: InputStream) {
        stop(peerId)
        val minBufferSize = AudioTrack.getMinBufferSize(
            PcmVoiceConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            PcmVoiceConfig.AUDIO_FORMAT,
        )
        val playBufferSize = maxOf(minBufferSize, PcmVoiceConfig.FRAME_BYTES * PLAY_BUFFER_FRAME_COUNT)
        val audioTrack = AudioTrack.Builder()
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
            .build()

        val job = externalScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(playBufferSize)
            runCatching {
                audioTrack.play()
                Log.i(TAG, "[play] Воспроизведение голосового stream запущено usage=MEDIA peerId=${peerId.value} bufferBytes=$playBufferSize")
                while (isActive) {
                    val readBytes = inputStream.read(buffer)
                    if (readBytes < 0) {
                        break
                    }
                    if (readBytes > 0) {
                        audioTrack.write(buffer, 0, readBytes)
                    }
                }
            }.onFailure { cause ->
                Log.w(TAG, "[play] Воспроизведение голосового stream оборвалось peerId=${peerId.value}: ${cause.message}", cause)
            }
            releaseAudioTrack(audioTrack)
            runCatching { inputStream.close() }
            activePlayers.remove(peerId)
            Log.i(TAG, "[play] Воспроизведение голосового stream остановлено peerId=${peerId.value}")
        }
        activePlayers[peerId] = ActivePlayer(inputStream, audioTrack, job)
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
        private const val PLAY_BUFFER_FRAME_COUNT = 8
    }
}
