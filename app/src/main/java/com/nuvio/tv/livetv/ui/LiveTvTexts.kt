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
    /** The same words in capitals, for headings shown in capitals ("WATCH", "SCHEDULED"). */
    @Volatile private var upper: Map<String, String> = emptyMap()
    @Volatile private var builtFor: String? = null

    /** Reads the lists for [context]'s language (once per language). */
    fun init(context: Context) {
        val config = context.resources.configuration
        val locale = if (android.os.Build.VERSION.SDK_INT >= 24) config.locales[0] else @Suppress("DEPRECATION") config.locale
        val tag = locale.toLanguageTag()
        if (tag == builtFor) return
        map = runCatching {
            val en = context.resources.getStringArray(R.array.livetv_texts_en)
            val local = context.resources.getStringArray(R.array.livetv_texts)
            if (en.size != local.size) emptyMap()
            else HashMap<String, String>(en.size * 2).apply {
                for (i in en.indices) if (en[i] != local[i] && local[i].isNotEmpty()) put(en[i], local[i])
            }
        }.getOrDefault(emptyMap())
        upper = HashMap<String, String>(map.size * 2).apply {
            for ((en, local) in map) { val k = en.uppercase(); if (k !in this) put(k, local.uppercase(locale)) }
        }
        builtFor = tag
    }

    /**
     * [text] in the device's language, when it's one of Live TV's own words. A menu item that
     * opens another step ends in "…" ("Rename channel…"): it's found without it too. Headings
     * put in capitals before they're shown ("THIS SHOW") are found in capitals.
     */
    fun tr(text: String): String = map[text]
        ?: text.takeIf { it.length > 1 && it.endsWith('…') }?.let { t -> map[t.dropLast(1)]?.let { "$it…" } }
        ?: text.takeIf { it != it.lowercase() && it == it.uppercase() }?.let { upper[it] }
        ?: text

    /**
     * [template] in the device's language with {0}, {1}… replaced by [args], for texts with a
     * number or a name in them ("Copied {0} channels to {1}"): the translation can put them
     * where its own word order needs them.
     */
    fun trf(template: String, vararg args: Any?): String =
        args.foldIndexed(tr(template)) { i, s, a -> s.replace("{$i}", a.toString()) }
}

/** This Live TV word in the device's language (see [LiveTvTexts]). */
internal fun String.tr(): String = LiveTvTexts.tr(this)
