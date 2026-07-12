package com.yellastro.btration.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.yellastro.btration.domain.model.PeerId
import com.yellastro.btration.domain.model.VoiceTransportControlInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Экспериментальный VoiceTransport поверх Wi-Fi Direct group и UDP-датаграмм с уже закодированными Opus voice frames.
 */
class WifiDirectVoiceTransport(
    context: Context,
    private val externalScope: CoroutineScope,
) : VoiceTransport {
    private val applicationContext = context.applicationContext
    private val wifiP2pManager = applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = wifiP2pManager.initialize(applicationContext, Looper.getMainLooper(), null)
    private val _events = MutableSharedFlow<VoiceTransportEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val peerUdpEndpoints = ConcurrentHashMap<PeerId, InetSocketAddress>()
    private val receiver = WifiDirectReceiver()

    private var selfPeerId: PeerId? = null
    private var sessionRole: VoiceTransportSessionRole? = null
    private var localDeviceAddress: String? = null
    private var hostDeviceAddress: String? = null
    private var lastDiscoveredHostDeviceAddress: String? = null
    private var expectedHostPeerId: PeerId? = null
    private var publishedServicePeerId: PeerId? = null
    private var publishingServicePeerId: PeerId? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var groupOwnerAddress: InetAddress? = null
    private var udpSocket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var helloJob: Job? = null
    private var connectRetryJob: Job? = null
    private var peerDiscoveryRetryJob: Job? = null
    private var connectAttemptCount = 0
    private var pendingConnectDeviceAddress: String? = null
    private var peerDiscoveryTargetDeviceAddress: String? = null
    private var connectCooldownUntilMillis = 0L
    private var cachedLocalControlInfo: VoiceTransportControlInfo? = null

    /**
     * События Wi-Fi Direct voice transport для RoomRuntime.
     */
    override val events: SharedFlow<VoiceTransportEvent> = _events.asSharedFlow()

    /**
     * Последняя локальная Wi-Fi Direct визитка, которую можно отправить участникам через Nearby signaling.
     */
    override val localControlInfo: VoiceTransportControlInfo?
        get() = cachedLocalControlInfo

    init {
        configureServiceDiscoveryListeners()
        registerReceiver()
    }

    /**
     * Запускает Wi-Fi Direct UDP media-plane для host или client роли.
     */
    override fun startSession(selfPeerId: PeerId, role: VoiceTransportSessionRole) {
        this.selfPeerId = selfPeerId
        sessionRole = role
        startUdpSocket()
        requestLocalDeviceInfo()
        publishLocalControlInfoIfReady()
        when (role) {
            VoiceTransportSessionRole.HOST -> {
                publishHostServiceIfReady()
                startHostGroup()
            }
            VoiceTransportSessionRole.CLIENT -> startClientDiscovery()
        }
        Log.i(TAG, "[startSession] Wi-Fi Direct voice session запущена role=$role selfPeerId=${selfPeerId.value}")
    }

    /**
     * Останавливает UDP media-plane и просит Android выйти из Wi-Fi Direct group.
     */
    override fun stopSession() {
        Log.i(TAG, "[stopSession] Останавливаем Wi-Fi Direct voice session role=$sessionRole")
        helloJob?.cancel()
        helloJob = null
        connectRetryJob?.cancel()
        connectRetryJob = null
        peerDiscoveryRetryJob?.cancel()
        peerDiscoveryRetryJob = null
        connectAttemptCount = 0
        pendingConnectDeviceAddress = null
        peerDiscoveryTargetDeviceAddress = null
        connectCooldownUntilMillis = 0L
        receiveJob?.cancel()
        receiveJob = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        peerUdpEndpoints.clear()
        groupOwnerAddress = null
        hostDeviceAddress = null
        lastDiscoveredHostDeviceAddress = null
        expectedHostPeerId = null
        cachedLocalControlInfo = null
        publishedServicePeerId = null
        publishingServicePeerId = null
        clearServiceDiscoveryState()
        selfPeerId = null
        sessionRole = null
        removeGroup("stop_session")
    }

    /**
     * Принимает служебную информацию от Nearby signaling и запускает поиск DNS-SD service нужного hostPeerId.
     */
    override fun handleControlInfo(fromPeerId: PeerId, info: VoiceTransportControlInfo) {
        if (info.mode != MODE) {
            Log.i(TAG, "[handleControlInfo] Игнорируем voice info другого mode=${info.mode} fromPeerId=${fromPeerId.value}")
            return
        }
        if (sessionRole != VoiceTransportSessionRole.CLIENT || !info.isGroupOwner) {
            Log.i(TAG, "[handleControlInfo] Wi-Fi Direct info сохранен без connect role=$sessionRole fromPeerId=${fromPeerId.value} isGroupOwner=${info.isGroupOwner}")
            return
        }
        expectedHostPeerId = fromPeerId
        Log.i(
            TAG,
            "[handleControlInfo] Client ждёт Wi-Fi Direct service hostPeerId=${fromPeerId.value} diagnosticDeviceAddress=${info.wifiDirectDeviceAddress}",
        )
        startClientDiscovery()
    }

    /**
     * Отправляет Opus voice frame через UDP: client шлет group owner-у, host шлет известным client endpoint-ам.
     */
    override fun sendFrameToPeers(peerIds: Set<PeerId>, frame: VoiceFrame) {
        val socket = udpSocket
        val senderPeerId = selfPeerId
        if (socket == null || senderPeerId == null) {
            Log.w(TAG, "[sendFrameToPeers] UDP socket еще не готов peerCount=${peerIds.size}")
            emitEvent(VoiceTransportEvent.FrameSendFailed(peerIds, IllegalStateException("Wi-Fi Direct UDP socket is not ready")))
            return
        }
        val payload = WifiDirectVoiceDatagramCodec.encodeFrame(senderPeerId, frame)
        val failedPeerIds = mutableSetOf<PeerId>()
        peerIds.forEach { peerId ->
            val endpoint = resolveUdpEndpoint(peerId)
            if (endpoint == null) {
                failedPeerIds += peerId
                Log.w(TAG, "[sendFrameToPeers] Нет UDP endpoint для peerId=${peerId.value} role=$sessionRole")
            } else if (Looper.myLooper() == Looper.getMainLooper()) {
                sendDatagramOffMain(socket, endpoint, payload, peerId)
            } else {
                sendDatagram(socket, endpoint, payload, peerId)
            }
        }
        if (failedPeerIds.isNotEmpty()) {
            emitEvent(VoiceTransportEvent.FrameSendFailed(failedPeerIds, IllegalStateException("Wi-Fi Direct UDP endpoints $failedPeerIds are unknown")))
        }
    }

    /**
     * Регистрирует receiver Wi-Fi Direct событий на весь срок жизни application container.
     */
    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(receiver, filter)
        }
        Log.i(TAG, "[registerReceiver] Wi-Fi Direct receiver зарегистрирован")
    }

    /**
     * Регистрирует DNS-SD listeners, через которые client сопоставляет RoomInfo.host.peerId с найденным Wi-Fi Direct device.
     */
    private fun configureServiceDiscoveryListeners() {
        wifiP2pManager.setDnsSdResponseListeners(
            channel,
            WifiP2pManager.DnsSdServiceResponseListener { instanceName, registrationType, srcDevice ->
                Log.i(
                    TAG,
                    "[configureServiceDiscoveryListeners] Найден Wi-Fi Direct service instance=$instanceName type=$registrationType device=${srcDevice.deviceName}",
                )
            },
            WifiP2pManager.DnsSdTxtRecordListener { fullDomain, record, srcDevice ->
                handleDnsSdTxtRecord(fullDomain, record, srcDevice)
            },
        )
        Log.i(TAG, "[configureServiceDiscoveryListeners] DNS-SD listeners зарегистрированы")
    }

    /**
     * Запрашивает локальный Wi-Fi Direct device info, если API Android это поддерживает.
     */
    @SuppressLint("MissingPermission")
    private fun requestLocalDeviceInfo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !hasWifiDirectPermission()) {
            return
        }
        wifiP2pManager.requestDeviceInfo(channel, ::handleThisDeviceChanged)
        Log.i(TAG, "[requestLocalDeviceInfo] Запрошен локальный Wi-Fi Direct device info")
    }

    /**
     * Запускает UDP socket и receive loop, если они еще не активны.
     */
    private fun startUdpSocket() {
        if (udpSocket != null) {
            return
        }
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(UDP_PORT))
        }
        udpSocket = socket
        receiveJob = externalScope.launch(Dispatchers.IO) {
            receiveLoop(socket)
        }
        Log.i(TAG, "[startUdpSocket] UDP socket запущен port=$UDP_PORT")
    }

    /**
     * Читает UDP datagrams до остановки socket-а.
     */
    private suspend fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)
        while (currentCoroutineContext().isActive && !socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching {
                socket.receive(packet)
                handleUdpPacket(packet)
            }.onFailure { cause ->
                if (cause !is SocketException && cause !is CancellationException) {
                    Log.w(TAG, "[receiveLoop] Ошибка чтения UDP voice packet: ${cause.message}", cause)
                }
            }
        }
        Log.i(TAG, "[receiveLoop] UDP receive loop остановлен")
    }

    /**
     * Обрабатывает HELLO или FRAME UDP datagram и обновляет таблицу peerId -> UDP endpoint.
     */
    private fun handleUdpPacket(packet: DatagramPacket) {
        val endpoint = InetSocketAddress(packet.address, packet.port)
        when (val datagram = WifiDirectVoiceDatagramCodec.decode(packet.data, packet.length)) {
            is WifiDirectVoiceDatagram.Hello -> {
                peerUdpEndpoints[datagram.senderPeerId] = endpoint
                Log.i(TAG, "[handleUdpPacket] Получен Wi-Fi Direct HELLO peerId=${datagram.senderPeerId.value} endpoint=$endpoint")
            }

            is WifiDirectVoiceDatagram.Frame -> {
                peerUdpEndpoints[datagram.senderPeerId] = endpoint
                emitEvent(
                    VoiceTransportEvent.FrameReceived(
                        transportPeerId = datagram.senderPeerId,
                        frame = datagram.frame,
                        transportEndpointId = endpoint.toString(),
                    ),
                )
            }
        }
    }

    /**
     * Отправляет одну UDP datagram и логирует только ошибки.
     */
    private fun sendDatagram(socket: DatagramSocket, endpoint: InetSocketAddress, payload: ByteArray, peerId: PeerId) {
        runCatching {
            socket.send(DatagramPacket(payload, payload.size, endpoint.address, endpoint.port))
        }.onFailure { cause ->
            Log.w(TAG, "[sendDatagram] Не удалось отправить UDP voice datagram peerId=${peerId.value} endpoint=$endpoint cause=${cause.javaClass.simpleName}: ${cause.message}", cause)
            emitEvent(VoiceTransportEvent.FrameSendFailed(setOf(peerId), cause))
        }
    }

    /**
     * Переносит UDP send с main thread на IO, чтобы final-frame при отпускании PTT не падал в StrictMode.
     */
    private fun sendDatagramOffMain(socket: DatagramSocket, endpoint: InetSocketAddress, payload: ByteArray, peerId: PeerId) {
        externalScope.launch(Dispatchers.IO) {
            sendDatagram(socket, endpoint, payload, peerId)
        }
        Log.i(TAG, "[sendDatagramOffMain] UDP voice datagram перенесена с main thread peerId=${peerId.value} endpoint=$endpoint")
    }

    /**
     * Возвращает UDP endpoint для peer: client всегда шлет group owner-у, host использует HELLO-таблицу.
     */
    private fun resolveUdpEndpoint(peerId: PeerId): InetSocketAddress? {
        if (sessionRole == VoiceTransportSessionRole.CLIENT) {
            return groupOwnerAddress?.let { address -> InetSocketAddress(address, UDP_PORT) }
        }
        return peerUdpEndpoints[peerId]
    }

    /**
     * Запускает Wi-Fi Direct group owner на host-у.
     */
    @SuppressLint("MissingPermission")
    private fun startHostGroup() {
        if (!hasWifiDirectPermission()) {
            Log.w(TAG, "[startHostGroup] Нет permissions для Wi-Fi Direct host")
            return
        }
        removeGroupBeforeCreate()
        Log.i(TAG, "[startHostGroup] Запрошен cleanup перед созданием Wi-Fi Direct group")
    }

    /**
     * Сначала убирает старую Wi-Fi Direct group, затем запускает создание новой host group.
     */
    @SuppressLint("MissingPermission")
    private fun removeGroupBeforeCreate() {
        wifiP2pManager.removeGroup(
            channel,
            object : WifiP2pManager.ActionListener {
                /**
                 * Создает group после успешного удаления старой.
                 */
                override fun onSuccess() {
                    Log.i(TAG, "[removeGroupBeforeCreate] Старая Wi-Fi Direct group удалена")
                    createGroupAfterCleanup()
                }

                /**
                 * Все равно пробует создать group, если старой группы не было или removeGroup отказался.
                 */
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "[removeGroupBeforeCreate] Не удалось удалить старую group reason=$reason, пробуем создать новую")
                    createGroupAfterCleanup()
                }
            },
        )
    }

    /**
     * Запрашивает создание Wi-Fi Direct group после cleanup.
     */
    @SuppressLint("MissingPermission")
    private fun createGroupAfterCleanup() {
        wifiP2pManager.createGroup(channel, actionListener("createGroup"))
        Log.i(TAG, "[createGroupAfterCleanup] Запрошено создание Wi-Fi Direct group")
    }

    /**
     * Публикует DNS-SD service с hostPeerId, чтобы client нашёл настоящий WifiP2pDevice хоста без локального MAC.
     */
    @SuppressLint("MissingPermission")
    private fun publishHostServiceIfReady() {
        val peerId = selfPeerId ?: return
        if (sessionRole != VoiceTransportSessionRole.HOST) {
            return
        }
        if (publishedServicePeerId == peerId || publishingServicePeerId == peerId) {
            return
        }
        if (!hasWifiDirectPermission()) {
            Log.w(TAG, "[publishHostServiceIfReady] Нет permissions для публикации Wi-Fi Direct service")
            return
        }
        val record = mapOf(
            SERVICE_TXT_APP to SERVICE_APP_VALUE,
            SERVICE_TXT_VERSION to SERVICE_VERSION_VALUE,
            SERVICE_TXT_HOST_PEER_ID to peerId.value,
            SERVICE_TXT_PORT to UDP_PORT.toString(),
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE_NAME,
            SERVICE_REGISTRATION_TYPE,
            record,
        )
        publishingServicePeerId = peerId
        wifiP2pManager.clearLocalServices(
            channel,
            object : WifiP2pManager.ActionListener {
                /**
                 * Добавляет актуальный service после очистки старых записей.
                 */
                override fun onSuccess() {
                    addHostService(serviceInfo, peerId)
                }

                /**
                 * Все равно пробует добавить service, если очистка не прошла.
                 */
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "[publishHostServiceIfReady] Не удалось очистить старые local services reason=$reason")
                    addHostService(serviceInfo, peerId)
                }
            },
        )
        Log.i(TAG, "[publishHostServiceIfReady] Запрошена публикация Wi-Fi Direct service hostPeerId=${peerId.value}")
    }

    /**
     * Добавляет DNS-SD service в Wi-Fi Direct manager и запоминает опубликованный hostPeerId.
     */
    @SuppressLint("MissingPermission")
    private fun addHostService(serviceInfo: WifiP2pDnsSdServiceInfo, peerId: PeerId) {
        wifiP2pManager.addLocalService(
            channel,
            serviceInfo,
            object : WifiP2pManager.ActionListener {
                /**
                 * Фиксирует успешную публикацию DNS-SD service.
                 */
                override fun onSuccess() {
                    publishingServicePeerId = null
                    publishedServicePeerId = peerId
                    Log.i(TAG, "[addHostService] Wi-Fi Direct service опубликован hostPeerId=${peerId.value}")
                }

                /**
                 * Логирует отказ публикации DNS-SD service.
                 */
                override fun onFailure(reason: Int) {
                    if (publishingServicePeerId == peerId) {
                        publishingServicePeerId = null
                    }
                    Log.w(TAG, "[addHostService] Не удалось опубликовать Wi-Fi Direct service reason=$reason hostPeerId=${peerId.value}")
                }
            },
        )
    }

    /**
     * Запускает Wi-Fi Direct service discovery на client-е.
     */
    @SuppressLint("MissingPermission")
    private fun startClientDiscovery() {
        if (!hasWifiDirectPermission()) {
            Log.w(TAG, "[startClientDiscovery] Нет permissions для Wi-Fi Direct discovery")
            return
        }
        if (sessionRole != VoiceTransportSessionRole.CLIENT) {
            return
        }
        if (groupOwnerAddress != null) {
            Log.i(TAG, "[startClientDiscovery] Wi-Fi Direct group уже сформирована, discovery не нужен")
            return
        }
        if (expectedHostPeerId == null) {
            Log.i(TAG, "[startClientDiscovery] HostPeerId еще неизвестен, ждём VOICE_TRANSPORT_INFO")
            return
        }
        if (serviceRequest != null) {
            discoverServices()
            return
        }
        val request = WifiP2pDnsSdServiceRequest.newInstance()
        serviceRequest = request
        wifiP2pManager.addServiceRequest(
            channel,
            request,
            object : WifiP2pManager.ActionListener {
                /**
                 * Запускает DNS-SD discovery после регистрации service request.
                 */
                override fun onSuccess() {
                    Log.i(TAG, "[startClientDiscovery] Wi-Fi Direct service request добавлен")
                    discoverServices()
                }

                /**
                 * Логирует отказ регистрации service request.
                 */
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "[startClientDiscovery] Не удалось добавить Wi-Fi Direct service request reason=$reason")
                }
            },
        )
        Log.i(TAG, "[startClientDiscovery] Запрошен Wi-Fi Direct DNS-SD discovery hostPeerId=${expectedHostPeerId?.value}")
    }

    /**
     * Просит Android начать DNS-SD service discovery.
     */
    @SuppressLint("MissingPermission")
    private fun discoverServices() {
        if (!hasWifiDirectPermission()) {
            Log.w(TAG, "[discoverServices] Нет permissions для Wi-Fi Direct service discovery")
            return
        }
        val pendingDeviceAddress = pendingConnectDeviceAddress
        if (pendingDeviceAddress != null) {
            Log.i(TAG, "[discoverServices] Wi-Fi Direct connect уже ожидает group, discovery пропущен deviceAddress=$pendingDeviceAddress")
            return
        }
        wifiP2pManager.discoverServices(channel, actionListener("discoverServices"))
        Log.i(TAG, "[discoverServices] Запрошен Wi-Fi Direct discoverServices hostPeerId=${expectedHostPeerId?.value}")
    }

    /**
     * Обрабатывает TXT record host-а и подключается только к service с ожидаемым hostPeerId из RoomInfo.
     */
    private fun handleDnsSdTxtRecord(fullDomain: String, record: Map<String, String>, srcDevice: WifiP2pDevice) {
        val expectedPeerId = expectedHostPeerId
        if (sessionRole != VoiceTransportSessionRole.CLIENT || expectedPeerId == null) {
            Log.i(TAG, "[handleDnsSdTxtRecord] DNS-SD TXT получен без ожидаемого hostPeerId fullDomain=$fullDomain")
            return
        }
        if (record[SERVICE_TXT_APP] != SERVICE_APP_VALUE) {
            Log.i(TAG, "[handleDnsSdTxtRecord] Игнорируем чужой Wi-Fi Direct service fullDomain=$fullDomain record=$record")
            return
        }
        val serviceHostPeerId = record[SERVICE_TXT_HOST_PEER_ID]?.let(::PeerId)
        if (serviceHostPeerId != expectedPeerId) {
            Log.i(
                TAG,
                "[handleDnsSdTxtRecord] Игнорируем service другого host serviceHostPeerId=${serviceHostPeerId?.value} expectedHostPeerId=${expectedPeerId.value}",
            )
            return
        }
        val deviceAddress = srcDevice.deviceAddress
        if (deviceAddress.isBlank() || deviceAddress == ANONYMIZED_DEVICE_ADDRESS) {
            Log.w(TAG, "[handleDnsSdTxtRecord] Найденный host service без валидного deviceAddress device=${srcDevice.deviceName} address=$deviceAddress")
            return
        }
        val pendingDeviceAddress = pendingConnectDeviceAddress
        if (pendingDeviceAddress == deviceAddress) {
            Log.i(TAG, "[handleDnsSdTxtRecord] Connect к host device уже ожидает group device=${srcDevice.deviceName} address=$deviceAddress")
            return
        }
        val cooldownMillis = connectCooldownRemainingMillis()
        if (cooldownMillis > 0L) {
            Log.i(TAG, "[handleDnsSdTxtRecord] Wi-Fi Direct connect временно отложен cooldownMillis=$cooldownMillis device=${srcDevice.deviceName} address=$deviceAddress")
            return
        }
        if (groupOwnerAddress != null) {
            Log.i(TAG, "[handleDnsSdTxtRecord] Wi-Fi Direct group уже сформирована, повторный connect не нужен device=${srcDevice.deviceName} address=$deviceAddress")
            return
        }
        if (hostDeviceAddress == deviceAddress) {
            Log.i(TAG, "[handleDnsSdTxtRecord] Host device уже выбран device=${srcDevice.deviceName} address=$deviceAddress")
            return
        }
        hostDeviceAddress = deviceAddress
        lastDiscoveredHostDeviceAddress = deviceAddress
        peerDiscoveryTargetDeviceAddress = deviceAddress
        Log.i(
            TAG,
            "[handleDnsSdTxtRecord] Найден host Wi-Fi Direct service hostPeerId=${expectedPeerId.value} device=${srcDevice.deviceName} address=$deviceAddress",
        )
        discoverPeersBeforeConnect(deviceAddress)
    }

    /**
     * Запускает обычный Wi-Fi Direct peer discovery, чтобы Android добавил найденный DNS-SD host в peer table перед connect.
     */
    @SuppressLint("MissingPermission")
    private fun discoverPeersBeforeConnect(deviceAddress: String) {
        if (!hasWifiDirectPermission()) {
            Log.w(TAG, "[discoverPeersBeforeConnect] Нет permissions для Wi-Fi Direct peer discovery")
            return
        }
        peerDiscoveryTargetDeviceAddress = deviceAddress
        wifiP2pManager.discoverPeers(
            channel,
            object : WifiP2pManager.ActionListener {
                /**
                 * Запрашивает peer table после принятого discoverPeers.
                 */
                override fun onSuccess() {
                    Log.i(TAG, "[discoverPeersBeforeConnect] Wi-Fi Direct peer discovery запущен deviceAddress=$deviceAddress")
                    requestPeersForPendingHost("discover_peers_success")
                }

                /**
                 * Все равно запрашивает peer table, если discovery уже активен или стек отказал в запуске.
                 */
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "[discoverPeersBeforeConnect] Не удалось запустить Wi-Fi Direct peer discovery reason=$reason deviceAddress=$deviceAddress")
                    requestPeersForPendingHost("discover_peers_failure_$reason")
                }
            },
        )
        Log.i(TAG, "[discoverPeersBeforeConnect] Запрошен Wi-Fi Direct peer discovery для host deviceAddress=$deviceAddress")
    }

    /**
     * Запрашивает текущий Wi-Fi Direct peer table и ищет в нем host device из DNS-SD callback.
     */
    @SuppressLint("MissingPermission")
    private fun requestPeersForPendingHost(reason: String) {
        if (!hasWifiDirectPermission()) {
            Log.w(TAG, "[requestPeersForPendingHost] Нет permissions для Wi-Fi Direct peer table")
            return
        }
        val targetDeviceAddress = peerDiscoveryTargetDeviceAddress
        if (targetDeviceAddress == null) {
            Log.i(TAG, "[requestPeersForPendingHost] Нет ожидаемого host device для peer table reason=$reason")
            return
        }
        wifiP2pManager.requestPeers(channel) { peers ->
            handlePeerTableForConnect(targetDeviceAddress, peers.deviceList, reason)
        }
        Log.i(TAG, "[requestPeersForPendingHost] Запрошен Wi-Fi Direct peer table reason=$reason targetDeviceAddress=$targetDeviceAddress")
    }

    /**
     * Подключается к host только после того, как Android peer table увидела его как WifiP2pDevice.
     */
    private fun handlePeerTableForConnect(targetDeviceAddress: String, peers: Collection<WifiP2pDevice>, reason: String) {
        val targetDevice = peers.firstOrNull { device -> device.deviceAddress == targetDeviceAddress }
        if (targetDevice == null) {
            Log.i(
                TAG,
                "[handlePeerTableForConnect] Host device пока не найден в peer table reason=$reason targetDeviceAddress=$targetDeviceAddress peerCount=${peers.size}",
            )
            schedulePeerTableRetry(targetDeviceAddress, reason)
            return
        }
        peerDiscoveryTargetDeviceAddress = null
        Log.i(
            TAG,
            "[handlePeerTableForConnect] Host device найден в peer table reason=$reason device=${targetDevice.deviceName} address=${targetDevice.deviceAddress}",
        )
        connectToHost(targetDevice.deviceAddress)
    }

    /**
     * Повторяет peer discovery, если DNS-SD service найден, но peer table еще не содержит нужное устройство.
     */
    private fun schedulePeerTableRetry(deviceAddress: String, reason: String) {
        peerDiscoveryRetryJob?.cancel()
        peerDiscoveryRetryJob = externalScope.launch {
            delay(PEER_TABLE_RETRY_DELAY_MILLIS)
            if (sessionRole == VoiceTransportSessionRole.CLIENT &&
                groupOwnerAddress == null &&
                pendingConnectDeviceAddress == null &&
                peerDiscoveryTargetDeviceAddress == deviceAddress
            ) {
                Log.i(TAG, "[schedulePeerTableRetry] Повторяем Wi-Fi Direct peer discovery reason=$reason deviceAddress=$deviceAddress")
                discoverPeersBeforeConnect(deviceAddress)
            }
        }
    }

    /**
     * Подключает client к Wi-Fi Direct deviceAddress host-а.
     */
    @SuppressLint("MissingPermission")
    private fun connectToHost(deviceAddress: String) {
        if (!hasWifiDirectPermission()) {
            Log.w(TAG, "[connectToHost] Нет permissions для Wi-Fi Direct connect")
            return
        }
        if (deviceAddress == ANONYMIZED_DEVICE_ADDRESS) {
            Log.w(TAG, "[connectToHost] Не подключаемся к anonymized deviceAddress=$deviceAddress")
            return
        }
        val pendingDeviceAddress = pendingConnectDeviceAddress
        if (pendingDeviceAddress != null) {
            Log.i(TAG, "[connectToHost] Wi-Fi Direct connect уже в процессе pendingDeviceAddress=$pendingDeviceAddress requestedDeviceAddress=$deviceAddress")
            return
        }
        val cooldownMillis = connectCooldownRemainingMillis()
        if (cooldownMillis > 0L) {
            Log.i(TAG, "[connectToHost] Wi-Fi Direct connect временно отложен cooldownMillis=$cooldownMillis deviceAddress=$deviceAddress")
            return
        }
        if (groupOwnerAddress != null) {
            Log.i(TAG, "[connectToHost] Wi-Fi Direct group уже сформирована, connect не нужен deviceAddress=$deviceAddress")
            return
        }
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = CLIENT_GROUP_OWNER_INTENT
        }
        connectAttemptCount += 1
        val attempt = connectAttemptCount
        pendingConnectDeviceAddress = deviceAddress
        peerDiscoveryRetryJob?.cancel()
        peerDiscoveryRetryJob = null
        requestConnectToHost(deviceAddress, config, attempt)
        Log.i(TAG, "[connectToHost] Подключаемся к Wi-Fi Direct host из peer table deviceAddress=$deviceAddress attempt=$attempt")
    }

    /**
     * Запрашивает Wi-Fi Direct connect к host device после подготовки P2P стека.
     */
    @SuppressLint("MissingPermission")
    private fun requestConnectToHost(deviceAddress: String, config: WifiP2pConfig, attempt: Int) {
        if (pendingConnectDeviceAddress != deviceAddress) {
            Log.i(TAG, "[requestConnectToHost] Connect отменен до запроса deviceAddress=$deviceAddress pendingDeviceAddress=$pendingConnectDeviceAddress")
            return
        }
        wifiP2pManager.connect(
            channel,
            config,
            object : WifiP2pManager.ActionListener {
                /**
                 * Логирует принятую Android попытку подключения к найденному host device.
                 */
                override fun onSuccess() {
                    scheduleConnectRetry(deviceAddress, attempt)
                    Log.i(TAG, "[connectToHost] Wi-Fi Direct connect принят deviceAddress=$deviceAddress attempt=$attempt")
                }

                /**
                 * Сбрасывает выбранный host device при отказе, чтобы следующий DNS-SD TXT мог повторить connect.
                 */
                override fun onFailure(reason: Int) {
                    if (reason == WifiP2pManager.BUSY) {
                        pendingConnectDeviceAddress = deviceAddress
                    } else if (pendingConnectDeviceAddress == deviceAddress) {
                        pendingConnectDeviceAddress = null
                    }
                    if (hostDeviceAddress == deviceAddress) {
                        hostDeviceAddress = null
                    }
                    connectCooldownUntilMillis = System.currentTimeMillis() + connectFailureCooldownMillis(reason)
                    if (reason == WifiP2pManager.BUSY) {
                        scheduleCancelConnectBeforeRetry(deviceAddress, "connect_busy", attempt)
                    } else {
                        scheduleDiscoveryRetry("connect_failure_$reason")
                    }
                    Log.w(TAG, "[connectToHost] Wi-Fi Direct connect отклонен reason=$reason deviceAddress=$deviceAddress attempt=$attempt")
                }
            },
        )
        Log.i(TAG, "[connectToHost] Запрошено подключение к Wi-Fi Direct host deviceAddress=$deviceAddress attempt=$attempt")
    }

    /**
     * Планирует watchdog принятого connect, чтобы не запускать новые connect поверх системного pairing.
     */
    private fun scheduleConnectRetry(deviceAddress: String, attempt: Int) {
        connectRetryJob?.cancel()
        connectRetryJob = externalScope.launch {
            delay(CONNECT_FORMATION_TIMEOUT_MILLIS)
            if (sessionRole != VoiceTransportSessionRole.CLIENT || groupOwnerAddress != null) {
                return@launch
            }
            if (pendingConnectDeviceAddress != deviceAddress) {
                return@launch
            }
            Log.w(TAG, "[scheduleConnectRetry] Wi-Fi Direct group не сформирована после принятого connect, отменяем попытку deviceAddress=$deviceAddress attempt=$attempt")
            pendingConnectDeviceAddress = null
            if (hostDeviceAddress == deviceAddress) {
                hostDeviceAddress = null
            }
            cancelConnectBeforeRetry(deviceAddress, "connect_timeout", attempt)
        }
    }

    /**
     * Перезапускает DNS-SD discovery после отказа connect или другой recoverable ошибки.
     */
    private fun scheduleDiscoveryRetry(reason: String) {
        connectRetryJob?.cancel()
        connectRetryJob = externalScope.launch {
            delay(maxOf(DISCOVERY_RETRY_DELAY_MILLIS, connectCooldownRemainingMillis()))
            if (sessionRole == VoiceTransportSessionRole.CLIENT && groupOwnerAddress == null && expectedHostPeerId != null) {
                if (pendingConnectDeviceAddress != null) {
                    Log.i(TAG, "[scheduleDiscoveryRetry] Retry отложен, connect еще в процессе reason=$reason deviceAddress=$pendingConnectDeviceAddress")
                    return@launch
                }
                Log.i(TAG, "[scheduleDiscoveryRetry] Повторяем Wi-Fi Direct discovery reason=$reason hostPeerId=${expectedHostPeerId?.value}")
                discoverServices()
                lastDiscoveredHostDeviceAddress?.let(::discoverPeersBeforeConnect)
            }
        }
    }

    /**
     * Отменяет зависший Wi-Fi Direct connect и только после cleanup возвращает client к DNS-SD discovery.
     */
    @SuppressLint("MissingPermission")
    private fun scheduleCancelConnectBeforeRetry(deviceAddress: String, reason: String, attempt: Int) {
        connectRetryJob?.cancel()
        connectRetryJob = externalScope.launch {
            delay(CONNECT_CANCEL_DELAY_MILLIS)
            if (sessionRole != VoiceTransportSessionRole.CLIENT || groupOwnerAddress != null) {
                return@launch
            }
            if (pendingConnectDeviceAddress == deviceAddress) {
                pendingConnectDeviceAddress = null
            }
            connectCooldownUntilMillis = System.currentTimeMillis() + CONNECT_CANCEL_COOLDOWN_MILLIS
            if (hostDeviceAddress == deviceAddress) {
                hostDeviceAddress = null
            }
            cancelConnectBeforeRetry(deviceAddress, reason, attempt)
        }
    }

    /**
     * Вызывает WifiP2pManager.cancelConnect перед повторным поиском host-а.
     */
    @SuppressLint("MissingPermission")
    private fun cancelConnectBeforeRetry(deviceAddress: String, reason: String, attempt: Int) {
        if (!hasWifiDirectPermission()) {
            Log.w(TAG, "[cancelConnectBeforeRetry] Нет permissions для Wi-Fi Direct cancelConnect")
            return
        }
        wifiP2pManager.cancelConnect(
            channel,
            object : WifiP2pManager.ActionListener {
                /**
                 * Перезапускает discovery после успешной отмены зависшего connect.
                 */
                override fun onSuccess() {
                    Log.i(TAG, "[cancelConnectBeforeRetry] Wi-Fi Direct connect отменен reason=$reason deviceAddress=$deviceAddress attempt=$attempt")
                    scheduleDiscoveryRetry(reason)
                }

                /**
                 * Все равно перезапускает discovery, если Android не смог отменить connect.
                 */
                override fun onFailure(cancelReason: Int) {
                    Log.w(
                        TAG,
                        "[cancelConnectBeforeRetry] Не удалось отменить Wi-Fi Direct connect reason=$reason cancelReason=$cancelReason deviceAddress=$deviceAddress attempt=$attempt",
                    )
                    scheduleDiscoveryRetry("${reason}_cancel_failed_$cancelReason")
                }
            },
        )
        Log.i(TAG, "[cancelConnectBeforeRetry] Запрошена отмена Wi-Fi Direct connect reason=$reason deviceAddress=$deviceAddress attempt=$attempt")
    }

    /**
     * Запрашивает актуальную ConnectionInfo после системного broadcast-а.
     */
    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        if (!hasWifiDirectPermission()) {
            Log.w(TAG, "[requestConnectionInfo] Нет permissions для Wi-Fi Direct connection info")
            return
        }
        wifiP2pManager.requestConnectionInfo(channel, ::handleConnectionInfo)
    }

    /**
     * Обрабатывает group owner address и запускает HELLO от client-а.
     */
    private fun handleConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) {
            Log.i(TAG, "[handleConnectionInfo] Wi-Fi Direct group еще не сформирована")
            return
        }
        if (sessionRole == VoiceTransportSessionRole.CLIENT && info.isGroupOwner) {
            Log.w(TAG, "[handleConnectionInfo] Client стал Wi-Fi Direct group owner, пересоздаем подключение как client")
            groupOwnerAddress = null
            pendingConnectDeviceAddress = null
            connectCooldownUntilMillis = System.currentTimeMillis() + CONNECT_CANCEL_COOLDOWN_MILLIS
            removeGroup("client_became_group_owner")
            scheduleDiscoveryRetry("client_became_group_owner")
            return
        }
        if (sessionRole == VoiceTransportSessionRole.HOST && !info.isGroupOwner) {
            Log.w(TAG, "[handleConnectionInfo] Host не является Wi-Fi Direct group owner, voice topology некорректна")
        }
        groupOwnerAddress = info.groupOwnerAddress
        pendingConnectDeviceAddress = null
        peerDiscoveryTargetDeviceAddress = null
        connectCooldownUntilMillis = 0L
        connectRetryJob?.cancel()
        connectRetryJob = null
        peerDiscoveryRetryJob?.cancel()
        peerDiscoveryRetryJob = null
        connectAttemptCount = 0
        Log.i(
            TAG,
            "[handleConnectionInfo] Wi-Fi Direct group сформирована isGroupOwner=${info.isGroupOwner} groupOwnerAddress=${info.groupOwnerAddress?.hostAddress}",
        )
        if (!info.isGroupOwner) {
            startHelloLoop()
        }
    }

    /**
     * Периодически отправляет HELLO group owner-у, чтобы host узнал UDP endpoint client-а.
     */
    private fun startHelloLoop() {
        helloJob?.cancel()
        helloJob = externalScope.launch(Dispatchers.IO) {
            repeat(HELLO_REPEAT_COUNT) { index ->
                sendHello()
                Log.i(TAG, "[startHelloLoop] HELLO отправлен index=$index")
                delay(HELLO_REPEAT_DELAY_MILLIS)
            }
        }
    }

    /**
     * Отправляет HELLO datagram на group owner address.
     */
    private fun sendHello() {
        val socket = udpSocket ?: return
        val peerId = selfPeerId ?: return
        val ownerAddress = groupOwnerAddress ?: return
        val payload = WifiDirectVoiceDatagramCodec.encodeHello(peerId)
        sendDatagram(socket, InetSocketAddress(ownerAddress, UDP_PORT), payload, peerId)
    }

    /**
     * Удаляет текущую Wi-Fi Direct group, если Android считает ее активной.
     */
    @SuppressLint("MissingPermission")
    private fun removeGroup(reason: String) {
        if (!hasWifiDirectPermission()) {
            return
        }
        wifiP2pManager.removeGroup(channel, actionListener("removeGroup:$reason"))
    }

    /**
     * Обновляет локальный Wi-Fi Direct deviceAddress и публикует control info, если session уже стартовала.
     */
    private fun handleThisDeviceChanged(device: WifiP2pDevice?) {
        localDeviceAddress = device?.deviceAddress
        Log.i(TAG, "[handleThisDeviceChanged] Локальный Wi-Fi Direct device=${device?.deviceName} address=$localDeviceAddress")
        publishLocalControlInfoIfReady()
        publishHostServiceIfReady()
    }

    /**
     * Публикует локальную Wi-Fi Direct визитку для Nearby signaling.
     */
    private fun publishLocalControlInfoIfReady() {
        val peerId = selfPeerId ?: return
        val role = sessionRole ?: return
        val info = VoiceTransportControlInfo(
            mode = MODE,
            peerId = peerId,
            wifiDirectDeviceAddress = localDeviceAddress,
            udpPort = UDP_PORT,
            isGroupOwner = role == VoiceTransportSessionRole.HOST,
            sentAtMillis = System.currentTimeMillis(),
        )
        cachedLocalControlInfo = info
        emitEvent(VoiceTransportEvent.LocalControlInfoChanged(info))
        Log.i(TAG, "[publishLocalControlInfoIfReady] Wi-Fi Direct voice info обновлен role=$role diagnosticDeviceAddress=$localDeviceAddress")
    }

    /**
     * Убирает DNS-SD service request и local service при остановке voice session.
     */
    @SuppressLint("MissingPermission")
    private fun clearServiceDiscoveryState() {
        if (!hasWifiDirectPermission()) {
            return
        }
        serviceRequest?.let { request ->
            wifiP2pManager.removeServiceRequest(channel, request, actionListener("removeServiceRequest"))
        }
        serviceRequest = null
        wifiP2pManager.clearLocalServices(channel, actionListener("clearLocalServices"))
    }

    /**
     * Проверяет runtime permissions, нужные для Wi-Fi Direct API на текущем Android.
     */
    private fun hasWifiDirectPermission(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Возвращает оставшуюся паузу перед новым Wi-Fi Direct connect после отказа стека.
     */
    private fun connectCooldownRemainingMillis(): Long {
        return (connectCooldownUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * Подбирает cooldown после отказа Wi-Fi Direct connect, чтобы не заспамить системный P2P стек.
     */
    private fun connectFailureCooldownMillis(reason: Int): Long {
        return when (reason) {
            WifiP2pManager.ERROR -> CONNECT_ERROR_COOLDOWN_MILLIS
            WifiP2pManager.BUSY -> CONNECT_CANCEL_COOLDOWN_MILLIS
            else -> CONNECT_FAILURE_COOLDOWN_MILLIS
        }
    }

    /**
     * Создает ActionListener с русскими логами для Wi-Fi Direct операций.
     */
    private fun actionListener(functionName: String): WifiP2pManager.ActionListener {
        return object : WifiP2pManager.ActionListener {
            /**
             * Логирует успешное завершение Wi-Fi Direct операции.
             */
            override fun onSuccess() {
                Log.i(TAG, "[$functionName] Wi-Fi Direct операция принята")
            }

            /**
             * Логирует код ошибки Wi-Fi Direct операции.
             */
            override fun onFailure(reason: Int) {
                Log.w(TAG, "[$functionName] Wi-Fi Direct операция отклонена reason=$reason")
            }
        }
    }

    /**
     * Отправляет событие подписчикам без блокировки Wi-Fi/UDP callback.
     */
    private fun emitEvent(event: VoiceTransportEvent) {
        if (!_events.tryEmit(event)) {
            Log.w(TAG, "[emitEvent] Буфер Wi-Fi Direct voice events переполнен type=${event.javaClass.simpleName}")
        }
    }

    /**
     * Receiver системных Wi-Fi Direct событий.
     */
    private inner class WifiDirectReceiver : BroadcastReceiver() {
        /**
         * Обрабатывает системные Wi-Fi Direct broadcasts.
         */
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED)
                    Log.i(TAG, "[onReceive] Wi-Fi Direct state=$state")
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    handleThisDeviceChanged(device)
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.i(TAG, "[onReceive] Wi-Fi Direct peers changed")
                    if (sessionRole == VoiceTransportSessionRole.CLIENT && expectedHostPeerId != null) {
                        if (peerDiscoveryTargetDeviceAddress != null) {
                            requestPeersForPendingHost("peers_changed")
                        } else {
                            discoverServices()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        private const val TAG = "WifiDirectVoiceTransport"
        private const val MODE = "WIFI_DIRECT_UDP"
        private const val UDP_PORT = 48982
        private const val MAX_DATAGRAM_BYTES = 16_384
        private const val EVENT_BUFFER_CAPACITY = 64
        private const val HELLO_REPEAT_COUNT = 40
        private const val HELLO_REPEAT_DELAY_MILLIS = 500L
        private const val CONNECT_FORMATION_TIMEOUT_MILLIS = 60_000L
        private const val CONNECT_CANCEL_DELAY_MILLIS = 2_000L
        private const val DISCOVERY_RETRY_DELAY_MILLIS = 2_000L
        private const val PEER_TABLE_RETRY_DELAY_MILLIS = 2_000L
        private const val CONNECT_FAILURE_COOLDOWN_MILLIS = 5_000L
        private const val CONNECT_ERROR_COOLDOWN_MILLIS = 15_000L
        private const val CONNECT_CANCEL_COOLDOWN_MILLIS = 10_000L
        private const val CLIENT_GROUP_OWNER_INTENT = 0
        private const val ANONYMIZED_DEVICE_ADDRESS = "02:00:00:00:00:00"
        private const val SERVICE_INSTANCE_NAME = "btratio_voice"
        private const val SERVICE_REGISTRATION_TYPE = "_presence._tcp"
        private const val SERVICE_TXT_APP = "app"
        private const val SERVICE_TXT_VERSION = "v"
        private const val SERVICE_TXT_HOST_PEER_ID = "hostPeerId"
        private const val SERVICE_TXT_PORT = "udpPort"
        private const val SERVICE_APP_VALUE = "btratio"
        private const val SERVICE_VERSION_VALUE = "1"
    }
}
