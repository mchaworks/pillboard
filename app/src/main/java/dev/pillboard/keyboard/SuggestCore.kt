package dev.pillboard.keyboard

/**
 * The suggestion engine, with no Android types so it can run (and be tested) on a plain JVM.
 *
 * Every built-in dictionary is active at once. Which language is being written is never read from the system or a
 * setting: it is inferred from the words already in the field (plus the person's long-term habit), as a soft mix,
 * so a message that switches between languages still gets suggestions from both.
 *
 * Score of a candidate = ln( (1 - b) * P(word) + b * P(word | previous word) ), where P(word) blends the language
 * mix with what the person actually types, and P(word | previous) blends their own word pairs with the seed tables.
 * Typo candidates pay a penalty per unit of keyboard-aware edit distance. Main thread only.
 */
internal class SuggestCore(private val lex: List<Lexicon>, val user: UserModel) {

    class Ctx(
        /** The word being typed, empty right after a space. */
        val word: String,
        /** Word before it in this sentence (lowercase), or [BOS]. */
        val prev: String,
        /** The last few words, for language inference. */
        val recent: List<String>,
        /** Inside a number, address or handle: no suggestions at all. */
        val blocked: Boolean = false,
    )

    enum class Kind { COMPLETE, CORRECT, NEXT, LITERAL }

    class Cand(val text: String, val kind: Kind) {
        /** What the strip shows: the literal as typed is quoted, like iOS. */
        val label: String get() = if (kind == Kind.LITERAL) "“$text”" else text
    }

    class Result(private val left: Cand?, private val center: Cand?, private val right: Cand?) {
        /** 0 = left, 1 = centre (the lead), 2 = right. */
        fun slot(i: Int): Cand? = when (i) { 0 -> left; 1 -> center; else -> right }
    }

    private class Raw(val lower: String, val kind: Kind, val score: Float, val cost: Float)

    companion object {
        private val TOKEN = Regex("[\\p{L}']+")
        private val EMPTY = Result(null, null, null)
        private const val HISTORY = 120
        private const val BIGRAM_SHARE = 0.55f
        private const val COST_WEIGHT = 2.2f
        private val HARMONIC = FloatArray(65).also { h -> for (n in 1..64) h[n] = h[n - 1] + 1f / (n + 0.5f) }

        /** Explicit shortcuts, applied on space (the language mix decides whether a clash with a real word counts). */
        val FIXES = mapOf(
            "teh" to "the", "adn" to "and", "thier" to "their", "becuase" to "because", "recieve" to "receive",
            "definately" to "definitely", "wierd" to "weird", "freind" to "friend", "alot" to "a lot",
            "tommorow" to "tomorrow", "dont" to "don't", "cant" to "can't", "wont" to "won't", "didnt" to "didn't",
            "doesnt" to "doesn't", "isnt" to "isn't", "wasnt" to "wasn't", "couldnt" to "couldn't",
            "shouldnt" to "shouldn't", "wouldnt" to "wouldn't", "youre" to "you're", "theyre" to "they're",
            "thats" to "that's", "im" to "I'm", "ive" to "I've", "i" to "I",
        )
    }

    // ---- reading the text around the caret ----

    /** Parse the text before the cursor (a short tail is enough). */
    fun context(before: CharSequence): Ctx {
        val s = before.toString().replace('’', '\'')
        var i = s.length
        while (i > 0 && (s[i - 1].isLetter() || s[i - 1] == '\'')) i--
        val word = s.substring(i).trimStart('\'')                     // a leading quote is punctuation, not part of the word
        val head = s.substring(0, s.length - word.length)
        val glue = head.lastOrNull()
        if (glue != null && (glue.isDigit() || glue == '@' || glue == '/' || glue == '\\' || glue == '_' || glue == '#')) {
            return Ctx(word, BOS, emptyList(), blocked = true)
        }
        val truncated = s.length >= HISTORY
        val rest = head.trimEnd(' ', '\t', ' ')
        val cut = rest.indexOfLast { isStop(it) }
        val sentence = if (cut >= 0) rest.substring(cut + 1) else rest
        var sentenceTokens = tokens(sentence)
        if (truncated && cut < 0 && sentenceTokens.isNotEmpty()) sentenceTokens = sentenceTokens.drop(1)   // first one may be cut in half
        val atStart = rest.isEmpty() || isStop(rest.last())
        val prev = if (atStart) BOS else sentenceTokens.lastOrNull() ?: BOS
        var recent = tokens(rest)
        if (truncated && recent.isNotEmpty()) recent = recent.drop(1)
        return Ctx(word, prev, recent.takeLast(12))
    }

    private fun isStop(c: Char) = c == '.' || c == '!' || c == '?' || c == '\n' || c == '…'

    private fun tokens(s: String): List<String> =
        TOKEN.findAll(s).map { it.value.trim('\'').lowercase() }.filter { it.isNotEmpty() }.toList()

    // ---- language mix ----

    /** Soft weight of each dictionary for the text so far; never below a small floor, so a second language stays in play. */
    fun mix(recent: List<String>): FloatArray {
        val n = lex.size
        if (n == 0) return FloatArray(0)
        if (n == 1) return floatArrayOf(1f)
        val ll = FloatArray(n)
        var decay = 1f
        for (k in recent.indices.reversed()) {               // newest word counts most
            for (i in 0 until n) ll[i] += decay * lex[i].logP(recent[k])
            decay *= 0.88f
        }
        var priorSum = 0f
        for (l in lex) priorSum += user.lang[l.code] ?: (1f / n)
        for (i in 0 until n) {
            val p = (user.lang[lex[i].code] ?: (1f / n)) / priorSum
            ll[i] += Math.log(p.coerceAtLeast(0.02f).toDouble()).toFloat()
        }
        val top = ll.max()
        val w = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) { w[i] = Math.exp(((ll[i] - top) * 0.5f).toDouble()).toFloat().coerceAtLeast(0.04f); sum += w[i] }
        for (i in 0 until n) w[i] /= sum
        return w
    }

    // ---- scoring ----

    private fun pStatic(w: String, mix: FloatArray): Float {
        var p = 0f
        for (i in lex.indices) p += mix[i] * Math.exp(lex[i].logP(w).toDouble()).toFloat()
        return p
    }

    private fun uni(w: String, mix: FloatArray): Float {
        val lambda = user.total / (user.total + 400f) * 0.7f
        val pu = if (user.total > 0f) user.count(w) / user.total else 0f
        return (1f - lambda) * pStatic(w, mix) + lambda * pu
    }

    /** P(w | prev) from the person's own pairs and the seed tables, or 0 when neither knows the previous word. */
    private fun bigram(prev: String, w: String, mix: FloatArray): Float {
        var seed = 0f
        for (i in lex.indices) {
            val succ = lex[i].successors(prev) ?: continue
            val pos = succ.indexOf(w)
            if (pos >= 0) seed += mix[i] * 0.8f / (pos + 1.5f) / HARMONIC[minOf(succ.size, 64)]
        }
        val row = user.bi[prev] ?: return seed
        val s = user.biSum[prev] ?: 0f
        if (s <= 0f) return seed
        val share = s / (s + 4f)
        return share * ((row[w] ?: 0f) / s) + (1f - share) * seed
    }

    private fun score(w: String, prev: String, mix: FloatArray): Float {
        val u = uni(w, mix)
        val b = bigram(prev, w, mix)
        val p = if (b > 0f) (1f - BIGRAM_SHARE) * u + BIGRAM_SHARE * b else u
        return Math.log((p + 1e-9f).toDouble()).toFloat()
    }

    // ---- knowledge ----

    /** Valid in any built-in language, used often enough by the person, or a regular derivation of such a word. */
    fun isKnown(w: String): Boolean {
        if (user.known(w)) return true
        for (x in lex) if (x.has(w)) return true
        if (w.length > 3 && w.endsWith("'s") && isKnown(w.dropLast(2))) return true
        for (x in lex) if (x.code == "id" && IdStem.known(w, x)) return true
        return false
    }

    private fun surfaceOf(lower: String): String {
        if (lower == "i" || lower.startsWith("i'")) return "I" + lower.substring(1)
        return user.surface(lower)
    }

    /** Dress a candidate the way the person is typing: Capitalised, ALL CAPS, or as the dictionary has it. */
    private fun shape(s: String, typed: String, sentenceStart: Boolean): String = when {
        typed.length >= 2 && typed.any { it.isUpperCase() } && typed.all { it.isUpperCase() || it == '\'' } -> s.uppercase()
        typed.isNotEmpty() && typed[0].isUpperCase() -> s.replaceFirstChar { it.uppercase() }
        typed.isEmpty() && sentenceStart -> s.replaceFirstChar { it.uppercase() }
        else -> s
    }

    // ---- suggestions ----

    fun suggest(c: Ctx): Result {
        if (c.blocked) return EMPTY
        val mix = mix(c.recent)
        val lw = c.word.lowercase()
        return if (lw.isEmpty()) nextWord(c, mix) else inWord(c, lw, mix)
    }

    private fun nextWord(c: Ctx, mix: FloatArray): Result {
        val found = HashMap<String, Raw>()
        fun add(w: String) {
            if (w == c.prev || found.containsKey(w)) return
            found[w] = Raw(w, Kind.NEXT, score(w, c.prev, mix), 0f)
        }
        user.bi[c.prev]?.entries?.sortedByDescending { it.value }?.take(6)?.forEach { add(it.key) }
        for (i in lex.indices) if (mix[i] >= 0.08f) lex[i].successors(c.prev)?.take(8)?.forEach { add(it) }
        if (found.size < 6) {
            for (i in lex.indices) if (mix[i] >= 0.08f) for (w in lex[i].top.take(8)) add(w)
            user.topWords(5).forEach { add(it) }
        }
        return layout(found.values, c.word, c.prev == BOS, literal = false)
    }

    private fun inWord(c: Ctx, lw: String, mix: FloatArray): Result {
        val known = isKnown(lw)
        val found = HashMap<String, Raw>()
        fun add(w: String, kind: Kind, penalty: Float = 0f, cost: Float = 0f) {
            if (kind == Kind.COMPLETE && w == c.prev) return          // "mau" right after "mau" is rarely what's wanted
            val s = score(w, c.prev, mix) - penalty
            val old = found[w]
            if (old == null || s > old.score) found[w] = Raw(w, kind, s, cost)
        }
        // completions: the best few words per dictionary that start with what was typed, plus the person's own words
        for (x in lex) {
            val r = x.prefixRange(lw)
            if (r.isEmpty()) continue
            r.asSequence().filter { x.words[it] != lw }.sortedByDescending { x.logPAt(it) }.take(5)
                .forEach { add(x.words[it], Kind.COMPLETE) }
        }
        user.words.subMap(lw, true, lw + '￿', true).entries.asSequence().filter { it.key != lw }
            .sortedByDescending { it.value.n }.take(5).forEach { add(it.key, Kind.COMPLETE) }
        // typos: only for a word nothing recognises, and only while few completions already fit
        if (!known && lw.length >= 3 && found.size < 3) {
            for ((w, cost) in nearWords(lw, maxCost(lw.length))) add(w, Kind.CORRECT, COST_WEIGHT * cost, cost)
        }
        if (!known && !user.rejected.contains(lw)) FIXES[lw]?.let { add(it.lowercase(), Kind.CORRECT, -4f, 0.5f) }
        return layout(found.values, c.word, c.prev == BOS, literal = !known)
    }

    private fun maxCost(len: Int) = when { len <= 3 -> 1.0f; len <= 5 -> 1.4f; else -> 2.1f }

    /** Every known word within [max] keyboard-aware edits of [lw], with its distance. */
    private fun nearWords(lw: String, max: Float): List<Pair<String, Float>> {
        val out = HashMap<String, Float>()
        val lo = maxOf(2, lw.length - 2)
        for (x in lex) for (len in lo..lw.length + 2) for (idx in x.withLength(len)) {
            val w = x.words[idx]
            if (w == lw) continue
            val d = Fuzzy.cost(lw, w, max)
            if (d != Fuzzy.INF && d < (out[w] ?: Fuzzy.INF)) out[w] = d
        }
        for ((w, e) in user.words) {
            if (e.n < 2f || w == lw || Math.abs(w.length - lw.length) > 2) continue
            val d = Fuzzy.cost(lw, w, max)
            if (d != Fuzzy.INF && d < (out[w] ?: Fuzzy.INF)) out[w] = d
        }
        return out.map { it.key to it.value }
    }

    /** Put the ranked candidates into the three strip slots: [left, centre = lead, right]. */
    private fun layout(raws: Collection<Raw>, typed: String, sentenceStart: Boolean, literal: Boolean): Result {
        val ranked = raws.sortedByDescending { it.score }
        val seen = HashSet<String>()
        val cands = ArrayList<Cand>(3)
        for (r in ranked) {
            val text = shape(surfaceOf(r.lower), typed, sentenceStart)
            if (!seen.add(text.lowercase())) continue
            cands.add(Cand(text, r.kind))
            if (cands.size == 3) break
        }
        val lead = cands.firstOrNull()
        if (literal && typed.length >= 2) {
            if (lead == null) return Result(null, Cand(typed, Kind.LITERAL), null)
            // the lead is a fix for what was typed: offer the literal beside it so it is never forced on you
            if (lead.kind == Kind.CORRECT) return Result(Cand(typed, Kind.LITERAL), lead, cands.getOrNull(1))
        }
        return Result(cands.getOrNull(1), lead, cands.getOrNull(2))
    }

    // ---- auto-correct ----

    /** What to put instead of the word just typed when space is pressed, or null to leave it alone. */
    fun autoCorrection(c: Ctx): String? {
        if (c.blocked || c.word.isEmpty()) return null
        val typed = c.word
        val lw = typed.lowercase()
        if (user.rejected.contains(lw)) return null
        val sentenceStart = c.prev == BOS
        val mix = mix(c.recent)
        FIXES[lw]?.let { fix ->
            // an explicit shortcut, unless the word is genuinely common in the language being written ("teh" is tea in Indonesian)
            val real = user.known(lw) || lex.indices.any { mix[it] >= 0.25f && lex[it].has(lw) }
            if (lw == "i" || !real) return shape(fix, typed, sentenceStart).takeIf { it != typed }
        }
        if (typed.length < 3 || typed.contains('\'') || isKnown(lw)) return null
        if (typed.all { it.isUpperCase() }) return null                      // acronym
        if (typed[0].isUpperCase() && !sentenceStart) return null            // a name in the middle of a sentence
        // short words get only a swap or a neighbouring-key slip: "bgt" must never become "but"
        val near = nearWords(lw, when { typed.length <= 3 -> 0.8f; typed.length == 4 -> 1.0f; else -> 1.25f })
        if (near.isEmpty()) return null
        val ranked = near.map { (w, d) -> Raw(w, Kind.CORRECT, score(w, c.prev, mix) - COST_WEIGHT * d, d) }
            .sortedByDescending { it.score }
        val best = ranked[0]
        if (uni(best.lower, mix) < 5e-5f) return null                        // too rare to be a safe guess
        if (ranked.size > 1) {
            val second = ranked[1]
            if (best.cost >= second.cost && best.score - second.score < 0.5f) return null   // ambiguous
        }
        // a stem of a longer word ("hel", "gonn") is more likely unfinished than wrong
        for (x in lex) for (i in x.prefixRange(lw)) {
            val w = x.words[i]
            if (w != best.lower && w.length <= lw.length + 3) return null
        }
        return shape(surfaceOf(best.lower), typed, sentenceStart)
    }

    // ---- learning ----

    /** A finished word, as the person left it. [weight] 2 = they chose it on purpose (tapped the literal). */
    fun learn(c: Ctx, surface: String, weight: Float = 1f) {
        user.learn(surface, c.prev, weight)
        if (lex.size > 1) user.observeLang(lex.map { it.code }, mix(c.recent))
    }

    fun undoCorrection(typed: String) = user.reject(typed.lowercase())
}
