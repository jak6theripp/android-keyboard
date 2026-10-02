package org.futo.inputmethod.latin.dictation.cleanup

/**
 * Divergence guard: the cleanup pass may fix a few words and any punctuation, but must not
 * rewrite. Compares word sequences (case- and punctuation-insensitive) with a weighted edit
 * distance in which the kinds of change a recognizer fix produces are cheap:
 *   - a word replaced by a similar-looking/sounding one costs in proportion to how different it is
 *     ("carburettor"→"carburetor" ≈ 0.2, "more"→"mower" ≈ 0.8, an unrelated word = 1)
 *   - two words merged into one, or one split in two, costs 0.25 plus how different the result is
 *     ("fly wheel"→"flywheel")
 *   - dropping a word that repeats the one before it (a stutter) costs 0.25
 *   - inserting or deleting a word costs 1
 * A rewrite substitutes, adds and drops unrelated words, so it still scores about one per word.
 */
object CleanupGuard {
    /** Fraction of the raw word count that may differ. */
    const val MAX_DIVERGENCE = 0.25
    private const val MERGE_COST = 0.25
    private const val STUTTER_COST = 0.25

    data class Verdict(val accepted: Boolean, val reason: String, val edits: Double, val rawWords: Int, val allowed: Int)

    fun words(s: String): List<String> =
        s.lowercase().split(Regex("\\s+"))
            .map { w -> w.filter { it.isLetterOrDigit() || it == '\'' } }
            .filter { it.isNotEmpty() }

    fun check(raw: String, cleaned: String): Verdict {
        val a = words(raw)
        val b = words(cleaned)
        // Short segments still get room for one fix (a stutter, one misheard word).
        val allowed = maxOf(1, Math.ceil(a.size * MAX_DIVERGENCE).toInt())
        if (b.isEmpty() && a.isNotEmpty()) return Verdict(false, "empty_output", a.size.toDouble(), a.size, allowed)
        val d = weightedDistance(a, b)
        return when {
            d > allowed + 1e-9 -> Verdict(false, "diverged", d, a.size, allowed)
            else -> Verdict(true, if (d == 0.0 && raw.trim() == cleaned.trim()) "unchanged" else "ok", d, a.size, allowed)
        }
    }

    private fun substitutionCost(x: String, y: String): Double {
        if (x == y) return 0.0
        val cd = charDistance(x, y)
        // Twice the relative character distance, capped at a full edit.
        return minOf(1.0, 2.0 * cd / maxOf(x.length, y.length))
    }

    fun weightedDistance(a: List<String>, b: List<String>): Double {
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 0..n) dp[i][0] = i.toDouble()
        for (j in 0..m) dp[0][j] = j.toDouble()
        for (i in 1..n) for (j in 1..m) {
            // dropping a word that merely repeats the one before it (a stutter) is cheap
            val deletion = if (i >= 2 && a[i - 2] == a[i - 1]) STUTTER_COST else 1.0
            var best = minOf(dp[i - 1][j] + deletion, dp[i][j - 1] + 1.0, dp[i - 1][j - 1] + substitutionCost(a[i - 1], b[j - 1]))
            // two raw words merged into one cleaned word ("fly wheel"→"flywheel", "hooks varna"→"Husqvarna")
            if (i >= 2) best = minOf(best, dp[i - 2][j - 1] + MERGE_COST + substitutionCost(a[i - 2] + a[i - 1], b[j - 1]))
            // one raw word split into two cleaned words
            if (j >= 2) best = minOf(best, dp[i - 1][j - 2] + MERGE_COST + substitutionCost(a[i - 1], b[j - 2] + b[j - 1]))
            dp[i][j] = best
        }
        return dp[n][m]
    }

    private fun charDistance(x: String, y: String): Int {
        var prev = IntArray(y.length + 1) { it }
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            cur[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[y.length]
    }
}
