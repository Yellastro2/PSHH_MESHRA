package com.yellastro.btration

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * Главный контейнер UI-слоя: запрашивает Nearby/voice/notification permissions и выбирает стартовый экран.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var nearbyPermissionLauncher: ActivityResultLauncher<Array<String>>
    private val appContainer: AppContainer
        get() = (application as BtRationApplication).appContainer

    /**
     * Загружает контейнер, запрашивает permissions и показывает лобби или экран профиля.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nearbyPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            handleStartupPermissionResult()
        }
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            requestStartupPermissionsIfNeeded()
        }
    }

    /**
     * Запрашивает runtime permissions, без которых Nearby, голос и уведомления не смогут работать полноценно.
     */
    private fun requestStartupPermissionsIfNeeded() {
        val missingPermissions = requiredStartupPermissions()
            .filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }

        if (missingPermissions.isNotEmpty()) {
            nearbyPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            showStartFragmentIfNeeded()
        }
    }

    /**
     * Проверяет результат runtime-запроса, показывает недостающие permissions и открывает стартовый экран.
     */
    private fun handleStartupPermissionResult() {
        val missingPermissions = missingRequiredStartupPermissions()
        if (missingPermissions.isNotEmpty()) {
            Toast.makeText(
                this,
                "Не выданы permissions: ${missingPermissions.joinToString()}",
                Toast.LENGTH_LONG,
            ).show()
        }

        showStartFragmentIfNeeded()
    }

    /**
     * Возвращает набор runtime permissions для текущей версии Android.
     */
    private fun requiredStartupPermissions(): List<String> {
        return (requiredNearbyPermissions() + requiredVoicePermissions() + optionalNotificationPermissions()).distinct()
    }

    /**
     * Возвращает runtime permissions для будущей PTT-передачи голоса.
     */
    private fun requiredVoicePermissions(): List<String> {
        return listOf(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Возвращает обязательные runtime permissions для Nearby discovery/advertising.
     */
    private fun requiredNearbyPermissions(): List<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )

            else -> listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }

    /**
     * Возвращает необязательные permissions для дополнительных уведомлений.
     */
    private fun optionalNotificationPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }
    }

    /**
     * Возвращает список обязательных startup permissions, которые Android сейчас не выдал.
     */
    private fun missingRequiredStartupPermissions(): List<String> {
        return requiredStartupPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Показывает стартовый фрагмент один раз после проверки startup permissions.
     */
    private fun showStartFragmentIfNeeded() {
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) != null) {
            return
        }

        val startFragment = if (!appContainer.profileRepository.getPeerName().isNullOrEmpty()) {
            LobbyFragment()
        } else {
            ProfileSetupFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, startFragment)
            .commit()
    }

    /**
     * Открывает следующий фрагмент с простой fade-анимацией и добавлением в back stack.
     */
    fun navigateTo(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    /**
     * Возвращает пользователя на предыдущий экран фрагментного стека.
     */
    fun popBackStack() {
        supportFragmentManager.popBackStack()
    }
}
