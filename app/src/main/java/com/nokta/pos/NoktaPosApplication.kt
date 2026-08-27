package com.nokta.pos

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.nokta.pos.device.DeviceHeartbeatCoordinator
import com.nokta.pos.sync.SyncTriggerCoordinator
import com.nokta.pos.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NoktaPosApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncTriggerCoordinator: SyncTriggerCoordinator
    @Inject lateinit var deviceHeartbeatCoordinator: DeviceHeartbeatCoordinator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedulePeriodic(WorkManager.getInstance(this))
        syncTriggerCoordinator.start()
        deviceHeartbeatCoordinator.start()
    }
}
