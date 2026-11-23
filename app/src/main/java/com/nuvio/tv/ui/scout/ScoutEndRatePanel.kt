package com.nuvio.tv.ui.scout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ScoutAskTarget
import com.nuvio.tv.domain.model.ScoutRating
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * "How was it?" right on the end-of-film screen, under the small window with the credits, so the
 * question comes while the credits roll instead of once the player is closed.
 */
@Composable
fun ScoutEndRatePanel(
    target: ScoutAskTarget,
    viewModel: ScoutRatingViewModel,
    firstFocus: FocusRequester,
    upFocus: FocusRequester?,
    downFocus: FocusRequester,
    modifier: Modifier = Modifier
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val current = profile.rating(target.title.id)
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.62f), shape)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.scout_ask_title, target.title.name),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.scout_ask_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScoutRating.entries.forEach { rating ->
                ScoutRateChip(
                    rating = rating,
                    selected = current == rating,
                    onClick = { viewModel.rateAtEnd(target, if (current == rating) null else rating) },
                    modifier = Modifier.focusProperties {
                        upFocus?.let { up = it }
                        down = downFocus
                    },
                    focusRequester = firstFocus.takeIf { rating == (current ?: ScoutRating.LIKE) }
                )
            }
        }
    }
}
