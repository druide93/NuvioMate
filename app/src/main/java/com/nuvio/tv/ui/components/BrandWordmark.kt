package com.nuvio.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.brandWordmarkResource

@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
    drawableOverride: Int? = null
) {
    Image(
        // Live TV fork: the NuvioMate logo wherever Nuvio's wordmark is shown.
        painter = painterResource(
            id = com.nuvio.tv.livetv.branding.LiveTvBranding.iptvWordmark(
                drawableOverride ?: NuvioTheme.currentTheme.brandWordmarkResource
            )
        ),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alpha = alpha
    )
}
