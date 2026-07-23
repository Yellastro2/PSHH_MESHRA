package com.yellastro.btration.voice

import com.yellastro.btration.domain.model.PeerId
import java.util.zip.CRC32

/**
 * Назначение компактного voice-пакета на общем NeighborTransport с отдельным magic для Star и Mesh.
 */
enum class CompactVoicePacketKind(
    internal val magicFirst: Byte,
    internal val magicSecond: Byte,
) {
    STAR(
        magicFirst = 0x53,
        magicSecond = 0x56,
    ),
    MESH(
        magicFirst = 0x4D,
        magicSecond = 0x56,
    ),
}

/**
 * Компактный voice wire-packet с UInt16 адресацией, PTT-сессией, sequence, flags/TTL и Opus payload.
 */
data class CompactVoicePacket(
    val originNodeId: Int,
    val pttSessionId: Int,
    val sequence: Int,
    val encodedBytes: ByteArray,
    val isFinal: Boolean,
    val ttl: Int,
)

/**
 * Кодирует и декодирует общий девятибайтовый Star/Mesh voice wire-format.
 *
 * Формат: `magic[2] + control[1] + origin[2] + session[2] + sequence[2] + Opus`.
 */
class CompactVoicePacketCodec(
    private val kind: CompactVoicePacketKind,
) {
    /**
     * Возвращает true, если payload имеет magic, версию и DATA-тип выбранного voice packet kind.
     */
    fun isVoicePacket(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_SIZE) {
            return false
        }
        val control = bytes[CONTROL_OFFSET].toInt() and UNSIGNED_BYTE_MASK
        return bytes[MAGIC_FIRST_OFFSET] == kind.magicFirst &&
            bytes[MAGIC_SECOND_OFFSET] == kind.magicSecond &&
            (control and VERSION_MASK) == VERSION_BITS &&
            (control and TYPE_MASK) == DATA_TYPE_BITS
    }

    /**
     * Кодирует компактный packet и проверяет границы всех коротких полей.
     */
    fun encode(packet: CompactVoicePacket): ByteArray {
        require(packet.originNodeId in UNSIGNED_SHORT_RANGE) { "originNodeId должен помещаться в UInt16" }
        require(packet.pttSessionId in UNSIGNED_SHORT_RANGE) { "pttSessionId должен помещаться в UInt16" }
        require(packet.sequence in UNSIGNED_SHORT_RANGE) { "sequence должен помещаться в UInt16" }
        require(packet.ttl in TTL_RANGE) { "ttl должен помещаться в 4 бита" }
        require(packet.encodedBytes.size <= MAX_OPUS_PAYLOAD_SIZE) { "Opus payload слишком большой" }

        val bytes = ByteArray(HEADER_SIZE + packet.encodedBytes.size)
        bytes[MAGIC_FIRST_OFFSET] = kind.magicFirst
        bytes[MAGIC_SECOND_OFFSET] = kind.magicSecond
        bytes[CONTROL_OFFSET] = (
            VERSION_BITS or
                DATA_TYPE_BITS or
                (if (packet.isFinal) FINAL_MASK else 0) or
                packet.ttl
            ).toByte()
        writeUnsignedShort(bytes, ORIGIN_OFFSET, packet.originNodeId)
        writeUnsignedShort(bytes, SESSION_OFFSET, packet.pttSessionId)
        writeUnsignedShort(bytes, SEQUENCE_OFFSET, packet.sequence)
        packet.encodedBytes.copyInto(bytes, destinationOffset = HEADER_SIZE)
        return bytes
    }

    /**
     * Декодирует проверенный compact voice packet и возвращает независимую копию Opus payload.
     */
    fun decode(bytes: ByteArray): CompactVoicePacket {
        require(isVoicePacket(bytes)) { "Payload не является поддерживаемым compact voice packet kind=$kind" }
        require(bytes.size <= HEADER_SIZE + MAX_OPUS_PAYLOAD_SIZE) { "Opus payload слишком большой" }
        val control = bytes[CONTROL_OFFSET].toInt() and UNSIGNED_BYTE_MASK
        return CompactVoicePacket(
            originNodeId = readUnsignedShort(bytes, ORIGIN_OFFSET),
            pttSessionId = readUnsignedShort(bytes, SESSION_OFFSET),
            sequence = readUnsignedShort(bytes, SEQUENCE_OFFSET),
            encodedBytes = bytes.copyOfRange(HEADER_SIZE, bytes.size),
            isFinal = (control and FINAL_MASK) != 0,
            ttl = control and TTL_MASK,
        )
    }

    /**
     * Записывает UInt16 в network byte order без промежуточных stream-объектов.
     */
    private fun writeUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr BITS_PER_BYTE).toByte()
        bytes[offset + 1] = value.toByte()
    }

    /**
     * Читает UInt16 из network byte order как неотрицательный Int.
     */
    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and UNSIGNED_BYTE_MASK) shl BITS_PER_BYTE) or
            (bytes[offset + 1].toInt() and UNSIGNED_BYTE_MASK)
    }

    companion object {
        const val HEADER_SIZE = 9
        private const val MAGIC_FIRST_OFFSET = 0
        private const val MAGIC_SECOND_OFFSET = 1
        private const val CONTROL_OFFSET = 2
        private const val ORIGIN_OFFSET = 3
        private const val SESSION_OFFSET = 5
        private const val SEQUENCE_OFFSET = 7
        private const val BITS_PER_BYTE = 8
        private const val UNSIGNED_BYTE_MASK = 0xFF
        private const val VERSION_MASK = 0x80
        private const val VERSION_BITS = 0x80
        private const val TYPE_MASK = 0x60
        private const val DATA_TYPE_BITS = 0x00
        private const val FINAL_MASK = 0x10
        private const val TTL_MASK = 0x0F
        private const val MAX_OPUS_PAYLOAD_SIZE = 4_096
        private val UNSIGNED_SHORT_RANGE = 0..0xFFFF
        private val TTL_RANGE = 0..TTL_MASK
    }
}

/**
 * Строит короткий UInt16 node id из стабильного PeerId одинаково для Star и Mesh.
 */
object CompactVoiceNodeId {
    /**
     * Считает CRC32 UTF-8 представления PeerId и берет младшие 16 бит.
     */
    fun fromPeerIdValue(peerIdValue: String): Int {
        val checksum = CRC32()
        checksum.update(peerIdValue.toByteArray(Charsets.UTF_8))
        return (checksum.value and UNSIGNED_SHORT_MASK).toInt()
    }

    private const val UNSIGNED_SHORT_MASK = 0xFFFFL
}

/**
 * Потокобезопасно хранит вынесенную из voice-пакетов таблицу UInt16 node id -> PeerId и отмечает коллизии.
 */
class CompactVoicePeerIndex {
    @Volatile
    private var peerIdsByNodeId: Map<Int, PeerId?> = emptyMap()

    /**
     * Полностью заменяет таблицу актуальными участниками и возвращает найденные collision node id.
     */
    fun replacePeers(peerIds: Set<PeerId>): Set<Int> {
        val nextIndex = mutableMapOf<Int, PeerId?>()
        val collisions = mutableSetOf<Int>()
        peerIds.forEach { peerId ->
            val nodeId = CompactVoiceNodeId.fromPeerIdValue(peerId.value)
            val previousPeerId = nextIndex[nodeId]
            if (nodeId !in nextIndex) {
                nextIndex[nodeId] = peerId
            } else if (previousPeerId != peerId) {
                nextIndex[nodeId] = null
                collisions += nodeId
            }
        }
        peerIdsByNodeId = nextIndex.toMap()
        return collisions
    }

    /**
     * Возвращает короткий node id только если PeerId однозначно присутствует в текущей таблице.
     */
    fun nodeIdFor(peerId: PeerId): Int? {
        val nodeId = CompactVoiceNodeId.fromPeerIdValue(peerId.value)
        return nodeId.takeIf { peerIdsByNodeId[nodeId] == peerId }
    }

    /**
     * Возвращает PeerId только для известного node id без коллизии.
     */
    fun peerIdFor(nodeId: Int): PeerId? {
        return peerIdsByNodeId[nodeId]
    }
}
