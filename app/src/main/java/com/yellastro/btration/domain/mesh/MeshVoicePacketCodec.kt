package com.yellastro.btration.domain.mesh

import java.util.zip.CRC32

/**
 * Компактный ephemeral voice-пакет MESHRA с девятибайтовым заголовком и Opus payload без JSON-обертки.
 */
data class MeshVoicePacket(
    val originNodeId: Int,
    val pttSessionId: Int,
    val sequence: Int,
    val encodedBytes: ByteArray,
    val isFinal: Boolean,
    val ttl: Int,
)

/**
 * Кодирует и декодирует бинарный MESHRA voice wire-format `MV + control + origin + session + sequence + Opus`.
 *
 * В control byte старший бит хранит версию 1, следующие два бита зарезервированы под тип пакета,
 * бит final завершает PTT, а младшие четыре бита содержат hop TTL от 0 до 15.
 */
class MeshVoicePacketCodec {
    /**
     * Возвращает true, если payload имеет magic, версию и тип текущего MESHRA voice DATA-пакета.
     */
    fun isVoicePacket(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_SIZE) {
            return false
        }
        val control = bytes[CONTROL_OFFSET].toInt() and UNSIGNED_BYTE_MASK
        return bytes[MAGIC_FIRST_OFFSET] == MAGIC_FIRST &&
            bytes[MAGIC_SECOND_OFFSET] == MAGIC_SECOND &&
            (control and VERSION_MASK) == VERSION_BITS &&
            (control and TYPE_MASK) == DATA_TYPE_BITS
    }

    /**
     * Кодирует DATA-пакет и проверяет, что короткие поля и Opus payload помещаются в wire-format.
     */
    fun encode(packet: MeshVoicePacket): ByteArray {
        require(packet.originNodeId in UNSIGNED_SHORT_RANGE) { "originNodeId должен помещаться в UInt16" }
        require(packet.pttSessionId in UNSIGNED_SHORT_RANGE) { "pttSessionId должен помещаться в UInt16" }
        require(packet.sequence in UNSIGNED_SHORT_RANGE) { "sequence должен помещаться в UInt16" }
        require(packet.ttl in TTL_RANGE) { "ttl должен помещаться в 4 бита" }
        require(packet.encodedBytes.size <= MAX_OPUS_PAYLOAD_SIZE) { "Opus payload слишком большой" }

        val bytes = ByteArray(HEADER_SIZE + packet.encodedBytes.size)
        bytes[MAGIC_FIRST_OFFSET] = MAGIC_FIRST
        bytes[MAGIC_SECOND_OFFSET] = MAGIC_SECOND
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
     * Декодирует проверенный MESHRA voice DATA-пакет и возвращает независимую копию Opus payload.
     */
    fun decode(bytes: ByteArray): MeshVoicePacket {
        require(isVoicePacket(bytes)) { "Payload не является поддерживаемым MESHRA voice-пакетом" }
        require(bytes.size <= HEADER_SIZE + MAX_OPUS_PAYLOAD_SIZE) { "Opus payload слишком большой" }
        val control = bytes[CONTROL_OFFSET].toInt() and UNSIGNED_BYTE_MASK
        return MeshVoicePacket(
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
        private const val MAGIC_FIRST: Byte = 0x4D
        private const val MAGIC_SECOND: Byte = 0x56
    }
}

/**
 * Получает стабильный локально вычисляемый UInt16 node id из полного PeerId для компактного voice-заголовка.
 *
 * Полный PeerId остается в snapshot комнаты; при приеме короткий id обязан однозначно разрешиться среди участников.
 */
object MeshVoiceNodeId {
    /**
     * Считает CRC32 UTF-8 представления PeerId и берет младшие 16 бит; коллизии проверяет MeshTransport.
     */
    fun fromPeerIdValue(peerIdValue: String): Int {
        val checksum = CRC32()
        checksum.update(peerIdValue.toByteArray(Charsets.UTF_8))
        return (checksum.value and UNSIGNED_SHORT_MASK).toInt()
    }

    private const val UNSIGNED_SHORT_MASK = 0xFFFFL
}
