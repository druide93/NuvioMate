package com.nuvio.tv.livetv.ui

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/** A picture taken off the screen, and where it was on screen (window pixels). */
internal class FrozenFrame(val image: ImageBitmap, val bounds: Rect)

/**
 * The Live TV picture on screen, kept as an image for a moment. A skip in a replay that asks the
 * provider again shows it frozen (dimmed, with the channel's logo and "Loading the replay"): once
 * the player lets go of the stream, the video layer is left with its last picture stretched to
 * the whole screen (channels that aren't 16:9), or black on some TVs.
 */
internal object LiveVideoFrame {
    /** Bigger pictures are taken smaller: a 4K one would be 33 MB. */
    private const val MAX_WIDTH = 1280
    private const val COPY_TIMEOUT_MS = 300L

    /** The player views on screen, the one that last took over the picture last. */
    private val views = ArrayList<WeakReference<PlayerView>>()

    /** [view] now shows Live TV's picture. */
    fun register(view: PlayerView) {
        views.removeAll { it.get().let { v -> v == null || v === view } }
        views += WeakReference(view)
    }

    /** [view] is gone from the screen. */
    fun unregister(view: PlayerView) {
        views.removeAll { it.get().let { v -> v == null || v === view } }
    }

    /** The picture on screen now, or null if it can't be had (nothing showing, protected, black). */
    suspend fun capture(): FrozenFrame? = withContext(Dispatchers.Main.immediate) {
        val playerView = views.asReversed().firstNotNullOfOrNull { ref ->
            ref.get()?.takeIf { it.isAttachedToWindow && it.player != null && it.isShown }
        } ?: return@withContext null
        val surface = playerView.videoSurfaceView ?: return@withContext null
        val w = surface.width
        val h = surface.height
        if (w < 16 || h < 16 || !surface.isShown) return@withContext null
        val bounds = boundsInWindow(surface)
        val scale = minOf(1f, MAX_WIDTH / w.toFloat())
        val bitmap = runCatching {
            Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return@withContext null
        val copied = when (surface) {
            is SurfaceView -> copySurface(surface, bitmap)
            is TextureView -> runCatching { surface.getBitmap(bitmap) != null }.getOrDefault(false)
            else -> false
        }
        // A failed copy can also come back all black (protected video, some TV video layers):
        // then the plain black waiting screen is better than a black "picture".
        if (!copied || looksBlack(bitmap)) return@withContext null
        FrozenFrame(bitmap.asImageBitmap(), bounds)
    }

    private suspend fun copySurface(view: SurfaceView, bitmap: Bitmap): Boolean {
        if (view.holder.surface?.isValid != true) return false
        return withTimeoutOrNull(COPY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    PixelCopy.request(view, bitmap, { result ->
                        if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS)
                    }, Handler(Looper.getMainLooper()))
                } catch (e: IllegalArgumentException) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
    }

    /** Nearly every point of the picture is black (or see-through). */
    private fun looksBlack(bitmap: Bitmap): Boolean {
        val steps = 8
        for (i in 0 until steps) for (j in 0 until steps) {
            val x = (bitmap.width * (2 * i + 1)) / (2 * steps)
            val y = (bitmap.height * (2 * j + 1)) / (2 * steps)
            val c = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
            val alpha = c ushr 24
            val brightest = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            if (alpha > 200 && brightest > 24) return false
        }
        return true
    }

    /**
     * Where [view] is drawn in the window, its parents' zoom and stretch included (the picture
     * shapes and sizes move and scale the frame the video sits in).
     */
    private fun boundsInWindow(view: View): Rect {
        val r = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
        var v: View = view
        while (true) {
            if (!v.matrix.isIdentity) v.matrix.mapRect(r)
            r.offset(v.left.toFloat(), v.top.toFloat())
            val parent = v.parent as? View ?: break
            r.offset(-parent.scrollX.toFloat(), -parent.scrollY.toFloat())
            v = parent
        }
        return Rect(r.left, r.top, r.right, r.bottom)
    }
}
