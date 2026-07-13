package com.yellastro.btration

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Главный контейнер UI-слоя: запрашивает permissions, выбирает стартовый экран и управляет ручной fragment-навигацией.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var nearbyPermissionLauncher: ActivityResultLauncher<Array<String>>
    private val appContainer: AppContainer
        get() = (application as BtRationApplication).appContainer

    private val _keyboardVisible = MutableStateFlow(false)
    val keyboardVisible: StateFlow<Boolean> = _keyboardVisible.asStateFlow()

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

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            requestStartupPermissionsIfNeeded()
        }

        val root = findViewById<View>(R.id.fragment_container)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )

            val imeVisible =
                insets.isVisible(WindowInsetsCompat.Type.ime())

            val imeBottom =
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom

            view.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = maxOf(systemBars.bottom, imeBottom),
            )

            _keyboardVisible.value = imeVisible

            insets
        }

        ViewCompat.requestApplyInsets(root)
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
     * Заменяет текущий стартовый экран без сохранения предыдущего фрагмента в back stack.
     */
    fun replaceRoot(fragment: Fragment) {
        supportFragmentManager.popBackStack(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE,
        )
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    /**
     * Возвращает пользователя на предыдущий экран фрагментного стека.
     */
    fun popBackStack() {
        supportFragmentManager.popBackStack()
    }
}
