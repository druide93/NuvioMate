@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.nuvio.tv.ui.screens.detail

// Scout's Rate button on a title page, from Nuvio Arc (ysosrs123/NuvioTV-Fork, GPL-3.0), classic look.

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.domain.model.ScoutTitle
import com.nuvio.tv.ui.theme.NuvioTheme

private enum class ScoutRatePhase { CLOSED, OPENING, OPEN, CLOSING }

/** Rate button for [title], when the profile has Scout ratings on. */
@Composable
internal fun HeroScoutRate(title: ScoutTitle, aliasId: String?, onFocused: () -> Unit) {
    val scoutRatings: com.nuvio.tv.ui.scout.ScoutRatingViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val profile = scoutRatings.profile.collectAsStateWithLifecycle().value
    if (!profile.settings.ratingsEnabled) return
    val aliases = setOfNotNull(aliasId?.takeIf(String::isNotBlank)) - title.id
    ScoutRateAction(
        rating = profile.rating(title.id) ?: aliases.firstNotNullOfOrNull(profile::rating),
        onRate = { scoutRatings.setRatingMerging(title, aliases, it) },
        onFocused = onFocused
    )
}

/**
 * The Rate button opens its three choices in place. While focus moves between the two, both stay
 * composed for a frame with the outgoing one at no width, so focus never falls out of the row.
 * The choices stay open until one is picked or Back is pressed.
 */
@Composable
private fun ScoutRateAction(
    rating: ScoutRating?,
    onRate: (ScoutRating?) -> Unit,
    onFocused: () -> Unit
) {
    var phase by remember { mutableStateOf(ScoutRatePhase.CLOSED) }
    val buttonFocus = remember { FocusRequester() }
    val choiceFocus = remember { FocusRequester() }
    val initial = rating ?: ScoutRating.LIKE

    LaunchedEffect(phase) {
        when (phase) {
            ScoutRatePhase.OPENING -> {
                withFrameNanos { }
                runCatching { choiceFocus.requestFocus() }
                phase = ScoutRatePhase.OPEN
            }
            ScoutRatePhase.CLOSING -> {
                withFrameNanos { }
                runCatching { buttonFocus.requestFocus() }
                phase = ScoutRatePhase.CLOSED
            }
            else -> Unit
        }
    }

    if (phase != ScoutRatePhase.OPEN) {
        Box(if (phase == ScoutRatePhase.OPENING) Modifier.width(0.dp).clipToBounds() else Modifier) {
            val label = rating?.let { stringResource(com.nuvio.tv.ui.scout.scoutRatingLabelRes(it)) }
                ?: stringResource(R.string.scout_rate)
            ScoutRateButton(
                rating = rating,
                label = label,
                focusRequester = buttonFocus,
                onClick = { phase = ScoutRatePhase.OPENING },
                onFocused = onFocused
            )
        }
    }
    if (phase != ScoutRatePhase.CLOSED) {
        val shape = RoundedCornerShape(999.dp)
        val startKey = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Key.DirectionRight else Key.DirectionLeft
        Row(
            modifier = (if (phase == ScoutRatePhase.CLOSING) Modifier.width(0.dp).clipToBounds() else Modifier)
                .background(NuvioTheme.colors.TextPrimary.copy(alpha = 0.09f), shape)
                .onFocusChanged { state ->
                    if (!state.hasFocus && phase == ScoutRatePhase.OPEN) phase = ScoutRatePhase.CLOSED
                    if (state.hasFocus) onFocused()
                }
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                    if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_UP) phase = ScoutRatePhase.CLOSING
                    true
                }
                .focusGroup()
                .focusProperties {
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                },
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScoutRating.entries.forEach { choice ->
                com.nuvio.tv.ui.scout.ScoutRateChip(
                    rating = choice,
                    selected = rating == choice,
                    onClick = {
                        onRate(if (rating == choice) null else choice)
                        phase = ScoutRatePhase.CLOSING
                    },
                    focusRequester = choiceFocus.takeIf { choice == initial },
                    modifier = Modifier
                        .focusProperties {
                            if (choice == ScoutRating.entries.last()) end = FocusRequester.Cancel
                        }
                        .then(
                            if (choice == ScoutRating.entries.first()) {
                                Modifier.onKeyEvent { it.key == startKey }
                            } else Modifier
                        )
                )
            }
        }
    }
}

@Composable
private fun ScoutRateButton(
    rating: ScoutRating?,
    label: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onFocused: () -> Unit
) {
    val secondary = NuvioTheme.colors.Secondary
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .size(NuvioTheme.spacing.xxxl)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusProperties { up = FocusRequester.Cancel }
            .semantics { contentDescription = label },
        colors = IconButtonDefaults.colors(
            containerColor = if (rating != null) secondary else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = secondary,
            contentColor = if (rating != null) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondary
        ),
        border = IconButtonDefaults.border(
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = CircleShape)
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            com.nuvio.tv.ui.scout.ScoutRatingIcon(rating, 20.dp)
        }
    }
}
