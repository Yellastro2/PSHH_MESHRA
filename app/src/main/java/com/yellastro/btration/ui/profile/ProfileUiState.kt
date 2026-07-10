package com.yellastro.btration.ui.profile

/**
 * UI-состояние экрана ввода имени пользователя.
 */
data class ProfileUiState(
    val name: String = "",
    val canContinue: Boolean = false,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null,
)
