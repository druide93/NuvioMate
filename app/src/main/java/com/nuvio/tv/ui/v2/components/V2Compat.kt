package com.nuvio.tv.ui.v2.components

// Classic stand-ins for the few Nuvio Arc "V2" pieces its Scout screens use. Arc draws these
// the same way when its V2 look is off, which is always the case in NuvioMate.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

enum class GlassRole { NAVIGATION, CONTROL, PANEL, MODAL, HUD, CARD_FOCUS, TOOLTIP }

/** Arc's V2 focus edge: nothing without the V2 look. */
fun Modifier.nuvioV2Focus(
    focused: Boolean,
    shape: Shape,
    accentOverride: Color? = null,
    hardwareShadow: Boolean = true,
    stationary: Boolean = false
): Modifier = this

/** Arc's glass surface, as a plain theme surface. */
@Composable
fun Modifier.nuvioGlass(
    role: GlassRole,
    focused: Boolean = false,
    shape: Shape = RoundedCornerShape(20.dp),
    trailingEdgeOnly: Boolean = false,
    neutralBackdrop: Boolean = false
): Modifier {
    val color = when {
        focused -> NuvioTheme.colors.Secondary.copy(alpha = 0.24f)
        role == GlassRole.PANEL || role == GlassRole.MODAL -> NuvioTheme.colors.BackgroundCard
        else -> NuvioTheme.colors.BackgroundElevated
    }
    return this.background(color, shape)
}

@Composable
fun NuvioActionPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    neutralBackdrop: Boolean = false,
    stationary: Boolean = false,
    containerColor: Color? = null,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = remember { RoundedCornerShape(12.dp) }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .background(containerColor ?: NuvioTheme.colors.BackgroundElevated, shape),
        shape = ButtonDefaults.shape(shape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = NuvioTheme.colors.Secondary.copy(alpha = .24f),
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.TextPrimary
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        content = content
    )
}

data class AvatarModel(val id: String, val name: String, val colorHex: String, val imageUrl: String? = null)

/** A profile's picture, or its initial on its colour. */
@Composable
fun NuvioAvatar(
    avatar: AvatarModel,
    size: Dp,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    selected: Boolean = false,
    portraitPlaceholder: Boolean = false,
    description: String? = avatar.name
) {
    val color = remember(avatar.colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(avatar.colorHex)) }.getOrDefault(Color(0xFF4A5568))
    }
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        val url = avatar.imageUrl
        if (!url.isNullOrBlank()) {
            coil3.compose.AsyncImage(
                model = url,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        } else {
            Text(
                text = avatar.name.trim().take(1).uppercase(),
                color = Color.White,
                fontSize = (size.value * 0.42f).sp
            )
        }
    }
}

/** Arc's side-menu page offset: NuvioMate's root screens already sit beside the menu. */
fun Modifier.sidebarPageContent(): Modifier = this
