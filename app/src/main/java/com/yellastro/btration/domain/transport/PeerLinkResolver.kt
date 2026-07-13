package com.yellastro.btration.domain.transport

import com.yellastro.btration.domain.model.PeerId

/**
 * Резолвит доменные PeerId в linkId нижнего транспорта для протоколов поверх NeighborTransport.
 */
interface PeerLinkResolver {
    /**
     * Возвращает linkId прямого транспорта для доменного PeerId.
     */
    fun linkIdForPeer(peerId: PeerId): NeighborLinkId?

    /**
     * Возвращает доменный PeerId, связанный с прямым linkId.
     */
    fun peerIdForLink(linkId: NeighborLinkId): PeerId?
}
