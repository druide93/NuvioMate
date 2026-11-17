package com.nuvio.tv.livetv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.roundToInt

/** How dark the picture behind the logo and text is made, so they read on any picture. */
private const val PICTURE_DIM = 0.6f

/**
 * Full screen while a replay is loading (just picked, or skipped to a part that isn't loaded):
 * the channel's logo (its name when it has none) and "Loading the replay". On black for a replay
 * just picked; over the picture as it was when you skipped otherwise, dimmed. Fades away as the
 * replay's picture appears. [wait] null: nothing loading.
 */
@Composable
internal fun ReplayWaitingScreen(wait: LiveTvViewModel.ReplayWait?, modifier: Modifier = Modifier) {
    // Kept while it fades out, after the picture has come; let go of once it has (a kept
    // picture is a few MB).
    var shown by remember { mutableStateOf(wait) }
    if (wait != null) shown = wait
    val visible = remember { MutableTransitionState(wait != null) }
    visible.targetState = wait != null
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && wait == null) shown = null
    }
    val current = shown
    AnimatedVisibility(
        visibleState = visible,
        // For a skip it's up at once (over a kept picture it's the same picture; the one behind
        // is about to go); over the picture in place it comes quickly; on black it fades in.
        enter = when {
            current?.frozen != null || current?.instant == true -> EnterTransition.None
            current?.seeThrough == true -> fadeIn(tween(150))
            else -> fadeIn()
        },
        exit = fadeOut(),
        modifier = modifier
    ) {
        val w = shown ?: return@AnimatedVisibility
        var origin by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionInWindow() }
                .background(if (w.seeThrough) Color.Black.copy(alpha = PICTURE_DIM) else Color.Black)
        ) {
            w.frozen?.let { frame ->
                // The picture where it was on screen (its shape and zoom included), dimmed.
                Canvas(Modifier.fillMaxSize().clipToBounds()) {
                    val b = frame.bounds
                    drawImage(
                        image = frame.image,
                        dstOffset = IntOffset((b.left - origin.x).roundToInt(), (b.top - origin.y).roundToInt()),
                        dstSize = IntSize(b.width.roundToInt().coerceAtLeast(1), b.height.roundToInt().coerceAtLeast(1))
                    )
                    drawRect(Color.Black.copy(alpha = PICTURE_DIM))
                }
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val ch = w.channel
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
                        align = TextAlign.Center,
                        shadow = true
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
                    LiveText("Loading the replay", color = Color.White, size = 22.sp, shadow = true)
                }
            }
        }
    }
}
