package com.nuvio.tv.core.util

import android.content.Context
import com.nuvio.tv.R

/**
 * Raw bytes of the app wordmark image, served to the web-config pages,
 * or null when the resource cannot be read.
 */
fun Context.readAppLogoBytes(): ByteArray? =
    try {
        resources.openRawResource(R.drawable.livetv_wordmark_iptv).use { it.readBytes() } // Live TV fork: NuvioMate logo
    } catch (_: Exception) {
        null
    }
