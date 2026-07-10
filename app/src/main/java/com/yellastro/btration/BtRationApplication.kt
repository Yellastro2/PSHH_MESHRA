package com.yellastro.btration

import android.app.Application

/**
 * Application-точка проекта, создающая ручной контейнер зависимостей без DI-фреймворков.
 */
class BtRationApplication : Application() {
    /**
     * Трекер видимости приложения для фоновых уведомлений.
     */
    lateinit var appVisibilityTracker: AppVisibilityTracker
        private set

    /**
     * Контейнер долгоживущих объектов приложения.
     */
    lateinit var appContainer: AppContainer
        private set

    /**
     * Создает AppContainer при старте процесса приложения.
     */
    override fun onCreate() {
        super.onCreate()
        appVisibilityTracker = AppVisibilityTracker()
        registerActivityLifecycleCallbacks(appVisibilityTracker)
        appContainer = AppContainer(this)
    }
}
