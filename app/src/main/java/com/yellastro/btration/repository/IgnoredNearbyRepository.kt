package com.yellastro.btration.repository

import android.content.SharedPreferences
import android.util.Log
import com.yellastro.btration.domain.model.PeerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Хранит локальный ignore-list Nearby host-ов по стабильным PeerId из рекламируемых комнат.
 */
class IgnoredNearbyRepository(
    private val prefs: SharedPreferences,
) {
    private val _ignoredHostPeerIds = MutableStateFlow(readIgnoredHostPeerIds())

    /**
     * Текущий набор PeerId host-ов, комнаты которых нужно скрывать и не использовать для Nearby-входа.
     */
    val ignoredHostPeerIds: StateFlow<Set<PeerId>> = _ignoredHostPeerIds.asStateFlow()

    /**
     * Добавляет host-а в локальный ignore-list и сохраняет список в SharedPreferences.
     */
    fun ignoreHost(peerId: PeerId) {
        val updatedPeerIds = _ignoredHostPeerIds.value + peerId
        persistIgnoredHostPeerIds(updatedPeerIds)
        Log.i(TAG, "[ignoreHost] Host добавлен в ignore-list peerId=${peerId.value} ignoredCount=${updatedPeerIds.size}")
    }

    /**
     * Очищает локальный ignore-list Nearby host-ов.
     */
    fun clearIgnoredHosts() {
        persistIgnoredHostPeerIds(emptySet())
        Log.i(TAG, "[clearIgnoredHosts] Ignore-list Nearby host-ов очищен")
    }

    /**
     * Возвращает true, если host с указанным PeerId уже находится в ignore-list.
     */
    fun isHostIgnored(peerId: PeerId): Boolean {
        return peerId in _ignoredHostPeerIds.value
    }

    /**
     * Читает сохраненный набор PeerId host-ов из SharedPreferences.
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
     * Сохраняет новый набор PeerId host-ов и обновляет flow для UI.
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
