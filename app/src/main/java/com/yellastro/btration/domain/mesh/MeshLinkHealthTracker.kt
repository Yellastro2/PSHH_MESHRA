package com.yellastro.btration.domain.mesh

import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.transport.NeighborLinkId
import java.util.ArrayDeque

/**
 * Состояние живости прямого MESHRA link-а, рассчитанное по Nearby lifecycle и app-level heartbeat.
 */
data class MeshLinkHealth(
    val linkId: NeighborLinkId,
    val status: MeshLinkStatus,
    val rttMillis: Long?,
    val lossPercent: Int,
    val missedInRow: Int,
    val sentCount: Long,
    val receivedPongCount: Long,
    val lastSeenMillis: Long,
)

/**
 * Состояние прямого MESHRA-соседа после привязки транспортного link-а к PeerId.
 */
data class MeshPeerConnectionState(
    val peerId: PeerId,
    val status: MeshLinkStatus,
    val rttMillis: Long?,
    val lossPercent: Int,
    val missedInRow: Int,
)

/**
 * Упрощенный статус прямого соседского link-а для диагностики MESHRA.
 */
enum class MeshLinkStatus {
    CONNECTED,
    SUSPECT,
    LOST,
}

/**
 * Считает RTT, подряд потерянные heartbeat и процент потерь по небольшому окну без знания о Nearby SDK.
 */
class MeshLinkHealthTracker(
    private val heartbeatTimeoutMillis: Long,
    private val heartbeatLostMillis: Long,
    private val lossWindowSize: Int,
) {
    private val linkStates = mutableMapOf<NeighborLinkId, LinkState>()

    /**
     * Создает или сбрасывает health-состояние для только что установленного link-а.
     */
    @Synchronized
    fun onLinkConnected(linkId: NeighborLinkId, nowMillis: Long): MeshLinkHealth {
        val state = LinkState(lastSeenMillis = nowMillis)
        linkStates[linkId] = state
        return state.toHealth(linkId)
    }

    /**
     * Помечает link потерянным после явного disconnect от нижнего транспорта.
     */
    @Synchronized
    fun onLinkDisconnected(linkId: NeighborLinkId, nowMillis: Long): MeshLinkHealth {
        val state = linkStates.getOrPut(linkId) { LinkState(lastSeenMillis = nowMillis) }
        state.pendingPings.clear()
        state.status = MeshLinkStatus.LOST
        return state.toHealth(linkId)
    }

    /**
     * Готовит следующий ping и запоминает время отправки для будущего RTT.
     */
    @Synchronized
    fun preparePing(linkId: NeighborLinkId, nowMillis: Long): MeshHeartbeatPacket? {
        val state = linkStates[linkId] ?: return null
        val sequence = state.nextSequence
        state.nextSequence = (state.nextSequence + 1) and UNSIGNED_SHORT_MASK
        state.pendingPings[sequence] = nowMillis
        state.sentCount += 1
        return MeshHeartbeatPacket(kind = MeshHeartbeatKind.PING, sequence = sequence)
    }

    /**
     * Отмечает входящий ping как признак живого соседнего направления.
     */
    @Synchronized
    fun onPingReceived(linkId: NeighborLinkId, nowMillis: Long): MeshLinkHealth {
        val state = linkStates.getOrPut(linkId) { LinkState(lastSeenMillis = nowMillis) }
        state.lastSeenMillis = nowMillis
        refreshStatus(state, nowMillis)
        return state.toHealth(linkId)
    }

    /**
     * Отмечает входящий pong, считает RTT по локальной таблице sequence -> sentAt и обновляет потери.
     */
    @Synchronized
    fun onPongReceived(linkId: NeighborLinkId, sequence: Int, nowMillis: Long): MeshLinkHealth? {
        val state = linkStates[linkId] ?: return null
        val sentAtMillis = state.pendingPings.remove(sequence)
        state.lastSeenMillis = nowMillis
        if (sentAtMillis != null) {
            state.rttMillis = nowMillis - sentAtMillis
            state.receivedPongCount += 1
            state.missedInRow = 0
            recordOutcome(state, success = true)
        }
        refreshStatus(state, nowMillis)
        return state.toHealth(linkId)
    }

    /**
     * Учитывает ошибку отправки heartbeat как потерю текущего link-а.
     */
    @Synchronized
    fun onHeartbeatSendFailed(linkId: NeighborLinkId, nowMillis: Long): MeshLinkHealth? {
        val state = linkStates[linkId] ?: return null
        state.missedInRow += 1
        recordOutcome(state, success = false)
        refreshStatus(state, nowMillis)
        return state.toHealth(linkId)
    }

    /**
     * Закрывает просроченные ping-и и возвращает link-и, у которых появились новые потери.
     */
    @Synchronized
    fun collectTimedOut(nowMillis: Long): List<MeshLinkHealth> {
        return linkStates.mapNotNull { (linkId, state) ->
            val expiredSequences = state.pendingPings
                .filterValues { sentAtMillis -> nowMillis - sentAtMillis >= heartbeatTimeoutMillis }
                .keys
                .toList()
            if (expiredSequences.isEmpty()) {
                refreshStatus(state, nowMillis)
                return@mapNotNull null
            }
            expiredSequences.forEach { sequence -> state.pendingPings.remove(sequence) }
            state.missedInRow += expiredSequences.size
            repeat(expiredSequences.size) { recordOutcome(state, success = false) }
            refreshStatus(state, nowMillis)
            state.toHealth(linkId)
        }
    }

    /**
     * Возвращает последнее известное health-состояние link-а.
     */
    @Synchronized
    fun healthFor(linkId: NeighborLinkId): MeshLinkHealth? {
        return linkStates[linkId]?.toHealth(linkId)
    }

    /**
     * Возвращает snapshot всех известных health-состояний.
     */
    @Synchronized
    fun snapshot(): List<MeshLinkHealth> {
        return linkStates.map { (linkId, state) -> state.toHealth(linkId) }
    }

    /**
     * Очищает все link health-состояния при полном сбросе mesh transport-а.
     */
    @Synchronized
    fun clear() {
        linkStates.clear()
    }

    /**
     * Добавляет результат heartbeat в ограниченное окно потерь.
     */
    private fun recordOutcome(state: LinkState, success: Boolean) {
        state.lossWindow.addLast(success)
        while (state.lossWindow.size > lossWindowSize) {
            state.lossWindow.removeFirst()
        }
    }

    /**
     * Пересчитывает статус link-а по последнему контакту и числу подряд потерянных heartbeat.
     */
    private fun refreshStatus(state: LinkState, nowMillis: Long) {
        val silentMillis = nowMillis - state.lastSeenMillis
        state.status = when {
            silentMillis >= heartbeatLostMillis -> MeshLinkStatus.LOST
            state.missedInRow >= SUSPECT_MISSES_IN_ROW -> MeshLinkStatus.SUSPECT
            else -> MeshLinkStatus.CONNECTED
        }
    }

    /**
     * Строит immutable DTO текущего состояния link-а.
     */
    private fun LinkState.toHealth(linkId: NeighborLinkId): MeshLinkHealth {
        val total = lossWindow.size
        val lost = lossWindow.count { success -> !success }
        val lossPercent = if (total == 0) 0 else (lost * PERCENT_DIVISOR) / total
        return MeshLinkHealth(
            linkId = linkId,
            status = status,
            rttMillis = rttMillis,
            lossPercent = lossPercent,
            missedInRow = missedInRow,
            sentCount = sentCount,
            receivedPongCount = receivedPongCount,
            lastSeenMillis = lastSeenMillis,
        )
    }

    /**
     * Внутреннее изменяемое состояние одного link-а.
     */
    private data class LinkState(
        var lastSeenMillis: Long,
        var status: MeshLinkStatus = MeshLinkStatus.CONNECTED,
        var rttMillis: Long? = null,
        var missedInRow: Int = 0,
        var sentCount: Long = 0L,
        var receivedPongCount: Long = 0L,
        var nextSequence: Int = 0,
        val pendingPings: MutableMap<Int, Long> = mutableMapOf(),
        val lossWindow: ArrayDeque<Boolean> = ArrayDeque(),
    )

    private companion object {
        private const val UNSIGNED_SHORT_MASK = 0xFFFF
        private const val SUSPECT_MISSES_IN_ROW = 2
        private const val PERCENT_DIVISOR = 100
    }
}
