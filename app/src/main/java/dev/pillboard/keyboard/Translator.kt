package dev.pillboard.keyboard

import android.content.Context
import android.os.LocaleList
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Languages offered as translation targets. System languages (Settings > System > Languages) come first. */
internal object Languages {
    class Lang(val code: String, val name: String)

    private val CODES = listOf(
        "en", "id", "ms", "zh-CN", "zh-TW", "ja", "ko", "ar", "hi", "bn", "ta", "ur", "fa", "th", "vi", "tl", "sw",
        "es", "fr", "de", "it", "pt", "nl", "sv", "pl", "cs", "ro", "hu", "el", "ru", "uk", "tr", "he",
    )

    /** Java still reports some ISO-639 languages under their old codes (id = "in", he = "iw"). */
    private fun norm(l: Locale): String = when (val c = l.language) {
        "in" -> "id"; "iw" -> "he"; "fil" -> "tl"; "ji" -> "yi"
        "zh" -> if (l.script == "Hant" || l.country == "TW" || l.country == "HK") "zh-TW" else "zh-CN"
        else -> c
    }

    fun nameOf(c: Context, code: String): String = when (code) {
        "zh-CN" -> "Chinese (Simplified)"
        "zh-TW" -> "Chinese (Traditional)"
        else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.uppercase() }.ifEmpty { code }
    }

    private fun systemCodes(): List<String> {
        val ll = LocaleList.getDefault()
        return (0 until ll.size()).map { norm(ll[it]) }.distinct().filter { it in CODES }
    }

    fun ordered(c: Context): List<Lang> {
        val sys = systemCodes()
        val rest = CODES.filter { it !in sys }.sortedBy { nameOf(c, it) }
        return (sys + rest).map { Lang(it, nameOf(c, it)) }
    }

    /** First system language: typing English on an Indonesian phone translates to Indonesian, and the reverse is a tap away. */
    fun defaultTarget(c: Context): String = systemCodes().firstOrNull() ?: "en"
}

/**
 * Online translation. Strictly opt-in (Settings > Translation) and only called while the keyboard's translate mode is
 * open; the keyboard never calls it in password fields. Blocking: always call from a worker thread.
 */
internal object Translator {
    enum class Provider(val id: String, val label: String) {
        GOOGLE("google", "Google Translate (unofficial endpoint)"),
        MYMEMORY("mymemory", "MyMemory (free, limited per day)"),
        LIBRE("libre", "LibreTranslate (your own server)");
        companion object { fun of(id: String) = values().firstOrNull { it.id == id } ?: GOOGLE }
    }

    class Out(val text: String, val detected: String?)

    fun provider(c: Context): Provider = Provider.of(Prefs.str(c, "tr_provider", Provider.GOOGLE.id))

    @Throws(IOException::class)
    fun translate(c: Context, text: String, target: String): Out = when (provider(c)) {
        Provider.GOOGLE -> google(text.take(1500), target)
        Provider.MYMEMORY -> myMemory(text.take(450), target)
        Provider.LIBRE -> libre(c, text.take(3000), target)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun google(text: String, target: String): Out {
        val raw = http("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=${enc(target)}&dt=t&q=${enc(text)}")
        val root = JSONArray(raw)
        val parts = root.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONArray(i).optString(0, ""))
        val det = if (root.length() > 2 && !root.isNull(2)) root.getString(2) else null
        if (sb.isEmpty()) throw IOException("Empty result")
        return Out(sb.toString(), det)
    }

    private fun myMemory(text: String, target: String): Out {
        val j = JSONObject(http("https://api.mymemory.translated.net/get?q=${enc(text)}&langpair=Autodetect%7C${enc(target)}"))
        val status = j.optInt("responseStatus", 200)
        if (status != 200) throw IOException(if (status == 429) "Daily limit reached" else "Service error ($status)")
        val d = j.getJSONObject("responseData")
        val det = d.optString("detectedLanguage", "").ifEmpty { null }
        return Out(d.getString("translatedText"), det)
    }

    private fun libre(c: Context, text: String, target: String): Out {
        val base = Prefs.str(c, "tr_url", "").trim().trimEnd('/')
        if (base.isEmpty()) throw IOException("Set the server URL in Settings")
        if (!base.startsWith("https://")) throw IOException("Server URL must start with https://")
        val body = JSONObject().put("q", text).put("source", "auto")
            .put("target", when (target) { "zh-CN" -> "zh"; "zh-TW" -> "zt"; else -> target })   // LibreTranslate's own codes
            .put("format", "text")
        Prefs.str(c, "tr_key", "").takeIf { it.isNotEmpty() }?.let { body.put("api_key", it) }
        val j = JSONObject(http("$base/translate", body.toString(), json = true))
        j.optString("error", "").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
        return Out(j.getString("translatedText"), j.optJSONObject("detectedLanguage")?.optString("language"))
    }

    private fun http(url: String, body: String? = null, json: Boolean = false): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = 9000
            conn.setRequestProperty("User-Agent", "Pillboard/1.0")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", if (json) "application/json; charset=UTF-8" else "application/x-www-form-urlencoded; charset=UTF-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(if (code == 429) "Rate limited, try again later" else "Server error ($code)")
            return txt
        } finally { conn.disconnect() }
    }
}
