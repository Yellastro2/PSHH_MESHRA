package com.yellastro.btration.domain.mesh

import com.yellastro.btration.domain.model.ChatMessage
import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomTransportMode
import com.yellastro.btration.voice.VoiceTransportMode
import kotlinx.serialization.Serializable

/**
 * Идентификатор события комнаты в mesh-log; используется для dedup при flooding.
 */
@JvmInline
@Serializable
value class MeshRoomEventId(val value: String)

/**
 * Тип события комнаты, которое живет в mesh-log и участвует в восстановлении snapshot-а.
 */
@Serializable
enum class MeshRoomEventType {
    MEMBER_JOINED,
    MEMBER_LEFT,
    PEER_DISCONNECTED,
    CHAT_MESSAGE,
}

/**
 * Durable-событие комнаты для текстового mesh MVP: мета комнаты, вступление, выход/разрыв и сообщение общего канала.
 */
@Serializable
data class MeshRoomEvent(
    val eventId: MeshRoomEventId,
    val roomId: RoomId,
    val roomName: String,
    val knownHost: Peer,
    val authorPeerId: PeerId,
    val type: MeshRoomEventType,
    val createdAtMillis: Long,
    val roomTransportMode: RoomTransportMode = RoomTransportMode.MESHRA,
    val voiceTransportMode: VoiceTransportMode = VoiceTransportMode.WIFI_DIRECT_UDP,
    val isDirectAudioReady: Boolean = false,
    val peer: Peer? = null,
    val message: ChatMessage? = null,
)

/**
 * Текущий snapshot комнаты с метой transport-ов, участниками, сообщениями и списком известных событий.
 */
@Serializable
data class MeshRoomSnapshot(
    val roomId: RoomId,
    val roomName: String,
    val knownHost: Peer,
    val roomTransportMode: RoomTransportMode = RoomTransportMode.MESHRA,
    val voiceTransportMode: VoiceTransportMode = VoiceTransportMode.WIFI_DIRECT_UDP,
    val isDirectAudioReady: Boolean = false,
    val members: List<Peer> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val events: List<MeshRoomEvent> = emptyList(),
    val updatedAtMillis: Long = 0L,
)

/**
 * Идентификатор ephemeral voice frame-а в mesh flood, чтобы один и тот же audio payload не проигрывался и не пересылался повторно.
 */
@JvmInline
@Serializable
value class MeshVoiceFrameId(val value: String)

/**
 * Ephemeral Opus voice frame для MESHRA: не попадает в event-log и нужен только для realtime flooding-а.
 */
@Serializable
data class MeshVoiceFrame(
    val frameId: MeshVoiceFrameId,
    val originPeerId: PeerId,
    val sequence: Long,
    val encodedBytes: ByteArray,
    val isFinal: Boolean,
)

/**
 * Тип payload-а внутри mesh envelope: durable room-события, snapshot комнаты или служебный hello соседнего peer-а.
 */
@Serializable
enum class MeshPayloadKind {
    ROOM_EVENT,
    ROOM_SNAPSHOT,
    PEER_HELLO,
    VOICE_FRAME,
}

/**
 * Транспортная обертка mesh-слоя без собственного envelopeId.
 *
 * Room-события дедупятся по eventId, snapshot не flood-ится, PEER_HELLO используется для live-мапы linkId -> PeerId,
 * а VOICE_FRAME дедупится по собственному frameId и не сохраняется в snapshot.
 */
@Serializable
data class MeshEnvelope(
    val roomId: RoomId? = null,
    val previousHopPeerId: PeerId?,
    val ttl: Int,
    val payloadKind: MeshPayloadKind,
    val event: MeshRoomEvent? = null,
    val snapshot: MeshRoomSnapshot? = null,
    val voiceFrame: MeshVoiceFrame? = null,
    val sentAtMillis: Long,
)
