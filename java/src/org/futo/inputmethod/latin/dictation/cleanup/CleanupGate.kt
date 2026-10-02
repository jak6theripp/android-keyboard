package org.futo.inputmethod.latin.dictation.cleanup

/**
 * SPIKE (branch cleanup-gate-spike): decides whether a finalized segment is clean enough to commit
 * with the local rules alone, or looks suspicious and should be sent through the Claude cleanup pass.
 *
 * The bias is deliberately conservative: a wrong "clean" (committing a segment that actually needed
 * Claude) is worse than a wasted $0.0007 call, so any whiff of trouble routes to Claude. The gate
 * never edits text; it only chooses the path.
 *
 * Not wired into dictation. Reached from DictationController only when the gate is switched on, and
 * from DictationDebugReceiver for benchmarking.
 */
object CleanupGate {
    /** One recognizer token with its confidence, as far as the gate cares. */
    data class Word(val content: String, val confidence: Float, val isPunctuation: Boolean)

    data class Decision(val clean: Boolean, val reason: String)

    // A single very-unsure word is enough to ask Claude; several merely-soft words also are.
    private const val HARD_LOW_CONF = 0.60f
    private const val SOFT_LOW_CONF = 0.75f
    private const val SOFT_LOW_COUNT = 2

    /** Recognizer outputs that are not close in spelling to the intended word but recur for this speaker. */
    private val KNOWN_MISRECOGNITIONS = setOf(
        "cooler", "coolers",          // Kohler
        "x", "mark",                  // Exmark split
        "varna", "husky",             // Husqvarna
        "stater", "staters",          // stator
        "brigs", "briggs's",          // Briggs
        "carburettor",                // carburetor (British spelling the formatter sometimes keeps)
    )

    /** Words that are allowed to be split onto their own token and should not trip the boundary check. */
    private val SHORT_OK = setOf("a", "i", "an", "to", "of", "or", "is", "it", "in", "on", "so", "at", "as", "we", "he", "my", "by", "up", "no", "ok", "us", "am", "be", "do", "go", "if", "me")

    /**
     * @param segment  the raw body of the utterance (no carried boundary punctuation)
     * @param words    per-word confidence for this body, or empty if the recognizer did not send it
     *                 (then only the text heuristics below can fire)
     * @param vocabulary the speaker's custom terms
     * @param boundary sentence punctuation carried from the previous utterance ("", ".", "?", "!")
     * @param context  text already committed before this segment
     */
    fun inspect(segment: String, words: List<Word>, vocabulary: List<String>, boundary: String, context: String): Decision {
        val body = segment.trim()
        if (body.isEmpty()) return Decision(true, "empty")

        // 1. Confidence. A hard-low word, or two soft-low words, means the recognizer was guessing.
        val spoken = words.filter { !it.isPunctuation }
        spoken.firstOrNull { it.confidence < HARD_LOW_CONF }?.let {
            return Decision(false, "low_conf=${"%.2f".format(it.confidence)}:${it.content}")
        }
        val soft = spoken.count { it.confidence < SOFT_LOW_CONF }
        if (soft >= SOFT_LOW_COUNT) return Decision(false, "soft_conf=$soft")

        val tokens = CleanupGuard.words(body)

        // 2. Near-vocabulary. A word one or two small edits from a custom term, but not the term
        //    itself, is very likely a misheard vocabulary word (e.g. "stater" for "stator").
        val vocabWords = vocabulary.flatMap { it.split(' ') }.map { it.lowercase() }.filter { it.length >= 4 }.toSet()
        for (t in tokens) {
            val lt = t.lowercase()
            if (lt.length < 4 || lt in vocabWords) continue
            for (v in vocabWords) {
                val d = editDistance(lt, v, 2)
                if (d in 1..nearThreshold(v)) return Decision(false, "near_vocab:$t~$v")
            }
        }

        // 3. Known recurring misrecognitions for this speaker.
        tokens.firstOrNull { it.lowercase() in KNOWN_MISRECOGNITIONS }?.let {
            return Decision(false, "known_misrec:$it")
        }

        // 4. Malformed word boundaries. A short real-looking fragment next to another word often
        //    means one word was split in two ("fly wheel", "carbu rettor"). Allow genuine short words.
        for (i in tokens.indices) {
            val t = tokens[i].lowercase()
            if (t.length in 1..2 && t !in SHORT_OK && t.all { it.isLetter() }) return Decision(false, "short_frag:$t")
        }

        // 5. Pause/boundary ambiguity. When a sentence boundary was carried in, the local rules can
        //    only decide the clear cases (ends on a linking word / opener / lone connective). If the
        //    context looks like a finished sentence but might also continue, that JOIN/BREAK call is
        //    exactly what Claude is for, so route it there.
        if (boundary.isNotEmpty() && context.isNotBlank() && !PauseHeuristics.endsUnfinished(context)) {
            return Decision(false, "boundary_ambiguous")
        }

        // 6. Any sentence punctuation inside the body (not the trailing mark) is a possible pause-period
        //    the local rules may not catch; the recognizer split one thought into two. Send to Claude.
        val inner = body.dropLast(1)
        if (inner.any { it == '.' || it == '?' || it == '!' }) return Decision(false, "internal_sentence")

        // 7. Capitalization. When this segment begins a new sentence (context empty or already finished)
        //    but the recognizer left the first word lowercase, the local rules will not fix it.
        val firstAlpha = body.firstOrNull { it.isLetter() }
        if (firstAlpha != null && firstAlpha.isLowerCase() && !PauseHeuristics.endsUnfinished(context)
            && (boundary.isNotEmpty() || context.isBlank())) {
            return Decision(false, "needs_capital")
        }

        return Decision(true, "clean")
    }

    private fun nearThreshold(v: String) = if (v.length >= 7) 2 else 1

    /** Bounded Levenshtein; returns [max]+1 once it is certain the distance exceeds [max]. */
    private fun editDistance(a: String, b: String, max: Int): Int {
        if (Math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
