package com.yellastro.btration

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/**
 * Главный контейнер мокового UI-слоя, выбирающий стартовый экран по сохраненному имени.
 */
class MainActivity : AppCompatActivity() {
    /**
     * Загружает контейнер фрагментов и показывает лобби или экран профиля.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sharedPrefs = getSharedPreferences("walkie_talkie_prefs", MODE_PRIVATE)
        val selfName = sharedPrefs.getString("self_name", null)

        if (savedInstanceState == null) {
            val startFragment = if (!selfName.isNullOrEmpty()) {
                LobbyFragment()
            } else {
                ProfileSetupFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, startFragment)
                .commit()
        }
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
