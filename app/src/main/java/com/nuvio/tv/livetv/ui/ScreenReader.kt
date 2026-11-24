package com.nuvio.tv.livetv.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * True while a screen reader (TalkBack or another service that speaks) is on.
 *
 * Any accessibility service at all, even one that only watches which app is in front (ATV
 * Launcher's Home button redirect, Button Mapper), makes Compose describe the whole screen to it
 * again at every frame. On the guide, with hundreds of shows on screen, that cost more than the
 * guide itself at each press of a key (about 25 ms on a TCL TV). Screens use this to leave that
 * description out when nobody is listening to it (see [quietForAccessibility]).
 */
@Composable
internal fun rememberScreenReaderOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var on by remember(manager) { mutableStateOf(manager.screenReaderOn()) }
    DisposableEffect(manager) {
        val m = manager ?: return@DisposableEffect onDispose { }
        val state = AccessibilityManager.AccessibilityStateChangeListener { on = m.screenReaderOn() }
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { on = m.screenReaderOn() }
        m.addAccessibilityStateChangeListener(state)
        m.addTouchExplorationStateChangeListener(touch)
        // A screen reader turned on while another service was already on changes neither of the
        // two above: the list of services in use is followed too where Android says when it changes.
        val services = if (Build.VERSION.SDK_INT >= 33) {
            AccessibilityManager.AccessibilityServicesStateChangeListener { on = m.screenReaderOn() }
                .also { m.addAccessibilityServicesStateChangeListener(context.mainExecutor, it) }
        } else null
        onDispose {
            m.removeAccessibilityStateChangeListener(state)
            m.removeTouchExplorationStateChangeListener(touch)
            if (Build.VERSION.SDK_INT >= 33 && services != null) m.removeAccessibilityServicesStateChangeListener(services)
        }
    }
    return on
}

private fun AccessibilityManager?.screenReaderOn(): Boolean {
    val m = this ?: return false
    if (!m.isEnabled) return false
    if (m.isTouchExplorationEnabled) return true
    return runCatching {
        m.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
    }.getOrDefault(true)
}

/**
 * With no screen reader on ([screenReaderOn] false), this part of the screen has no description
 * for accessibility services (nothing for Compose to work out again at every frame). With one
 * on, it is described as usual. Focus, keys and clicks don't depend on it.
 */
internal fun Modifier.quietForAccessibility(screenReaderOn: Boolean): Modifier =
    if (screenReaderOn) this else this.clearAndSetSemantics { }
