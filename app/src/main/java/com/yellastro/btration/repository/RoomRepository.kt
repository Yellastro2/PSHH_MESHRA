package com.yellastro.btration.repository

import android.util.Log
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.RoomId
import com.yellastro.btration.domain.model.RoomTransportMode
import com.yellastro.btration.domain.runtime.RoomRuntime
import com.yellastro.btration.domain.runtime.RoomRuntimeState
import com.yellastro.btration.service.RoomServiceController
import com.yellastro.btration.voice.VoiceAudioProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Фасад комнаты для ViewModel и foreground service: прокидывает discovery, создание с voice-профилем,
 * команды комнаты, чат, состояние микрофона, mesh-connect статусы и ping в runtime.
 */
class RoomRepository(
    private val roomRuntime: RoomRuntime,
    private val roomServiceController: RoomServiceController,
) {
    private val _isTalking = MutableStateFlow(false)
    private val _isMicrophoneLocked = MutableStateFlow(false)
    private val microphoneMutex = Mutex()

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
     * PeerId участников, с которыми у текущего устройства есть прямой mesh link без LOST heartbeat-статуса.
     */
    val directMeshPeerIds = roomRuntime.directMeshPeerIds

    /**
     * Ping/status прямых mesh-соседей для UI карточек участников.
     */
    val meshPeerConnectionStates = roomRuntime.meshPeerConnectionStates

    /**
     * Одноразовые уведомления runtime для snackbar во ViewModel/UI.
     */
    val notices = roomRuntime.notices

    /**
     * Показывает UI и service, передает ли локальный пользователь голос прямо сейчас.
     */
    val isTalking = _isTalking.asStateFlow()

    /**
     * Показывает foreground service, закреплена ли передача без удержания PTT-кнопки.
     */
    val isMicrophoneLocked = _isMicrophoneLocked.asStateFlow()

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
     * Завершает текущий снимок Nearby-комнат и запускает следующий без повторного старта foreground service.
     */
    suspend fun refreshSearch() {
        Log.i(TAG, "[refreshSearch] Обновляем список комнат через новый discovery-цикл")
        roomRuntime.refreshSearch()
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
     * Создает комнату с выбранными room transport и voice-профилем и поднимает foreground-обвязку.
     */
    suspend fun createRoom(
        name: String,
        roomTransportMode: RoomTransportMode,
        voiceAudioProfile: VoiceAudioProfile,
    ) {
        Log.i(
            TAG,
            "[createRoom] Создаем комнату через RoomRepository name=$name roomTransportMode=$roomTransportMode frameMs=${voiceAudioProfile.frameDuration.millis}",
        )
        roomRuntime.createRoom(name, roomTransportMode, voiceAudioProfile)
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
     * Покидает текущую комнату, сбрасывает локальное состояние микрофона
     * и просит service-обвязку остановиться, если runtime idle.
     */
    suspend fun leaveRoom() {
        Log.i(TAG, "[leaveRoom] Покидаем комнату через RoomRepository")
        roomRuntime.leaveRoom()
        resetMicrophoneState()
        roomServiceController.stopIfIdle(roomRuntime.state.value.isIdle())
        Log.i(TAG, "[leaveRoom] Выход обработан state=${roomRuntime.state.value.javaClass.simpleName}")
    }

    /**
     * Закрывает хостимую комнату, сбрасывает локальное состояние микрофона
     * и просит service-обвязку остановиться, если runtime idle.
     */
    suspend fun closeRoom() {
        Log.i(TAG, "[closeRoom] Закрываем комнату через RoomRepository")
        roomRuntime.closeRoom()
        resetMicrophoneState()
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
     * Сериализованно начинает передачу микрофона в активную комнату, публикует результат
     * для UI/service и возвращает true при реальном старте.
     */
    suspend fun startTalking(): Boolean {
        Log.i(TAG, "[startTalking] Запускаем передачу голоса через RoomRepository")
        return microphoneMutex.withLock {
            val started = roomRuntime.startTalking()
            _isTalking.value = started
            if (!started) {
                _isMicrophoneLocked.value = false
            }
            roomServiceController.startIfNeeded(roomRuntime.state.value.needsService())
            started
        }
    }

    /**
     * Сразу публикует выключение микрофона, снимает закрепление и сериализованно
     * останавливает runtime-передачу после возможного незавершенного старта.
     */
    suspend fun stopTalking() {
        Log.i(TAG, "[stopTalking] Останавливаем передачу голоса через RoomRepository")
        resetMicrophoneState()
        microphoneMutex.withLock {
            _isTalking.value = false
            _isMicrophoneLocked.value = false
            roomRuntime.stopTalking()
        }
    }

    /**
     * Публикует включение или снятие закрепленного PTT-режима для foreground notification.
     */
    fun setMicrophoneLocked(isLocked: Boolean) {
        _isMicrophoneLocked.value = isLocked
        Log.i(TAG, "[setMicrophoneLocked] Обновляем закрепление микрофона isLocked=$isLocked")
    }

    /**
     * Возвращает общие локальные признаки микрофона в выключенное состояние.
     */
    private fun resetMicrophoneState() {
        _isTalking.value = false
        _isMicrophoneLocked.value = false
        Log.i(TAG, "[resetMicrophoneState] Локальное состояние микрофона сброшено")
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
