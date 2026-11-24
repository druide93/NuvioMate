package com.nuvio.tv.launcher.atv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/** One wallpaper of the set ATV Launcher is handed, in the order it shows them. */
data class WallEntry(
    /** Names the picture's content; a change of picture comes with a new id. */
    val id: String,
    /** The picture's file name in [AtvWall.PICTURES]. */
    val file: String,
    val title: String,
    /** Opens the title's page in the app (`nuvio://detail/<type>/tmdb:<id>`). */
    val link: String,
    /** Why Scout offers it, as the picture says ("Because you watched…"). */
    val reason: String?,
)

data class WallSet(
    val createdAt: Long,
    /** The Scout pool the set was made from ([com.nuvio.tv.domain.model.ScoutPool.builtAt]). */
    val poolBuiltAt: Long,
    val profileId: Int,
    val style: Int,
    val width: Int,
    val height: Int,
    val language: String,
    val entries: List<WallEntry>,
)

/**
 * The files the ATV Launcher wallpaper plugin shares between the app, which makes the pictures,
 * and the plugin's own small process, which hands them to the launcher:
 * - `files/atvwall_pictures/<id>.jpg`: the pictures, served read-only by [ScoutWallpaperFiles];
 * - `files/atvwall/set.json`: the set, written last, so the launcher never meets a missing file;
 * - `files/atvwall/shown.json`: the picture on screen now, for the "See details" widget;
 * - `files/atvwall/in_use.json`: written when the launcher first uses the plugin (and the screen
 *   size it gave); until then the app makes nothing.
 * Plain files and org.json only: the plugin process starts none of the app.
 */
object AtvWall {

    const val PROCESS_SUFFIX = ":atvwall"
    const val PICTURES = "atvwall_pictures"
    const val SET_FILE = "set.json"
    private const val DIR = "atvwall"
    private const val SHOWN_FILE = "shown.json"
    private const val IN_USE_FILE = "in_use.json"
    private const val ASKED_FILE = "asked.json"

    fun isWallProcess(context: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return name?.endsWith(PROCESS_SUFFIX) == true
    }

    /**
     * [context] in the language picked in the app's settings (the app sets it per activity, so the
     * application's own resources keep the system's), for the words the pictures and widget print.
     */
    fun inAppLanguage(context: Context): Context {
        val tag = runCatching {
            context.getSharedPreferences("app_locale", Context.MODE_PRIVATE).getString("locale_tag", null)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return context
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config)
    }

    fun dir(context: Context) = File(context.filesDir, DIR)
    fun picturesDir(context: Context) = File(context.filesDir, PICTURES)
    private fun file(context: Context, name: String) = File(dir(context), name)

    // ---- In use ------------------------------------------------------------------------------

    /** The launcher bound the plugin: remember it, and the screen it makes pictures for. */
    fun markInUse(context: Context, width: Int, height: Int) {
        val o = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("w", width.takeIf { it > 0 } ?: 1920)
            .put("h", height.takeIf { it > 0 } ?: 1080)
        writeAtomically(file(context, IN_USE_FILE), o.toString())
    }

    fun inUse(context: Context): Boolean = file(context, IN_USE_FILE).isFile

    /** The screen the launcher said it fills, as a 16:9 picture no wider than 1920 (4K is drawn at 1080p). */
    fun screenSize(context: Context): Pair<Int, Int> {
        val o = readJson(file(context, IN_USE_FILE))
        val w = o?.optInt("w", 1920)?.takeIf { it > 0 } ?: 1920
        val h = o?.optInt("h", 1080)?.takeIf { it > 0 } ?: 1080
        if (w <= 1920) return w to h
        return 1920 to (h * 1920f / w).toInt()
    }

    /** When the plugin last asked the app to make a set, so it asks at most so often. */
    fun lastAskedAt(context: Context): Long = readJson(file(context, ASKED_FILE))?.optLong("at", 0L) ?: 0L

    fun markAsked(context: Context) = writeAtomically(file(context, ASKED_FILE), JSONObject().put("at", System.currentTimeMillis()).toString())

    // ---- The set -----------------------------------------------------------------------------

    fun readSet(context: Context): WallSet? {
        val o = readJson(file(context, SET_FILE)) ?: return null
        return runCatching {
            val arr = o.getJSONArray("entries")
            WallSet(
                createdAt = o.optLong("createdAt"),
                poolBuiltAt = o.optLong("poolBuiltAt"),
                profileId = o.optInt("profileId"),
                style = o.optInt("style"),
                width = o.optInt("width"),
                height = o.optInt("height"),
                language = o.optString("language"),
                entries = (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    WallEntry(
                        id = e.getString("id"),
                        file = e.getString("file"),
                        title = e.optString("title"),
                        link = e.optString("link"),
                        reason = e.optString("reason").ifBlank { null },
                    )
                },
            )
        }.getOrNull()
    }

    fun hasPicture(context: Context, file: String): Boolean = File(picturesDir(context), file).let { it.isFile && it.length() > 0 }

    /** Writes [bitmap] as [file], JPEG quality 90, through a temporary file. */
    @Throws(IOException::class)
    fun writePicture(context: Context, file: String, bitmap: Bitmap) {
        val dir = picturesDir(context).apply { mkdirs() }
        val target = File(dir, file)
        val tmp = File(dir, "$file.tmp")
        try {
            tmp.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw IOException("could not encode $file")
            }
            if (!tmp.renameTo(target)) throw IOException("could not move $file into place")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Makes [set] the current one, then deletes the pictures it no longer names. */
    @Synchronized
    fun commitSet(context: Context, set: WallSet) {
        val arr = JSONArray()
        set.entries.forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("file", e.file).put("title", e.title)
                    .put("link", e.link).put("reason", e.reason ?: "")
            )
        }
        val o = JSONObject()
            .put("createdAt", set.createdAt).put("poolBuiltAt", set.poolBuiltAt).put("profileId", set.profileId)
            .put("style", set.style).put("width", set.width).put("height", set.height).put("language", set.language)
            .put("entries", arr)
        writeAtomically(file(context, SET_FILE), o.toString())
        val keep = set.entries.map { it.file }.toSet()
        picturesDir(context).listFiles()?.filter { it.isFile && it.name !in keep }?.forEach { it.delete() }
    }

    // ---- On screen ---------------------------------------------------------------------------

    fun writeShown(context: Context, itemId: String) =
        writeAtomically(file(context, SHOWN_FILE), JSONObject().put("id", itemId).put("at", System.currentTimeMillis()).toString())

    /** The entry on screen now, or the set's first one when the launcher has not said yet. */
    fun currentEntry(context: Context): WallEntry? {
        val set = readSet(context) ?: return null
        val shown = readJson(file(context, SHOWN_FILE))?.optString("id")
        return set.entries.firstOrNull { it.id == shown } ?: set.entries.firstOrNull()
    }

    // ---- Files -------------------------------------------------------------------------------

    private fun readJson(file: File): JSONObject? = runCatching {
        if (file.isFile) JSONObject(file.readText()) else null
    }.getOrNull()

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("could not move ${file.name} into place")
        }
    }
}
