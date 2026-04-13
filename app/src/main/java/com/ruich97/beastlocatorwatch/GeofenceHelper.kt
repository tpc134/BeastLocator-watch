package com.ruich97.beastlocatorwatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

object GeofenceHelper {
    const val ACTION_GEOFENCE = "com.ruich97.beastlocatorwatch.ACTION_GEOFENCE_EVENT"
    private const val GEOFENCE_ID = "destination_geofence"

    private fun geofencingClient(context: Context): GeofencingClient {
        return LocationServices.getGeofencingClient(context)
    }

    private fun geofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE
        }
        return PendingIntent.getBroadcast(
            context,
            10,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun canRegisterDestinationGeofence(context: Context): Boolean {
        // 手表版只检查前台定位权限
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun registerDestinationGeofence(context: Context, destination: Destination) {
        if (!canRegisterDestinationGeofence(context)) {
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(destination.lat, destination.lng, 50f)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        runCatching {
            geofencingClient(context).addGeofences(request, geofencePendingIntent(context))
        }
    }

    fun clearDestinationGeofence(context: Context) {
        geofencingClient(context).removeGeofences(geofencePendingIntent(context))
    }
}

