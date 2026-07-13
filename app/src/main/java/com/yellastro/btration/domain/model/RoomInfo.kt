package com.yellastro.btration.domain.model

import com.yellastro.btration.voice.VoiceTransportMode
import kotlinx.serialization.Serializable

/**
 * Публичное описание комнаты, ее voice transport и capabilities для лобби и участников.
 */
@Serializable
data class RoomInfo(
    val roomId: RoomId,
    val name: String,
    val host: Peer,
    val createdAtMillis: Long,
    val voiceTransportMode: VoiceTransportMode = VoiceTransportMode.WIFI_DIRECT_UDP,
    val isDirectAudioReady: Boolean = false,
)
