package com.yellastro.btration

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Моковый экран первичной настройки имени пользователя.
 */
class ProfileSetupFragment : Fragment() {

    private lateinit var etNickname: EditText
    private lateinit var btnContinue: Button
    private lateinit var tvError: TextView

    /**
     * Создает XML-разметку экрана профиля.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile_setup, container, false)
    }

    /**
     * Настраивает поле имени, кнопку продолжения и действие клавиатуры Done.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etNickname = view.findViewById(R.id.et_nickname)
        btnContinue = view.findViewById(R.id.btn_continue)
        tvError = view.findViewById(R.id.tv_error)

        etNickname.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleContinue()
                true
            } else {
                false
            }
        }

        btnContinue.setOnClickListener {
            handleContinue()
        }
    }

    /**
     * Валидирует имя, сохраняет его локально и открывает лобби.
     */
    private fun handleContinue() {
        val nickname = etNickname.text.toString().trim()
        if (nickname.isEmpty()) {
            tvError.text = "Имя не может быть пустым"
            tvError.visibility = View.VISIBLE
            return
        }

        if (nickname.length > 18) {
            tvError.text = "Имя слишком длинное (макс. 18 символов)"
            tvError.visibility = View.VISIBLE
            return
        }

        tvError.visibility = View.GONE

        val sharedPrefs = requireActivity().getSharedPreferences("walkie_talkie_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("self_name", nickname).apply()

        (activity as? MainActivity)?.navigateTo(LobbyFragment())
    }
}
