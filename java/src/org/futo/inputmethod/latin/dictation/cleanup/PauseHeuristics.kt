package org.futo.inputmethod.latin.dictation.cleanup

/**
 * Grammar-free rules for the one error that needs no model to spot: a "sentence" that ends on a
 * word no sentence can end on. Used as the cleaner when Claude is unavailable, and as a backstop
 * for the model's JOIN/BREAK decision. Deliberately conservative: it only ever removes a period,
 * and only after words in a short closed list.
 */
object PauseHeuristics {
    /** Words that cannot end an English sentence. */
    private val NEVER_FINAL = setOf(
        "the", "a", "an", "because", "and", "or", "of", "my", "your", "our", "their", "its",
        "than", "into", "onto", "from", "every", "very"
    )
    /** A one-word "sentence" made of one of these is a pause after a conjunction ("So. I'm gonna…"). */
    private val LONE_CONNECTIVE = setOf("so", "and", "but", "or", "because", "plus", "then", "also", "well")

    /** A "sentence" that is only one of these opening phrases is waiting for its main clause ("Other than that. Things are…"). */
    private val OPENERS = setOf(
        "other than that", "in that case", "for example", "for instance", "on top of that", "that being said",
        "having said that", "first of all", "at this point", "on the other hand", "as far as i can tell", "either way"
    )

    private fun bare(word: String) = word.lowercase().trim { !it.isLetterOrDigit() && it != '\'' }

    /** True if the text cannot be a finished sentence, so whatever follows must continue it. */
    fun endsUnfinished(context: String): Boolean {
        val t = context.trim()
        if (t.isEmpty()) return false
        val lastSentence = t.split(Regex("(?<=[.?!])\\s+")).last().trim()
        val words = lastSentence.split(Regex("\\s+")).map { bare(it) }.filter { it.isNotEmpty() }
        val last = words.lastOrNull() ?: return false
        if (last in NEVER_FINAL) return true
        if (words.joinToString(" ") in OPENERS && !t.endsWith(".") && !t.endsWith("?") && !t.endsWith("!")) return true
        // "…again. So" → the last sentence so far is just a connective
        return words.size == 1 && last in LONE_CONNECTIVE && !t.endsWith(".") && !t.endsWith("?") && !t.endsWith("!")
    }

    /**
     * Lowercases the first word of a continuation, leaving "I" forms and the speaker's own
     * capitalized vocabulary (names, brands) alone.
     */
    fun lowercaseContinuation(segment: String, vocabulary: List<String>): String {
        val s = segment.trimStart()
        if (s.isEmpty() || !s.first().isUpperCase()) return s
        val first = s.takeWhile { !it.isWhitespace() }
        val firstBare = bare(first)
        if (firstBare == "i" || firstBare.startsWith("i'")) return s
        if (vocabulary.any { term -> term.firstOrNull()?.isUpperCase() == true && bare(term.substringBefore(' ')) == firstBare }) return s
        if (first.length > 1 && first.drop(1).any { it.isUpperCase() }) return s // acronym or CamelCase
        return s.replaceFirstChar { it.lowercase() }
    }

    /** Removes pause-periods inside a segment: a period right after a word that cannot end a sentence. */
    fun fixInside(segment: String, vocabulary: List<String>): String {
        val sb = StringBuilder()
        var rest = segment
        val re = Regex("([\\p{L}']+)([.?!])\\s+(?=\\p{Lu})")
        while (true) {
            val m = re.find(rest) ?: break
            val before = rest.substring(0, m.range.first) + m.groupValues[1]
            val after = rest.substring(m.range.last + 1)
            if (endsUnfinished(sb.toString() + before)) {
                sb.append(before).append(' ')
                rest = lowercaseContinuation(after, vocabulary)
            } else {
                sb.append(before).append(m.groupValues[2]).append(' ')
                rest = after
            }
        }
        return sb.append(rest).toString()
    }
}

/**
 * Model-free cleaner: applies [PauseHeuristics] only. Instant; changes no words. Used when the
 * cleanup pass is switched on but Claude cannot be reached (no key, no credit, hard API errors).
 * [delayMs] exists so tests can exercise the timeout path.
 */
class LocalCleaner(private val delayMs: Long = 0) : Cleaner {
    override fun clean(req: CleanupRequest): CleanupResponse {
        if (delayMs > 0) Thread.sleep(delayMs)
        val join = req.boundary.isNotEmpty() && PauseHeuristics.endsUnfinished(req.context)
        var text = PauseHeuristics.fixInside(req.segment.trim(), req.vocabulary)
        if (join) text = PauseHeuristics.lowercaseContinuation(text, req.vocabulary)
        return CleanupResponse.Ok(join, text, delayMs, 0, 0)
    }
    override fun warmUp(): CleanupResponse = CleanupResponse.Ok(false, "ok", 0, 0, 0)
}
