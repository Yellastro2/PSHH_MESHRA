package com.yellastro.btration.voice

import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.VoiceTransportControlInfo
import kotlinx.coroutines.flow.SharedFlow

/**
 * Режим media-plane для голоса, который позже можно привязать к пользовательской настройке.
 */
enum class VoiceTransportMode {
    NEARBY_BYTES,
    WIFI_DIRECT_UDP,
}

/**
 * Роль текущего устройства в voice media-plane сессии комнаты.
 */
enum class VoiceTransportSessionRole {
    HOST,
    CLIENT,
}

/**
 * Минимальный контракт media-plane для отправки и приема уже закодированных voice frames независимо от конкретного транспорта.
 */
interface VoiceTransport {
    /**
     * События голосового транспорта: входящие frames и ошибки отправки, которые не должны ронять комнату.
     */
    val events: SharedFlow<VoiceTransportEvent>

    /**
     * Последняя локальная служебная информация, которую RoomRuntime может отправить соседям через signaling.
     */
    val localControlInfo: VoiceTransportControlInfo?

    /**
     * Запускает media-plane сессию для текущей роли комнаты.
     */
    fun startSession(selfPeerId: PeerId, role: VoiceTransportSessionRole)

    /**
     * Останавливает media-plane сессию и освобождает временные transport-состояния.
     */
    fun stopSession()

    /**
     * Передает transport-специфичную служебную информацию от другого участника.
     */
    fun handleControlInfo(fromPeerId: PeerId, info: VoiceTransportControlInfo)

    /**
     * Проверяет, завершен ли media-plane handshake со всеми указанными получателями.
     */
    fun isReadyForPeers(peerIds: Set<PeerId>): Boolean

    /**
     * Отправляет один voice frame выбранным соседям/участникам через текущий транспорт.
     */
    fun sendFrameToPeers(peerIds: Set<PeerId>, frame: VoiceFrame)
}

/**
 * Событие media-plane, нормализованное для RoomRuntime и VoiceRuntime без привязки к Nearby endpoint-ам как API.
 */
sealed class VoiceTransportEvent {
    /**
     * Локальная transport-информация изменилась и должна быть разослана участникам комнаты через signaling.
     */
    data class LocalControlInfoChanged(
        val info: VoiceTransportControlInfo,
    ) : VoiceTransportEvent()

    /**
     * Локальный media-plane готов принимать direct-аудио для текущей роли.
     */
    object DirectAudioReady : VoiceTransportEvent()

    /**
     * Транспорт получил voice frame от прямого соседа.
     */
    data class FrameReceived(
        val transportPeerId: PeerId?,
        val frame: VoiceFrame,
        val transportEndpointId: String?,
    ) : VoiceTransportEvent()

    /**
     * Транспорт не смог отправить voice frame выбранным участникам.
     */
    data class FrameSendFailed(
        val peerIds: Set<PeerId>,
        val cause: Throwable,
    ) : VoiceTransportEvent()

    /**
     * Текущий media-plane недоступен на устройстве, но signaling/чат могут продолжать работать.
     */
    data class TransportUnavailable(
        val message: String,
    ) : VoiceTransportEvent()
}
