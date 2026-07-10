package com.yellastro.btration.ui.profile

import androidx.lifecycle.ViewModel
import com.yellastro.btration.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel экрана профиля: валидирует и сохраняет имя локального пользователя.
 */
class ProfileViewModel(
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(initialState())

    /**
     * Состояние формы имени пользователя.
     */
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    /**
     * Обновляет имя в форме и пересчитывает возможность продолжить.
     */
    fun onNameChanged(value: String) {
        _uiState.value = _uiState.value.copy(
            name = value,
            canContinue = canContinue(value),
            isCompleted = false,
            errorMessage = null,
        )
    }

    /**
     * Валидирует имя, сохраняет профиль и помечает экран завершенным.
     */
    fun onContinueClicked() {
        val name = _uiState.value.name.trim()
        val error = validateName(name)
        if (error != null) {
            _uiState.value = _uiState.value.copy(
                canContinue = false,
                isCompleted = false,
                errorMessage = error,
            )
            return
        }

        profileRepository.getOrCreatePeerId()
        profileRepository.setPeerName(name)
        _uiState.value = _uiState.value.copy(
            name = name,
            canContinue = true,
            isCompleted = true,
            errorMessage = null,
        )
    }

    /**
     * Собирает начальное состояние из сохраненного профиля.
     */
    private fun initialState(): ProfileUiState {
        val savedName = profileRepository.getPeerName().orEmpty()
        return ProfileUiState(
            name = savedName,
            canContinue = canContinue(savedName),
            isCompleted = false,
            errorMessage = null,
        )
    }

    /**
     * Возвращает true, если имя можно сохранить.
     */
    private fun canContinue(value: String): Boolean {
        return validateName(value.trim()) == null
    }

    /**
     * Возвращает текст ошибки для некорректного имени или null для валидного значения.
     */
    private fun validateName(value: String): String? {
        return when {
            value.isBlank() -> "Имя не может быть пустым"
            value.length > MAX_NAME_LENGTH -> "Имя слишком длинное (макс. $MAX_NAME_LENGTH символов)"
            else -> null
        }
    }

    private companion object {
        private const val MAX_NAME_LENGTH = 18
    }
}
