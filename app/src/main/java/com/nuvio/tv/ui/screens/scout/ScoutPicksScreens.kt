@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.scout

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.scout.ScoutAiState
import com.nuvio.tv.core.scout.ScoutAiStore
import com.nuvio.tv.core.scout.ScoutEngine
import com.nuvio.tv.core.scout.ScoutPicksAi
import com.nuvio.tv.core.scout.ask.AskFailure
import com.nuvio.tv.core.scout.ask.AskKeyVault
import com.nuvio.tv.core.scout.scoutSignals
import com.nuvio.tv.core.scout.toPick
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.data.local.ScoutStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ScoutPick
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutTitle
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

const val SCOUT_QUICK_START_COUNT = 5
private const val MIN_TITLES_TO_GO_ON = 3
private const val QUICK_MIN_VOTES = 3000

data class ScoutQuickState(val loading: Boolean = true, val picks: List<ScoutPick> = emptyList(), val chosen: Set<String> = emptySet())

@HiltViewModel
class ScoutQuickStartViewModel @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val store: ScoutStore,
    private val engine: ScoutEngine,
    private val aiStore: ScoutAiStore,
    private val watchProgress: dagger.Lazy<WatchProgressRepository>
) : ViewModel() {
    val state = MutableStateFlow(ScoutQuickState())

    /** Only once, and only for a profile with too little to go on. */
    suspend fun shouldOffer(): Boolean {
        if (aiStore.read().quickStartOffered) return false
        val signals = scoutSignals(
            watchProgress.get().tasteHistory.first(), watchProgress.get().allProgress.first(),
            store.current().ratings.values, System.currentTimeMillis()
        )
        return signals.count { it.weight > 0 } < MIN_TITLES_TO_GO_ON
    }

    fun load() {
        aiStore.update { it.copy(quickStartOffered = true) }
        viewModelScope.launch {
            val key = BuildConfig.TMDB_API_KEY
            val films = runCatching { tmdbApi.discoverMovies(apiKey = key, sortBy = "popularity.desc", voteCountGte = QUICK_MIN_VOTES) }
                .getOrNull()?.body()?.results.orEmpty().mapNotNull { it.toPick(false) }
            val series = runCatching { tmdbApi.discoverTv(apiKey = key, sortBy = "popularity.desc", voteCountGte = QUICK_MIN_VOTES, withoutGenres = "10763,10764,10766,10767") }
                .getOrNull()?.body()?.results.orEmpty().mapNotNull { it.toPick(true) }
            val mixed = (0 until maxOf(films.size, series.size)).flatMap { listOfNotNull(films.getOrNull(it), series.getOrNull(it)) }
            state.update { it.copy(loading = false, picks = mixed) }
        }
    }

    fun toggle(pick: ScoutPick) = state.update { old ->
        val chosen = if (pick.key in old.chosen) old.chosen - pick.key
        else if (old.chosen.size < SCOUT_QUICK_START_COUNT) old.chosen + pick.key else old.chosen
        old.copy(chosen = chosen)
    }

    fun finish(onDone: () -> Unit) {
        val now = state.value
        viewModelScope.launch {
            now.picks.filter { it.key in now.chosen }.forEach { pick ->
                store.setRating(
                    ScoutTitle("tmdb:${pick.tmdbId}", if (pick.tv) "series" else "movie", pick.name, pick.poster),
                    ScoutRating.LOVE
                )
            }
            if (now.chosen.isNotEmpty()) {
                engine.reset()
                engine.refreshIfDue()
            }
        }
        onDone()
    }
}

private fun ScoutPick.toCard() = MetaPreview(
    id = "tmdb:$tmdbId",
    type = if (tv) ContentType.SERIES else ContentType.MOVIE,
    rawType = if (tv) "series" else "movie",
    name = name,
    poster = poster,
    posterShape = PosterShape.POSTER,
    background = backdrop,
    logo = null,
    description = overview,
    releaseInfo = released?.take(4),
    released = released,
    imdbRating = rating,
    voteCount = votes,
    genres = emptyList()
)

@Composable
fun ScoutQuickStartGate(onOffer: () -> Unit, viewModel: ScoutQuickStartViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { if (viewModel.shouldOffer()) onOffer() }
}

@Composable
fun ScoutQuickStartScreen(onDone: () -> Unit, viewModel: ScoutQuickStartViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.picks.isNotEmpty()) { if (state.picks.isNotEmpty()) runCatching { first.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = NuvioTheme.spacing.screen.overscanHorizontal, vertical = NuvioTheme.spacing.screen.overscanVertical),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        ScoutPageTitle(stringResource(R.string.scout_picks_quick_title), inset = 0.dp) {
            Text(
                stringResource(R.string.scout_picks_quick_count, state.chosen.size, SCOUT_QUICK_START_COUNT),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary
            )
            AskButton(stringResource(R.string.scout_picks_quick_skip), onDone)
            AskButton(stringResource(R.string.scout_picks_quick_done), { viewModel.finish(onDone) }, primary = true, enabled = state.chosen.isNotEmpty())
        }
        Text(stringResource(R.string.scout_picks_quick_sub), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(NuvioTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            items(state.picks, key = { it.key }) { pick ->
                Box {
                    GridContentCard(
                        item = pick.toCard(),
                        focusRequester = if (pick == state.picks.first()) first else null,
                        showReleaseInfo = true,
                        onFocused = {},
                        onClick = { viewModel.toggle(pick) }
                    )
                    if (pick.key in state.chosen) {
                        Box(
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp)
                                .background(NuvioTheme.colors.Secondary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = NuvioTheme.colors.OnSecondary)
                        }
                    }
                }
            }
        }
    }
}

/** Arc's Ask button, here a Scout chip (Ask itself is not ported). */
@Composable
private fun AskButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    ScoutChip(label, onClick, modifier, primary = primary, enabled = enabled)
}
