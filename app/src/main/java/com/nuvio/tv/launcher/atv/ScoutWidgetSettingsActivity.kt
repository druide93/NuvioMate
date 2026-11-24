package com.nuvio.tv.launcher.atv

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import com.nuvio.tv.R
import java.io.File

/**
 * Settings for the Scout wallpaper and its widget, in the plugin's `:atvwall` process (plain
 * views; it starts none of the app).
 * - Opened by the launcher for a widget (when it is added, and from its "Widget settings"): the
 *   widget's content, line above the title, shape, background and size, previewed in a small slot (a round app tile)
 *   and a large one over the wallpaper on screen; each choice applies to the widget at once.
 * - Opened from the plugin's page in ATV Launcher (no widget): how often the picture changes,
 *   which is also offered with the widget's settings.
 */
class ScoutWidgetSettingsActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val forWidget get() = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
    private var style = WidgetStyle()
    private var smallPreview: FrameLayout? = null
    private var largePreview: FrameLayout? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AtvWall.inAppLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (forWidget) {
            // Leaving with Back keeps the widget, as it is (the launcher adds it either way).
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            style = WidgetStyles.get(this, widgetId)
        }
        setContentView(content())
        updatePreviews()
    }

    private fun content(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(0x0C, 0x0D, 0x0F))
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        // The choices scroll as the focus moves down them; the previews stay put on the right.
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(32), dp(24)) }
        scroll.addView(left, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        left.addView(label(getString(if (forWidget) R.string.atvwall_style_title else R.string.atvwall_wall_title), 26f, bold = true))
        left.addView(label(getString(if (forWidget) R.string.atvwall_style_hint else R.string.atvwall_wall_hint), 14f, alpha = 0.7f).apply {
            setPadding(0, dp(6), 0, dp(10))
        })

        var first: View? = null
        fun choices(title: Int, names: List<String>, chosen: Int, pick: (Int) -> Unit) {
            left.addView(label(getString(title).uppercase(), 13f, alpha = 0.6f, spacing = 0.12f).apply {
                setPadding(0, dp(12), 0, dp(6))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
            val chips = names.mapIndexed { i, name ->
                chip(name).also { c ->
                    c.isSelected = i == chosen
                    row.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(10)
                    })
                }
            }
            chips.forEachIndexed { i, c ->
                c.setOnClickListener {
                    chips.forEach { it.isSelected = it === c }
                    pick(i)
                }
            }
            if (first == null) first = chips.getOrNull(chosen) ?: chips.first()
            // A row too wide for the column scrolls sideways as the focus moves along it.
            left.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                addView(row)
            })
        }

        if (forWidget) {
            fun <E : Enum<E>> styleChoices(title: Int, values: List<E>, name: (E) -> Int, chosen: E, apply: (WidgetStyle, E) -> WidgetStyle) =
                choices(title, values.map { getString(name(it)) }, values.indexOf(chosen)) { i ->
                    style = apply(style, values[i])
                    saveStyle()
                }
            styleChoices(R.string.atvwall_style_content, WidgetContent.entries, ::contentName, style.content) { s, v -> s.copy(content = v) }
            styleChoices(R.string.atvwall_style_kicker, WidgetKicker.entries, ::kickerName, style.kicker) { s, v -> s.copy(kicker = v) }
            styleChoices(R.string.atvwall_style_shape, WidgetShape.entries, ::shapeName, style.shape) { s, v -> s.copy(shape = v) }
            styleChoices(R.string.atvwall_style_fill, WidgetFill.entries, ::fillName, style.fill) { s, v -> s.copy(fill = v) }
            styleChoices(R.string.atvwall_style_scale, WidgetScale.entries, ::scaleName, style.scale) { s, v -> s.copy(scale = v) }
        }
        val minutes = AtvWall.CHANGE_MINUTES
        choices(
            R.string.atvwall_wall_change_every,
            minutes.map { if (it < 60) getString(R.string.atvwall_wall_minutes, it) else getString(R.string.atvwall_wall_hours, it / 60) },
            minutes.indexOf(AtvWall.changeEveryMinutes(this)).coerceAtLeast(0),
        ) { i -> AtvWall.setChangeEveryMinutes(this, minutes[i]) }

        left.addView(chip(getString(R.string.atvwall_style_done)).apply {
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28)
        })

        if (forWidget) {
            // The previews, over the wallpaper on screen now.
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            root.addView(right, LinearLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT))
            right.addView(label(getString(R.string.atvwall_style_preview_small), 13f, alpha = 0.6f).apply { setPadding(0, dp(8), 0, dp(8)) })
            smallPreview = previewFrame(dp(340), dp(150)).also(right::addView)
            right.addView(label(getString(R.string.atvwall_style_preview_large), 13f, alpha = 0.6f).apply { setPadding(0, dp(20), 0, dp(8)) })
            largePreview = previewFrame(dp(340), dp(190)).also(right::addView)
        }

        first?.let { f -> f.post { f.requestFocus() } }
        return root
    }

    /** A frame showing the wallpaper on screen, behind a widget preview. */
    private fun previewFrame(w: Int, h: Int): FrameLayout = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(w, h)
        setBackgroundColor(Color.rgb(0x1B, 0x23, 0x30))
        currentPicture()?.let { bmp ->
            addView(ImageView(this@ScoutWidgetSettingsActivity).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.85f
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun currentPicture() = runCatching {
        val entry = AtvWall.currentEntry(this) ?: return@runCatching null
        val file = File(AtvWall.picturesDir(this), entry.file)
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })
    }.getOrNull()

    private fun saveStyle() {
        WidgetStyles.put(this, widgetId, style)
        updatePreviews()
        ScoutWallpaperWidget.refresh(this, AppWidgetManager.getInstance(this), widgetId)
    }

    /** The widget as the launcher would draw it: a round app tile, and a wide widget slot. */
    private fun updatePreviews() {
        val entry = AtvWall.currentEntry(this)
        smallPreview?.let { show(it, ScoutWidgetViews.build(this, style, entry, small = true, open = null), dp(116), dp(116)) }
        largePreview?.let { show(it, ScoutWidgetViews.build(this, style, entry, small = false, open = null), dp(300), dp(150)) }
    }

    private fun show(frame: FrameLayout, views: RemoteViews, w: Int, h: Int) {
        (frame.getTag(R.id.atvwall_widget_root) as? View)?.let(frame::removeView)
        val holder = FrameLayout(this)
        runCatching { views.apply(this, holder) }.getOrNull()?.let { holder.addView(it, FrameLayout.LayoutParams(w, h)) }
        frame.addView(holder, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        frame.setTag(R.id.atvwall_widget_root, holder)
    }

    private fun chip(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(resources.getColorStateList(R.color.atvwall_chip_text, theme))
        setBackgroundResource(R.drawable.atvwall_chip_bg)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        isSingleLine = true
        isFocusable = true
        isClickable = true
    }

    private fun label(text: String, sp: Float, alpha: Float = 1f, bold: Boolean = false, spacing: Float = 0f) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.rgb(0xF2, 0xEE, 0xE6))
        this.alpha = alpha
        letterSpacing = spacing
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun contentName(v: WidgetContent) = when (v) {
        WidgetContent.AUTO -> R.string.atvwall_style_auto
        WidgetContent.ICON -> R.string.atvwall_style_content_icon
        WidgetContent.ICON_LABEL -> R.string.atvwall_style_content_icon_label
        WidgetContent.TITLE -> R.string.atvwall_style_content_title
        WidgetContent.BUTTON -> R.string.atvwall_style_content_button
    }

    private fun kickerName(v: WidgetKicker) = when (v) {
        WidgetKicker.NONE -> R.string.atvwall_style_kicker_none
        WidgetKicker.WALLPAPER -> R.string.atvwall_style_kicker_wallpaper
        WidgetKicker.SCOUT -> R.string.atvwall_style_kicker_scout
        WidgetKicker.SHOWING -> R.string.atvwall_style_kicker_showing
    }

    private fun shapeName(v: WidgetShape) = when (v) {
        WidgetShape.AUTO -> R.string.atvwall_style_auto
        WidgetShape.ROUND -> R.string.atvwall_style_shape_round
        WidgetShape.ROUNDED -> R.string.atvwall_style_shape_rounded
        WidgetShape.PILL -> R.string.atvwall_style_shape_pill
        WidgetShape.SQUARE -> R.string.atvwall_style_shape_square
    }

    private fun fillName(v: WidgetFill) = when (v) {
        WidgetFill.DARK -> R.string.atvwall_style_fill_dark
        WidgetFill.GLASS -> R.string.atvwall_style_fill_glass
        WidgetFill.LIGHT -> R.string.atvwall_style_fill_light
        WidgetFill.BRAND -> R.string.atvwall_style_fill_brand
        WidgetFill.NONE -> R.string.atvwall_style_fill_none
    }

    private fun scaleName(v: WidgetScale) = when (v) {
        WidgetScale.SMALL -> R.string.atvwall_style_scale_small
        WidgetScale.MEDIUM -> R.string.atvwall_style_scale_medium
        WidgetScale.LARGE -> R.string.atvwall_style_scale_large
    }
}
