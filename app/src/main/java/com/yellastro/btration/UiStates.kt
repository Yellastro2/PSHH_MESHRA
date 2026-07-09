package com.yellastro.btration

/**
 * Моковое описание комнаты для текущего несвязанного UI-слоя.
 */
data class Room(
    val id: String,
    val name: String,
    val hostName: String,
    val memberCount: Int,
    val memberCountText: String,
    val isLost: Boolean = false
)

/**
 * Моковое текстовое сообщение для экрана комнаты.
 */
data class Message(
    val id: String,
    val text: String,
    val senderName: String,
    val timestamp: String,
    val isSystem: Boolean = false,
    val isMe: Boolean = false
)

/**
 * Моковый участник комнаты для горизонтального списка участников.
 */
data class Member(
    val id: String,
    val name: String,
    val isTalking: Boolean = false,
    val isMuted: Boolean = false,
    val isMe: Boolean = false,
    val role: String = "USER",
)
