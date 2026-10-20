package com.smartcooking.app

import android.app.Application
import com.smartcooking.app.core.AppContainer

class CookXApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    fun reloadContainer() {
        container.dispose()
        container = AppContainer(this)
    }
}
