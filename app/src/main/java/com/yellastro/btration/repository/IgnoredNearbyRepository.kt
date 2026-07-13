package com.yellastro.btration.repository

import android.content.SharedPreferences
import android.util.Log
import com.yellastro.btration.domain.model.PeerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Хранит локальный ignore-list прямых Nearby peer-ов/gateway-ев по PeerId из рекламируемых endpointName.
 */
class IgnoredNearbyRepository(
    private val prefs: SharedPreferences,
) {
    private val _ignoredHostPeerIds = MutableStateFlow(readIgnoredHostPeerIds())

    /**
     * Текущий набор PeerId прямых peer-ов, которых нельзя показывать и использовать как gateway.
     */
    val ignoredPeerIds: StateFlow<Set<PeerId>> = _ignoredHostPeerIds.asStateFlow()

    /**
     * Старое имя flow для совместимости с кодом, который еще ожидает ignore-list host-ов.
     */
    val ignoredHostPeerIds: StateFlow<Set<PeerId>> = ignoredPeerIds

    /**
     * Добавляет peer/gateway в локальный ignore-list и сохраняет список в SharedPreferences.
     */
    fun ignorePeer(peerId: PeerId) {
        val updatedPeerIds = _ignoredHostPeerIds.value + peerId
        persistIgnoredHostPeerIds(updatedPeerIds)
        Log.i(TAG, "[ignorePeer] Peer добавлен в ignore-list peerId=${peerId.value} ignoredCount=${updatedPeerIds.size}")
    }

    /**
     * Добавляет host-а в локальный ignore-list для совместимости со старым Nearby Star поведением.
     */
    fun ignoreHost(peerId: PeerId) {
        ignorePeer(peerId)
    }

    /**
     * Очищает локальный ignore-list Nearby peer-ов/gateway-ев.
     */
    fun clearIgnoredPeers() {
        persistIgnoredHostPeerIds(emptySet())
        Log.i(TAG, "[clearIgnoredPeers] Ignore-list Nearby peer-ов очищен")
    }

    /**
     * Очищает локальный ignore-list host-ов для совместимости со старым Nearby Star поведением.
     */
    fun clearIgnoredHosts() {
        clearIgnoredPeers()
    }

    /**
     * Возвращает true, если peer/gateway с указанным PeerId уже находится в ignore-list.
     */
    fun isPeerIgnored(peerId: PeerId): Boolean {
        return peerId in _ignoredHostPeerIds.value
    }

    /**
     * Возвращает true, если host с указанным PeerId уже находится в ignore-list.
     */
    fun isHostIgnored(peerId: PeerId): Boolean {
        return isPeerIgnored(peerId)
    }

    /**
     * Читает сохраненный набор PeerId peer-ов/gateway-ев из SharedPreferences.
     */
    private fun readIgnoredHostPeerIds(): Set<PeerId> {
        return prefs.getStringSet(KEY_IGNORED_HOST_PEER_IDS, emptySet())
            .orEmpty()
            .asSequence()
            .filter { value -> value.isNotBlank() }
            .map(::PeerId)
            .toSet()
    }

    /**
     * Сохраняет новый набор PeerId peer-ов/gateway-ев и обновляет flow для UI.
     */
    private fun persistIgnoredHostPeerIds(peerIds: Set<PeerId>) {
        prefs.edit()
            .putStringSet(KEY_IGNORED_HOST_PEER_IDS, peerIds.map { peerId -> peerId.value }.toSet())
            .apply()
        _ignoredHostPeerIds.value = peerIds
    }

    private companion object {
        private const val TAG = "IgnoredNearbyRepository"
        private const val KEY_IGNORED_HOST_PEER_IDS = "ignored_nearby_host_peer_ids"
    }
}
