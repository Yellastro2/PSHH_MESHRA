package com.yellastro.btration

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.nearby.Nearby
import com.yellastro.btration.data.nearby.NearbyTransport
import com.yellastro.btration.data.wire.WireCodec
import com.yellastro.btration.domain.runtime.RoomRuntime
import com.yellastro.btration.domain.util.IdGenerator
import com.yellastro.btration.repository.ProfileRepository
import com.yellastro.btration.repository.RoomRepository
import com.yellastro.btration.service.RoomServiceController
import com.yellastro.btration.ui.lobby.LobbyViewModel
import com.yellastro.btration.ui.profile.ProfileViewModel
import com.yellastro.btration.ui.room.RoomViewModel
import com.yellastro.btration.voice.NearbyVoiceTransport
import com.yellastro.btration.voice.PcmVoiceCapture
import com.yellastro.btration.voice.PcmVoicePlayer
import com.yellastro.btration.voice.UnavailableVoiceTransport
import com.yellastro.btration.voice.VoiceRuntime
import com.yellastro.btration.voice.VoiceTransport
import com.yellastro.btration.voice.VoiceTransportMode
import com.yellastro.btration.voice.WifiDirectVoiceTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/**
 * Ручной composition root приложения: собирает signaling-транспорт, voice-транспорт, runtime, repository и ViewModel factories.
 */
class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val wireCodec = WireCodec(json)
    private val idGenerator = IdGenerator()
    private val nearbyTransport = NearbyTransport(
        context = applicationContext,
        connectionsClient = Nearby.getConnectionsClient(applicationContext),
        wireCodec = wireCodec,
    )
    private val voiceTransportMode = VoiceTransportMode.WIFI_DIRECT_UDP
    private val voiceTransport = createVoiceTransport(voiceTransportMode)
    private val roomServiceController = RoomServiceController(applicationContext)
    private val voiceRuntime = VoiceRuntime(
        voiceTransport = voiceTransport,
        voiceCapture = PcmVoiceCapture(applicationScope),
        voicePlayer = PcmVoicePlayer(applicationScope),
        externalScope = applicationScope,
    )

    init {
        nearbyTransport.stopAllEndpointsAndClearState(reason = "app_container_init")
    }

    /**
     * Репозиторий локального профиля пользователя.
     */
    val profileRepository = ProfileRepository(
        prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val roomRuntime = RoomRuntime(
        profileRepository = profileRepository,
        nearbyTransport = nearbyTransport,
        voiceTransport = voiceTransport,
        voiceRuntime = voiceRuntime,
        idGenerator = idGenerator,
        externalScope = applicationScope,
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
            LobbyViewModel(roomRepository, profileRepository)
        }
    }

    /**
     * Создает factory для RoomViewModel.
     */
    fun roomViewModelFactory(): ViewModelProvider.Factory {
        return viewModelFactory(RoomViewModel::class.java) {
            RoomViewModel(roomRepository)
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
     * Создает текущую реализацию media-plane для голоса; позже сюда придет выбор из настроек.
     */
    private fun createVoiceTransport(mode: VoiceTransportMode): VoiceTransport {
        return when (mode) {
            VoiceTransportMode.NEARBY_BYTES -> NearbyVoiceTransport(
                nearbyTransport = nearbyTransport,
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

    private companion object {
        private const val PREFS_NAME = "walkie_talkie_prefs"
        private const val WIFI_DIRECT_UNAVAILABLE_MESSAGE = "Wi-Fi Direct не поддерживается на этом устройстве"
    }
}
