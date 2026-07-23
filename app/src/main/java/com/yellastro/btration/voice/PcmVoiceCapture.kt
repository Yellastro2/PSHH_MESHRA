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
 * Захватывает PCM16 mono из voice communication источника порциями выбранного room voice-профиля.
 */
class PcmVoiceCapture(
    private val externalScope: CoroutineScope,
) {
    private var activeCapture: ActiveCapture? = null

    /**
     * Запускает legacy stream-запись с указанным профилем и возвращает PCM InputStream.
     */
    @SuppressLint("MissingPermission")
    fun start(profile: VoiceAudioProfile = VoiceAudioProfile()): InputStream {
        stop()
        val minBufferSize = AudioRecord.getMinBufferSize(
            PcmVoiceConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            PcmVoiceConfig.AUDIO_FORMAT,
        )
        val readChunkBytes = PcmVoiceConfig.frameBytes(profile) * READ_CHUNK_FRAME_COUNT
        val audioRecordBufferSize = maxOf(minBufferSize, readChunkBytes)
        val pipeBufferBytes = PcmVoiceConfig.pipeBufferBytes(profile)
        val inputStream = PipedInputStream(pipeBufferBytes)
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
                    "[start] Запись микрофона запущена source=VOICE_COMMUNICATION frameMs=${profile.frameDuration.millis} audioRecordBufferBytes=$audioRecordBufferSize readChunkBytes=$readChunkBytes pipeBufferBytes=$pipeBufferBytes",
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

        activeCapture = ActiveCapture(job) {
            closeAudioRecord(audioRecord)
            runCatching { outputStream.close() }
        }
        return inputStream
    }

    /**
     * Запускает запись и отдает PCM-фреймы выбранной длительности в callback compact voice pipeline.
     */
    @SuppressLint("MissingPermission")
    fun startFrames(profile: VoiceAudioProfile, onFrame: (ByteArray) -> Unit) {
        stop()
        val minBufferSize = AudioRecord.getMinBufferSize(
            PcmVoiceConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            PcmVoiceConfig.AUDIO_FORMAT,
        )
        val readChunkBytes = PcmVoiceConfig.frameBytes(profile) * READ_CHUNK_FRAME_COUNT
        val audioRecordBufferSize = maxOf(minBufferSize, readChunkBytes)
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
            val frameBytes = PcmVoiceConfig.frameBytes(profile)
            val pendingFrame = ByteArray(frameBytes)
            var pendingFrameBytes = 0
            val captureStartedAtMillis = System.currentTimeMillis()
            var firstReadLogged = false
            runCatching {
                audioRecord.startRecording()
                Log.i(
                    TAG,
                    "[startFrames] Запись микрофона для voice frames запущена source=VOICE_COMMUNICATION frameMs=${profile.frameDuration.millis} audioRecordBufferBytes=$audioRecordBufferSize readChunkBytes=$readChunkBytes",
                )
                while (isActive) {
                    val readBytes = audioRecord.read(buffer, 0, buffer.size)
                    if (readBytes > 0) {
                        if (!firstReadLogged) {
                            firstReadLogged = true
                            Log.i(
                                TAG,
                                "[startFrames] Первый read микрофона для voice frame readBytes=$readBytes elapsedMs=${System.currentTimeMillis() - captureStartedAtMillis}",
                            )
                        }
                        var sourceOffset = 0
                        while (sourceOffset < readBytes) {
                            val copiedBytes = minOf(
                                frameBytes - pendingFrameBytes,
                                readBytes - sourceOffset,
                            )
                            buffer.copyInto(
                                destination = pendingFrame,
                                destinationOffset = pendingFrameBytes,
                                startIndex = sourceOffset,
                                endIndex = sourceOffset + copiedBytes,
                            )
                            pendingFrameBytes += copiedBytes
                            sourceOffset += copiedBytes
                            if (pendingFrameBytes == frameBytes) {
                                onFrame(pendingFrame.copyOf())
                                pendingFrameBytes = 0
                            }
                        }
                    } else if (readBytes < 0) {
                        Log.w(TAG, "[startFrames] AudioRecord вернул ошибку readBytes=$readBytes")
                        break
                    }
                }
            }.onFailure { cause ->
                Log.w(TAG, "[startFrames] Запись voice frames оборвалась: ${cause.message}", cause)
            }
            closeAudioRecord(audioRecord)
            Log.i(TAG, "[startFrames] Запись voice frames остановлена")
        }

        activeCapture = ActiveCapture(job) {
            closeAudioRecord(audioRecord)
        }
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
     * Активная запись и действие освобождения связанных ресурсов.
     */
    private inner class ActiveCapture(
        private val job: Job,
        private val closeResources: () -> Unit,
    ) : Closeable {
        /**
         * Останавливает coroutine и освобождает ресурсы захвата.
         */
        override fun close() {
            Log.i(TAG, "[close] Останавливаем активную запись микрофона")
            job.cancel()
            closeResources()
        }
    }

    private companion object {
        private const val TAG = "PcmVoiceCapture"
        private const val READ_CHUNK_FRAME_COUNT = 1
    }
}
