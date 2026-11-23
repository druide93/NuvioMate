package com.nuvio.tv.core.scout

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.catalog.BuiltInCatalogs
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.EpisodeShufflePlayback
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.ScoutMix
import com.nuvio.tv.domain.model.ScoutMixNameWords
import com.nuvio.tv.domain.model.suggestScoutMixName
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.domain.model.ScoutMixEpisodes
import com.nuvio.tv.domain.model.ScoutMixShow
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val LOOKUP_TIMEOUT_MS = 20_000L
private const val ID_TIMEOUT_MS = 5_000L

/** Turns a poster into a saved show, and a saved show into the episodes it can offer. */
@Singleton
class ScoutMixLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metaRepository: MetaRepository,
    private val shufflePlayback: EpisodeShufflePlayback,
    private val watchProgress: WatchProgressRepository,
    private val tmdbService: TmdbService,
    private val finder: ScoutPicksFinder,
    private val addonRepository: AddonRepository
) {
    private val words = ScoutMixNameWords(
        oneShow = { local().getString(R.string.scout_mixes_name_default, it) },
        genre = { local().getString(R.string.scout_mixes_name_default, it) },
        twoShows = { first, second -> local().getString(R.string.scout_mixes_name_two, first, second) },
        moreShows = { first, second, more ->
            local().resources.getQuantityString(R.plurals.scout_mixes_name_more, more, first, second, more)
        }
    )

    private fun local() = context.withAppLocale()

    fun suggestedName(shows: List<ScoutMixShow>, taken: List<String>): String =
        suggestScoutMixName(shows, taken, words)

    /** A name the viewer has not chosen follows the shows. An emptied mix keeps the one it has. */
    fun withSuggestedName(mix: ScoutMix, others: List<ScoutMix>): ScoutMix =
        if (!mix.autoName || mix.shows.isEmpty()) mix
        else mix.copy(name = suggestedName(mix.shows, others.filter { it.id != mix.id }.map { it.name }))

    suspend fun showFrom(item: MetaPreview): ScoutMixShow {
        val id = addonFriendlyId(item)
        return ScoutMixShow(
            id = id,
            name = item.name,
            poster = item.rawPosterUrl ?: item.poster,
            backdrop = item.background ?: item.landscapePoster,
            type = item.apiType,
            imdbId = item.imdbId ?: id.takeIf { it.startsWith("tt") } ?: imdbFor(id),
            addonBaseUrl = item.sourceAddonBaseUrl?.takeUnless { it.startsWith(BuiltInCatalogs.BASE_PREFIX) },
            genres = item.genres,
            logo = item.logo?.takeIf(String::isNotBlank)
        )
    }

    /**
     * NuvioMate: a show keeps the id it came with (Arc turned a TMDB id into an IMDb one), and its IMDb
     * id is kept beside it, so its episodes can be asked for with whichever id the meta add-ons take.
     */
    private fun addonFriendlyId(item: MetaPreview): String = item.id

    /** The IMDb id of a show known by its TMDB id, or null. */
    private suspend fun imdbFor(id: String): String? {
        val number = id.takeIf { it.startsWith("tmdb:") }?.substringAfter(':')?.substringBefore(':')?.toIntOrNull() ?: return null
        return withTimeoutOrNull(ID_TIMEOUT_MS) {
            runCatching { tmdbService.tmdbToImdb(number, "tv") }.getOrNull()
        }?.takeIf { it.startsWith("tt") }
    }

    /** "tmdb:N" for a show, from its saved id or its IMDb id, or null. */
    private suspend fun tmdbIdOf(show: ScoutMixShow): String? {
        if (show.id.startsWith("tmdb:")) return show.id
        val imdb = show.imdbId ?: show.id.takeIf { it.startsWith("tt") } ?: return null
        val number = withTimeoutOrNull(ID_TIMEOUT_MS) {
            runCatching { tmdbService.ensureTmdbId(imdb, "tv") }.getOrNull()
        }?.toIntOrNull() ?: return null
        return "tmdb:$number"
    }

    /**
     * The ids to ask the meta add-ons for, those an enabled meta add-on takes first (AIOMetadata takes
     * tmdb:, Cinemeta tt), TMDB before IMDb when both are taken. The episodes then carry the same kind
     * of id as on the show's own page, which is what the sources are asked with.
     */
    private suspend fun lookupIds(show: ScoutMixShow): List<String> {
        val tmdb = tmdbIdOf(show)
        val imdb = show.imdbId ?: show.id.takeIf { it.startsWith("tt") } ?: imdbFor(show.id)
        val ids = listOfNotNull(tmdb, imdb, show.id).distinct()
        val metaAddons = withTimeoutOrNull(ID_TIMEOUT_MS) {
            addonRepository.getInstalledAddons().first().enabledAddons().filter { addon -> addon.resources.any { it.name == "meta" } }
        }.orEmpty()
        if (metaAddons.isEmpty()) return ids
        val (taken, others) = ids.partition { id -> metaAddons.any { it.takesMetaId(id) } }
        return taken + others
    }

    private fun Addon.takesMetaId(id: String): Boolean {
        val prefixes = resources.firstOrNull { it.name == "meta" }?.idPrefixes?.takeIf { it.isNotEmpty() } ?: idPrefixes
        return prefixes.isEmpty() || prefixes.any { id.startsWith(it, ignoreCase = true) }
    }

    /** The same artwork source as the show's own page, where an add-on's logo can be a dark one. */
    suspend fun bestLogo(show: ScoutMixShow): String? = withTimeoutOrNull(ID_TIMEOUT_MS) {
        val id = runCatching { tmdbService.ensureTmdbId(show.imdbId ?: show.id, "tv")?.toIntOrNull() }.getOrNull() ?: return@withTimeoutOrNull null
        finder.logo(id, tv = true)
    }

    /** The episode of these shows stopped partway most recently, from saved progress alone, with no lookup. */
    suspend fun startedEpisode(shows: List<ScoutMixShow>): Pair<ScoutMixShow, com.nuvio.tv.domain.model.WatchProgress>? {
        val partway = watchProgress.allProgress.first().filter { it.season != null && it.episode != null && it.isInProgress() }
        if (partway.isEmpty()) return null
        return shows.mapNotNull { show ->
            partway.filter { it.contentId == show.id || it.contentId == show.imdbId }.maxByOrNull { it.lastWatched }?.let { show to it }
        }.maxByOrNull { it.second.lastWatched }
    }

    suspend fun episodes(profileId: Int, show: ScoutMixShow): ScoutMixEpisodes? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        // NuvioMate: asked with the id the meta add-ons take, the others as fallbacks (see lookupIds).
        val meta = lookupIds(show).firstNotNullOfOrNull { id ->
            val result = metaRepository.getMetaFromAllAddons(show.type, id)
                .first { it !is NetworkResult.Loading }
            (result as? NetworkResult.Success)?.data?.takeIf { it.videos.isNotEmpty() }
        } ?: return@withTimeoutOrNull null
        val state = shufflePlayback.observe(profileId, meta.id, meta.apiType).first()
        val elsewhere = meta.videos.filter { video ->
            video.episode?.let { watchProgress.isWatchedByVideoId(video.id, it) } == true
        }.mapNotNull { video -> video.season?.let { season -> video.episode?.let { season to it } } }.toSet()
        ScoutMixEpisodes(meta, state.watched + elsewhere, state.progress)
    }
}
