package com.nuvio.tv.ui.v2.appearance

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * NuvioMate has no Arc "V2" look. Screens ported from Nuvio Arc (Scout) check this for null and then
 * draw their classic version, so it always stays null here.
 */
val LocalV2Appearance = staticCompositionLocalOf<Any?> { null }
