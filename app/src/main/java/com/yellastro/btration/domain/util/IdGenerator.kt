package com.yellastro.btration.domain.util

import com.yellastro.btration.domain.model.MessageId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.WirePacketId
import java.util.UUID

/**
 * Генерирует доменные идентификаторы для комнат, сообщений и wire-пакетов.
 */
class IdGenerator {
    /**
     * Создает новый идентификатор комнаты.
     */
    fun newRoomId(): RoomId {
        return RoomId(newId("room"))
    }

    /**
     * Создает новый идентификатор сообщения.
     */
    fun newMessageId(): MessageId {
        return MessageId(newId("msg"))
    }

    /**
     * Создает новый идентификатор wire-пакета для будущих dedup/relay-сценариев.
     */
    fun newWirePacketId(): WirePacketId {
        return WirePacketId(newId("packet"))
    }

    /**
     * Создает строковый идентификатор с читаемым префиксом.
     */
    private fun newId(prefix: String): String {
        return "${prefix}_${UUID.randomUUID()}"
    }
}
