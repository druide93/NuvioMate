package com.nuvio.tv.launcher.atv

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.nuvio.tv.R

/** The widget's shape; [AUTO] is round in a small slot and a rounded rectangle in a large one. */
enum class WidgetShape(val key: String) { AUTO("auto"), ROUND("round"), ROUNDED("rounded"), PILL("pill"), SQUARE("square") }

/** What is behind the widget's content. */
enum class WidgetFill(val key: String, val tint: Int, val alpha: Int) {
    DARK("dark", Color.rgb(0x0C, 0x0D, 0x0F), 230),
    GLASS("glass", Color.BLACK, 115),
    LIGHT("light", Color.rgb(0xF2, 0xEE, 0xE6), 245),
    /** NuvioMate's purple-to-cyan, drawn as it is (no tint). */
    BRAND("brand", 0, 255),
    NONE("none", 0, 0),
}

/** How big the content is drawn in the slot the launcher gives (the slot itself is the launcher's). */
enum class WidgetScale(
    val key: String,
    val iconDp: Float,
    val labelSp: Float,
    val kickerSp: Float,
    val titleSp: Float,
    val reasonSp: Float,
    val actionSp: Float,
    val paddingDp: Float,
) {
    SMALL("small", 22f, 10f, 9f, 14f, 10f, 11f, 8f),
    MEDIUM("medium", 30f, 11f, 11f, 18f, 12f, 14f, 12f),
    LARGE("large", 42f, 14f, 12f, 22f, 14f, 16f, 16f),
}

/**
 * What the widget shows; [AUTO] is the icon and "Details" in a small slot, the title in a large one.
 * [BUTTON] is "See details" alone: the whole tile is the button (the wallpaper already says what
 * is on it), so the launcher's highlight, which goes round the whole tile, goes round the button.
 */
enum class WidgetContent(val key: String) { AUTO("auto"), ICON("icon"), ICON_LABEL("icon_label"), TITLE("title"), BUTTON("button") }

/** The small line above the title, when the title shows. */
enum class WidgetKicker(val key: String) { NONE("none"), WALLPAPER("wallpaper"), SCOUT("scout"), SHOWING("showing") }

data class WidgetStyle(
    val shape: WidgetShape = WidgetShape.AUTO,
    val fill: WidgetFill = WidgetFill.DARK,
    val scale: WidgetScale = WidgetScale.MEDIUM,
    val content: WidgetContent = WidgetContent.AUTO,
    val kicker: WidgetKicker = WidgetKicker.NONE,
) {
    fun shapeIn(small: Boolean) = when {
        shape != WidgetShape.AUTO -> shape
        content == WidgetContent.BUTTON -> WidgetShape.PILL
        small -> WidgetShape.ROUND
        else -> WidgetShape.ROUNDED
    }
    fun contentIn(small: Boolean) = if (content == WidgetContent.AUTO) (if (small) WidgetContent.ICON_LABEL else WidgetContent.TITLE) else content
}

/**
 * Each placed widget's style, by its id. Read and written only in the plugin's `:atvwall` process
 * (the widget, its settings and the plugin all run there), so plain preferences stay consistent.
 */
object WidgetStyles {
    private const val PREFS = "atvwall_widgets"

    fun get(context: Context, widgetId: Int): WidgetStyle {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("w_$widgetId", null)
            ?: return WidgetStyle()
        val p = raw.split('|')
        fun <E : Enum<E>> pick(values: Array<E>, index: Int, key: (E) -> String, otherwise: E) =
            values.firstOrNull { key(it) == p.getOrNull(index) } ?: otherwise
        val d = WidgetStyle()
        return WidgetStyle(
            shape = pick(WidgetShape.entries.toTypedArray(), 0, { it.key }, d.shape),
            fill = pick(WidgetFill.entries.toTypedArray(), 1, { it.key }, d.fill),
            scale = pick(WidgetScale.entries.toTypedArray(), 2, { it.key }, d.scale),
            content = pick(WidgetContent.entries.toTypedArray(), 3, { it.key }, d.content),
            kicker = pick(WidgetKicker.entries.toTypedArray(), 4, { it.key }, d.kicker),
        )
    }

    fun put(context: Context, widgetId: Int, style: WidgetStyle) {
        val raw = listOf(style.shape.key, style.fill.key, style.scale.key, style.content.key, style.kicker.key).joinToString("|")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("w_$widgetId", raw).apply()
    }

    fun remove(context: Context, widgetIds: IntArray) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        widgetIds.forEach { edit.remove("w_$it") }
        edit.apply()
    }
}

/** The widget's views for a style: what the launcher hosts, and what the settings preview. */
internal object ScoutWidgetViews {

    private val LIGHT_INK = Color.rgb(0xF2, 0xEE, 0xE6)
    private val DARK_INK = Color.rgb(0x0C, 0x0D, 0x0F)

    /**
     * For the launcher: from Android 12 both the small and the large look, and the host picks the
     * one that fits the room it gives (it does not tell the app); before that the large one.
     */
    fun forHost(context: Context, style: WidgetStyle, entry: WallEntry?, open: PendingIntent): RemoteViews {
        val large = build(context, style, entry, small = false, open = open)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return large
        val small = build(context, style, entry, small = true, open = open)
        return RemoteViews(mapOf(SizeF(40f, 40f) to small, SizeF(LARGE_MIN_WIDTH_DP, LARGE_MIN_HEIGHT_DP) to large))
    }

    fun build(context: Context, style: WidgetStyle, entry: WallEntry?, small: Boolean, open: PendingIntent?): RemoteViews {
        val words = AtvWall.inAppLanguage(context)
        val shape = style.shapeIn(small)
        val content = style.contentIn(small)
        val scale = style.scale
        val density = context.resources.displayMetrics.density
        // With nothing behind it, the text gets a shadow to read over any wallpaper.
        val layout = if (style.fill == WidgetFill.NONE) R.layout.atvwall_widget_shadow else R.layout.atvwall_widget
        val v = RemoteViews(context.packageName, layout)

        // Behind: a round shape keeps its circle (fit), the others fill the slot.
        val bg = if (shape == WidgetShape.ROUND) R.id.atvwall_widget_bg_fit else R.id.atvwall_widget_bg_fill
        v.setViewVisibility(R.id.atvwall_widget_bg_fit, View.GONE)
        v.setViewVisibility(R.id.atvwall_widget_bg_fill, View.GONE)
        if (style.fill != WidgetFill.NONE) {
            v.setViewVisibility(bg, View.VISIBLE)
            v.setImageViewResource(bg, shapeRes(shape, brand = style.fill == WidgetFill.BRAND))
            v.setInt(bg, "setColorFilter", style.fill.tint)
            v.setInt(bg, "setImageAlpha", style.fill.alpha)
        }
        val ink = if (style.fill == WidgetFill.LIGHT) DARK_INK else LIGHT_INK

        val title = content == WidgetContent.TITLE
        val pad = (scale.paddingDp * density).toInt()
        // A pill's round ends would cut the text: keep it clear of them.
        val side = pad + if (shape == WidgetShape.PILL && title) (scale.paddingDp * 1.5f * density).toInt() else 0
        v.setViewPadding(R.id.atvwall_widget_content, side, pad, side, pad)
        v.setInt(R.id.atvwall_widget_content, "setGravity", if (title) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER)

        // The "i", alone or with "Details"; or "See details" alone, the tile being the button.
        val button = content == WidgetContent.BUTTON
        v.setViewVisibility(R.id.atvwall_widget_icon, if (title || button) View.GONE else View.VISIBLE)
        v.setInt(R.id.atvwall_widget_icon, "setColorFilter", ink)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setViewLayoutWidth(R.id.atvwall_widget_icon, scale.iconDp, TypedValue.COMPLEX_UNIT_DIP)
            v.setViewLayoutHeight(R.id.atvwall_widget_icon, scale.iconDp, TypedValue.COMPLEX_UNIT_DIP)
        }
        if (button) {
            // In a small slot (a round app tile) "See details" goes on two centred lines.
            val size = if (small) scale.labelSp + 2f else scale.actionSp + 2f
            text(v, R.id.atvwall_widget_label, true, words.getString(R.string.atvwall_widget_open), size, ink)
            v.setInt(R.id.atvwall_widget_label, "setMaxLines", if (small) 2 else 1)
            v.setInt(R.id.atvwall_widget_label, "setGravity", Gravity.CENTER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                v.setViewLayoutMargin(R.id.atvwall_widget_label, RemoteViews.MARGIN_TOP, 0f, TypedValue.COMPLEX_UNIT_DIP)
            }
        } else {
            text(v, R.id.atvwall_widget_label, content == WidgetContent.ICON_LABEL, words.getString(R.string.atvwall_widget_open_short), scale.labelSp, ink)
        }

        // The title, why, and "See details".
        val kicker = when (style.kicker) {
            WidgetKicker.NONE -> null
            WidgetKicker.WALLPAPER -> words.getString(R.string.atvwall_widget_kicker)
            WidgetKicker.SCOUT -> "SCOUT"
            WidgetKicker.SHOWING -> words.getString(R.string.atvwall_widget_kicker_showing)
        }
        text(v, R.id.atvwall_widget_kicker, title && kicker != null, kicker.orEmpty(), scale.kickerSp, ink)
        // Without a line above it, the title needs no gap at its top.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setViewLayoutMargin(R.id.atvwall_widget_title, RemoteViews.MARGIN_TOP, if (kicker != null) 4f else 0f, TypedValue.COMPLEX_UNIT_DIP)
        }
        text(v, R.id.atvwall_widget_title, title, entry?.title ?: words.getString(R.string.atvwall_widget_empty), scale.titleSp, ink)
        text(v, R.id.atvwall_widget_reason, title && !entry?.reason.isNullOrBlank(), entry?.reason.orEmpty(), scale.reasonSp, ink)
        text(v, R.id.atvwall_widget_action, title, words.getString(R.string.atvwall_widget_open), scale.actionSp,
            if (style.fill == WidgetFill.LIGHT) LIGHT_INK else DARK_INK)
        v.setInt(R.id.atvwall_widget_action, "setBackgroundResource",
            if (style.fill == WidgetFill.LIGHT) R.drawable.atvwall_pill_dark else R.drawable.atvwall_pill_light)

        v.setContentDescription(R.id.atvwall_widget_root, entry?.title ?: words.getString(R.string.atvwall_widget_open))
        if (open != null) {
            v.setOnClickPendingIntent(R.id.atvwall_widget_root, open)
            v.setOnClickPendingIntent(R.id.atvwall_widget_action, open)
        }
        return v
    }

    private fun text(v: RemoteViews, id: Int, shown: Boolean, value: CharSequence, sp: Float, color: Int) {
        v.setViewVisibility(id, if (shown) View.VISIBLE else View.GONE)
        if (!shown) return
        v.setTextViewText(id, value)
        v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp)
        v.setTextColor(id, color)
    }

    private fun shapeRes(shape: WidgetShape, brand: Boolean): Int = when (shape) {
        WidgetShape.ROUND, WidgetShape.AUTO -> if (brand) R.drawable.atvwall_shape_round_brand else R.drawable.atvwall_shape_round
        WidgetShape.ROUNDED -> if (brand) R.drawable.atvwall_shape_rounded_brand else R.drawable.atvwall_shape_rounded
        WidgetShape.PILL -> if (brand) R.drawable.atvwall_shape_pill_brand else R.drawable.atvwall_shape_pill
        WidgetShape.SQUARE -> if (brand) R.drawable.atvwall_shape_square_brand else R.drawable.atvwall_shape_square
    }

    /** From this room (dp) on, the large look fits. */
    const val LARGE_MIN_WIDTH_DP = 180f
    const val LARGE_MIN_HEIGHT_DP = 96f

    fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, ScoutWallpaperOpenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
