package com.yellastro.btration

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.nearby.Nearby
import com.yellastro.btration.data.nearby.NearbyRoomAdvertisement
import com.yellastro.btration.data.nearby.NearbyTransport
import com.yellastro.btration.data.wire.WireCodec
import com.yellastro.btration.domain.mesh.MeshCodec
import com.yellastro.btration.domain.mesh.MeshHeartbeatCodec
import com.yellastro.btration.domain.mesh.MeshRoomAdvertisement
import com.yellastro.btration.domain.mesh.MeshTransport
import com.yellastro.btration.domain.mesh.MeshVoicePacketCodec
import com.yellastro.btration.domain.runtime.RoomTransport
import com.yellastro.btration.domain.runtime.RoomRuntime
import com.yellastro.btration.domain.util.IdGenerator
import com.yellastro.btration.repository.IgnoredNearbyRepository
import com.yellastro.btration.repository.ProfileRepository
import com.yellastro.btration.repository.RoomRepository
import com.yellastro.btration.repository.RoomSettingsRepository
import com.yellastro.btration.repository.VoiceSettingsRepository
import com.yellastro.btration.service.RoomServiceController
import com.yellastro.btration.ui.lobby.LobbyViewModel
import com.yellastro.btration.ui.profile.ProfileViewModel
import com.yellastro.btration.ui.room.RoomViewModel
import com.yellastro.btration.voice.NearbyVoiceTransport
import com.yellastro.btration.voice.PcmVoiceCapture
import com.yellastro.btration.voice.PcmVoicePlayer
import com.yellastro.btration.voice.SwitchableVoiceTransport
import com.yellastro.btration.voice.UnavailableVoiceTransport
import com.yellastro.btration.voice.VoiceFrameCodec
import com.yellastro.btration.voice.VoiceRuntime
import com.yellastro.btration.voice.VoiceTransport
import com.yellastro.btration.voice.VoiceTransportMode
import com.yellastro.btration.voice.WifiDirectVoiceTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/**
 * Ручной composition root приложения: собирает prefs, Nearby transport, room/mesh/voice кодеки, runtime и repository.
 */
class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val wireCodec = WireCodec(json)
    private val meshCodec = MeshCodec(json)
    private val meshHeartbeatCodec = MeshHeartbeatCodec()
    private val meshVoicePacketCodec = MeshVoicePacketCodec()
    private val idGenerator = IdGenerator()
    private val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    /**
     * Репозиторий локального профиля пользователя.
     */
    val profileRepository = ProfileRepository(
        prefs = prefs,
    )
    private val ignoredNearbyRepository = IgnoredNearbyRepository(
        prefs = prefs,
    )
    private val nearbyTransport = NearbyTransport(
        context = applicationContext,
        connectionsClient = Nearby.getConnectionsClient(applicationContext),
    )
    private val roomTransport = RoomTransport(
        neighborTransport = nearbyTransport,
        wireCodec = wireCodec,
        externalScope = applicationScope,
        shouldIgnoreMessage = { bytes ->
            VoiceFrameCodec.isVoiceFrame(bytes) ||
                meshCodec.isMeshEnvelope(bytes) ||
                meshHeartbeatCodec.isHeartbeatPacket(bytes) ||
                meshVoicePacketCodec.isVoicePacket(bytes)
        },
        shouldAcceptConnection = ::shouldAcceptNeighborConnection,
    )
    private val meshTransport = MeshTransport(
        selfPeerId = profileRepository.getOrCreatePeerId(),
        neighborTransport = nearbyTransport,
        codec = meshCodec,
        voicePacketCodec = meshVoicePacketCodec,
        externalScope = applicationScope,
        acceptIncomingConnections = false,
    )
    /**
     * Репозиторий пользовательских voice-настроек, сохраненных в SharedPreferences.
     */
    val voiceSettingsRepository = VoiceSettingsRepository(
        prefs = prefs,
    )
    private val roomSettingsRepository = RoomSettingsRepository(
        prefs = prefs,
    )
    private val voiceTransportPreference = voiceSettingsRepository.voiceTransportPreference.value
    private val voiceTransportMode = voiceTransportPreference.transportMode ?: VoiceTransportMode.WIFI_DIRECT_UDP
    private val voiceTransport = SwitchableVoiceTransport(
        initialMode = voiceTransportMode,
        externalScope = applicationScope,
        createDelegate = ::createVoiceTransport,
    )
    private val roomServiceController = RoomServiceController(applicationContext)
    private val voiceRuntime = VoiceRuntime(
        voiceTransport = voiceTransport,
        voiceCapture = PcmVoiceCapture(applicationScope),
        voicePlayer = PcmVoicePlayer(applicationScope),
        externalScope = applicationScope,
    )

    init {
        roomTransport.stopAllEndpointsAndClearState(reason = "app_container_init")
    }

    private val roomRuntime = RoomRuntime(
        profileRepository = profileRepository,
        roomTransport = roomTransport,
        meshTransport = meshTransport,
        voiceTransport = voiceTransport,
        voiceRuntime = voiceRuntime,
        voiceSettingsRepository = voiceSettingsRepository,
        idGenerator = idGenerator,
        externalScope = applicationScope,
        shouldUseMeshGateway = { peerId -> !ignoredNearbyRepository.isPeerIgnored(peerId) },
    )

    /**
     * Репозиторий комнаты для ViewModel-слоя.
     */
    val roomRepository = RoomRepository(
        roomRuntime = roomRuntime,
        roomServiceController = roomServiceController,
    )

    /**
     * Создает factory для ProfileViewModel.
     */
    fun profileViewModelFactory(): ViewModelProvider.Factory {
        return viewModelFactory(ProfileViewModel::class.java) {
            ProfileViewModel(profileRepository)
        }
    }

    /**
     * Создает factory для LobbyViewModel.
     */
    fun lobbyViewModelFactory(): ViewModelProvider.Factory {
        return viewModelFactory(LobbyViewModel::class.java) {
            LobbyViewModel(
                roomRepository = roomRepository,
                profileRepository = profileRepository,
                ignoredNearbyRepository = ignoredNearbyRepository,
                roomSettingsRepository = roomSettingsRepository,
                voiceSettingsRepository = voiceSettingsRepository,
            )
        }
    }

    /**
     * Создает factory для RoomViewModel с состоянием комнаты и сохраненными voice-настройками.
     */
    fun roomViewModelFactory(): ViewModelProvider.Factory {
        return viewModelFactory(RoomViewModel::class.java) {
            RoomViewModel(
                roomRepository = roomRepository,
                voiceSettingsRepository = voiceSettingsRepository,
            )
        }
    }

    /**
     * Создает простую factory для ViewModel без DI-фреймворка.
     */
    private fun <T : ViewModel> viewModelFactory(
        expectedClass: Class<T>,
        createViewModel: () -> T,
    ): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            /**
             * Возвращает ViewModel ожидаемого типа или сообщает о неверном классе.
             */
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
                if (modelClass.isAssignableFrom(expectedClass)) {
                    return createViewModel() as VM
                }
                error("Неизвестный класс ViewModel: ${modelClass.name}")
            }
        }
    }

    /**
     * Создает текущую реализацию media-plane для голоса по режиму, прочитанному из пользовательских настроек.
     */
    private fun createVoiceTransport(mode: VoiceTransportMode): VoiceTransport {
        return when (mode) {
            VoiceTransportMode.NEARBY_BYTES -> NearbyVoiceTransport(
                neighborTransport = nearbyTransport,
                peerLinkResolver = roomTransport,
                externalScope = applicationScope,
            )
            VoiceTransportMode.WIFI_DIRECT_UDP -> createWifiDirectVoiceTransport()
        }
    }

    /**
     * Создает Wi-Fi Direct media-plane только если устройство заявляет поддержку Wi-Fi Direct и отдает системный manager.
     */
    private fun createWifiDirectVoiceTransport(): VoiceTransport {
        val hasWifiDirect = applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        val wifiP2pManager = applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (!hasWifiDirect || wifiP2pManager == null) {
            return UnavailableVoiceTransport(WIFI_DIRECT_UNAVAILABLE_MESSAGE)
        }
        return WifiDirectVoiceTransport(
            context = applicationContext,
            externalScope = applicationScope,
        )
    }

    /**
     * Отклоняет прямое connection request от gateway/host-а, который локально добавлен в ignore-list.
     */
    private fun shouldAcceptNeighborConnection(endpointName: String): Boolean {
        val meshGatewayPeerId = MeshRoomAdvertisement.decode(endpointName)
            ?.toAdvertisedGateway()
            ?.peerId
        if (meshGatewayPeerId != null && ignoredNearbyRepository.isPeerIgnored(meshGatewayPeerId)) {
            Log.i(TAG, "[shouldAcceptNeighborConnection] Отклоняем ignored mesh gateway peerId=${meshGatewayPeerId.value}")
            return false
        }

        val nearbyGatewayPeerId = NearbyRoomAdvertisement.decode(endpointName)
            ?.toRoomInfo()
            ?.let { roomInfo -> roomInfo.gateway?.peerId ?: roomInfo.host.peerId }
        if (nearbyGatewayPeerId != null && ignoredNearbyRepository.isPeerIgnored(nearbyGatewayPeerId)) {
            Log.i(TAG, "[shouldAcceptNeighborConnection] Отклоняем ignored Nearby gateway peerId=${nearbyGatewayPeerId.value}")
            return false
        }
        return true
    }

    private companion object {
        private const val TAG = "AppContainer"
        private const val PREFS_NAME = "walkie_talkie_prefs"
        private const val WIFI_DIRECT_UNAVAILABLE_MESSAGE = "Wi-Fi Direct не поддерживается на этом устройстве"
    }
}
