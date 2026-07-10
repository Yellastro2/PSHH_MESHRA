package com.yellastro.btration

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yellastro.btration.ui.profile.ProfileUiState
import com.yellastro.btration.ui.profile.ProfileViewModel
import kotlinx.coroutines.launch

/**
 * Экран первичной настройки имени пользователя, который после завершения заменяется лобби без back stack.
 */
class ProfileSetupFragment : Fragment() {
    private val viewModel: ProfileViewModel by viewModels {
        (requireActivity().application as BtRationApplication).appContainer.profileViewModelFactory()
    }

    private lateinit var etNickname: EditText
    private lateinit var btnContinue: Button
    private lateinit var tvError: TextView
    private var openedLobby = false

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

        etNickname.doAfterTextChanged { editable ->
            viewModel.onNameChanged(editable?.toString().orEmpty())
        }
        etNickname.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.onContinueClicked()
                true
            } else {
                false
            }
        }

        btnContinue.setOnClickListener {
            viewModel.onContinueClicked()
        }

        collectUiState()
    }

    /**
     * Подписывается на состояние формы профиля.
     */
    private fun collectUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::renderState)
            }
        }
    }

    /**
     * Отрисовывает состояние формы и открывает лобби после успешного сохранения.
     */
    private fun renderState(state: ProfileUiState) {
        if (etNickname.text.toString() != state.name) {
            etNickname.setText(state.name)
            etNickname.setSelection(state.name.length)
        }
        btnContinue.isEnabled = state.canContinue
        tvError.text = state.errorMessage.orEmpty()
        tvError.visibility = if (state.errorMessage == null) View.GONE else View.VISIBLE
        if (state.isCompleted && !openedLobby) {
            openedLobby = true
            (activity as? MainActivity)?.replaceRoot(LobbyFragment())
        }
    }
}
