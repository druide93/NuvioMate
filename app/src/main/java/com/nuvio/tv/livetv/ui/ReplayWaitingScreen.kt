package com.nuvio.tv.livetv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Full screen while a replay just picked hasn't shown a picture yet: black, the channel's logo
 * (its name when it has none) and "Loading the replay", instead of the last live picture frozen
 * behind a small "Loading…". Fades away as the replay appears. [channel] null: not loading one.
 */
@Composable
internal fun ReplayWaitingScreen(channel: LiveChannel?, modifier: Modifier = Modifier) {
    // Kept while it fades out, after the replay has started.
    var shown by remember { mutableStateOf(channel) }
    if (channel != null) shown = channel
    AnimatedVisibility(
        visible = channel != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val ch = shown ?: return@AnimatedVisibility
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val logo = ch.logo?.takeIf { it.isNotBlank() && !DeadLogos.isDead(it) }
            if (logo != null) {
                ChannelLogo(logo, size = 130.dp)
            } else {
                LiveText(
                    ch.name,
                    color = Color.White,
                    size = 40.sp,
                    weight = FontWeight.Bold,
                    maxLines = 2,
                    align = TextAlign.Center
                )
            }
            Spacer(Modifier.height(36.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = NuvioTheme.colors.Secondary,
                    strokeWidth = 2.5.dp
                )
                Spacer(Modifier.width(14.dp))
                LiveText("Loading the replay", color = Color.White, size = 22.sp)
            }
        }
    }
}
