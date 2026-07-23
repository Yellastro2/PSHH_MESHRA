package com.yellastro.btration.voice

import android.media.AudioFormat

/**
 * Общие PCM16-параметры и вычисления размеров для выбранного per-room voice-профиля.
 */
object PcmVoiceConfig {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_COUNT = 1
    const val BYTES_PER_SAMPLE = 2
    const val MAX_DECODE_FRAME_MILLIS = 60
    const val PIPE_BUFFER_FRAME_COUNT = 4
    val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    /**
     * Возвращает число PCM samples на канал в одном выбранном Opus-фрейме.
     */
    fun frameSamples(profile: VoiceAudioProfile): Int {
        return SAMPLE_RATE_HZ * profile.frameDuration.millis / MILLIS_PER_SECOND
    }

    /**
     * Возвращает число PCM16 bytes в одном выбранном mono voice frame.
     */
    fun frameBytes(profile: VoiceAudioProfile): Int {
        return frameSamples(profile) * CHANNEL_COUNT * BYTES_PER_SAMPLE
    }

    /**
     * Возвращает короткую емкость pipe в четыре фрейма текущего профиля.
     */
    fun pipeBufferBytes(profile: VoiceAudioProfile): Int {
        return frameBytes(profile) * PIPE_BUFFER_FRAME_COUNT
    }

    /**
     * Возвращает максимальный decoder output в samples для приема любого поддержанного Opus frame до 60 мс.
     */
    fun maxDecodeFrameSamples(): Int {
        return SAMPLE_RATE_HZ * MAX_DECODE_FRAME_MILLIS / MILLIS_PER_SECOND
    }

    private const val MILLIS_PER_SECOND = 1_000
}
