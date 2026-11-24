package com.nuvio.tv.launcher.atv

import android.os.FileObserver
import android.util.Log
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperBatch
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperContract
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperEnvironment
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperFileProvider
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperItem
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperProviderService
import java.util.concurrent.Executors

/**
 * Scout suggestions as ATV Launcher wallpapers (its wallpaper plugin API, github.com/dstio/wallpaper-api).
 * Runs in the app's small `:atvwall` process: it only hands the launcher the pictures the app made
 * ([ScoutWallpaperMaker]), tells the "See details" widget which one is on screen, and asks the app
 * for a set when there is none or it is old. Nothing is read or written on the main thread.
 */
class ScoutWallpaperService : WallpaperProviderService() {

    private val io = Executors.newSingleThreadExecutor { Thread(it, "atvwall") }

    @Volatile private var observer: FileObserver? = null

    override fun onAttach(env: WallpaperEnvironment) {
        Log.i(TAG, "attached: ${env.widthPx}x${env.heightPx}, api ${env.apiVersion}")
        io.execute { runCatching { AtvWall.markInUse(this, env.widthPx, env.heightPx) } }
        watchSet()
    }

    override fun onRequest(reason: Int) {
        io.execute { answer(reason) }
    }

    override fun onShown(itemId: String) {
        Log.i(TAG, "shown $itemId")
        io.execute {
            runCatching { AtvWall.writeShown(this, itemId) }
            ScoutWallpaperWidget.refreshAll(this)
        }
    }

    override fun onFailed(itemId: String, why: Int) {
        Log.w(TAG, "the launcher could not use $itemId: $why")
    }

    override fun onDetach() {
        observer?.stopWatching()
        observer = null
    }

    override fun onDestroy() {
        observer?.stopWatching()
        observer = null
        io.shutdownNow()
        super.onDestroy()
    }

    /**
     * On the io thread. The launcher is handed one picture at a time, so the plugin always knows
     * which one is on screen: ATV Launcher changes pictures on its return to Home before it binds
     * the plugin again, and never says so, which left the "See details" widget on another title.
     * The plugin moves to the next title itself once the time set in its settings has passed
     * (asked again then), or when the user asks for the next picture; otherwise it hands back the
     * same one, which the launcher keeps on screen.
     */
    private fun answer(reason: Int) {
        val set = AtvWall.readSet(this)
        if (set == null || set.entries.isEmpty()) {
            Log.i(TAG, "request $reason: nothing yet")
            push(WallpaperBatch(emptyList(), NOTHING_YET_RETRY_MS))
            ScoutWallpaperJob.ask(this)
            return
        }
        val entries = set.entries
        val now = System.currentTimeMillis()
        val every = AtvWall.changeEveryMinutes(this) * 60_000L
        var state = AtvWall.onScreen(this)
        // Back on Home after a minute or more away, the launcher has already put the picture it
        // was handed on screen, before binding the plugin, and does not say so.
        if (reason == WallpaperContract.REASON_START && state.pending != null && state.pending != state.id &&
            now - state.pushedAt >= WallpaperContract.MIN_REQUEST_INTERVAL_MS
        ) {
            runCatching { AtvWall.writeShown(this, state.pending!!, now) }
            state = AtvWall.onScreen(this)
        }
        val handed = state.pending ?: state.id
        val at = entries.indexOfFirst { it.id == handed }
        val due = now - state.pushedAt >= every - EARLY_SLACK_MS
        val next = when {
            at < 0 -> entries.first()
            reason == WallpaperContract.REASON_MANUAL || due -> entries[(at + 1) % entries.size]
            else -> entries[at]
        }
        runCatching { AtvWall.writePending(this, next.id, now) }
        val pushedAt = AtvWall.onScreen(this).pushedAt
        val untilNext = (pushedAt + every - now).coerceAtLeast(WallpaperContract.MIN_REQUEST_INTERVAL_MS)
        Log.i(TAG, "request $reason: ${next.title} (${entries.indexOf(next) + 1}/${entries.size}), next in ${untilNext / 1000} s")
        push(
            WallpaperBatch(
                items = listOf(
                    WallpaperItem(
                        id = next.id,
                        uri = "content://$packageName.atvwall/${next.file}",
                        type = WallpaperContract.TYPE_IMAGE,
                        mode = WallpaperContract.MODE_FILL,
                        title = next.title,
                        author = AUTHOR,
                    )
                ),
                nextRequestMs = untilNext,
            )
        )
        ScoutWallpaperWidget.refreshAll(this)
        if (now - set.createdAt > OLD_SET_MS) ScoutWallpaperJob.ask(this)
    }

    /** A set the app writes while the launcher is bound reaches the screen without waiting for the timer. */
    @Suppress("DEPRECATION") // FileObserver(File, Int) is API 29; the app starts at 24.
    private fun watchSet() {
        if (observer != null) return
        val dir = AtvWall.dir(this).apply { mkdirs() }
        observer = object : FileObserver(dir.path, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == AtvWall.SET_FILE) invalidate()
            }
        }.also { it.startWatching() }
    }

    private companion object {
        const val TAG = "AtvWall"
        const val AUTHOR = "NuvioMate · Scout"
        const val NOTHING_YET_RETRY_MS = 5 * 60_000L
        /** A request a little before the change is due still moves on (the launcher's timer is not exact). */
        const val EARLY_SLACK_MS = 5_000L
        const val OLD_SET_MS = 26 * 60 * 60_000L
    }
}

/** Serves `files/atvwall_pictures`, read-only, to a launcher holding the wallpaper permission. */
class ScoutWallpaperFiles : WallpaperFileProvider() {
    override val rootDir: String = AtvWall.PICTURES
}
