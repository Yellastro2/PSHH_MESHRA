package com.yellastro.btration.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.yellastro.btration.BtRationApplication
import com.yellastro.btration.MainActivity
import com.yellastro.btration.R
import com.yellastro.btration.domain.model.ChatMessage
import com.yellastro.btration.domain.model.MessageId
import com.yellastro.btration.domain.runtime.RoomRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service, который показывает постоянное уведомление о поиске или активной комнате,
 * отражает закрепленный микрофон и позволяет выключить его из notification action.
 */
class RoomConnectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateCollectorStarted = false
    private var messageCollectorStarted = false
    private var latestKnownMessageId: MessageId? = null

    private val roomRepository
        get() = (application as BtRationApplication).appContainer.roomRepository

    private val appVisibilityTracker
        get() = (application as BtRationApplication).appVisibilityTracker

    /**
     * Создает notification channels и запускает foreground notification
     * с актуальным признаком закрепленного микрофона.
     */
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[onCreate] Создаем RoomConnectionService")
        createNotificationChannels()
        startForegroundSafely(
            notification = buildForegroundNotification(
                state = roomRepository.runtimeState.value,
                isMicrophoneLocked = roomRepository.isMicrophoneLocked.value,
            ),
            includesMicrophone = roomRepository.isMicrophoneLocked.value,
        )
    }

    /**
     * Обрабатывает команды запуска/остановки, отключения runtime и микрофона без sticky-восстановления пустой runtime-сессии.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "[onStartCommand] Получена команда action=${intent?.action} startId=$startId")
        if (intent == null) {
            Log.w(TAG, "[onStartCommand] Service перезапущен системой без команды, останавливаемся чтобы не держать пустую комнату")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_DISCONNECT) {
            Log.i(TAG, "[onStartCommand] Отключаем runtime по ACTION_DISCONNECT")
            handleDisconnectAction()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "[onStartCommand] Останавливаем service по ACTION_STOP")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_DISABLE_MICROPHONE) {
            Log.i(TAG, "[onStartCommand] Отключаем закрепленный микрофон по ACTION_DISABLE_MICROPHONE")
            handleDisableMicrophoneAction()
        }

        startStateCollection()
        startMessageNotificationCollection()
        return START_NOT_STICKY
    }

    /**
     * Сервис не поддерживает bind API.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Освобождает coroutine scope при остановке сервиса.
     */
    override fun onDestroy() {
        Log.i(TAG, "[onDestroy] Уничтожаем RoomConnectionService")
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Подписывается на состояние комнаты и признак закрепленного микрофона,
     * чтобы обновлять foreground notification и его service type.
     */
    private fun startStateCollection() {
        if (stateCollectorStarted) {
            Log.i(TAG, "[startStateCollection] Подписка на state уже запущена")
            return
        }
        Log.i(TAG, "[startStateCollection] Запускаем подписку на state runtime")
        stateCollectorStarted = true
        serviceScope.launch {
            combine(
                roomRepository.runtimeState,
                roomRepository.isMicrophoneLocked,
            ) { state, isMicrophoneLocked ->
                state to isMicrophoneLocked
            }.collectLatest { (state, isMicrophoneLocked) ->
                Log.i(
                    TAG,
                    "[startStateCollection] Получено состояние runtime state=${state.javaClass.simpleName} isMicrophoneLocked=$isMicrophoneLocked",
                )
                if (state is RoomRuntimeState.Idle || state is RoomRuntimeState.Error) {
                    Log.i(TAG, "[startStateCollection] Runtime idle/error, останавливаем service")
                    stopSelf()
                    return@collectLatest
                }
                updateForegroundNotification(state, isMicrophoneLocked)
            }
        }
    }

    /**
     * Подписывается на сообщения и показывает notification для новых чужих сообщений в фоне.
     */
    private fun startMessageNotificationCollection() {
        if (messageCollectorStarted) {
            Log.i(TAG, "[startMessageNotificationCollection] Подписка на сообщения уже запущена")
            return
        }
        Log.i(TAG, "[startMessageNotificationCollection] Запускаем подписку на сообщения")
        messageCollectorStarted = true
        serviceScope.launch {
            roomRepository.messages.collect(::handleMessagesChanged)
        }
    }

    /**
     * Отключает текущую активность комнаты или поиска из action-кнопки foreground notification.
     */
    private fun handleDisconnectAction() {
        serviceScope.launch {
            runCatching {
                roomRepository.leaveRoom()
                Log.i(TAG, "[handleDisconnectAction] Runtime отключен из кнопки уведомления")
            }.onFailure { cause ->
                Log.w(TAG, "[handleDisconnectAction] Не удалось отключить runtime из кнопки уведомления: ${cause.message}", cause)
            }
            stopSelf()
        }
    }

    /**
     * Останавливает передачу микрофона из action-кнопки, не покидая текущую комнату.
     */
    private fun handleDisableMicrophoneAction() {
        serviceScope.launch {
            runCatching {
                roomRepository.stopTalking()
                Log.i(TAG, "[handleDisableMicrophoneAction] Микрофон отключен из кнопки уведомления")
            }.onFailure { cause ->
                Log.w(
                    TAG,
                    "[handleDisableMicrophoneAction] Не удалось отключить микрофон из кнопки уведомления: ${cause.message}",
                    cause,
                )
            }
        }
    }

    /**
     * Обрабатывает новое состояние списка сообщений без уведомления о старой истории.
     */
    private fun handleMessagesChanged(messages: List<ChatMessage>) {
        val newestMessage = messages.lastOrNull()
        if (newestMessage == null) {
            latestKnownMessageId = null
            Log.i(TAG, "[handleMessagesChanged] Список сообщений пуст")
            return
        }

        val previousMessageId = latestKnownMessageId
        latestKnownMessageId = newestMessage.messageId
        if (previousMessageId == null || previousMessageId == newestMessage.messageId) {
            Log.i(TAG, "[handleMessagesChanged] Новых сообщений для notification нет newestMessageId=${newestMessage.messageId.value}")
            return
        }

        val previousIndex = messages.indexOfFirst { message -> message.messageId == previousMessageId }
        val newMessages = if (previousIndex == -1) {
            listOf(newestMessage)
        } else {
            messages.drop(previousIndex + 1)
        }

        newMessages
            .filter(::shouldShowMessageNotification)
            .forEach(::showMessageNotification)
        Log.i(TAG, "[handleMessagesChanged] Обработаны новые сообщения count=${newMessages.size}")
    }

    /**
     * Возвращает true, если сообщение должно стать отдельным notification.
     */
    private fun shouldShowMessageNotification(message: ChatMessage): Boolean {
        val shouldShow = !appVisibilityTracker.isAppVisible &&
            message.author.peerId != roomRepository.getSelfPeerId() &&
            canPostNotifications()
        Log.i(
            TAG,
            "[shouldShowMessageNotification] Проверка notification messageId=${message.messageId.value} shouldShow=$shouldShow appVisible=${appVisibilityTracker.isAppVisible}",
        )
        return shouldShow
    }

    /**
     * Проверяет, можно ли сейчас показывать notifications.
     */
    private fun canPostNotifications(): Boolean {
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "[canPostNotifications] Проверка POST_NOTIFICATIONS result=$canPost")
        return canPost
    }

    /**
     * Показывает отдельное notification для нового сообщения комнаты.
     */
    @SuppressLint("MissingPermission")
    private fun showMessageNotification(message: ChatMessage) {
        runCatching {
            NotificationManagerCompat.from(this).notify(
                notificationIdFor(message.messageId),
                buildMessageNotification(message),
            )
            Log.i(TAG, "[showMessageNotification] Notification сообщения показан messageId=${message.messageId.value}")
        }.onFailure { cause ->
            Log.w(TAG, "[showMessageNotification] Не удалось показать notification сообщения messageId=${message.messageId.value}: ${cause.message}", cause)
        }
    }

    /**
     * Создает notification для нового сообщения комнаты.
     */
    private fun buildMessageNotification(message: ChatMessage): Notification {
        val title = message.author.name
        val text = message.text
        return NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(createOpenAppPendingIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    /**
     * Безопасно переводит сервис в foreground с типом connectedDevice и при закреплении
     * на поддерживаемых SDK добавляет microphone, чтобы запись могла продолжаться вне активного экрана приложения.
     */
    @SuppressLint("MissingPermission")
    private fun startForegroundSafely(notification: Notification, includesMicrophone: Boolean) {
        val foregroundServiceType = if (
            includesMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                FOREGROUND_NOTIFICATION_ID,
                notification,
                foregroundServiceType,
            )
            Log.i(
                TAG,
                "[startForegroundSafely] Foreground notification применен includesMicrophone=$includesMicrophone",
            )
        }.onFailure { cause ->
            Log.w(TAG, "[startForegroundSafely] Не удалось перевести service в foreground: ${cause.message}", cause)
            stopSelf()
        }
    }

    /**
     * Обновляет foreground notification и активный service type по состоянию runtime и закрепления микрофона.
     */
    private fun updateForegroundNotification(state: RoomRuntimeState, isMicrophoneLocked: Boolean) {
        startForegroundSafely(
            notification = buildForegroundNotification(state, isMicrophoneLocked),
            includesMicrophone = isMicrophoneLocked,
        )
        Log.i(
            TAG,
            "[updateForegroundNotification] Foreground notification обновлен state=${state.javaClass.simpleName} isMicrophoneLocked=$isMicrophoneLocked",
        )
    }

    /**
     * Создает notification о текущем состоянии поиска или комнаты и добавляет управление закрепленным микрофоном.
     */
    private fun buildForegroundNotification(
        state: RoomRuntimeState,
        isMicrophoneLocked: Boolean,
    ): Notification {
        val title = when (state) {
            is RoomRuntimeState.Hosting -> "$APP_DISPLAY_NAME: комната запущена"
            is RoomRuntimeState.Client -> "$APP_DISPLAY_NAME: вы в комнате"
            is RoomRuntimeState.Joining -> "$APP_DISPLAY_NAME: подключение к комнате"
            RoomRuntimeState.Searching -> "$APP_DISPLAY_NAME: поиск комнат"
            RoomRuntimeState.Idle,
            is RoomRuntimeState.Error,
            -> APP_DISPLAY_NAME
        }
        val runtimeText = when (state) {
            is RoomRuntimeState.Hosting -> "Хостим ${state.room.name}"
            is RoomRuntimeState.Client -> "Подключены к ${state.room.name}"
            is RoomRuntimeState.Joining -> "Входим в ${state.room.name}"
            RoomRuntimeState.Searching -> "Ищем комнаты поблизости"
            RoomRuntimeState.Idle -> "Соединение не активно"
            is RoomRuntimeState.Error -> state.message
        }
        val text = if (isMicrophoneLocked) {
            "$MICROPHONE_ENABLED_TEXT · $runtimeText"
        } else {
            runtimeText
        }

        val builder = NotificationCompat.Builder(this, ROOM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(createOpenAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (isMicrophoneLocked) {
            builder.addAction(
                R.drawable.ic_mute,
                DISABLE_MICROPHONE_ACTION_TITLE,
                createDisableMicrophonePendingIntent(),
            )
        }
        builder.addAction(R.drawable.ic_radio, DISCONNECT_ACTION_TITLE, createDisconnectPendingIntent())
        return builder.build()
    }

    /**
     * Создает channels для foreground notification и сообщений комнаты.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(TAG, "[createNotificationChannels] Notification channels не нужны для SDK=${Build.VERSION.SDK_INT}")
            return
        }
        val foregroundChannel = NotificationChannel(
            ROOM_CHANNEL_ID,
            "Активная комната",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Постоянное уведомление о поиске или активной комнате PSHH MESHRA"
        }
        val messageChannel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            "Сообщения комнаты",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Уведомления о новых сообщениях, когда PSHH MESHRA не на экране"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(foregroundChannel, messageChannel),
        )
        Log.i(TAG, "[createNotificationChannels] Notification channels созданы")
    }

    /**
     * Создает PendingIntent для открытия приложения из notification.
     */
    private fun createOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Создает PendingIntent для отключения комнаты или поиска из foreground notification.
     */
    private fun createDisconnectPendingIntent(): PendingIntent {
        return PendingIntent.getService(
            this,
            DISCONNECT_REQUEST_CODE,
            createDisconnectIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Создает PendingIntent для отключения только закрепленного микрофона без выхода из комнаты.
     */
    private fun createDisableMicrophonePendingIntent(): PendingIntent {
        return PendingIntent.getService(
            this,
            DISABLE_MICROPHONE_REQUEST_CODE,
            createDisableMicrophoneIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Возвращает стабильный notification id для сообщения.
     */
    private fun notificationIdFor(messageId: MessageId): Int {
        return MESSAGE_NOTIFICATION_BASE_ID + ((messageId.value.hashCode() and Int.MAX_VALUE) % MESSAGE_NOTIFICATION_BUCKET)
    }

    /**
     * Factory-команды сервиса.
     */
    companion object {
        private const val TAG = "RoomConnectionService"
        private const val APP_DISPLAY_NAME = "PSHH MESHRA"
        private const val ACTION_START = "com.yellastro.btration.service.START_ROOM_CONNECTION"
        private const val ACTION_STOP = "com.yellastro.btration.service.STOP_ROOM_CONNECTION"
        private const val ACTION_DISCONNECT = "com.yellastro.btration.service.DISCONNECT_ROOM_CONNECTION"
        private const val ACTION_DISABLE_MICROPHONE = "com.yellastro.btration.service.DISABLE_MICROPHONE"
        private const val ROOM_CHANNEL_ID = "room_connection"
        private const val MESSAGE_CHANNEL_ID = "room_messages"
        private const val DISCONNECT_ACTION_TITLE = "Отключить"
        private const val DISABLE_MICROPHONE_ACTION_TITLE = "Откл. микро"
        private const val MICROPHONE_ENABLED_TEXT = "Микрофон включён"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val MESSAGE_NOTIFICATION_BASE_ID = 2_000
        private const val MESSAGE_NOTIFICATION_BUCKET = 100_000
        private const val OPEN_APP_REQUEST_CODE = 2001
        private const val DISCONNECT_REQUEST_CODE = 2002
        private const val DISABLE_MICROPHONE_REQUEST_CODE = 2003

        /**
         * Создает intent запуска foreground service.
         */
        fun createStartIntent(context: Context): Intent {
            return Intent(context, RoomConnectionService::class.java).apply {
                action = ACTION_START
            }
        }

        /**
         * Создает intent остановки foreground service.
         */
        fun createStopIntent(context: Context): Intent {
            return Intent(context, RoomConnectionService::class.java).apply {
                action = ACTION_STOP
            }
        }

        /**
         * Создает intent отключения комнаты или поиска из foreground notification.
         */
        fun createDisconnectIntent(context: Context): Intent {
            return Intent(context, RoomConnectionService::class.java).apply {
                action = ACTION_DISCONNECT
            }
        }

        /**
         * Создает intent отключения закрепленного микрофона без выхода из комнаты.
         */
        fun createDisableMicrophoneIntent(context: Context): Intent {
            return Intent(context, RoomConnectionService::class.java).apply {
                action = ACTION_DISABLE_MICROPHONE
            }
        }
    }
}
