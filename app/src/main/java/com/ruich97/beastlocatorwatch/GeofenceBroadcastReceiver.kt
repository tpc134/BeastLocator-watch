package com.ruich97.beastlocatorwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GeofenceHelper.ACTION_GEOFENCE) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val store = DestinationStore(context)
        val transition = event.geofenceTransition
        if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            if (store.isArrivalRearmRequired()) {
                store.setArrivalRearmRequired(false)
            }
            return
        }
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        if (store.isArrivalRearmRequired()) return

        val destination = store.getDestination()
        if (store.isDestinationAnswered()) return

        if (store.isArrivalSoundEnabled()) {
            SoundEffectPlayer.play(context, R.raw.arrival_0km)
        }
        store.setDestinationAnswered(true)
        store.setArrivalDestinationName("${destination.lat}, ${destination.lng}")
        NotificationHelper.cancelApproachProgress(context)
        val pending = goAsync()
        Thread {
            try {
                val destinationText = ReverseGeocoder.resolve(context, destination)
                if (!store.isDestinationAnswered() || store.getDestination() != destination) {
                    return@Thread
                }
                store.setArrivalDestinationName(destinationText)
                NotificationHelper.showDestinationReached(
                    context,
                    context.getString(R.string.notification_body, destinationText)
                )
                DestinationWidgetProvider.refreshAllWidgets(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

