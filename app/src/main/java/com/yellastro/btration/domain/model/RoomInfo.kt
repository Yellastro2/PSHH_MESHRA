package com.yellastro.btration.domain.model

import com.yellastro.btration.voice.VoiceTransportMode
import com.yellastro.btration.voice.VoiceAudioProfile
import kotlinx.serialization.Serializable

/**
 * Публичное описание комнаты, ее gateway/endpoint, transport-режимы, voice-профиль и capabilities.
 */
@Serializable
data class RoomInfo(
    val roomId: RoomId,
    val name: String,
    val host: Peer,
    val createdAtMillis: Long,
    val roomTransportMode: RoomTransportMode = RoomTransportMode.NEARBY_STAR,
    val voiceTransportMode: VoiceTransportMode = VoiceTransportMode.WIFI_DIRECT_UDP,
    val voiceAudioProfile: VoiceAudioProfile = VoiceAudioProfile(),
    val isDirectAudioReady: Boolean = false,
    val gateway: Peer? = null,
    val discoveryGroupId: String? = null,
    val discoveryEndpointId: String? = null,
    val memberCount: Int? = null,
)
