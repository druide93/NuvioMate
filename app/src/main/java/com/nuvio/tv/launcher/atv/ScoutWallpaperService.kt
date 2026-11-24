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

    /** On the io thread: push the set there is, and ask the app for one when it is missing or old. */
    private fun answer(reason: Int) {
        val set = AtvWall.readSet(this)
        if (set == null || set.entries.isEmpty()) {
            Log.i(TAG, "request $reason: nothing yet")
            push(WallpaperBatch(emptyList(), NOTHING_YET_RETRY_MS))
            ScoutWallpaperJob.ask(this)
            return
        }
        Log.i(TAG, "request $reason: ${set.entries.size} pictures")
        push(
            WallpaperBatch(
                items = set.entries.take(WallpaperContract.MAX_ITEMS).map { e ->
                    WallpaperItem(
                        id = e.id,
                        uri = "content://$packageName.atvwall/${e.file}",
                        type = WallpaperContract.TYPE_IMAGE,
                        mode = WallpaperContract.MODE_FILL,
                        title = e.title,
                        author = AUTHOR,
                    )
                },
                // A new set says so itself (the set file changes and the launcher is told), so
                // the timer is only a safety net.
                nextRequestMs = ASK_AGAIN_MS,
            )
        )
        ScoutWallpaperWidget.refreshAll(this)
        if (System.currentTimeMillis() - set.createdAt > OLD_SET_MS) ScoutWallpaperJob.ask(this)
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
        const val ASK_AGAIN_MS = 6 * 60 * 60_000L
        const val OLD_SET_MS = 26 * 60 * 60_000L
    }
}

/** Serves `files/atvwall_pictures`, read-only, to a launcher holding the wallpaper permission. */
class ScoutWallpaperFiles : WallpaperFileProvider() {
    override val rootDir: String = AtvWall.PICTURES
}
