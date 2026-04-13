package com.ruich97.beastlocatorwatch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.material.color.DynamicColors

class RandomDirectionApp : Application() {
    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base)
        if (base != null) {
            AppLanguageController.applyPolicy(base)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLanguageController.applyPolicy(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        BackgroundLocationUpdater.updateRegistration(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NotificationHelper.CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }
}

