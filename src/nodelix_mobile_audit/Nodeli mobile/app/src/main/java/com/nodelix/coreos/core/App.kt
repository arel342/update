package com.nodelix.coreos.core

import android.app.Application
import com.nodelix.coreos.worker.ConnectionWorker

/**
 * Application entry point initializing the non-blocking [AppContainer] and scheduling
 * OS-compliant periodic WorkManager health checks.
 */
class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        runCatching {
            ConnectionWorker.schedulePeriodicSync(this)
        }
    }
}
