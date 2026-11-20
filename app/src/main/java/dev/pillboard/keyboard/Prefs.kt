package dev.pillboard.keyboard

import android.content.Context
import android.graphics.Color

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pillboard", Context.MODE_PRIVATE)
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun dynamic(c: Context) = bool(c, "dynamic", true) && android.os.Build.VERSION.SDK_INT >= 31
    /** 0..PRESETS.lastIndex = preset, PRESETS.size = custom (hue slider). */
    fun preset(c: Context) = int(c, "preset", 0).coerceIn(0, PRESETS.size)
    fun hue(c: Context) = int(c, "hue", 260).coerceIn(0, 359)
    fun customColor(c: Context): Int = Color.HSVToColor(floatArrayOf(hue(c).toFloat(), 0.55f, 0.85f))
    fun seedColor(c: Context): Int = preset(c).let { if (it >= PRESETS.size) customColor(c) else PRESETS[it].second }

    /** name to seed color; used when Material You colors is off */
    val PRESETS = listOf(
        "Lavender" to 0xFF6750A4.toInt(),
        "Mint" to 0xFF2E7D6B.toInt(),
        "Sunset" to 0xFFE8743B.toInt(),
        "Midnight" to 0xFF5C6BC0.toInt(),
        "Rose" to 0xFFD81B60.toInt(),
        "Ocean" to 0xFF0288D1.toInt(),
    )
    fun clear(c: Context) = sp(c).edit().clear().apply()
}
