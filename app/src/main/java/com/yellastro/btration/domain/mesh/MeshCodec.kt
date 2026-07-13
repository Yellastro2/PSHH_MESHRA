package com.yellastro.btration.domain.mesh

import java.nio.charset.StandardCharsets
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Кодирует mesh envelope в непрозрачные bytes с короткой сигнатурой, чтобы соседние протоколы могли их игнорировать.
 */
class MeshCodec(
    private val json: Json,
) {
    /**
     * Возвращает true, если bytes выглядят как payload текстового mesh-протокола.
     */
    fun isMeshEnvelope(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC_BYTES.size) {
            return false
        }
        return MAGIC_BYTES.indices.all { index -> bytes[index] == MAGIC_BYTES[index] }
    }

    /**
     * Кодирует envelope в UTF-8 bytes с префиксом протокола.
     */
    fun encode(envelope: MeshEnvelope): ByteArray {
        val payload = json.encodeToString(envelope).toByteArray(StandardCharsets.UTF_8)
        return MAGIC_BYTES + payload
    }

    /**
     * Декодирует bytes mesh-протокола или бросает ошибку, если сигнатура не совпала.
     */
    fun decode(bytes: ByteArray): MeshEnvelope {
        require(isMeshEnvelope(bytes)) { "Payload is not a mesh envelope" }
        val payload = bytes.copyOfRange(MAGIC_BYTES.size, bytes.size).toString(StandardCharsets.UTF_8)
        return json.decodeFromString(payload)
    }

    private companion object {
        private val MAGIC_BYTES = "BTME1\n".toByteArray(StandardCharsets.UTF_8)
    }
}
