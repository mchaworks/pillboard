package dev.pillboard.keyboard

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pillboard", Context.MODE_PRIVATE)
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun dynamic(c: Context) = bool(c, "dynamic", true) && android.os.Build.VERSION.SDK_INT >= 31
    fun preset(c: Context) = int(c, "preset", 0).coerceIn(0, PRESETS.lastIndex)

    /** name to seed color; used when Material You colors is off */
    val PRESETS = listOf(
        "Lavender" to 0xFF6750A4.toInt(),
        "Mint" to 0xFF2E7D6B.toInt(),
        "Sunset" to 0xFFE8743B.toInt(),
        "Midnight" to 0xFF5C6BC0.toInt(),
    )
    fun clear(c: Context) = sp(c).edit().clear().apply()
}
