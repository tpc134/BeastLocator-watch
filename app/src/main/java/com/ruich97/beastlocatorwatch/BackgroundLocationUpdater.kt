package com.ruich97.beastlocatorwatch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object BackgroundLocationUpdater {
    const val ACTION_LOCATION_UPDATE = "com.ruich97.beastlocatorwatch.ACTION_LOCATION_UPDATE"

    fun updateRegistration(context: Context) {
        val store = DestinationStore(context)
        val shouldRunForegroundMonitor =
            store.isBackgroundLocationUpdateActive() && hasRequiredPermission(context)
        if (shouldRunForegroundMonitor) {
            ForegroundDistanceMonitorService.start(context)
        } else {
            ForegroundDistanceMonitorService.stop(context)
        }
    }

    private fun hasRequiredPermission(context: Context): Boolean {
        // 手表版只检查前台定位权限
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}

