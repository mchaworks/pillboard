package dev.pillboard.keyboard

import android.content.Context

/** Features the top-right strip button can carry. Swipe up/down on the button cycles them; hold shows them all. */
internal enum class StripFeature(val id: String, val label: String, val icon: Int) {
    TRANSLATE("translate", "Translate", R.drawable.ic_translate),
    CLIPBOARD("clipboard", "Clipboard", R.drawable.ic_clipboard),
    TEXT_EDIT("text_edit", "Text editing", R.drawable.ic_text_edit),
    SETTINGS("settings", "Settings", R.drawable.ic_settings);

    companion object {
        /** Features usable right now (Translate only when it is switched on in Settings). Order = swipe order. */
        fun available(c: Context): List<StripFeature> =
            values().filter { it != TRANSLATE || Prefs.bool(c, "translate", false) }

        fun current(c: Context): StripFeature {
            val l = available(c)
            return l.firstOrNull { it.id == Prefs.str(c, "strip_feature", "") } ?: l.first()
        }
    }
}
