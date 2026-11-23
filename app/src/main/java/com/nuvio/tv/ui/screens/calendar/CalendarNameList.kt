package com.nuvio.tv.ui.screens.calendar

// From Nuvio Arc (ysosrs123/NuvioTV-Fork, GPL-3.0), ui/screens/calendar/CalendarFormats.kt.

/** "A, B and C" in the user's language. */
fun calendarNameList(names: List<String>, locale: java.util.Locale, join: (List<String>) -> String): String {
    if (names.size <= 1) return names.firstOrNull().orEmpty()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        runCatching { android.icu.text.ListFormatter.getInstance(locale).format(names) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return join(names)
}
