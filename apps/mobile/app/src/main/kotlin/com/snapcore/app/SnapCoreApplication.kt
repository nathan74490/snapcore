package com.snapcore.app

import android.app.Application
import com.snapcore.app.push.Notifications

class SnapCoreApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
    }
}
