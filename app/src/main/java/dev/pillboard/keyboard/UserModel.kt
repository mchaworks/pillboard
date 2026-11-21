package dev.pillboard.keyboard

import java.io.Reader
import java.io.Writer
import java.util.TreeMap

/**
 * What the keyboard has learned from the person typing: their words, which word follows which, how they capitalise,
 * which language they usually write, and corrections they refused. Lives on this device only.
 * Counts halve once the total gets large, so old habits fade and the model stays small. Main thread only.
 */
internal class UserModel {
    class Word(var n: Float) {
        /** Observations in the middle of a sentence, and how many of those were capitalised (names). */
        var mid = 0f
        var cap = 0f
        /** Exact spelling when it has inner capitals (iPhone, YouTube). */
        var form: String? = null
    }

    val words = TreeMap<String, Word>()
    /** previous word (or [BOS]) to next word to count */
    val bi = HashMap<String, HashMap<String, Float>>()
    val biSum = HashMap<String, Float>()
    /** Long-term share of each language in what this person writes. */
    val lang = HashMap<String, Float>()
    /** Words the person put back after an auto-correction: never correct these again. */
    val rejected = HashSet<String>()
    var total = 0f
    var dirty = false

    fun count(w: String): Float = words[w]?.n ?: 0f

    /** Used at least twice, or confirmed by the person: not a typo any more. */
    fun known(w: String): Boolean = count(w) >= 2f

    fun surface(w: String): String {
        val e = words[w] ?: return w
        e.form?.let { return it }
        return if (e.mid >= 2f && e.cap >= 0.7f * e.mid) w.replaceFirstChar { it.uppercase() } else w
    }

    fun topWords(k: Int): List<String> = words.entries.sortedByDescending { it.value.n }.take(k).map { it.key }

    fun learn(surface: String, prev: String, weight: Float = 1f) {
        val w = surface.lowercase()
        if (w.length < 2 || w.length > 24 || !w.any { it.isLetter() }) return
        val e = words.getOrPut(w) { Word(0f) }
        e.n += weight
        total += weight
        if (prev.isNotEmpty() && prev != BOS) {
            e.mid += 1f
            if (surface[0].isUpperCase()) e.cap += 1f
        }
        if (surface.length > 1 && surface.drop(1).any { it.isUpperCase() } && !surface.all { it.isUpperCase() }) e.form = surface
        if (prev.isNotEmpty()) {
            val row = bi.getOrPut(prev) { HashMap() }
            row[w] = (row[w] ?: 0f) + weight
            val sum = (biSum[prev] ?: 0f) + weight
            biSum[prev] = sum
            if (sum > 300f || row.size > 40) halveRow(prev)
        }
        dirty = true
        if (total > 8000f) fade()
        if (words.size > 6000) trim()
    }

    /** The person undid an auto-correction: keep their spelling, and never fix it again. */
    fun reject(w: String) {
        if (w.isEmpty()) return
        rejected.add(w)
        words.getOrPut(w) { Word(0f) }.n += 2f
        total += 2f
        dirty = true
    }

    fun observeLang(codes: List<String>, weights: FloatArray) {
        for (i in codes.indices) {
            val old = lang[codes[i]] ?: weights[i]
            lang[codes[i]] = old * 0.97f + weights[i] * 0.03f
        }
        dirty = true
    }

    fun clear() {
        words.clear(); bi.clear(); biSum.clear(); lang.clear(); rejected.clear(); total = 0f; dirty = true
    }

    private fun halveRow(prev: String) {
        val row = bi[prev] ?: return
        val it = row.entries.iterator()
        var sum = 0f
        while (it.hasNext()) {
            val e = it.next()
            e.setValue(e.value * 0.5f)
            if (e.value < 0.6f) it.remove() else sum += e.value
        }
        if (row.isEmpty()) { bi.remove(prev); biSum.remove(prev) } else biSum[prev] = sum
    }

    private fun fade() {
        val it = words.entries.iterator()
        var sum = 0f
        while (it.hasNext()) {
            val w = it.next().value
            w.n *= 0.5f; w.mid *= 0.5f; w.cap *= 0.5f
            if (w.n < 0.9f && w.form == null) it.remove() else sum += w.n
        }
        total = sum
        for (p in bi.keys.toList()) halveRow(p)
    }

    private fun trim() {
        val drop = words.entries.sortedBy { it.value.n }.take(words.size - 5000).map { it.key }
        drop.forEach { words.remove(it) }
        total = words.values.sumOf { it.n.toDouble() }.toFloat()
        if (bi.size > 4000) bi.entries.sortedBy { biSum[it.key] ?: 0f }.take(bi.size - 3000).map { it.key }
            .forEach { bi.remove(it); biSum.remove(it) }
    }

    fun write(out: Writer) {
        out.write("V1\n")
        for ((k, e) in words) out.write("W\t$k\t${e.n}\t${e.mid}\t${e.cap}\t${e.form ?: "-"}\n")
        for ((p, row) in bi) for ((n, c) in row) out.write("B\t$p\t$n\t$c\n")
        for ((c, v) in lang) out.write("L\t$c\t$v\n")
        for (r in rejected) out.write("R\t$r\n")
    }

    fun read(input: Reader) {
        clear()
        input.buffered().forEachLine { line ->
            try {
                val f = line.split('\t')
                when (f[0]) {
                    "W" -> words[f[1]] = Word(f[2].toFloat()).also {
                        it.mid = f[3].toFloat(); it.cap = f[4].toFloat(); it.form = f[5].takeIf { s -> s != "-" }
                    }
                    "B" -> bi.getOrPut(f[1]) { HashMap() }[f[2]] = f[3].toFloat()
                    "L" -> lang[f[1]] = f[2].toFloat()
                    "R" -> rejected.add(f[1])
                }
            } catch (_: Exception) { /* a damaged line is just skipped */ }
        }
        total = words.values.sumOf { it.n.toDouble() }.toFloat()
        for ((p, row) in bi) biSum[p] = row.values.sum()
        dirty = false
    }
}
