package com.yellastro.btration.voice

import android.media.AudioFormat

/**
 * Общие параметры MVP-голоса: PCM16 по краям audio stack, 40 ms Opus frames и короткие pipe-буферы для снижения нагрузки на Nearby.
 */
object PcmVoiceConfig {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_COUNT = 1
    const val BYTES_PER_SAMPLE = 2
    const val FRAME_MILLIS = 40
    const val FRAME_SAMPLES = SAMPLE_RATE_HZ * FRAME_MILLIS / 1_000
    const val FRAME_BYTES = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE * FRAME_MILLIS / 1_000
    const val PIPE_BUFFER_FRAME_COUNT = 16
    const val PIPE_BUFFER_BYTES = FRAME_BYTES * PIPE_BUFFER_FRAME_COUNT
    val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
}
