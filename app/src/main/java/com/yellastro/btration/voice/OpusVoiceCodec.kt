package com.yellastro.btration.voice

import android.util.Log
import eu.buney.kopus.OPUS_OK
import eu.buney.kopus.OPUS_SIGNAL_VOICE
import eu.buney.kopus.OpusApplication
import eu.buney.kopus.OpusDecoder
import eu.buney.kopus.OpusEncoder
import eu.buney.kopus.setBitrate
import eu.buney.kopus.setComplexity
import eu.buney.kopus.setSignal
import eu.buney.kopus.setVBR
import java.io.Closeable

/**
 * Кодирует PCM16 mono 16 kHz в Opus-пакеты выбранной комнатой длительности.
 */
class OpusVoiceEncoder(
    private val profile: VoiceAudioProfile,
) : Closeable {
    private val encoder = OpusEncoder(
        sampleRate = PcmVoiceConfig.SAMPLE_RATE_HZ,
        channels = PcmVoiceConfig.CHANNEL_COUNT,
        application = OpusApplication.Voip,
    ).also(::configureEncoder)
    private val frameSamples = PcmVoiceConfig.frameSamples(profile)
    private val pcmShorts = ShortArray(frameSamples * PcmVoiceConfig.CHANNEL_COUNT)
    private val encodedBuffer = ByteArray(MAX_OPUS_PACKET_BYTES)

    /**
     * Кодирует один PCM-фрейм в один Opus packet, дополняя короткий хвост тишиной до размера Opus frame.
     */
    fun encode(pcmBytes: ByteArray): ByteArray {
        pcmShorts.fill(0)
        val sampleCount = (pcmBytes.size / PcmVoiceConfig.BYTES_PER_SAMPLE)
            .coerceAtMost(pcmShorts.size)
        for (index in 0 until sampleCount) {
            val byteIndex = index * PcmVoiceConfig.BYTES_PER_SAMPLE
            val low = pcmBytes[byteIndex].toInt() and BYTE_MASK
            val high = pcmBytes[byteIndex + 1].toInt()
            pcmShorts[index] = ((high shl BYTE_BITS) or low).toShort()
        }

        val encodedBytes = encoder.encode(
            inPcm = pcmShorts,
            inPcmOffset = 0,
            frameSize = frameSamples,
            outData = encodedBuffer,
            outDataOffset = 0,
            maxDataBytes = encodedBuffer.size,
        )
        require(encodedBytes > 0) {
            "Opus encode вернул ошибку $encodedBytes"
        }
        return encodedBuffer.copyOf(encodedBytes)
    }

    /**
     * Освобождает native encoder.
     */
    override fun close() {
        encoder.close()
    }

    /**
     * Настраивает Opus под речь и логирует выбранную комнатой длительность фрейма.
     */
    private fun configureEncoder(encoder: OpusEncoder) {
        logCtlResult("setBitrate", encoder.setBitrate(BITRATE_BITS_PER_SECOND))
        logCtlResult("setSignal", encoder.setSignal(OPUS_SIGNAL_VOICE))
        logCtlResult("setComplexity", encoder.setComplexity(COMPLEXITY))
        logCtlResult("setVBR", encoder.setVBR(true))
        Log.i(
            TAG,
            "[configureEncoder] Opus encoder настроен sampleRate=${PcmVoiceConfig.SAMPLE_RATE_HZ} frameMs=${profile.frameDuration.millis} bitrate=$BITRATE_BITS_PER_SECOND",
        )
    }
}

/**
 * Декодирует Opus любой поддержанной длительности до 60 мс, не завися от локального encoder-профиля.
 */
class OpusVoiceDecoder : Closeable {
    private val decoder = OpusDecoder(
        sampleRate = PcmVoiceConfig.SAMPLE_RATE_HZ,
        channels = PcmVoiceConfig.CHANNEL_COUNT,
    )
    private val maxFrameSamples = PcmVoiceConfig.maxDecodeFrameSamples()
    private val pcmShorts = ShortArray(maxFrameSamples * PcmVoiceConfig.CHANNEL_COUNT)

    /**
     * Декодирует один Opus packet в PCM16 little-endian bytes.
     */
    fun decode(encodedBytes: ByteArray): ByteArray {
        val decodedSamples = decoder.decode(
            inData = encodedBytes,
            inDataOffset = 0,
            len = encodedBytes.size,
            outPcm = pcmShorts,
            outPcmOffset = 0,
            frameSize = maxFrameSamples,
            decodeFec = false,
        )
        require(decodedSamples > 0) {
            "Opus decode вернул ошибку $decodedSamples"
        }

        val decodedBytes = ByteArray(decodedSamples * PcmVoiceConfig.CHANNEL_COUNT * PcmVoiceConfig.BYTES_PER_SAMPLE)
        for (index in 0 until decodedSamples * PcmVoiceConfig.CHANNEL_COUNT) {
            val sample = pcmShorts[index].toInt()
            val byteIndex = index * PcmVoiceConfig.BYTES_PER_SAMPLE
            decodedBytes[byteIndex] = (sample and BYTE_MASK).toByte()
            decodedBytes[byteIndex + 1] = ((sample ushr BYTE_BITS) and BYTE_MASK).toByte()
        }
        return decodedBytes
    }

    /**
     * Освобождает native decoder.
     */
    override fun close() {
        decoder.close()
    }
}

/**
 * Логирует результат Opus CTL-команды, если native codec не принял настройку.
 */
private fun logCtlResult(functionName: String, result: Int) {
    if (result != OPUS_OK) {
        Log.w(TAG, "[$functionName] Opus CTL вернул код=$result")
    }
}

private const val TAG = "OpusVoiceCodec"
private const val BITRATE_BITS_PER_SECOND = 24_000
private const val COMPLEXITY = 5
private const val MAX_OPUS_PACKET_BYTES = 1_276
private const val BYTE_MASK = 0xFF
private const val BYTE_BITS = 8
