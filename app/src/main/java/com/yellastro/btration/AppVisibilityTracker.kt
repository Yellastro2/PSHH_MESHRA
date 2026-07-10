package com.yellastro.btration

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Отслеживает, есть ли у приложения хотя бы одна видимая Activity.
 */
class AppVisibilityTracker : Application.ActivityLifecycleCallbacks {
    private val startedActivityCount = AtomicInteger(0)

    /**
     * Возвращает true, если приложение сейчас находится на экране пользователя.
     */
    val isAppVisible: Boolean
        get() = startedActivityCount.get() > 0

    /**
     * Не используется: видимость приложения меняется на onActivityStarted/onActivityStopped.
     */
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    /**
     * Увеличивает счетчик видимых Activity.
     */
    override fun onActivityStarted(activity: Activity) {
        startedActivityCount.incrementAndGet()
    }

    /**
     * Не используется: paused Activity все еще может быть частично видимой.
     */
    override fun onActivityResumed(activity: Activity) = Unit

    /**
     * Не используется: paused Activity все еще может быть частично видимой.
     */
    override fun onActivityPaused(activity: Activity) = Unit

    /**
     * Уменьшает счетчик видимых Activity, не позволяя ему уйти ниже нуля.
     */
    override fun onActivityStopped(activity: Activity) {
        startedActivityCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    /**
     * Не используется: состояние видимости не зависит от сохранения instance state.
     */
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    /**
     * Не используется: остановка Activity уже обработана в onActivityStopped.
     */
    override fun onActivityDestroyed(activity: Activity) = Unit
}
