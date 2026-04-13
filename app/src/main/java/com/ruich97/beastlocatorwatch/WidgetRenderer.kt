package com.ruich97.beastlocatorwatch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

object WidgetRenderer {
    const val ACTION_REFRESH_WIDGETS = "com.ruich97.beastlocatorwatch.ACTION_REFRESH_WIDGETS"
    private const val ARROW_IMAGE_FORWARD_OFFSET_DEGREES = 45f

    fun render(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        layoutRes: Int
    ) {
        val store = DestinationStore(context)
        val current = store.getLastKnownLocation()
        val target = store.getDestination()
        val heading = store.getLastKnownHeading()
        val widgetBearingMode = store.getWidgetBearingMode()
        val isArrived = store.isDestinationAnswered()
        val isSmallLayout = layoutRes == R.layout.widget_small
        val arrivalText = context.getString(
            if (isSmallLayout) R.string.widget_arrival_short else R.string.arrival_title
        )
        val normalDistanceText = if (current == null) {
            context.getString(R.string.widget_distance_placeholder)
        } else if (!isValidDestination(current) || !isValidDestination(target)) {
            context.getString(R.string.widget_distance_placeholder)
        } else {
            val d = runCatching { GeoUtils.distanceMeters(current, target) }.getOrDefault(0f)
            formatWidgetDistance(d)
        }
        val absoluteBearing = if (current == null) {
            0f
        } else if (!isValidDestination(current) || !isValidDestination(target)) {
            0f
        } else {
            runCatching { GeoUtils.bearingDegrees(current, target) }.getOrDefault(0f)
        }
        val displayBearing = if (current == null) {
            0f
        } else {
            if (widgetBearingMode == WidgetBearingMode.RELATIVE && heading != null) {
                normalizeTo360(absoluteBearing - heading)
            } else {
                absoluteBearing
            }
        }
        val titleText = if (current == null) {
            context.getString(R.string.widget_waiting_location)
        } else {
            context.getString(
                R.string.direction_label,
                GeoUtils.cardinalFromBearing(absoluteBearing)
            )
        }
        val arrowRotation = normalizeTo360(displayBearing - ARROW_IMAGE_FORWARD_OFFSET_DEGREES)

        appWidgetIds.forEach { id ->
            val isSmall = isSmallLayout
            val views = RemoteViews(context.packageName, layoutRes)
            if (isSmall) {
                views.setViewVisibility(R.id.widgetTitle, android.view.View.GONE)
            } else {
                views.setViewVisibility(R.id.widgetTitle, android.view.View.VISIBLE)
                views.setTextViewText(R.id.widgetTitle, titleText)
            }
            if (isSmall) {
                views.setTextViewText(
                    R.id.widgetDistance,
                    if (isArrived) arrivalText else normalDistanceText
                )
            } else if (isArrived) {
                views.setViewVisibility(R.id.widgetDistance, android.view.View.GONE)
                views.setViewVisibility(R.id.widgetArrivalDistance, android.view.View.VISIBLE)
                views.setTextViewText(R.id.widgetArrivalDistance, arrivalText)
            } else {
                views.setViewVisibility(R.id.widgetDistance, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.widgetArrivalDistance, android.view.View.GONE)
                views.setTextViewText(R.id.widgetDistance, normalDistanceText)
            }
            if (isArrived) {
                views.setViewVisibility(R.id.widgetArrow, android.view.View.GONE)
                views.setViewVisibility(R.id.widgetParty, android.view.View.VISIBLE)
                views.setTextViewText(R.id.widgetParty, context.getString(R.string.widget_arrival_celebration))
            } else {
                views.setViewVisibility(R.id.widgetArrow, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.widgetParty, android.view.View.GONE)
                views.setFloat(R.id.widgetArrow, "setRotation", arrowRotation)
            }
            if (!isSmall) {
                views.setTextColor(
                    R.id.widgetTitle,
                    ContextCompat.getColor(context, R.color.expressive_on_surface_variant)
                )
            }
            views.setTextColor(
                R.id.widgetDistance,
                ContextCompat.getColor(context, R.color.expressive_on_surface)
            )
            if (!isSmall) {
                views.setTextColor(
                    R.id.widgetArrivalDistance,
                    ContextCompat.getColor(context, R.color.expressive_on_surface)
                )
            }
            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                PendingIntent.getActivity(
                    context,
                    id,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    fun refreshAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val smallIds = manager.getAppWidgetIds(
            ComponentName(context, DestinationWidgetProvider::class.java)
        )
        val largeIds = manager.getAppWidgetIds(
            ComponentName(context, DestinationWidgetProviderLarge::class.java)
        )
        render(context, manager, smallIds, R.layout.widget_small)
        render(context, manager, largeIds, R.layout.widget_large)
    }

    private fun formatWidgetDistance(distanceMeters: Float): String {
        return if (distanceMeters >= 1000f) {
            val km = distanceMeters / 1000f
            if (km >= 100f) String.format("%.0f km", km) else String.format("%.1f km", km)
        } else {
            "${distanceMeters.toInt()} m"
        }
    }

    private fun normalizeTo360(value: Float): Float {
        if (!value.isFinite()) return 0f
        val mod = value % 360f
        return if (mod < 0f) mod + 360f else mod
    }

    private fun isValidDestination(destination: Destination): Boolean {
        if (!destination.lat.isFinite() || !destination.lng.isFinite()) return false
        if (destination.lat !in -90.0..90.0) return false
        if (destination.lng !in -180.0..180.0) return false
        return true
    }
}

