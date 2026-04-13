package com.ruich97.beastlocatorwatch

import android.content.Context
import android.location.Geocoder
import java.util.Locale

object ReverseGeocoder {
    fun resolve(context: Context, destination: Destination): String {
        return resolveWithAndroidGeocoder(context, destination)
    }

    private fun resolveWithAndroidGeocoder(context: Context, destination: Destination): String {
        return try {
            val geocoder = Geocoder(context, Locale.JAPAN)
            @Suppress("DEPRECATION")
            val address = geocoder.getFromLocation(destination.lat, destination.lng, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
            address ?: fallbackLatLng(destination)
        } catch (_: Exception) {
            fallbackLatLng(destination)
        }
    }

    private fun fallbackLatLng(destination: Destination): String {
        return "${destination.lat}, ${destination.lng}"
    }
}


