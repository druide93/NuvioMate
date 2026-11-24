package com.nuvio.tv.launcher.atv

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * The "See details" button for the Scout wallpaper: a wallpaper cannot be pressed, so this widget,
 * put in an ATV Launcher row, names the title on screen now and opens its page in the app. Its
 * shape, background, size and content are set per widget ([ScoutWidgetSettingsActivity], opened
 * by the launcher when it is added and from its "Widget settings"). Runs in the plugin's
 * `:atvwall` process; the plugin refreshes it each time the launcher shows a picture.
 */
class ScoutWallpaperWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { refresh(context, manager, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        WidgetStyles.remove(context, ids)
    }

    companion object {
        private const val TAG = "AtvWall"

        /** Redraws every placed copy of the widget with the title on screen now. Any thread. */
        fun refreshAll(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                manager.getAppWidgetIds(ComponentName(context, ScoutWallpaperWidget::class.java))
                    .forEach { refresh(context, manager, it) }
            }.onFailure { Log.w(TAG, "widget not refreshed", it) }
        }

        /** Redraws one placed widget in its own style. */
        fun refresh(context: Context, manager: AppWidgetManager, widgetId: Int) {
            runCatching {
                val views = ScoutWidgetViews.forHost(
                    context, WidgetStyles.get(context, widgetId), AtvWall.currentEntry(context), ScoutWidgetViews.openIntent(context),
                )
                manager.updateAppWidget(widgetId, views)
            }.onFailure { Log.w(TAG, "widget $widgetId not updated", it) }
        }
    }
}

/**
 * Opens the page of the title on the Scout wallpaper now, for the widget. It reads the title when
 * pressed, so it is always the picture on screen, however late the widget was redrawn.
 */
class ScoutWallpaperOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = AtvWall.currentEntry(this)?.link?.takeIf { it.isNotBlank() }
        val intent = link?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(packageName) }
            ?: packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }.onFailure { Log.w("AtvWall", "could not open $link", it) }
        }
        finish()
    }
}
