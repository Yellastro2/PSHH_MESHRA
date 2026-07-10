package com.yellastro.btration.service

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Точка управления RoomConnectionService без бизнес-логики комнаты.
 */
class RoomServiceController(
    private val context: Context,
) {
    private var started = false

    /**
     * Запускает foreground service, если runtime находится в активном состоянии.
     */
    fun startIfNeeded(isNeeded: Boolean) {
        if (!isNeeded) {
            Log.i(TAG, "[startIfNeeded] Service не нужен для текущего состояния")
            return
        }
        Log.i(TAG, "[startIfNeeded] Запускаем RoomConnectionService")
        started = true
        ContextCompat.startForegroundService(
            context,
            RoomConnectionService.createStartIntent(context),
        )
    }

    /**
     * Останавливает service-обвязку, если runtime уже idle или error.
     */
    fun stopIfIdle(isIdle: Boolean) {
        if (!isIdle || !started) {
            Log.i(TAG, "[stopIfIdle] Service оставлен активным isIdle=$isIdle started=$started")
            return
        }
        Log.i(TAG, "[stopIfIdle] Останавливаем RoomConnectionService")
        started = false
        context.startService(RoomConnectionService.createStopIntent(context))
    }

    /**
     * Возвращает, считает ли controller service-обвязку активной.
     */
    fun isStarted(): Boolean {
        return started
    }

    private companion object {
        private const val TAG = "RoomServiceController"
    }
}
