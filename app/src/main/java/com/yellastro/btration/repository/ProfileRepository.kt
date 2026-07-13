package com.yellastro.btration.repository

import android.content.SharedPreferences
import com.yellastro.btration.domain.model.Peer
import com.yellastro.btration.domain.model.PeerId
import java.util.UUID

/**
 * Хранит локальный профиль участника, последнее имя комнаты, стабильный peerId и отображаемое имя.
 */
class ProfileRepository(
    private val prefs: SharedPreferences,
) {
    /**
     * Возвращает стабильный PeerId, создавая его при первом обращении.
     */
    fun getOrCreatePeerId(): PeerId {
        val existingPeerId = prefs.getString(KEY_PEER_ID, null)
        if (!existingPeerId.isNullOrBlank()) {
            return PeerId(existingPeerId)
        }

        val peerId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_PEER_ID, peerId).apply()
        return PeerId(peerId)
    }

    /**
     * Возвращает сохраненное имя участника или null, если профиль еще не настроен.
     */
    fun getPeerName(): String? {
        return prefs.getString(KEY_PEER_NAME, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * Сохраняет отображаемое имя участника.
     */
    fun setPeerName(name: String) {
        prefs.edit().putString(KEY_PEER_NAME, name.trim()).apply()
    }

    /**
     * Возвращает последнее использованное название комнаты для предзаполнения диалога создания.
     */
    fun getLastRoomName(): String {
        return prefs.getString(KEY_LAST_ROOM_NAME, null)?.takeIf { it.isNotBlank() }.orEmpty()
    }

    /**
     * Сохраняет последнее использованное название комнаты.
     */
    fun setLastRoomName(name: String) {
        prefs.edit().putString(KEY_LAST_ROOM_NAME, name.trim()).apply()
    }

    /**
     * Возвращает полное описание локального участника для runtime и wire-пакетов.
     */
    fun getSelfPeer(): Peer {
        return Peer(
            peerId = getOrCreatePeerId(),
            name = getPeerName() ?: DEFAULT_PEER_NAME,
        )
    }

    private companion object {
        private const val KEY_PEER_ID = "peer_id"
        private const val KEY_PEER_NAME = "self_name"
        private const val KEY_LAST_ROOM_NAME = "last_room_name"
        private const val DEFAULT_PEER_NAME = "Гость"
    }
}
