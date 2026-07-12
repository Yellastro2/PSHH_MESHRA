package com.yellastro.btration.domain.runtime

/**
 * Одноразовое пользовательское уведомление runtime-слоя, которое UI показывает через snackbar.
 */
data class RoomRuntimeNotice(
    val id: Long,
    val message: String,
)
