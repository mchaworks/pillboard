package dev.pillboard.keyboard

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pillboard", Context.MODE_PRIVATE)
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
}
