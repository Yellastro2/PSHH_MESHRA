package com.yellastro.btration.data.wire

import com.yellastro.btration.domain.model.WirePacket
import kotlinx.serialization.json.Json

/**
 * Кодирует и декодирует wire-пакеты в JSON-байты для транспортного слоя.
 */
class WireCodec(
    private val json: Json,
) {
    /**
     * Преобразует пакет протокола в UTF-8 JSON-байты.
     */
    fun encode(packet: WirePacket): ByteArray {
        return json.encodeToString(WirePacket.serializer(), packet).encodeToByteArray()
    }

    /**
     * Восстанавливает пакет протокола из UTF-8 JSON-байтов.
     */
    fun decode(bytes: ByteArray): WirePacket {
        return json.decodeFromString(WirePacket.serializer(), bytes.decodeToString())
    }
}
