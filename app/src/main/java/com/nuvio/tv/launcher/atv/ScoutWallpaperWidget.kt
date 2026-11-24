package com.nuvio.tv.launcher.atv

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import com.nuvio.tv.R

/**
 * The "See details" button for the Scout wallpaper: a wallpaper cannot be pressed, so this widget,
 * put in an ATV Launcher row, names the title on screen now and opens its page in the app. Runs
 * in the plugin's `:atvwall` process; the plugin refreshes it each time the launcher shows a
 * picture ([AtvWall.writeShown]).
 */
class ScoutWallpaperWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runCatching { manager.updateAppWidget(ids, views(context)) }
            .onFailure { Log.w(TAG, "widget not updated", it) }
    }

    companion object {
        private const val TAG = "AtvWall"
        /** Below this room (dp) the full widget does not fit and the small one is shown. */
        private const val FULL_MIN_WIDTH_DP = 180f
        private const val FULL_MIN_HEIGHT_DP = 96f

        /** Redraws every placed copy of the widget with the title on screen now. Any thread. */
        fun refreshAll(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, ScoutWallpaperWidget::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
            }.onFailure { Log.w(TAG, "widget not refreshed", it) }
        }

        /**
         * The full widget (what is on screen, its reason, "See details") where there is room, and
         * an "i" with "Details" in a small slot such as a round app tile. From Android 12 the host
         * picks the one that fits the room it gives; before that it is always the full one.
         */
        private fun views(context: Context): RemoteViews {
            val entry = AtvWall.currentEntry(context)
            val words = AtvWall.inAppLanguage(context)
            val open = openIntent(context)
            val full = RemoteViews(context.packageName, R.layout.atvwall_widget).apply {
                setTextViewText(R.id.atvwall_widget_kicker, words.getString(R.string.atvwall_widget_kicker))
                setTextViewText(R.id.atvwall_widget_action, words.getString(R.string.atvwall_widget_open))
                setTextViewText(R.id.atvwall_widget_title, entry?.title ?: words.getString(R.string.atvwall_widget_empty))
                setTextViewText(R.id.atvwall_widget_reason, entry?.reason.orEmpty())
                setOnClickPendingIntent(R.id.atvwall_widget_root, open)
                setOnClickPendingIntent(R.id.atvwall_widget_action, open)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return full
            val small = RemoteViews(context.packageName, R.layout.atvwall_widget_small).apply {
                setTextViewText(R.id.atvwall_widget_action, words.getString(R.string.atvwall_widget_open_short))
                setOnClickPendingIntent(R.id.atvwall_widget_root, open)
                setContentDescription(R.id.atvwall_widget_root, entry?.title ?: words.getString(R.string.atvwall_widget_open))
            }
            return RemoteViews(mapOf(SizeF(40f, 40f) to small, SizeF(FULL_MIN_WIDTH_DP, FULL_MIN_HEIGHT_DP) to full))
        }

        private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ScoutWallpaperOpenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
