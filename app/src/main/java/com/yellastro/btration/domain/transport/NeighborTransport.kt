package com.yellastro.btration.domain.transport

import java.io.InputStream
import kotlinx.coroutines.flow.SharedFlow

/**
 * Общий контракт соседского транспорта: topology-aware discovery/advertising/connect, lifecycle и передача байтов.
 */
interface NeighborTransport {
    /**
     * Поток низкоуровневых событий транспорта без знания протокола комнаты, аудио или другого контента.
     */
    val neighborEvents: SharedFlow<NeighborTransportEvent>

    /**
     * Запускает поиск кандидатов в одном topology-режиме либо попеременно в Star и Cluster для общего лобби.
     */
    fun startDiscovery(mode: NeighborDiscoveryMode = NeighborDiscoveryMode.ALTERNATING)

    /**
     * Останавливает поиск соседских кандидатов.
     */
    fun stopDiscovery()

    /**
     * Запускает публикацию локального устройства с визиткой и topology-режимом комнаты.
     */
    fun startAdvertising(advertisement: NeighborAdvertisement, topology: NeighborTopology)

    /**
     * Останавливает публикацию локального устройства.
     */
    fun stopAdvertising()

    /**
     * Запрашивает соединение с кандидатом через topology, заявленную режимом комнаты.
     */
    fun connect(candidateId: NeighborCandidateId, topology: NeighborTopology)

    /**
     * Принимает входящее соединение.
     */
    fun acceptConnection(connectionId: NeighborConnectionId)

    /**
     * Отклоняет входящее соединение.
     */
    fun rejectConnection(connectionId: NeighborConnectionId)

    /**
     * Разрывает конкретный активный линк.
     */
    fun disconnect(linkId: NeighborLinkId)

    /**
     * Разрывает все активные линк-соединения без обязательного сброса discovery-кандидатов.
     */
    fun disconnectAll()

    /**
     * Полностью останавливает транспорт и очищает его локальное состояние.
     */
    fun stopAll(reason: String)

    /**
     * Отправляет атомарное сообщение байт в один линк и возвращает ошибку только владельцу этой отправки.
     *
     * Флаг isRealtime помечает частые payload-ы голоса/медиа, чтобы нижний транспорт мог логировать их агрегированно.
     */
    fun sendMessage(
        linkId: NeighborLinkId,
        bytes: ByteArray,
        isRealtime: Boolean = false,
        onFailure: (Throwable) -> Unit = {},
    )

    /**
     * Отправляет атомарное сообщение байт в несколько линков и возвращает ошибку только владельцу этой отправки.
     *
     * Флаг isRealtime помечает частые payload-ы голоса/медиа, чтобы нижний транспорт мог логировать их агрегированно.
     */
    fun sendMessage(
        linkIds: Collection<NeighborLinkId>,
        bytes: ByteArray,
        isRealtime: Boolean = false,
        onFailure: (Throwable) -> Unit = {},
    )

    /**
     * Отправляет поток байт в несколько линков и возвращает ошибку только владельцу этой отправки.
     */
    fun sendStream(linkIds: Collection<NeighborLinkId>, inputStream: InputStream, onFailure: (Throwable) -> Unit = {})
}

/**
 * Физическая Nearby topology, которую доменный слой выбирает независимо от Google SDK-классов.
 */
enum class NeighborTopology {
    STAR,
    CLUSTER,
}

/**
 * Режим discovery: фиксированная topology для подключения/healing либо чередование обеих topology в лобби.
 */
enum class NeighborDiscoveryMode {
    STAR_ONLY,
    CLUSTER_ONLY,
    ALTERNATING,
}

/**
 * Транспортная визитка для advertising без привязки к конкретному SDK.
 */
data class NeighborAdvertisement(
    val endpointName: String,
)

/**
 * Найденный соседский кандидат, к которому можно попытаться подключиться.
 */
data class NeighborCandidate(
    val candidateId: NeighborCandidateId,
    val endpointName: String,
    val serviceId: String,
)

/**
 * Идентификатор найденного кандидата на соединение.
 */
@JvmInline
value class NeighborCandidateId(val value: String)

/**
 * Идентификатор входящего connection request.
 */
@JvmInline
value class NeighborConnectionId(val value: String)

/**
 * Идентификатор установленного соседского линка.
 */
@JvmInline
value class NeighborLinkId(val value: String)

/**
 * Транспортно-нейтральная сводка статуса передачи payload-а.
 */
data class NeighborPayloadTransferState(
    val payloadId: Long,
    val status: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
)

/**
 * Событие общего соседского транспорта, не зависящее от Nearby Connections API.
 */
sealed class NeighborTransportEvent {
    /**
     * Транспорт нашел кандидата для подключения.
     */
    data class CandidateFound(val candidate: NeighborCandidate) : NeighborTransportEvent()

    /**
     * Транспорт потерял ранее найденного кандидата.
     */
    data class CandidateLost(val candidateId: NeighborCandidateId) : NeighborTransportEvent()

    /**
     * Удаленное устройство запросило входящее соединение.
     */
    data class ConnectionInitiated(
        val connectionId: NeighborConnectionId,
        val endpointName: String,
    ) : NeighborTransportEvent()

    /**
     * Входящее соединение не удалось принять.
     */
    data class ConnectionAcceptFailed(
        val connectionId: NeighborConnectionId,
        val cause: Throwable,
    ) : NeighborTransportEvent()

    /**
     * Входящее соединение не удалось отклонить.
     */
    data class ConnectionRejectFailed(
        val connectionId: NeighborConnectionId,
        val cause: Throwable,
    ) : NeighborTransportEvent()

    /**
     * Исходящее соединение не удалось запросить.
     */
    data class ConnectionRequestFailed(
        val candidateId: NeighborCandidateId,
        val cause: Throwable,
    ) : NeighborTransportEvent()

    /**
     * Транспорт просит полностью восстановить линк после recoverable ошибки.
     */
    data class ConnectionRecoveryRequired(
        val linkId: NeighborLinkId,
        val cause: Throwable,
    ) : NeighborTransportEvent()

    /**
     * Линк стал готов к передаче данных.
     */
    data class LinkConnected(
        val linkId: NeighborLinkId,
        val statusCode: Int,
        val reused: Boolean,
    ) : NeighborTransportEvent()

    /**
     * Попытка подключения завершилась отказом или ошибкой.
     */
    data class LinkConnectionFailed(
        val linkId: NeighborLinkId,
        val statusCode: Int,
    ) : NeighborTransportEvent()

    /**
     * Активный линк был разорван.
     */
    data class LinkDisconnected(val linkId: NeighborLinkId) : NeighborTransportEvent()

    /**
     * Discovery не удалось запустить.
     */
    data class DiscoveryFailed(val cause: Throwable) : NeighborTransportEvent()

    /**
     * Advertising не удалось запустить.
     */
    data class AdvertisingFailed(val cause: Throwable) : NeighborTransportEvent()

    /**
     * В один из линков пришло атомарное сообщение байт.
     */
    data class MessageReceived(
        val linkId: NeighborLinkId,
        val bytes: ByteArray,
    ) : NeighborTransportEvent() {
        /**
         * Сравнивает byte-array по содержимому, потому что data class иначе сравнит ссылки.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as MessageReceived

            if (linkId != other.linkId) return false
            return bytes.contentEquals(other.bytes)
        }

        /**
         * Возвращает hash с учетом содержимого byte-array.
         */
        override fun hashCode(): Int {
            var result = linkId.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    /**
     * В один из линков пришел поток байт.
     */
    data class StreamReceived(
        val linkId: NeighborLinkId,
        val inputStream: InputStream,
    ) : NeighborTransportEvent()

    /**
     * Транспорт получил неподдерживаемый тип payload-а.
     */
    data class UnsupportedPayloadReceived(
        val linkId: NeighborLinkId,
        val payloadType: Int,
    ) : NeighborTransportEvent()

    /**
     * Входящий payload не удалось прочитать из нижнего transport API.
     */
    data class PayloadReadFailed(
        val linkId: NeighborLinkId,
        val cause: Throwable,
    ) : NeighborTransportEvent()

    /**
     * Транспорт сообщил промежуточное состояние передачи payload-а.
     */
    data class PayloadTransferUpdated(
        val linkId: NeighborLinkId,
        val state: NeighborPayloadTransferState,
    ) : NeighborTransportEvent()

}
