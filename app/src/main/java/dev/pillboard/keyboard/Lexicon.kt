package dev.pillboard.keyboard

import java.io.InputStream

/** Start-of-sentence marker, used as the "previous word" in next-word tables. */
internal const val BOS = "^"

/**
 * One built-in language: a frequency-weighted word list plus seed next-word tables, parsed from assets/dict/<code>.txt.
 * Pure Kotlin (no Android types), so the whole suggestion engine can be tested on a plain JVM.
 */
internal class Lexicon private constructor(
    val code: String,
    /** Alphabetical, so a prefix is one contiguous range. */
    val words: Array<String>,
    /** ln P(word | this language), parallel to [words]. */
    private val logp: FloatArray,
    private val index: HashMap<String, Int>,
    private val next: HashMap<String, Array<String>>,
    /** The most common words first: filler when nothing better predicts the next word. */
    val top: Array<String>,
    /** Positions in [words] grouped by word length, for typo search. */
    private val byLen: Array<IntArray>,
) {
    fun has(w: String): Boolean = index.containsKey(w)

    /** ln P(w | language); [ABSENT] for words this language does not know (same floor for every language). */
    fun logP(w: String): Float = index[w]?.let { logp[it] } ?: ABSENT

    fun logPAt(i: Int): Float = logp[i]

    fun successors(prev: String): Array<String>? = next[prev]

    fun withLength(len: Int): IntArray = if (len in byLen.indices) byLen[len] else NONE

    /** Positions of every word that starts with [prefix]. */
    fun prefixRange(prefix: String): IntRange {
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < prefix) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < words.size && words[end].startsWith(prefix)) end++
        return lo until end
    }

    companion object {
        const val ABSENT = -13.8f
        private val NONE = IntArray(0)
        private val SPACES = Regex("\\s+")
        private const val MAX_LEN = 24

        fun norm(s: String): String = s.lowercase().replace('’', '\'')

        fun validWord(w: String): Boolean =
            w.length in 1..MAX_LEN && w[0] != '\'' && w.all { it.isLetter() || it == '\'' }

        fun parse(code: String, input: InputStream): Lexicon {
            val ws = ArrayList<String>()
            val wt = ArrayList<Float>()
            val seen = HashSet<String>()
            val next = HashMap<String, Array<String>>()
            var inBigrams = false
            input.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                if (line.startsWith("@")) { inBigrams = line.startsWith("@bi"); return@forEachLine }
                if (inBigrams) {
                    val colon = line.indexOf(':')
                    if (colon <= 0) return@forEachLine
                    val prev = norm(line.substring(0, colon).trim())
                    val succ = line.substring(colon + 1).trim().split(SPACES).map { norm(it) }.filter { it.isNotEmpty() }
                    if (succ.isNotEmpty()) next[prev] = succ.toTypedArray()
                } else {
                    for (tok in line.split(SPACES)) {
                        if (tok.isEmpty()) continue
                        val colon = tok.indexOf(':')
                        val w = norm(if (colon > 0) tok.substring(0, colon) else tok)
                        if (!validWord(w) || !seen.add(w)) continue
                        val given = if (colon > 0) tok.substring(colon + 1).toFloatOrNull() else null
                        // rank-based Zipf weight unless a real count was supplied
                        ws.add(w); wt.add(given ?: (1_000_000f / (ws.size + 7f)))
                    }
                }
            }
            val total = wt.sum().coerceAtLeast(1f)
            val order = ws.indices.sortedBy { ws[it] }
            val words = Array(order.size) { ws[order[it]] }
            val logp = FloatArray(order.size) { Math.log((wt[order[it]] / total).toDouble()).toFloat() }
            val index = HashMap<String, Int>(words.size * 2)
            words.forEachIndexed { i, w -> index[w] = i }
            val top = order.indices.sortedByDescending { logp[it] }.take(60).map { words[it] }.toTypedArray()
            val lens = Array(MAX_LEN + 1) { ArrayList<Int>() }
            words.forEachIndexed { i, w -> lens[w.length].add(i) }
            return Lexicon(code, words, logp, index, next, top, Array(lens.size) { lens[it].toIntArray() })
        }
    }
}

/** Typo distance tuned for a phone keyboard: a slip onto a neighbouring key and a swapped pair are cheap. */
internal object Fuzzy {
    const val INF = Float.MAX_VALUE
    private const val NEAR_SUB = 0.6f
    private const val SWAP = 0.7f
    private const val DOUBLE = 0.6f

    private val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val offsets = floatArrayOf(0f, 0.25f, 0.75f)
    private val kx = FloatArray(26)
    private val ky = IntArray(26)

    init {
        rows.forEachIndexed { r, s -> s.forEachIndexed { c, ch -> kx[ch - 'a'] = c + offsets[r]; ky[ch - 'a'] = r } }
    }

    /** Neighbouring keys on a QWERTY layout. */
    fun near(a: Char, b: Char): Boolean {
        if (a !in 'a'..'z' || b !in 'a'..'z' || a == b) return false
        val i = a - 'a'
        val j = b - 'a'
        return Math.abs(ky[i] - ky[j]) <= 1 && Math.abs(kx[i] - kx[j]) <= 1.0f
    }

    /** Scratch matrix; the engine only ever runs on the main thread. */
    private val m = FloatArray(33 * 33)

    /** Weighted Damerau-Levenshtein between lowercase strings, or [INF] once it is certain to exceed [max]. */
    fun cost(a: String, b: String, max: Float): Float {
        val n = a.length
        val l = b.length
        if (n > 32 || l > 32 || Math.abs(n - l) * DOUBLE > max) return INF
        val w = l + 1
        for (j in 0..l) m[j] = j.toFloat()
        var prevMin = 0f
        for (i in 1..n) {
            m[i * w] = i.toFloat()
            var rowMin = m[i * w]
            val ca = a[i - 1]
            for (j in 1..l) {
                val cb = b[j - 1]
                var best = m[(i - 1) * w + j - 1] + if (ca == cb) 0f else if (near(ca, cb)) NEAR_SUB else 1f
                // a missing or extra copy of a doubled letter (helo / hello) is the most common slip of all
                val del = m[(i - 1) * w + j] + if ((i > 1 && ca == a[i - 2]) || (i < n && ca == a[i])) DOUBLE else 1f
                if (del < best) best = del
                val ins = m[i * w + j - 1] + if ((j > 1 && b[j - 1] == b[j - 2]) || (j < l && b[j - 1] == b[j])) DOUBLE else 1f
                if (ins < best) best = ins
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) {
                    val t = m[(i - 2) * w + j - 2] + SWAP
                    if (t < best) best = t
                }
                m[i * w + j] = best
                if (best < rowMin) rowMin = best
            }
            // a swap jumps over one row, so give up only when two rows in a row are already too expensive
            if (i > 1 && rowMin > max && prevMin > max) return INF
            prevMin = rowMin
        }
        val d = m[n * w + l]
        return if (d > max) INF else d
    }
}

/** Light Indonesian affix stripping, so valid derived words (mengerjakan, kerjaannya) are not mistaken for typos. */
internal object IdStem {
    private val particles = arrayOf("nya", "lah", "kah", "pun", "ku", "mu")
    private val suffixes = arrayOf("kan", "an", "i")

    /** Prefix to the letters that melt away when it attaches (meny+sapu = menyapu), "" when nothing does. */
    private val prefixes = arrayOf(
        "meny" to "s", "meng" to "", "meng" to "k", "mem" to "", "mem" to "p", "men" to "", "men" to "t", "me" to "",
        "peny" to "s", "peng" to "", "peng" to "k", "pem" to "", "pem" to "p", "pen" to "", "pen" to "t", "pe" to "",
        "ber" to "", "bel" to "", "be" to "", "ter" to "", "te" to "", "per" to "", "pel" to "", "di" to "", "ke" to "", "se" to "",
    )

    fun known(w: String, lex: Lexicon): Boolean {
        if (w.length < 5) return false
        val a = ArrayList<String>(3)
        a.add(w)
        for (p in particles) if (w.endsWith(p) && w.length - p.length >= 3) { a.add(w.dropLast(p.length)); break }
        val b = ArrayList<String>(6)
        for (s in a) {
            b.add(s)
            for (x in suffixes) if (s.endsWith(x) && s.length - x.length >= 3) { b.add(s.dropLast(x.length)); break }
        }
        for (s in b) {
            if (s !== w && lex.has(s)) return true
            for ((p, restore) in prefixes) {
                if (!s.startsWith(p) || s.length - p.length < 2) continue
                val root = restore + s.substring(p.length)
                if (root.length >= 3 && lex.has(root)) return true
            }
        }
        return false
    }
}
