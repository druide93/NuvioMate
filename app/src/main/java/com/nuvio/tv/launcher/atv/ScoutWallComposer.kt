/*
 * Adapted from Marquee (https://github.com/dstio/marquee-wallpaper-plugin), its Cinematic layout:
 * compose/Composer.kt, CinematicLayout.kt, InfoColumn.kt, Blocks.kt, Shade.kt, LogoTone.kt and
 * Fonts.kt. Copyright 2026 DStudio, Apache License 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Changes: one layout only (text at the top left), DM Sans instead of Jost and Fraunces, bitmaps
 * handed in instead of files, and the footer names why Scout offers the title.
 */
package com.nuvio.tv.launcher.atv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.nuvio.tv.R
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** What one wallpaper says, its pictures already decoded. */
internal class WallScene(
    val name: String,
    val backdrop: Bitmap,
    val logo: Bitmap?,
    /** Year, genres, length: shown in capitals. */
    val facts: List<String>,
    /** TMDB's average as "74%", or null. */
    val rating: String?,
    val overview: String,
    /** The footer: "SCOUT" and why it is offered. */
    val footerLabel: String,
    val footerValue: String?,
)

/**
 * Draws a Scout wallpaper with Canvas alone: the backdrop, a shade from the left and one from the
 * bottom, and a column of logo, facts, rating, synopsis and why Scout offers it. Laid out in a
 * 1920 x 1080 design space and scaled to the output. The column ends above y 640, clear of the
 * launcher's rows in the lower half.
 */
internal class ScoutWallComposer(context: Context, private val locale: Locale) {

    init {
        WallFonts.attach(context)
    }

    fun compose(scene: WallScene, width: Int, height: Int): Bitmap {
        val s = width / 1920f
        val w = 1920f
        val h = height / s
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(BACKGROUND)
        c.scale(s, s)
        drawCover(c, scene.backdrop, RectF(0f, 0f, w, h))
        val logo = scene.logo?.let(::legible)
        try {
            gradient(c, w, h, Edge.LEFT, listOf(0f to 0.82f, 0.32f to 0.58f, 0.60f to 0f))
            gradient(c, w, h, Edge.BOTTOM, listOf(0f to 0.72f, 0.44f to 0f))
            column(scene, logo).draw(c, SIDE, TOP)
        } finally {
            if (logo != null && logo !== scene.logo) logo.recycle()
        }
        return out
    }

    private fun column(scene: WallScene, logo: Bitmap?): Column {
        val room = BOTTOM_LIMIT - TOP
        // The synopsis matters more than the footer: the footer goes first, then synopsis lines.
        for (lines in SYNOPSIS_LINES downTo 0) {
            for (footer in listOf(true, false)) {
                val col = build(scene, logo, lines, footer)
                if (col.height <= room) return col
            }
        }
        return build(scene, logo, 0, footer = false)
    }

    private fun build(scene: WallScene, logo: Bitmap?, lines: Int, footer: Boolean): Column {
        val col = Column(WIDTH)
        col.add(0f, if (logo != null) ImageBlock(logo, 840f, LOGO_HEIGHT) else TitleBlock(scene.name, locale, WIDTH, 104f, 72f))
        if (scene.facts.isNotEmpty()) {
            val paint = textPaint(WallFonts.sans(500), 23f, alpha = 0.82f, spacingEm = 0.16f)
            col.add(22f, RowBlock(scene.facts.map { it.uppercase(locale) }, paint, 30f, WIDTH, 23f))
        }
        scene.rating?.let { col.add(24f, RatingsBlock(it, "TMDB")) }
        if (lines > 0 && scene.overview.isNotBlank()) {
            val paint = textPaint(WallFonts.sans(300), 30f, alpha = 0.88f)
            ParagraphBlock(scene.overview, paint, WIDTH, lines, 30f * 1.48f).takeUnless { it.isEmpty }?.let { col.add(24f, it) }
        }
        if (footer && !scene.footerValue.isNullOrBlank()) {
            col.add(24f, FooterBlock(scene.footerLabel.uppercase(locale), scene.footerValue, WIDTH))
        }
        return col
    }

    companion object {
        /** Bumped whenever the pixels change for the same inputs; part of every picture's id. */
        const val STYLE_VERSION = 1

        private val BACKGROUND = Color.parseColor("#0C0D0F")
        private const val SIDE = 112f
        private const val TOP = 140f
        private const val WIDTH = 840f
        private const val BOTTOM_LIMIT = 640f
        private const val LOGO_HEIGHT = 150f
        private const val SYNOPSIS_LINES = 3

        /** A logo is decoded to fit this box (design pixels, at 1080p). */
        const val LOGO_BOX_W = 840
        const val LOGO_BOX_H = 200
    }
}

// ---- Shades ---------------------------------------------------------------------------------

private enum class Edge { LEFT, BOTTOM }

private fun gradient(c: Canvas, w: Float, h: Float, edge: Edge, stops: List<Pair<Float, Float>>) {
    val (x0, y0, x1, y1) = when (edge) {
        Edge.LEFT -> listOf(0f, 0f, w, 0f)
        Edge.BOTTOM -> listOf(0f, h, 0f, 0f)
    }
    val colors = stops.map { (_, a) -> Color.argb((a * 255).toInt().coerceIn(0, 255), 0, 0, 0) }.toIntArray()
    val paint = Paint(Paint.DITHER_FLAG)
    paint.shader = LinearGradient(x0, y0, x1, y1, colors, stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w, h, paint)
}

/** [bitmap] centre-cropped to fill [dst]. */
private fun drawCover(c: Canvas, bitmap: Bitmap, dst: RectF) {
    val scale = max(dst.width() / bitmap.width, dst.height() / bitmap.height)
    val sw = dst.width() / scale
    val sh = dst.height() / scale
    val sx = (bitmap.width - sw) / 2f
    val sy = (bitmap.height - sh) / 2f
    c.drawBitmap(bitmap, Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), dst, Paint(Paint.FILTER_BITMAP_FLAG))
}

// ---- Logo -----------------------------------------------------------------------------------

/** Below this average luminance (0 black to 1 white) a logo is drawn as a white silhouette. */
private const val DARK_BELOW = 0.35

internal fun logoIsDark(pixels: IntArray): Boolean {
    var sum = 0.0
    var count = 0
    for (p in pixels) {
        val a = (p ushr 24) and 0xFF
        if (a * 2 <= 0xFF) continue
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        count++
    }
    return count > 0 && sum / count < DARK_BELOW
}

/** [logo] as it is, or a white silhouette of it when it is too dark to read on the shade. */
private fun legible(logo: Bitmap): Bitmap {
    val pixels = IntArray(logo.width * logo.height)
    logo.getPixels(pixels, 0, logo.width, 0, 0, logo.width, logo.height)
    if (!logoIsDark(pixels)) return logo
    val white = Bitmap.createBitmap(logo.width, logo.height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }
    Canvas(white).drawBitmap(logo, 0f, 0f, paint)
    return white
}

// ---- Fonts ----------------------------------------------------------------------------------

/**
 * The app's DM Sans (a variable font), copied once out of the resources so a weight can be
 * picked with font variation settings (Android 8.0 and up); before that the system's sans.
 */
internal object WallFonts {
    private val faces = ConcurrentHashMap<Int, Typeface>()
    @Volatile private var file: File? = null

    fun attach(context: Context) {
        if (file != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        file = runCatching {
            val target = File(context.cacheDir, "atvwall_fonts/dm_sans_variable.ttf")
            if (!target.isFile || target.length() == 0L) {
                target.parentFile?.mkdirs()
                context.resources.openRawResource(R.font.dm_sans_variable).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
            target
        }.getOrNull()
    }

    fun sans(weight: Int): Typeface = faces.getOrPut(weight) {
        val f = file
        val built = if (f != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { Typeface.Builder(f).setFontVariationSettings("'wght' $weight").setWeight(weight).build() }.getOrNull()
        } else null
        built ?: when {
            weight <= 300 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            weight >= 600 -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            weight >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.SANS_SERIF
        }
    }
}

// ---- Blocks: sizes are in the 1920 x 1080 design space ---------------------------------------

private fun textPaint(face: Typeface, size: Float, alpha: Float = 1f, spacingEm: Float = 0f, color: Int = Color.WHITE): TextPaint =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = face
        textSize = size
        letterSpacing = spacingEm
        this.color = color
        this.alpha = (alpha * 255).toInt().coerceIn(0, 255)
    }

/** The baseline of a line whose box starts at [top] and is [lineHeight] tall. */
private fun baseline(paint: Paint, top: Float, lineHeight: Float): Float {
    val fm = paint.fontMetrics
    return top + (lineHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent
}

private fun normalLineHeight(paint: Paint): Float = paint.fontMetrics.let { it.descent - it.ascent + it.leading }

/** [text] in at most [maxLines] lines of [width], the last one cut at a word with an ellipsis. */
private fun breakLines(text: String, paint: TextPaint, width: Float, maxLines: Int): List<String> {
    if (text.isEmpty() || maxLines <= 0) return emptyList()
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
    return (0 until minOf(layout.lineCount, maxLines)).map { i ->
        val start = layout.getLineStart(i)
        if (layout.getEllipsisCount(i) > 0) {
            val end = (start + layout.getEllipsisStart(i)).coerceAtMost(text.length)
            var shown = text.substring(start, end)
            val cutMidWord = end < text.length && !text[end].isWhitespace()
            val space = shown.lastIndexOf(' ')
            if (cutMidWord && space > shown.length / 2) shown = shown.substring(0, space)
            shown.trimEnd().trimEnd(',', ';', ':', '-') + "…"
        } else {
            text.substring(start, layout.getLineEnd(i).coerceAtMost(text.length)).trim()
        }
    }
}

private fun fitLine(text: String, paint: TextPaint, width: Float): String =
    TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

private interface Block {
    val height: Float
    fun draw(c: Canvas, left: Float, top: Float)
}

/** A column of blocks, each with its margin above. */
private class Column(private val width: Float) {
    private val items = ArrayList<Pair<Float, Block>>()

    fun add(marginTop: Float, block: Block) {
        items += (if (items.isEmpty()) 0f else marginTop) to block
    }

    val height: Float get() = items.sumOf { (m, b) -> (m + b.height).toDouble() }.toFloat()

    fun draw(c: Canvas, left: Float, top: Float) {
        var y = top
        for ((m, b) in items) {
            y += m
            b.draw(c, left, y)
            y += b.height
        }
    }
}

/** The title's logo, fitted inside [boxW] x [boxH], top-aligned. */
private class ImageBlock(private val bitmap: Bitmap, boxW: Float, boxH: Float) : Block {
    private val scale = minOf(boxW / bitmap.width, boxH / bitmap.height)
    private val w = bitmap.width * scale
    override val height = bitmap.height * scale

    override fun draw(c: Canvas, left: Float, top: Float) {
        c.drawBitmap(bitmap, null, RectF(left, top, left + w, top + height), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }
}

/** The title set in type when there is no logo: capitals, two lines at most, shrunk until it fits. */
private class TitleBlock(name: String, locale: Locale, width: Float, maxSize: Float, minSize: Float) : Block {
    private val lines: List<String>
    private val paint: TextPaint
    private val lineHeight: Float
    override val height: Float

    init {
        val text = name.trim().uppercase(locale)
        var size = maxSize
        var chosen: List<String>? = null
        var p = mainPaint(size)
        while (size >= minSize) {
            p = mainPaint(size)
            if (text.split(Regex("\\s+")).all { p.measureText(it) <= width }) {
                val broken = breakLines(text, p, width, 3)
                if (broken.size <= 2) {
                    chosen = broken
                    break
                }
            }
            size -= 4f
        }
        if (chosen == null) {
            size = minSize
            p = mainPaint(size)
            chosen = breakLines(text, p, width, 2)
        }
        paint = p
        lines = chosen
        lineHeight = size * 0.98f
        height = lines.size * lineHeight
    }

    private fun mainPaint(size: Float) = textPaint(WallFonts.sans(700), size, spacingEm = 0.02f, color = Color.parseColor("#F2EEE6"))

    override fun draw(c: Canvas, left: Float, top: Float) {
        lines.forEachIndexed { i, line -> c.drawText(line, left, baseline(paint, top + i * lineHeight, lineHeight), paint) }
    }
}

/** A paragraph of at most [maxLines] lines, ellipsized. */
private class ParagraphBlock(text: String, private val paint: TextPaint, width: Float, maxLines: Int, private val lineHeight: Float) : Block {
    private val lines = breakLines(text, paint, width, maxLines)
    override val height = lines.size * lineHeight
    val isEmpty: Boolean get() = lines.isEmpty()

    override fun draw(c: Canvas, left: Float, top: Float) {
        lines.forEachIndexed { i, line -> c.drawText(line, left, baseline(paint, top + i * lineHeight, lineHeight), paint) }
    }
}

/** Items in a row [gap] apart, wrapping onto more rows when they do not fit. */
private class RowBlock(items: List<String>, private val paint: TextPaint, private val gap: Float, width: Float, private val lineHeight: Float) : Block {
    private val rows: List<List<Pair<String, Float>>>
    override val height: Float

    init {
        val out = ArrayList<MutableList<Pair<String, Float>>>()
        var used = 0f
        for (raw in items) {
            val text = fitLine(raw, paint, width)
            val w = paint.measureText(text)
            val row = out.lastOrNull()
            if (row == null || used + gap + w > width) {
                out += mutableListOf(text to w)
                used = w
            } else {
                row += text to w
                used += gap + w
            }
        }
        rows = out
        height = rows.size * lineHeight
    }

    override fun draw(c: Canvas, left: Float, top: Float) {
        rows.forEachIndexed { r, row ->
            var x = left
            val y = baseline(paint, top + r * lineHeight, lineHeight)
            for ((text, w) in row) {
                c.drawText(text, x, y, paint)
                x += w + gap
            }
        }
    }
}

/** The rating as a figure: value and label on one baseline, 10 apart. */
private class RatingsBlock(private val value: String, label: String) : Block {
    private val valuePaint = textPaint(WallFonts.sans(500), 36f)
    private val labelPaint = textPaint(WallFonts.sans(400), 19f, alpha = 0.62f, spacingEm = 0.12f)
    private val label = label.uppercase(Locale.ROOT)
    override val height = 36f

    override fun draw(c: Canvas, left: Float, top: Float) {
        val y = baseline(valuePaint, top, height)
        c.drawText(value, left, y, valuePaint)
        c.drawText(label, left + valuePaint.measureText(value) + 10f, y, labelPaint)
    }
}

/** "SCOUT" and why the title is offered, on one baseline, 18 apart. */
private class FooterBlock(private val labelText: String, valueText: String, width: Float) : Block {
    private val label = textPaint(WallFonts.sans(500), 19f, alpha = 0.58f, spacingEm = 0.16f)
    private val value = textPaint(WallFonts.sans(500), 25f, alpha = 0.9f)
    private val valueText = fitLine(valueText, value, width - label.measureText(labelText) - 18f)
    override val height = normalLineHeight(value)

    override fun draw(c: Canvas, left: Float, top: Float) {
        val y = baseline(value, top, height)
        c.drawText(labelText, left, y, label)
        c.drawText(valueText, left + label.measureText(labelText) + 18f, y, value)
    }
}
