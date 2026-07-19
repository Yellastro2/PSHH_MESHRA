package com.yellastro.btration.data.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.Strategy
import com.yellastro.btration.domain.transport.NeighborAdvertisement
import java.util.concurrent.ConcurrentHashMap

/**
 * Управляет Nearby discovery/advertising с переданной на каждый запуск Strategy и lifecycle соединений без декодирования payload-ов.
 */
internal class NearbyConnectionLayer(
    private val context: Context,
    private val connectionsClient: ConnectionsClient,
    private val serviceId: String,
    private val payloadCallback: PayloadCallback,
    private val emitEvent: (NearbyConnectionLayerEvent) -> Unit,
) {
    private val connectedEndpointIds = ConcurrentHashMap.newKeySet<String>()
    private val retryHandler = Handler(Looper.getMainLooper())
    private var discoveryRetryRunnable: Runnable? = null
    private var advertisingRetryRunnable: Runnable? = null

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        /**
         * Сообщает фасаду о входящем handshake, оставляя accept/reject верхнему слою.
         */
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.i(TAG, "[onConnectionInitiated] Получен запрос соединения endpointId=$endpointId endpointName=${connectionInfo.endpointName}")
            emitEvent(NearbyConnectionLayerEvent.ConnectionInitiated(endpointId, connectionInfo))
        }

        /**
         * Запоминает успешный handshake либо запускает recovery для полуподключенного endpoint.
         */
        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            Log.i(
                TAG,
                "[onConnectionResult] Результат соединения endpointId=$endpointId statusCode=${resolution.status.statusCode} success=${resolution.status.isSuccess}",
            )
            if (!resolution.status.isSuccess && resolution.status.statusCode in RECOVERABLE_CONNECTION_STATUS_CODES) {
                val cause = ApiException(resolution.status)
                Log.w(TAG, "[onConnectionResult] Сбрасываем полуподключенный endpoint после statusCode=${resolution.status.statusCode} endpointId=$endpointId", cause)
                disconnectEndpoint(endpointId)
                emitEvent(NearbyConnectionLayerEvent.ConnectionRecoveryRequired(endpointId, cause))
                return
            }
            if (resolution.status.isSuccess) {
                connectedEndpointIds.add(endpointId)
            }
            emitEvent(NearbyConnectionLayerEvent.ConnectionResult(endpointId, resolution))
        }

        /**
         * Снимает только состояние active connection, сохраняя discovery-связки на уровне фасада.
         */
        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "[onDisconnected] Nearby endpoint отключился endpointId=$endpointId")
            connectedEndpointIds.remove(endpointId)
            emitEvent(NearbyConnectionLayerEvent.Disconnected(endpointId))
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        /**
         * Сообщает фасаду о найденном endpoint-е без разбора endpointName.
         */
        override fun onEndpointFound(endpointId: String, endpointInfo: DiscoveredEndpointInfo) {
            Log.i(
                TAG,
                "[onEndpointFound] Найден endpoint endpointId=$endpointId serviceId=${endpointInfo.serviceId} endpointNameLength=${endpointInfo.endpointName.length}",
            )
            emitEvent(NearbyConnectionLayerEvent.EndpointFound(endpointId, endpointInfo))
        }

        /**
         * Сообщает фасаду, что Nearby больше не видит найденный endpoint.
         */
        override fun onEndpointLost(endpointId: String) {
            Log.i(TAG, "[onEndpointLost] Потерян endpoint endpointId=$endpointId")
            emitEvent(NearbyConnectionLayerEvent.EndpointLost(endpointId))
        }
    }

    /**
     * Запускает поиск Nearby endpoint-ов с явно выбранной стратегией текущей discovery-фазы.
     */
    fun startDiscovery(strategy: Strategy) {
        cancelDiscoveryPermissionRetry()
        startDiscoveryInternal(
            strategy = strategy,
            permissionRetryAttempt = 0,
            alreadyDiscoveringRetryAttempt = 0,
        )
    }

    /**
     * Запускает discovery и тихо ретраит краткий permission-race внутри Nearby API.
     */
    private fun startDiscoveryInternal(
        strategy: Strategy,
        permissionRetryAttempt: Int,
        alreadyDiscoveringRetryAttempt: Int,
    ) {
        Log.i(TAG, "[startDiscovery] Запускаем discovery serviceId=$serviceId strategy=$strategy")
        val missingPermissions = missingPermissionsForDiscovery()
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "[startDiscovery] Discovery не стартует, нет permissions: ${missingPermissions.joinToString()}")
            emitEvent(
                NearbyConnectionLayerEvent.DiscoveryFailed(
                    SecurityException("Нет Nearby permissions для discovery: ${missingPermissions.joinToString()}"),
                ),
            )
            return
        }

        val locationRequirementError = locationRequirementErrorOrNull()
        if (locationRequirementError != null) {
            Log.w(TAG, "[startDiscovery] Discovery не стартует, не выполнено системное требование: ${locationRequirementError.message}")
            emitEvent(NearbyConnectionLayerEvent.DiscoveryFailed(locationRequirementError))
            return
        }

        val options = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                cancelDiscoveryPermissionRetry()
                Log.i(TAG, "[startDiscovery] Nearby discovery запущен")
            }
            .addOnFailureListener { cause ->
                if (cause is ApiException && cause.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                    if (alreadyDiscoveringRetryAttempt < MAX_ALREADY_DISCOVERING_RETRIES) {
                        scheduleDiscoveryStrategyRestart(
                            strategy = strategy,
                            permissionRetryAttempt = permissionRetryAttempt,
                            nextAttempt = alreadyDiscoveringRetryAttempt + 1,
                        )
                        return@addOnFailureListener
                    }
                    Log.w(TAG, "[startDiscovery] Не удалось сменить Nearby discovery strategy после повторных stop/start strategy=$strategy")
                    emitEvent(NearbyConnectionLayerEvent.DiscoveryFailed(cause))
                    return@addOnFailureListener
                }
                if (shouldRetryTransientPermissionFailure(
                        cause = cause,
                        permissionRetryAttempt = permissionRetryAttempt,
                        missingPermissionsProvider = ::missingPermissionsForDiscovery,
                    )
                ) {
                    scheduleDiscoveryPermissionRetry(strategy, permissionRetryAttempt + 1, cause)
                    return@addOnFailureListener
                }
                Log.w(TAG, "[startDiscovery] Nearby не запустил discovery: ${cause.message}", cause)
                emitEvent(NearbyConnectionLayerEvent.DiscoveryFailed(cause))
            }
    }

    /**
     * Останавливает поиск Nearby endpoint-ов.
     */
    fun stopDiscovery() {
        Log.i(TAG, "[stopDiscovery] Останавливаем discovery")
        cancelDiscoveryPermissionRetry()
        connectionsClient.stopDiscovery()
    }

    /**
     * Запускает advertising с подготовленной endpointName-визиткой и стратегией топологии комнаты.
     */
    fun startAdvertising(advertisement: NeighborAdvertisement, strategy: Strategy) {
        cancelAdvertisingPermissionRetry()
        startAdvertisingInternal(advertisement, strategy, permissionRetryAttempt = 0)
    }

    /**
     * Запускает advertising и тихо ретраит краткий permission-race внутри Nearby API.
     */
    private fun startAdvertisingInternal(
        advertisement: NeighborAdvertisement,
        strategy: Strategy,
        permissionRetryAttempt: Int,
    ) {
        Log.i(
            TAG,
            "[startAdvertising] Запускаем advertising endpointNameChars=${advertisement.endpointName.length} serviceId=$serviceId strategy=$strategy",
        )
        val missingPermissions = missingPermissionsForAdvertising()
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "[startAdvertising] Advertising не стартует, нет permissions: ${missingPermissions.joinToString()}")
            emitEvent(
                NearbyConnectionLayerEvent.AdvertisingFailed(
                    SecurityException("Нет Nearby permissions для advertising: ${missingPermissions.joinToString()}"),
                ),
            )
            return
        }

        val locationRequirementError = locationRequirementErrorOrNull()
        if (locationRequirementError != null) {
            Log.w(TAG, "[startAdvertising] Advertising не стартует, не выполнено системное требование: ${locationRequirementError.message}")
            emitEvent(NearbyConnectionLayerEvent.AdvertisingFailed(locationRequirementError))
            return
        }

        val endpointName = advertisement.endpointName
        Log.i(
            TAG,
            "[startAdvertising] Визитка комнаты закодирована в endpointName chars=${endpointName.length} bytes=${endpointName.encodeToByteArray().size}",
        )
        val options = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
        connectionsClient.startAdvertising(endpointName, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                cancelAdvertisingPermissionRetry()
                Log.i(TAG, "[startAdvertising] Nearby advertising запущен endpointNameChars=${endpointName.length}")
            }
            .addOnFailureListener { cause ->
                if (shouldRetryTransientPermissionFailure(
                        cause = cause,
                        permissionRetryAttempt = permissionRetryAttempt,
                        missingPermissionsProvider = ::missingPermissionsForAdvertising,
                    )
                ) {
                    scheduleAdvertisingPermissionRetry(advertisement, strategy, permissionRetryAttempt + 1, cause)
                    return@addOnFailureListener
                }
                Log.w(TAG, "[startAdvertising] Nearby не запустил advertising: ${cause.message}", cause)
                emitEvent(NearbyConnectionLayerEvent.AdvertisingFailed(cause))
            }
    }

    /**
     * Останавливает advertising текущего устройства.
     */
    fun stopAdvertising() {
        Log.i(TAG, "[stopAdvertising] Останавливаем advertising")
        cancelAdvertisingPermissionRetry()
        connectionsClient.stopAdvertising()
    }

    /**
     * Полностью останавливает локальное Nearby-состояние discovery, advertising и активные endpoint-ы.
     */
    fun stopAllEndpoints(reason: String) {
        Log.i(TAG, "[stopAllEndpoints] Полностью сбрасываем Nearby reason=$reason")
        cancelDiscoveryPermissionRetry()
        cancelAdvertisingPermissionRetry()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAdvertising()
        connectionsClient.stopAllEndpoints()
        connectedEndpointIds.clear()
    }

    /**
     * Запрашивает Nearby-соединение либо сообщает о пригодном для повторного использования активном endpoint.
     */
    fun connectToEndpoint(endpointId: String) {
        if (endpointId in connectedEndpointIds) {
            Log.i(TAG, "[connectToEndpoint] Endpoint уже подключен, переиспользуем endpointId=$endpointId")
            emitEvent(NearbyConnectionLayerEvent.ConnectionReused(endpointId))
            return
        }
        Log.i(TAG, "[connectToEndpoint] Запрашиваем соединение endpointId=$endpointId")
        connectionsClient.requestConnection(context.packageName, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener {
                Log.i(TAG, "[connectToEndpoint] Запрос соединения отправлен endpointId=$endpointId")
            }
            .addOnFailureListener { cause ->
                if (cause is ApiException && cause.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                    connectedEndpointIds.add(endpointId)
                    Log.i(TAG, "[connectToEndpoint] Nearby подтвердил существующее соединение endpointId=$endpointId")
                    emitEvent(NearbyConnectionLayerEvent.ConnectionReused(endpointId))
                    return@addOnFailureListener
                }
                if (cause is ApiException && cause.statusCode in RECOVERABLE_CONNECTION_STATUS_CODES) {
                    Log.w(TAG, "[connectToEndpoint] Сбрасываем полуподключенный endpoint после statusCode=${cause.statusCode} endpointId=$endpointId", cause)
                    disconnectEndpoint(endpointId)
                    emitEvent(NearbyConnectionLayerEvent.ConnectionRecoveryRequired(endpointId, cause))
                    return@addOnFailureListener
                }
                Log.w(TAG, "[connectToEndpoint] Не удалось запросить соединение endpointId=$endpointId: ${cause.message}", cause)
                emitEvent(NearbyConnectionLayerEvent.ConnectionRequestFailed(endpointId, cause))
            }
    }

    /**
     * Принимает входящее Nearby-соединение и привязывает к нему payload callback.
     */
    fun acceptConnection(endpointId: String) {
        Log.i(TAG, "[acceptConnection] Принимаем входящее соединение endpointId=$endpointId")
        connectionsClient.acceptConnection(endpointId, payloadCallback)
            .addOnFailureListener { cause ->
                Log.w(TAG, "[acceptConnection] Не удалось принять соединение endpointId=$endpointId: ${cause.message}", cause)
                emitEvent(NearbyConnectionLayerEvent.ConnectionAcceptFailed(endpointId, cause))
            }
    }

    /**
     * Отклоняет входящее Nearby-соединение.
     */
    fun rejectConnection(endpointId: String) {
        Log.i(TAG, "[rejectConnection] Отклоняем входящее соединение endpointId=$endpointId")
        connectionsClient.rejectConnection(endpointId)
            .addOnFailureListener { cause ->
                Log.w(TAG, "[rejectConnection] Не удалось отклонить соединение endpointId=$endpointId: ${cause.message}", cause)
                emitEvent(NearbyConnectionLayerEvent.ConnectionRejectFailed(endpointId, cause))
            }
    }

    /**
     * Явно разрывает все активные Nearby-соединения без сброса discovery-связок на уровне фасада.
     */
    fun disconnectAllPeers() {
        val endpointIds = connectedEndpointIds.toList()
        if (endpointIds.isEmpty()) {
            Log.i(TAG, "[disconnectAllPeers] Активных Nearby-соединений нет")
            return
        }
        endpointIds.forEach(::disconnectEndpoint)
    }

    /**
     * Безусловно отключает endpoint и очищает только локальный признак active connection.
     */
    fun disconnectEndpoint(endpointId: String) {
        connectedEndpointIds.remove(endpointId)
        connectionsClient.disconnectFromEndpoint(endpointId)
        Log.i(TAG, "[disconnectEndpoint] Endpoint отключен endpointId=$endpointId")
    }

    /**
     * Проверяет transient permission-race: app уже видит permissions, а Nearby API еще отвечает MISSING_PERMISSION.
     */
    private fun shouldRetryTransientPermissionFailure(
        cause: Throwable,
        permissionRetryAttempt: Int,
        missingPermissionsProvider: () -> List<String>,
    ): Boolean {
        if (!isNearbyMissingPermissionFailure(cause)) {
            return false
        }
        if (permissionRetryAttempt >= TRANSIENT_PERMISSION_RETRY_DELAYS_MILLIS.size) {
            return false
        }
        val missingPermissions = missingPermissionsProvider()
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "[shouldRetryTransientPermissionFailure] Nearby permission ошибка не transient: реально нет permissions ${missingPermissions.joinToString()}")
            return false
        }
        return true
    }

    /**
     * Распознает permission-ошибку Google Nearby, которая иногда приходит сразу после выдачи runtime permissions.
     */
    private fun isNearbyMissingPermissionFailure(cause: Throwable): Boolean {
        val apiException = cause as? ApiException ?: return false
        return apiException.statusCode == TRANSIENT_MISSING_PERMISSION_STATUS_CODE ||
            apiException.message?.contains(TRANSIENT_MISSING_PERMISSION_MARKER, ignoreCase = true) == true
    }

    /**
     * Планирует повторный discovery после краткой задержки, не отправляя ошибку в UI до исчерпания попыток.
     */
    private fun scheduleDiscoveryPermissionRetry(strategy: Strategy, nextAttempt: Int, cause: Throwable) {
        cancelDiscoveryPermissionRetry()
        val delayMillis = TRANSIENT_PERMISSION_RETRY_DELAYS_MILLIS[nextAttempt - 1]
        Log.w(
            TAG,
            "[scheduleDiscoveryPermissionRetry] Nearby discovery вернул transient permission race, повторяем attempt=$nextAttempt delayMs=$delayMillis message=${cause.message}",
        )
        val retryRunnable = Runnable {
            discoveryRetryRunnable = null
            startDiscoveryInternal(
                strategy = strategy,
                permissionRetryAttempt = nextAttempt,
                alreadyDiscoveringRetryAttempt = 0,
            )
        }
        discoveryRetryRunnable = retryRunnable
        retryHandler.postDelayed(retryRunnable, delayMillis)
    }

    /**
     * Повторяет stop/start, если предыдущая Strategy еще не успела остановиться перед новой discovery-фазой.
     */
    private fun scheduleDiscoveryStrategyRestart(
        strategy: Strategy,
        permissionRetryAttempt: Int,
        nextAttempt: Int,
    ) {
        cancelDiscoveryPermissionRetry()
        connectionsClient.stopDiscovery()
        Log.w(TAG, "[scheduleDiscoveryStrategyRestart] Предыдущий discovery еще активен, повторяем смену strategy=$strategy attempt=$nextAttempt")
        val retryRunnable = Runnable {
            discoveryRetryRunnable = null
            startDiscoveryInternal(
                strategy = strategy,
                permissionRetryAttempt = permissionRetryAttempt,
                alreadyDiscoveringRetryAttempt = nextAttempt,
            )
        }
        discoveryRetryRunnable = retryRunnable
        retryHandler.postDelayed(retryRunnable, DISCOVERY_STRATEGY_RESTART_DELAY_MILLIS)
    }

    /**
     * Планирует повторный advertising после краткой задержки, не отправляя ошибку в UI до исчерпания попыток.
     */
    private fun scheduleAdvertisingPermissionRetry(
        advertisement: NeighborAdvertisement,
        strategy: Strategy,
        nextAttempt: Int,
        cause: Throwable,
    ) {
        cancelAdvertisingPermissionRetry()
        val delayMillis = TRANSIENT_PERMISSION_RETRY_DELAYS_MILLIS[nextAttempt - 1]
        Log.w(
            TAG,
            "[scheduleAdvertisingPermissionRetry] Nearby advertising вернул transient permission race, повторяем attempt=$nextAttempt delayMs=$delayMillis message=${cause.message}",
        )
        val retryRunnable = Runnable {
            advertisingRetryRunnable = null
            startAdvertisingInternal(advertisement, strategy, permissionRetryAttempt = nextAttempt)
        }
        advertisingRetryRunnable = retryRunnable
        retryHandler.postDelayed(retryRunnable, delayMillis)
    }

    /**
     * Отменяет отложенный retry discovery, если пользователь остановил поиск или transport сбрасывается.
     */
    private fun cancelDiscoveryPermissionRetry() {
        discoveryRetryRunnable?.let { retryHandler.removeCallbacks(it) }
        discoveryRetryRunnable = null
    }

    /**
     * Отменяет отложенный retry advertising, если пользователь остановил комнату или transport сбрасывается.
     */
    private fun cancelAdvertisingPermissionRetry() {
        advertisingRetryRunnable?.let { retryHandler.removeCallbacks(it) }
        advertisingRetryRunnable = null
    }

    /**
     * Возвращает permissions, без которых discovery заведомо упадет в Nearby API.
     */
    private fun missingPermissionsForDiscovery(): List<String> {
        return requiredBaseNearbyPermissions()
            .plus(requiredDiscoveryPermissions())
            .filterNot(::isPermissionGranted)
            .distinct()
    }

    /**
     * Возвращает permissions, без которых advertising заведомо упадет в Nearby API.
     */
    private fun missingPermissionsForAdvertising(): List<String> {
        return requiredBaseNearbyPermissions()
            .plus(requiredAdvertisingPermissions())
            .filterNot(::isPermissionGranted)
            .distinct()
    }

    /**
     * Возвращает общие permissions Nearby Connections для текущего Android.
     */
    private fun requiredBaseNearbyPermissions(): List<String> {
        return buildList {
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.CHANGE_WIFI_STATE)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
    }

    /**
     * Возвращает permissions, специфичные для discovery.
     */
    private fun requiredDiscoveryPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyList()
        }
    }

    /**
     * Возвращает permissions, специфичные для advertising.
     */
    private fun requiredAdvertisingPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyList()
        }
    }

    /**
     * Проверяет, считает ли Android permission выданным этому приложению.
     */
    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Возвращает ошибку системной геолокации, если permissions есть, но Location toggle выключен.
     */
    private fun locationRequirementErrorOrNull(): NearbyRequirementException? {
        val locationPermissionsGranted = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ).all(::isPermissionGranted)
        if (!locationPermissionsGranted) {
            return null
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (LocationManagerCompat.isLocationEnabled(locationManager)) {
            null
        } else {
            NearbyRequirementException.LocationDisabled
        }
    }

    private companion object {
        private const val TAG = "NearbyConnectionLayer"
        private const val TRANSIENT_MISSING_PERMISSION_STATUS_CODE = 8034
        private const val TRANSIENT_MISSING_PERMISSION_MARKER = "MISSING_PERMISSION"
        private const val MAX_ALREADY_DISCOVERING_RETRIES = 2
        private const val DISCOVERY_STRATEGY_RESTART_DELAY_MILLIS = 350L
        private val TRANSIENT_PERMISSION_RETRY_DELAYS_MILLIS = longArrayOf(400L, 900L, 1_600L)
        private val RECOVERABLE_CONNECTION_STATUS_CODES = setOf(
            ConnectionsStatusCodes.STATUS_RADIO_ERROR,
            ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN,
            ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL,
            ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR,
        )
    }
}

/**
 * Низкоуровневое событие Nearby lifecycle, которое фасад переводит в публичный NeighborTransportEvent.
 */
internal sealed class NearbyConnectionLayerEvent {
    /**
     * Nearby начал входящий handshake от endpoint-а.
     */
    data class ConnectionInitiated(val endpointId: String, val connectionInfo: ConnectionInfo) : NearbyConnectionLayerEvent()

    /**
     * Автоматическое принятие Nearby-соединения завершилось ошибкой.
     */
    data class ConnectionAcceptFailed(val endpointId: String, val cause: Throwable) : NearbyConnectionLayerEvent()

    /**
     * Отклонение входящего Nearby-соединения завершилось ошибкой.
     */
    data class ConnectionRejectFailed(val endpointId: String, val cause: Throwable) : NearbyConnectionLayerEvent()

    /**
     * Nearby вернул итог handshake для endpoint-а.
     */
    data class ConnectionResult(val endpointId: String, val resolution: ConnectionResolution) : NearbyConnectionLayerEvent()

    /**
     * Nearby endpoint требует recovery после recoverable ошибки handshake или requestConnection.
     */
    data class ConnectionRecoveryRequired(val endpointId: String, val cause: ApiException) : NearbyConnectionLayerEvent()

    /**
     * Nearby сообщил о разрыве активного endpoint-а.
     */
    data class Disconnected(val endpointId: String) : NearbyConnectionLayerEvent()

    /**
     * Discovery нашел endpoint и, возможно, декодированную визитку комнаты.
     */
    data class EndpointFound(
        val endpointId: String,
        val endpointInfo: DiscoveredEndpointInfo,
    ) : NearbyConnectionLayerEvent()

    /**
     * Discovery потерял ранее найденный endpoint.
     */
    data class EndpointLost(val endpointId: String) : NearbyConnectionLayerEvent()

    /**
     * Nearby не смог запустить discovery.
     */
    data class DiscoveryFailed(val cause: Throwable) : NearbyConnectionLayerEvent()

    /**
     * Nearby не смог запустить advertising.
     */
    data class AdvertisingFailed(val cause: Throwable) : NearbyConnectionLayerEvent()

    /**
     * Endpoint уже имеет активное соединение и может быть переиспользован.
     */
    data class ConnectionReused(val endpointId: String) : NearbyConnectionLayerEvent()

    /**
     * Nearby не смог отправить requestConnection к endpoint-у.
     */
    data class ConnectionRequestFailed(val endpointId: String, val cause: Throwable) : NearbyConnectionLayerEvent()
}
