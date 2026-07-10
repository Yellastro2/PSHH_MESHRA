package com.yellastro.btration.data.nearby

import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId

/**
 * Хранит связи между Nearby endpointId и доменными идентификаторами приложения, где endpointId описывает прямого Nearby-соседа.
 */
class NearbyEndpointRegistry {
    private val endpointToPeer = mutableMapOf<String, PeerId>()
    private val peerToEndpoint = mutableMapOf<PeerId, String>()
    private val endpointToRoom = mutableMapOf<String, RoomId>()
    private val roomToEndpoints = mutableMapOf<RoomId, MutableSet<String>>()

    /**
     * Связывает Nearby endpointId с участником приложения, заменяя старую прямую связь при новом discovery/handshake.
     */
    fun bindPeer(endpointId: String, peerId: PeerId) {
        endpointToPeer[endpointId]?.let(peerToEndpoint::remove)
        peerToEndpoint[peerId]?.let(endpointToPeer::remove)
        endpointToPeer[endpointId] = peerId
        peerToEndpoint[peerId] = endpointId
    }

    /**
     * Связывает Nearby endpointId с участником только если endpoint еще не закреплен за другим прямым соседом.
     */
    fun bindPeerIfEndpointFree(endpointId: String, peerId: PeerId): Boolean {
        val existingPeerId = endpointToPeer[endpointId]
        if (existingPeerId != null) {
            return existingPeerId == peerId
        }
        peerToEndpoint[peerId]?.let(endpointToPeer::remove)
        endpointToPeer[endpointId] = peerId
        peerToEndpoint[peerId] = endpointId
        return true
    }

    /**
     * Связывает Nearby endpointId с найденной или активной комнатой.
     */
    fun bindRoom(endpointId: String, roomId: RoomId) {
        endpointToRoom[endpointId]?.let { previousRoomId ->
            roomToEndpoints[previousRoomId]?.remove(endpointId)
            if (roomToEndpoints[previousRoomId].isNullOrEmpty()) {
                roomToEndpoints.remove(previousRoomId)
            }
        }
        endpointToRoom[endpointId] = roomId
        roomToEndpoints.getOrPut(roomId) { mutableSetOf() }.add(endpointId)
    }

    /**
     * Возвращает участника, связанного с Nearby endpointId.
     */
    fun getPeerId(endpointId: String): PeerId? {
        return endpointToPeer[endpointId]
    }

    /**
     * Возвращает Nearby endpointId, связанный с участником.
     */
    fun getEndpointId(peerId: PeerId): String? {
        return peerToEndpoint[peerId]
    }

    /**
     * Возвращает комнату, связанную с Nearby endpointId.
     */
    fun getRoomId(endpointId: String): RoomId? {
        return endpointToRoom[endpointId]
    }

    /**
     * Возвращает все endpointId, известные для комнаты.
     */
    fun getEndpointIds(roomId: RoomId): Set<String> {
        return roomToEndpoints[roomId].orEmpty()
    }

    /**
     * Возвращает все endpointId, с которыми транспорт уже связал доменные данные.
     */
    fun getKnownEndpointIds(): Set<String> {
        return endpointToPeer.keys + endpointToRoom.keys
    }

    /**
     * Удаляет все связи для Nearby endpointId.
     */
    fun removeEndpoint(endpointId: String) {
        endpointToPeer.remove(endpointId)?.let(peerToEndpoint::remove)
        endpointToRoom.remove(endpointId)?.let { roomId ->
            roomToEndpoints[roomId]?.remove(endpointId)
            if (roomToEndpoints[roomId].isNullOrEmpty()) {
                roomToEndpoints.remove(roomId)
            }
        }
    }

    /**
     * Полностью очищает таблицу связей.
     */
    fun clear() {
        endpointToPeer.clear()
        peerToEndpoint.clear()
        endpointToRoom.clear()
        roomToEndpoints.clear()
    }
}
