package com.ruich97.beastlocatorwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.LocationResult

class BackgroundLocationReceiver : BroadcastReceiver() {
    companion object {
        private const val ARRIVAL_THRESHOLD_METERS = 50f
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackgroundLocationUpdater.ACTION_LOCATION_UPDATE) return
        val result = LocationResult.extractResult(intent) ?: return
        val location = result.lastLocation ?: return

        val store = DestinationStore(context)
        if (store.isDebugDistanceOverrideEnabled()) {
            DestinationWidgetProvider.refreshAllWidgets(context)
            return
        }
        val current = Destination(location.latitude, location.longitude)
        store.setLastKnownLocationFromSystem(current.lat, current.lng)
        if (location.hasBearing()) {
            store.setLastKnownHeading(location.bearing)
        }

        val destination = store.getDestination()
        if (!store.isDestinationAnswered()) {
            GeofenceHelper.registerDestinationGeofence(context, destination)
        } else {
            GeofenceHelper.clearDestinationGeofence(context)
        }

        val distance = GeoUtils.distanceMeters(current, destination)
        if (store.isArrivalRearmRequired() && distance > ARRIVAL_THRESHOLD_METERS) {
            store.setArrivalRearmRequired(false)
        }
        if (handleArrivalByDistance(context, store, destination, distance)) {
            return
        }
        updateApproachLiveUpdate(context, store, distance)
        DestinationWidgetProvider.refreshAllWidgets(context)
    }

    private fun handleArrivalByDistance(
        context: Context,
        store: DestinationStore,
        destination: Destination,
        distanceMeters: Float
    ): Boolean {
        if (store.isDestinationAnswered()) return false
        if (store.isArrivalRearmRequired()) return false
        if (distanceMeters > ARRIVAL_THRESHOLD_METERS) return false

        if (store.isArrivalSoundEnabled()) {
            SoundEffectPlayer.play(context, R.raw.arrival_0km)
        }
        store.setDestinationAnswered(true)
        store.setArrivalDestinationName("${destination.lat}, ${destination.lng}")
        NotificationHelper.cancelApproachProgress(context)
        NotificationHelper.showDestinationReached(
            context,
            context.getString(
                R.string.notification_body,
                "${destination.lat}, ${destination.lng}"
            )
        )
        GeofenceHelper.clearDestinationGeofence(context)

        val pending = goAsync()
        Thread {
            try {
                val resolved = ReverseGeocoder.resolve(context, destination)
                if (!store.isDestinationAnswered() || store.getDestination() != destination) {
                    return@Thread
                }
                store.setArrivalDestinationName(resolved)
                NotificationHelper.showDestinationReached(
                    context,
                    context.getString(R.string.notification_body, resolved)
                )
            } finally {
                DestinationWidgetProvider.refreshAllWidgets(context)
                pending.finish()
            }
        }.start()
        return true
    }

    private fun updateApproachLiveUpdate(
        context: Context,
        store: DestinationStore,
        distanceMeters: Float
    ) {
        if (!NotificationHelper.isLiveUpdateSupported()) {
            NotificationHelper.cancelApproachProgress(context)
            store.clearLiveUpdateAnchorDistanceMeters()
            return
        }
        if (!store.isLiveUpdateEnabled() || store.isDestinationAnswered()) {
            NotificationHelper.cancelApproachProgress(context)
            store.clearLiveUpdateAnchorDistanceMeters()
            return
        }
        val startDistanceMeters = store.getLiveUpdateStartDistanceMeters().coerceIn(200, 5000).toFloat()
        if (distanceMeters > startDistanceMeters || distanceMeters <= ARRIVAL_THRESHOLD_METERS) {
            NotificationHelper.cancelApproachProgress(context)
            store.clearLiveUpdateAnchorDistanceMeters()
            return
        }
        val anchorDistance = store.getLiveUpdateAnchorDistanceMeters()
            ?.takeIf { it > ARRIVAL_THRESHOLD_METERS } ?: distanceMeters.also {
            store.setLiveUpdateAnchorDistanceMeters(it)
        }
        val span = (anchorDistance - ARRIVAL_THRESHOLD_METERS).coerceAtLeast(1f)
        val progress = (((anchorDistance - distanceMeters) / span) * 100f).toInt().coerceIn(0, 100)
        NotificationHelper.showApproachProgress(context, distanceMeters, progress)
    }
}

