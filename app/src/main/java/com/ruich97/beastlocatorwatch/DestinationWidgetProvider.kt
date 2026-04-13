package com.ruich97.beastlocatorwatch

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class DestinationWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetRenderer.render(context, appWidgetManager, appWidgetIds, R.layout.widget_small)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetRenderer.ACTION_REFRESH_WIDGETS) {
            refreshAllWidgets(context)
        }
    }

    companion object {
        fun refreshAllWidgets(context: Context) {
            WidgetRenderer.refreshAllWidgets(context)
        }
    }
}

