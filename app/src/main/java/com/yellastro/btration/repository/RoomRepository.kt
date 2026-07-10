package com.yellastro.btration.repository

import android.util.Log
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.runtime.RoomRuntime
import com.yellastro.btration.domain.runtime.RoomRuntimeState
import com.yellastro.btration.service.RoomServiceController

/**
 * Фасад комнаты для ViewModel: прокидывает команды комнаты, чата и голоса в runtime и управляет service-обвязкой.
 */
class RoomRepository(
    private val roomRuntime: RoomRuntime,
    private val roomServiceController: RoomServiceController,
) {
    /**
     * Состояние runtime для наблюдения из ViewModel.
     */
    val runtimeState = roomRuntime.state

    /**
     * Найденные рядом комнаты.
     */
    val availableRooms = roomRuntime.availableRooms

    /**
     * Сообщения текущей комнаты.
     */
    val messages = roomRuntime.messages

    /**
     * Участники, которые сейчас передают или локально доигрывают голос.
     */
    val talkingPeerIds = roomRuntime.talkingPeerIds

    /**
     * Возвращает PeerId локального пользователя для маппинга UI-состояний комнаты.
     */
    fun getSelfPeerId(): PeerId {
        Log.i(TAG, "[getSelfPeerId] Запрошен PeerId локального пользователя")
        return roomRuntime.getSelfPeerId()
    }

    /**
     * Запускает поиск комнат и поднимает foreground-обвязку для активного состояния.
     */
    suspend fun startSearch() {
        Log.i(TAG, "[startSearch] Запускаем поиск через RoomRepository")
        roomRuntime.startSearch()
        roomServiceController.startIfNeeded(roomRuntime.state.value.needsService())
        Log.i(TAG, "[startSearch] Поиск обработан state=${roomRuntime.state.value.javaClass.simpleName}")
    }

    /**
     * Останавливает поиск комнат, не трогая service-обвязку.
     */
    suspend fun stopSearch() {
        Log.i(TAG, "[stopSearch] Останавливаем поиск через RoomRepository")
        roomRuntime.stopSearch()
        roomServiceController.stopIfIdle(roomRuntime.state.value.isIdle())
        Log.i(TAG, "[stopSearch] Остановка поиска обработана state=${roomRuntime.state.value.javaClass.simpleName}")
    }

    /**
     * Создает комнату и поднимает foreground-обвязку для активного состояния.
     */
    suspend fun createRoom(name: String) {
        Log.i(TAG, "[createRoom] Создаем комнату через RoomRepository name=$name")
        roomRuntime.createRoom(name)
        roomServiceController.startIfNeeded(roomRuntime.state.value.needsService())
        Log.i(TAG, "[createRoom] Создание комнаты обработано state=${roomRuntime.state.value.javaClass.simpleName}")
    }

    /**
     * Входит в найденную комнату и поднимает foreground-обвязку для активного состояния.
     */
    suspend fun joinRoom(roomId: RoomId) {
        Log.i(TAG, "[joinRoom] Входим в комнату через RoomRepository roomId=${roomId.value}")
        roomRuntime.joinRoom(roomId)
        roomServiceController.startIfNeeded(roomRuntime.state.value.needsService())
        Log.i(TAG, "[joinRoom] Вход обработан state=${roomRuntime.state.value.javaClass.simpleName}")
    }

    /**
     * Покидает текущую комнату и просит service-обвязку остановиться, если runtime idle.
     */
    suspend fun leaveRoom() {
        Log.i(TAG, "[leaveRoom] Покидаем комнату через RoomRepository")
        roomRuntime.leaveRoom()
        roomServiceController.stopIfIdle(roomRuntime.state.value.isIdle())
        Log.i(TAG, "[leaveRoom] Выход обработан state=${roomRuntime.state.value.javaClass.simpleName}")
    }

    /**
     * Закрывает хостимую комнату и просит service-обвязку остановиться, если runtime idle.
     */
    suspend fun closeRoom() {
        Log.i(TAG, "[closeRoom] Закрываем комнату через RoomRepository")
        roomRuntime.closeRoom()
        roomServiceController.stopIfIdle(roomRuntime.state.value.isIdle())
        Log.i(TAG, "[closeRoom] Закрытие обработано state=${roomRuntime.state.value.javaClass.simpleName}")
    }

    /**
     * Отправляет текстовое сообщение в текущую комнату.
     */
    suspend fun sendMessage(text: String) {
        Log.i(TAG, "[sendMessage] Отправляем сообщение через RoomRepository textLength=${text.length}")
        roomRuntime.sendMessage(text)
    }

    /**
     * Начинает передачу микрофона в активную комнату и возвращает true при реальном старте.
     */
    suspend fun startTalking(): Boolean {
        Log.i(TAG, "[startTalking] Запускаем передачу голоса через RoomRepository")
        val started = roomRuntime.startTalking()
        roomServiceController.startIfNeeded(roomRuntime.state.value.needsService())
        return started
    }

    /**
     * Останавливает передачу микрофона.
     */
    suspend fun stopTalking() {
        Log.i(TAG, "[stopTalking] Останавливаем передачу голоса через RoomRepository")
        roomRuntime.stopTalking()
    }

    /**
     * Возвращает true, если runtime уже не ищет комнаты и не держит активное соединение.
     */
    private fun RoomRuntimeState.isIdle(): Boolean {
        return this is RoomRuntimeState.Idle || this is RoomRuntimeState.Error
    }

    /**
     * Возвращает true, если runtime находится в состоянии, которое должно держать foreground service.
     */
    private fun RoomRuntimeState.needsService(): Boolean {
        return !isIdle()
    }

    private companion object {
        private const val TAG = "RoomRepository"
    }
}
