package com.yellastro.btration.domain.mesh

/**
 * Четырехбайтовый MESHRA heartbeat-пакет для замера живости прямого соседского link-а.
 */
data class MeshHeartbeatPacket(
    val kind: MeshHeartbeatKind,
    val sequence: Int,
)

/**
 * Тип служебного heartbeat-пакета: запрос ping или ответ pong с тем же sequence.
 */
enum class MeshHeartbeatKind {
    PING,
    PONG,
}

/**
 * Кодирует и декодирует компактный MESHRA heartbeat wire-format `magic + kind/flags + sequence`.
 */
class MeshHeartbeatCodec {
    /**
     * Возвращает true, если payload выглядит как поддерживаемый четырехбайтовый MESHRA heartbeat.
     */
    fun isHeartbeatPacket(bytes: ByteArray): Boolean {
        if (bytes.size != PACKET_SIZE || bytes[MAGIC_OFFSET] != MAGIC) {
            return false
        }
        val kind = bytes[KIND_FLAGS_OFFSET].toInt() and KIND_MASK
        return kind == KIND_PING || kind == KIND_PONG
    }

    /**
     * Кодирует heartbeat-пакет, оставляя верхние биты kind/flags зарезервированными.
     */
    fun encode(packet: MeshHeartbeatPacket): ByteArray {
        require(packet.sequence in UNSIGNED_SHORT_RANGE) { "sequence должен помещаться в UInt16" }
        val bytes = ByteArray(PACKET_SIZE)
        bytes[MAGIC_OFFSET] = MAGIC
        bytes[KIND_FLAGS_OFFSET] = packet.kind.toWireKind().toByte()
        writeUnsignedShort(bytes, SEQUENCE_OFFSET, packet.sequence)
        return bytes
    }

    /**
     * Декодирует уже распознанный heartbeat-пакет и возвращает kind с UInt16 sequence.
     */
    fun decode(bytes: ByteArray): MeshHeartbeatPacket {
        require(isHeartbeatPacket(bytes)) { "Payload не является поддерживаемым MESHRA heartbeat-пакетом" }
        val kind = when (bytes[KIND_FLAGS_OFFSET].toInt() and KIND_MASK) {
            KIND_PING -> MeshHeartbeatKind.PING
            KIND_PONG -> MeshHeartbeatKind.PONG
            else -> error("Недостижимый kind heartbeat")
        }
        return MeshHeartbeatPacket(kind = kind, sequence = readUnsignedShort(bytes, SEQUENCE_OFFSET))
    }

    /**
     * Записывает UInt16 в network byte order без промежуточных объектов.
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

    /**
     * Возвращает wire-kind для компактного поля kind/flags.
     */
    private fun MeshHeartbeatKind.toWireKind(): Int {
        return when (this) {
            MeshHeartbeatKind.PING -> KIND_PING
            MeshHeartbeatKind.PONG -> KIND_PONG
        }
    }

    companion object {
        const val PACKET_SIZE = 4
        private const val MAGIC_OFFSET = 0
        private const val KIND_FLAGS_OFFSET = 1
        private const val SEQUENCE_OFFSET = 2
        private const val BITS_PER_BYTE = 8
        private const val UNSIGNED_BYTE_MASK = 0xFF
        private const val KIND_MASK = 0x0F
        private const val KIND_PING = 0x01
        private const val KIND_PONG = 0x02
        private const val MAGIC: Byte = 0x48
        private val UNSIGNED_SHORT_RANGE = 0..0xFFFF
    }
}
