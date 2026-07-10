package com.yellastro.btration.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Захватывает микрофон в PCM16 mono из voice communication источника короткими 20 ms порциями для Nearby STREAM payload.
 */
class PcmVoiceCapture(
    private val externalScope: CoroutineScope,
) {
    private var activeCapture: ActiveCapture? = null

    /**
     * Запускает запись микрофона и возвращает InputStream, из которого Nearby будет читать PCM.
     */
    @SuppressLint("MissingPermission")
    fun start(): InputStream {
        stop()
        val minBufferSize = AudioRecord.getMinBufferSize(
            PcmVoiceConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            PcmVoiceConfig.AUDIO_FORMAT,
        )
        val readChunkBytes = PcmVoiceConfig.FRAME_BYTES * READ_CHUNK_FRAME_COUNT
        val audioRecordBufferSize = maxOf(minBufferSize, readChunkBytes)
        val inputStream = PipedInputStream(PcmVoiceConfig.PIPE_BUFFER_BYTES)
        val outputStream = PipedOutputStream(inputStream)
        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PcmVoiceConfig.SAMPLE_RATE_HZ)
                    .setEncoding(PcmVoiceConfig.AUDIO_FORMAT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(audioRecordBufferSize)
            .build()

        val job = externalScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(readChunkBytes)
            val captureStartedAtMillis = System.currentTimeMillis()
            var firstReadLogged = false
            var firstWriteLogged = false
            runCatching {
                audioRecord.startRecording()
                Log.i(
                    TAG,
                    "[start] Запись микрофона запущена source=VOICE_COMMUNICATION audioRecordBufferBytes=$audioRecordBufferSize readChunkBytes=$readChunkBytes pipeBufferBytes=${PcmVoiceConfig.PIPE_BUFFER_BYTES}",
                )
                while (isActive) {
                    val readBytes = audioRecord.read(buffer, 0, buffer.size)
                    if (readBytes > 0) {
                        if (!firstReadLogged) {
                            firstReadLogged = true
                            Log.i(
                                TAG,
                                "[start] Первый read микрофона readBytes=$readBytes elapsedMs=${System.currentTimeMillis() - captureStartedAtMillis}",
                            )
                        }
                        outputStream.write(buffer, 0, readBytes)
                        if (!firstWriteLogged) {
                            firstWriteLogged = true
                            Log.i(
                                TAG,
                                "[start] Первый write в pipe writeBytes=$readBytes elapsedMs=${System.currentTimeMillis() - captureStartedAtMillis}",
                            )
                        }
                    } else if (readBytes < 0) {
                        Log.w(TAG, "[start] AudioRecord вернул ошибку readBytes=$readBytes")
                        break
                    }
                }
            }.onFailure { cause ->
                Log.w(TAG, "[start] Запись микрофона оборвалась: ${cause.message}", cause)
            }
            closeAudioRecord(audioRecord)
            runCatching { outputStream.close() }
            Log.i(TAG, "[start] Запись микрофона остановлена")
        }

        activeCapture = ActiveCapture(audioRecord, outputStream, job)
        return inputStream
    }

    /**
     * Останавливает текущую запись микрофона, сохраняя read-side pipe открытым для дочитывания хвоста Nearby.
     */
    fun stop() {
        activeCapture?.close()
        activeCapture = null
    }

    /**
     * Закрывает AudioRecord без проброса ошибок Android audio stack.
     */
    private fun closeAudioRecord(audioRecord: AudioRecord) {
        runCatching { audioRecord.stop() }
        runCatching { audioRecord.release() }
    }

    /**
     * Активная запись и связанные с ней pipe-ресурсы.
     */
    private inner class ActiveCapture(
        private val audioRecord: AudioRecord,
        private val outputStream: PipedOutputStream,
        private val job: Job,
    ) : Closeable {
        /**
         * Останавливает coroutine, закрывает write-side pipe и освобождает AudioRecord без резкого закрытия InputStream.
         */
        override fun close() {
            Log.i(TAG, "[close] Останавливаем активную запись микрофона")
            job.cancel()
            closeAudioRecord(audioRecord)
            runCatching { outputStream.close() }
        }
    }

    private companion object {
        private const val TAG = "PcmVoiceCapture"
        private const val READ_CHUNK_FRAME_COUNT = 1
    }
}
