package dev.pillboard.keyboard

/** Tiny built-in prefix suggester (no learning yet). */
object Suggestions {
    private val words = ("the be to of and a in that have it for not on with he as you do at this but his by from they we say her she or an " +
        "will my one all would there their what so up out if about who get which go me when make can like time no just him know take people " +
        "into year your good some could them see other than then now look only come its over think also back after use two how our work first " +
        "well way even new want because any these give day most us hello thanks thank please okay yes love today tomorrow tonight going really " +
        "where why need here still never always something anything tell call home night morning great sorry friend right maybe where were was " +
        "are is am been being did does done doing had has having should very much many more best better little long thing things again " +
        "before between both through during without around another every nothing everyone someone together tomorrow yesterday sometimes " +
        "probably actually definitely already almost enough different important wait wanna gonna").split(" ").distinct()

    fun forPrefix(p: String): List<String> {
        if (p.isEmpty()) return listOf("I", "The", "I'm")
        val l = p.lowercase()
        val m = words.filter { it.startsWith(l) && it != l }.take(3)
        val out = if (m.isEmpty()) listOf(p) else m
        return if (p[0].isUpperCase()) out.map { it.replaceFirstChar { c -> c.uppercase() } } else out
    }
}
