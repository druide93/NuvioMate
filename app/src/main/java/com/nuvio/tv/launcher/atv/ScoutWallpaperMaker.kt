package com.nuvio.tv.launcher.atv

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import com.nuvio.tv.R
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.core.scout.ScoutEngine
import com.nuvio.tv.core.scout.ScoutPickDetail
import com.nuvio.tv.core.scout.ScoutPicksFinder
import com.nuvio.tv.core.scout.ScoutSlices
import com.nuvio.tv.core.scout.scoutCanonicalGenre
import com.nuvio.tv.domain.model.ScoutFeature
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutPool
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Makes the Scout wallpapers ATV Launcher shows ([ScoutWallpaperService]): for the active
 * profile's Scout picks, one 1080p picture each, Marquee-style ([ScoutWallComposer]), from the
 * title's backdrop, logo, facts, TMDB rating, synopsis and why Scout offers it. Nothing is made
 * until the launcher has used the plugin once ([AtvWall.inUse]). A picture whose content is
 * unchanged keeps its file, so a new day's set mostly reuses the last one. Drawn one at a time on
 * a background-priority thread, and never while something plays.
 */
@Singleton
class ScoutWallpaperMaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: dagger.Lazy<ScoutEngine>,
    private val finder: dagger.Lazy<ScoutPicksFinder>,
) {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "atvwall-make")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val lock = Mutex()

    /** At app start: follows the Scout picks, and makes a new set when they change. */
    fun start() {
        if (!ScoutFeature.available) return
        scope.launch {
            delay(START_DELAY_MS)
            engine.get().pool.filterNotNull().collectLatest { pool ->
                delay(SETTLE_MS)
                if (AtvWall.inUse(context)) runCatching { make(pool) }.onFailure { Log.w(TAG, "not made", it) }
            }
        }
    }

    /** For [ScoutWallpaperJob]: the plugin asked, and the app may not have loaded the picks yet. */
    suspend fun makeNow() {
        if (!ScoutFeature.available || !AtvWall.inUse(context)) return
        val engine = engine.get()
        val pool = engine.pool.value ?: run {
            engine.refreshIfDue()
            engine.pool.value
        } ?: return
        make(pool)
    }

    /** The app's own context, in the language picked in its settings; set for each run. */
    private var res: Context = context

    private suspend fun make(pool: ScoutPool) = lock.withLock {
        val profileId = engine.get().activeProfile()
        val (width, height) = AtvWall.screenSize(context)
        res = AtvWall.inAppLanguage(context)
        val locale = res.resources.configuration.locales[0] ?: Locale.getDefault()
        val language = locale.toLanguageTag()
        val old = AtvWall.readSet(context)
        val now = System.currentTimeMillis()
        if (old != null && old.entries.isNotEmpty() && old.poolBuiltAt == pool.builtAt && old.profileId == profileId &&
            old.style == ScoutWallComposer.STYLE_VERSION && old.width == width && old.height == height &&
            old.language == language && now - old.createdAt < REMAKE_AFTER_MS
        ) return@withLock

        val chosen = choose(pool)
        if (chosen.isEmpty()) return@withLock
        Log.i(TAG, "making ${chosen.size} pictures at ${width}x$height")
        val composer = ScoutWallComposer(context, locale)
        val entries = ArrayList<WallEntry>()
        var drawn = 0
        for (c in chosen) {
            // Out of the player's way: wait while something plays.
            TvRecommendationManager.isPlaybackActive.first { !it }
            val entry = runCatching { entryFor(c, composer, width, height, language) { drawn++ } }
                .onFailure { Log.w(TAG, "${c.pick.key}: ${it.javaClass.simpleName} ${it.message}") }
                .getOrNull()
            if (entry != null) entries += entry
        }
        if (entries.isEmpty()) return@withLock
        AtvWall.commitSet(
            context,
            WallSet(now, pool.builtAt, profileId, ScoutWallComposer.STYLE_VERSION, width, height, language, entries),
        )
        Log.i(TAG, "set of ${entries.size} pictures ($drawn new)")
    }

    private suspend fun entryFor(c: Chosen, composer: ScoutWallComposer, width: Int, height: Int, language: String, onDrawn: () -> Unit): WallEntry? {
        val pick = c.pick
        val backdropUrl = pick.backdrop?.let(::largest) ?: return null
        val detail = runCatching { finder.get().detail(pick, withRating = false) }.getOrNull()
        val logoUrl = runCatching { finder.get().logo(pick) }.getOrNull()
        val facts = facts(pick, detail)
        val rating = pick.rating?.takeIf { it > 0f }?.let { "${(it * 10).roundToInt()}%" }
        val overview = pick.overview.orEmpty()
        val fingerprint = listOf(
            pick.name, overview, facts.joinToString("|"), rating, c.reason, logoUrl, backdropUrl, "${width}x$height", language,
        ).joinToString("\u0001")
        val id = "s${ScoutWallComposer.STYLE_VERSION}-${if (pick.tv) "tv" else "mv"}${pick.tmdbId}-" +
            Integer.toHexString(fingerprint.hashCode())
        val file = "$id.jpg"
        if (!AtvWall.hasPicture(context, file)) {
            val s = width / 1920f
            val backdrop = load(backdropUrl, width, height, Scale.FILL) ?: return null
            val logo = logoUrl?.let { load(it, (ScoutWallComposer.LOGO_BOX_W * s).toInt(), (ScoutWallComposer.LOGO_BOX_H * s).toInt(), Scale.FIT) }
            try {
                val scene = WallScene(pick.name, backdrop, logo, facts, rating, overview, FOOTER_LABEL, c.reason)
                val picture = composer.compose(scene, width, height)
                try {
                    AtvWall.writePicture(context, file, picture)
                } finally {
                    picture.recycle()
                }
                onDrawn()
            } finally {
                backdrop.recycle()
                logo?.recycle()
            }
            delay(BETWEEN_PICTURES_MS)
        }
        return WallEntry(id, file, pick.name, link(pick), c.reason)
    }

    // ---- Which titles ------------------------------------------------------------------------

    private class Chosen(val pick: ScoutPick, val reason: String?)

    /**
     * Up to [MAX_PICTURES] titles with a backdrop, taken in turn from the picks, New for you,
     * Trending for you, each "Because you watched" row and the hidden gems, so the wallpaper
     * varies; each title once.
     */
    private fun choose(pool: ScoutPool): List<Chosen> {
        val lanes = ArrayList<ArrayDeque<Chosen>>()
        fun lane(picks: List<ScoutPick>, label: (ScoutPick) -> String?) {
            val q = ArrayDeque(picks.filter { !it.backdrop.isNullOrBlank() }.map { Chosen(it, reasonFor(it) ?: label(it)) })
            if (q.isNotEmpty()) lanes += q
        }
        lane(pool.picked) { res.getString(R.string.scout_row_picked) }
        lane(pool.newForYou) { res.getString(R.string.scout_picks_row_new) }
        lane(pool.trendingForYou) { res.getString(R.string.scout_picks_row_trending) }
        pool.because.forEach { row -> lane(row.picks) { res.getString(R.string.scout_row_because_title, row.seedName) } }
        lane(pool.gems) { res.getString(R.string.scout_row_gems) }
        val out = ArrayList<Chosen>()
        val seen = HashSet<String>()
        while (out.size < MAX_PICTURES && lanes.any { it.isNotEmpty() }) {
            for (q in lanes) {
                while (q.isNotEmpty()) {
                    val c = q.removeFirst()
                    if (seen.add(c.pick.key)) {
                        out += c
                        break
                    }
                }
                if (out.size >= MAX_PICTURES) break
            }
        }
        return out
    }

    /** Why Scout offers [pick], as its rows say it. */
    private fun reasonFor(pick: ScoutPick): String? {
        val english = res.resources.configuration.locales[0]?.language == "en"
        val word = pick.whyKeyword?.takeIf { english }
        val person = pick.whyPerson
        return when {
            !pick.aiReason.isNullOrBlank() -> pick.aiReason
            pick.stretch -> res.getString(R.string.scout_picks_why_stretch)
            person != null && word != null && pick.whyLoved -> res.getString(R.string.scout_picks_why_person_keyword, person, word)
            person != null && pick.whyLoved -> res.getString(R.string.scout_picks_why_person_loved, person)
            person != null -> res.getString(R.string.scout_picks_why_person, person)
            word != null -> res.getString(R.string.scout_picks_why_keyword, word)
            !pick.seedName.isNullOrBlank() -> res.getString(R.string.scout_row_because_title, pick.seedName)
            else -> null
        }
    }

    /** Year, two genres at most, then the length or the number of seasons. */
    private fun facts(pick: ScoutPick, detail: ScoutPickDetail?): List<String> = buildList {
        pick.released?.take(4)?.takeIf { it.length == 4 }?.let(::add)
        pick.genres.map(::scoutCanonicalGenre).distinct()
            .mapNotNull { id -> ScoutSlices.byId("genre_$id")?.titleRes?.takeIf { it != 0 }?.let { title -> res.getString(title) } }
            .take(2).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
        val seasons = detail?.seasons
        val minutes = detail?.minutes
        when {
            pick.tv && seasons != null && seasons > 0 -> add(res.resources.getQuantityString(R.plurals.atvwall_seasons, seasons, seasons))
            pick.tv -> add(res.getString(R.string.atvwall_series))
            minutes != null && minutes > 0 -> add(runtime(minutes))
        }
    }

    private fun runtime(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> res.getString(R.string.atvwall_runtime_minutes, m)
            m == 0 -> res.getString(R.string.atvwall_runtime_hours, h)
            else -> res.getString(R.string.atvwall_runtime_hours_minutes, h, m)
        }
    }

    // ---- Pictures ----------------------------------------------------------------------------

    /** TMDB's largest copy of a backdrop: the rows keep a smaller one. */
    private fun largest(url: String): String =
        if (url.contains("image.tmdb.org/t/p/")) url.replace(Regex("/t/p/[^/]+/"), "/t/p/original/") else url

    /** A software bitmap of [url], decoded no smaller than [w] x [h] needs; null when it cannot be read. */
    private suspend fun load(url: String, w: Int, h: Int, scale: Scale): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(w, h)
            .scale(scale)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        val bitmap = result.image.toBitmap()
        val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
        return if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    }

    private fun link(pick: ScoutPick): String =
        "nuvio://detail/${if (pick.tv) "series" else "movie"}/tmdb:${pick.tmdbId}"

    private companion object {
        const val TAG = "AtvWall"
        const val FOOTER_LABEL = "Scout"
        const val MAX_PICTURES = 30
        const val START_DELAY_MS = 45_000L
        const val SETTLE_MS = 15_000L
        const val BETWEEN_PICTURES_MS = 300L
        const val REMAKE_AFTER_MS = 24 * 60 * 60_000L
    }
}
