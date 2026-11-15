package com.nuvio.tv.livetv.ui

import android.content.Context
import com.nuvio.tv.R

/**
 * Live TV's own on-screen words (guide, player, Sports, menus…) are written in English in the
 * code. They're shown in the device's language when there's a translation: the English list
 * (R.array.livetv_texts_en) and the same list in the device's language (R.array.livetv_texts,
 * values-fr/…) line up entry for entry, and [LiveText] / [LiveTag] swap a text for its
 * translation when it's on the list. Anything not on it (show titles, channel names…) is shown
 * as it is.
 */
internal object LiveTvTexts {
    @Volatile private var map: Map<String, String> = emptyMap()
    @Volatile private var builtFor: String? = null

    /** Reads the lists for [context]'s language (once per language). */
    fun init(context: Context) {
        val config = context.resources.configuration
        val tag = if (android.os.Build.VERSION.SDK_INT >= 24) config.locales[0].toLanguageTag() else @Suppress("DEPRECATION") config.locale.toLanguageTag()
        if (tag == builtFor) return
        map = runCatching {
            val en = context.resources.getStringArray(R.array.livetv_texts_en)
            val local = context.resources.getStringArray(R.array.livetv_texts)
            if (en.size != local.size) emptyMap()
            else HashMap<String, String>(en.size * 2).apply {
                for (i in en.indices) if (en[i] != local[i] && local[i].isNotEmpty()) put(en[i], local[i])
            }
        }.getOrDefault(emptyMap())
        builtFor = tag
    }

    /** [text] in the device's language, when it's one of Live TV's own words. */
    fun tr(text: String): String = map[text] ?: text
}

/** This Live TV word in the device's language (see [LiveTvTexts]). */
internal fun String.tr(): String = LiveTvTexts.tr(this)
