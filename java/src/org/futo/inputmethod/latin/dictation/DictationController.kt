package org.futo.inputmethod.latin.dictation

import android.graphics.Color
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.futo.inputmethod.engine.general.DictationTransactionIME
import org.futo.inputmethod.latin.LatinIME
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.actions.DictationAction

/**
 * IME-side owner of all dictation text mutations. Exactly one coroutine (on Main.immediate)
 * consumes engine events, and every InputConnection call for dictation goes through here.
 *
 * Text tiers in the editor:
 *   committed       – commitText, never touched again
 *   pending finals  – composing, plain (awaiting cleanup; Phase 1 commits immediately)
 *   partial tail    – composing, dimmed + underlined (best-effort styling)
 */
class DictationController(private val latinIME: LatinIME) {
    private val engine = DictationEngine

    private var transaction: DictationTransactionIME? = null
    private var collector: Job? = null
    private var renderJob: Job? = null

    // Session text model
    private var sessionId = 0L
    private var lastCommittedSegment = 0L
    private val committedSegments = HashSet<Long>()
    private var pending = StringBuilder()     // finals shown as composing (none in Phase 1 unless cleanup on)
    private var partial = ""                   // provisional tail as displayed (after spoken replacements)
    private var partialRaw = ""                // provisional tail as received
    private var composingShown = ""
    private var lastPartialEndMs = 0L          // audio-timeline end of the newest partial
    private var discardUntilMs = 0L            // finals for audio before this were superseded by a user edit            // what we believe the editor's composing region holds
    private var needsLeadingSpace = true

    val stateForUi = mutableStateOf(DictationState.Idle)
    val idleSecondsLeft = mutableStateOf(-1)
    val level = mutableStateOf(0f)
    val lastError = mutableStateOf<String?>(null)

    private var inputViewActive = false
    /** The field this session was typing into is gone; ignore the rest of the session. */
    private var detached = false
    val isInputViewActive get() = inputViewActive

    init {
        latinIME.lifecycleScope.launch(Dispatchers.Main.immediate) {
            engine.state.collect {
                stateForUi.value = it
                if (it == DictationState.Idle) idleSecondsLeft.value = -1
            }
        }
    }

    // ---- public API used by the action ----

    fun toggle(triggerPath: String) {
        if (engine.isActive) { engine.stop("user_toggle"); return }
        start(triggerPath)
    }

    fun start(triggerPath: String) {
        if (engine.isActive) return
        lastError.value = null
        detached = false
        if (!inputViewActive || latinIME.currentInputConnection == null) {
            lastError.value = "no input field"; return
        }
        ensureTransaction()
        ensureCollector()
        engine.start(latinIME, triggerPath)
    }

    fun stop(reason: String) { engine.stop(reason) }

    // ---- lifecycle from LatinIME ----

    private var viewGoneJob: Job? = null

    fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        inputViewActive = true
        viewGoneJob?.cancel(); viewGoneJob = null
        val st = engine.state.value
        val live = st == DictationState.Starting || st == DictationState.Listening ||
                st == DictationState.Reconnecting || st == DictationState.Paused
        if (!live || detached) return
        // View (re)created mid-session (fold/unfold, rotation): re-anchor at the current cursor.
        DictationLog.event("ime_start_input_view", "restarting" to restarting)
        composingShown = ""; partial = ""; partialRaw = ""
        needsLeadingSpace = true
        ensureTransaction()
        ensureCollector()
        // The action window was closed by UixManager; reopen it without toggling the session.
        engine.suppressNextToggle()
        latinIME.uixManager.onActionActivated(DictationAction)
    }

    /** Must run BEFORE IMEManager.onFinishInput() ends the transaction. */
    fun onFinishInputView(finishingInput: Boolean) {
        inputViewActive = false
        val t = transaction
        if (engine.isActive && !detached && t != null && !t.isFinished) {
            DictationLog.event("ime_finish_input_view", "finishingInput" to finishingInput)
            if (finishingInput) {
                // The field is going away for good (app/field switch, unfold to the other display).
                // Keep exactly what is on screen, provisional words included, then stop. Results
                // that arrive after this belong to a field that no longer exists: ignore them.
                t.finishComposing()
                detached = true
                pending.clear(); partial = ""; partialRaw = ""; composingShown = ""
                engine.stop("input_finished")
            } else {
                // View recreation (rotation): the provisional tail is re-rendered after re-attach,
                // so remove it now instead of leaving a copy behind.
                if (composingShown.isNotEmpty()) t.setComposing("")
                pending.clear(); partial = ""; partialRaw = ""; composingShown = ""
                // Fold/rotation brings the view back within moments. If it doesn't come back, the
                // keyboard was dismissed: don't leave a hot mic with nowhere to type.
                viewGoneJob?.cancel()
                viewGoneJob = latinIME.lifecycleScope.launch(Dispatchers.Main.immediate) {
                    delay(3000)
                    if (!inputViewActive && engine.isActive) engine.stop("input_view_gone")
                }
            }
        }
        transaction?.end(); transaction = null
    }

    private fun onSelectionUpdate(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, composingStart: Int, composingEnd: Int) {
        if (!engine.isActive) return
        if (composingShown.isEmpty()) {
            // Between segments the user may move the cursor freely; new text goes wherever it is now.
            needsLeadingSpace = true
            return
        }
        // Selection callbacks are debounced and can describe an older state of our own edits, so
        // they are only a prompt to check the editor itself.
        if (!composingIntact()) {
            // The user touched the text (moved cursor, tapped elsewhere, edited). Never fight them:
            // freeze what's there, forget our composing region, and re-anchor at their cursor.
            DictationLog.event("user_intervention", "ours" to composingShown.length,
                "sel" to "$newSelStart-$newSelEnd", "comp" to "$composingStart-$composingEnd")
            freezeForUserEdit("selection")
        }
    }

    /** True if our provisional text is still exactly what sits before the cursor. */
    private fun composingIntact(): Boolean {
        if (composingShown.isEmpty()) return true
        val t = transaction ?: return true
        val before = t.textBeforeCursor(composingShown.length)?.toString() ?: return true
        return before == composingShown
    }

    /**
     * The user touched text we were still composing. Leave it exactly as it is on screen, and
     * discard the recognizer's later finals for that same audio so it is not inserted twice.
     */
    private fun freezeForUserEdit(why: String) {
        transaction?.finishComposing()
        if (composingShown.isNotEmpty() || partial.isNotEmpty()) discardUntilMs = maxOf(discardUntilMs, lastPartialEndMs)
        DictationLog.event("freeze_for_user_edit", "why" to why, "discardUntilMs" to discardUntilMs)
        pending.clear(); partial = ""; partialRaw = ""; composingShown = ""
        needsLeadingSpace = true
    }

    /** Deletes the previous word (and the whitespace after it). Used when backspace is held. */
    fun backspaceWord() {
        val t = transaction ?: return
        if (!engine.isActive || t.isFinished) return
        renderJob?.cancel()
        if (composingShown.isNotEmpty()) freezeForUserEdit("backspace")
        val before = t.textBeforeCursor(96)?.toString() ?: ""
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        val n = before.length - i
        if (n <= 0) t.sendBackspace() else t.deleteBefore(n)
        DictationLog.event("backspace_word", "chars" to n)
    }

    /** Backspace from the dictation panel; the mic stays hot. */
    fun backspace() {
        val t = transaction ?: return
        if (!engine.isActive || t.isFinished) return
        renderJob?.cancel()
        if (composingShown.isNotEmpty()) freezeForUserEdit("backspace")
        t.sendBackspace()
        DictationLog.event("backspace")
    }

    // ---- internals ----

    private fun ensureTransaction() {
        val t = transaction
        if (t != null && !t.isFinished) return
        transaction = latinIME.imeManager.createDictationTransaction(::onSelectionUpdate)
    }

    private fun ensureCollector() {
        if (collector?.isActive == true) return
        collector = latinIME.lifecycleScope.launch(Dispatchers.Main.immediate) {
            engine.events.collect { ev -> handle(ev) }
        }
    }

    private fun handle(ev: DictationEvent) {
        when (ev) {
            is DictationEvent.Level -> level.value = ev.rms
            is DictationEvent.IdleCountdown -> idleSecondsLeft.value = ev.secondsLeft
            is DictationEvent.Error -> {
                lastError.value = ev.message
                if (ev.message == "no_api_key") toast(R.string.dictation_error_no_key)
            }
            is DictationEvent.Stopped -> {
                if (ev.sessionId != sessionId && sessionId != 0L) return
                idleSecondsLeft.value = -1
                // Anything still provisional becomes real text; nothing is lost.
                val t = transaction
                if (t != null && !t.isFinished && !detached) {
                    if (ev.flushed) {
                        // Every final was delivered and committed; a leftover partial is stale.
                        if (composingShown.isNotEmpty()) t.setComposing("")
                    } else {
                        // No confirmation (offline, timeout): keep the provisional words as typed.
                        t.finishComposing()
                    }
                    t.end()
                } else if (t != null && !t.isFinished) {
                    t.end()
                }
                transaction = null
                pending.clear(); partial = ""; partialRaw = ""; composingShown = ""
                DictationLog.event("controller_stopped", "reason" to ev.reason)
                latinIME.uixManager.closeActionWindowIf(DictationAction)
            }
            is DictationEvent.Partial -> {
                if (detached || ev.sessionId != currentSession()) return
                if (ev.segmentId <= lastCommittedSegment) { DictationLog.event("stale_partial_ignored", "seg" to ev.segmentId); return }
                if (!composingIntact()) freezeForUserEdit("verify_partial")
                lastPartialEndMs = ev.audioEndMs
                if (ev.audioStartMs + 60 < discardUntilMs) return // covers text the user already edited
                partialRaw = ev.text.trim()
                partial = partialRaw.applySpokenReplacements()
                scheduleRender()
            }
            is DictationEvent.Final -> {
                if (detached || ev.sessionId != currentSession()) return
                if (!committedSegments.add(ev.segmentId)) { DictationLog.event("duplicate_final_ignored", "seg" to ev.segmentId); return }
                lastCommittedSegment = maxOf(lastCommittedSegment, ev.segmentId)
                if (!composingIntact()) freezeForUserEdit("verify_final")
                if ((ev.audioStartMs + ev.audioEndMs) / 2 < discardUntilMs) {
                    DictationLog.event("final_discarded_after_user_edit", "seg" to ev.segmentId, "text" to ev.text)
                    return
                }
                // Keep showing the not-yet-final remainder of the last partial so the tail doesn't blink;
                // the provider's next partial (covering exactly that remainder) replaces it.
                val finalRaw = ev.text.trim()
                partialRaw = remainderAfter(partialRaw, finalRaw)
                partial = partialRaw.applySpokenReplacements()
                commitSegment(ev.segmentId, finalRaw.applySpokenReplacements())
            }
            is DictationEvent.EndOfUtterance -> { /* Phase 2: cleanup trigger */ }
        }
    }

    private fun currentSession(): Long {
        if (sessionId != engine.sessionId) {
            sessionId = engine.sessionId
            committedSegments.clear(); lastCommittedSegment = 0
            discardUntilMs = 0; lastPartialEndMs = 0
            pending.clear(); partial = ""; partialRaw = ""; composingShown = ""; needsLeadingSpace = true
        }
        return sessionId
    }

    /** Phase 1: commit finals immediately (cleanup arrives in Phase 2). */
    private fun commitSegment(segmentId: Long, text: String) {
        val t = transaction ?: return
        if (t.isFinished || text.isEmpty()) return
        renderJob?.cancel()
        // One atomic edit: drop the provisional tail, commit the final, re-show the remainder.
        t.beginBatch()
        if (composingShown.isNotEmpty()) { t.setComposing(""); composingShown = "" }
        val toCommit = withSpacing(text)
        t.commitText(toCommit)
        render()
        t.endBatch()
        DictationLog.event("commit", "seg" to segmentId, "text" to toCommit)
    }

    private fun markCommitted(text: String) { /* spacing is derived from the editor; nothing to track */ }

    /** If [partial] begins with the words of [final], returns the words after them; else "". */
    private fun remainderAfter(partial: String, final: String): String {
        if (partial.isEmpty() || final.isEmpty()) return ""
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == '\'' }
        val p = partial.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val f = final.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (f.size > p.size) return ""
        for (i in f.indices) if (norm(p[i]) != norm(f[i])) return ""
        return p.drop(f.size).joinToString(" ")
    }

    private var spaceNeededBeforeComposing = false

    /**
     * Decide whether a space is needed before [text]. The editor is only consulted while our
     * composing region is empty; otherwise the cursor sits after our own provisional text.
     */
    private fun withSpacing(text: String): String {
        if (text.isEmpty()) return text
        if (composingShown.isEmpty()) {
            val before = transaction?.textBeforeCursor(1)
            spaceNeededBeforeComposing = !before.isNullOrEmpty() && !before.last().isWhitespace()
        }
        val startsWithPunct = text.first() in ".,!?;:)]}%…"
        return if (spaceNeededBeforeComposing && !startsWithPunct) " $text" else text
    }

    /** Rate-limit composing updates to ~10/s (leading edge); only the trailing tail ever changes. */
    private fun scheduleRender() {
        val wait = 100 - (SystemClock.uptimeMillis() - lastRenderAt)
        if (wait <= 0) { renderJob?.cancel(); render(); return }
        if (renderJob?.isActive == true) return
        renderJob = latinIME.lifecycleScope.launch(Dispatchers.Main.immediate) {
            delay(wait)
            render()
        }
    }

    private var lastRenderAt = 0L

    private fun render() {
        val t = transaction ?: return
        if (t.isFinished) return
        lastRenderAt = SystemClock.uptimeMillis()
        val pendingStr = pending.toString()
        val tail = if (pendingStr.isNotEmpty() && partial.isNotEmpty()) "$pendingStr $partial" else pendingStr + partial
        if (tail == composingShown) return
        if (tail.isEmpty()) { t.setComposing(""); composingShown = ""; return }
        val shown = withSpacing(tail)
        val ssb = SpannableStringBuilder(shown)
        if (partial.isNotEmpty()) {
            val start = shown.length - partial.length
            ssb.setSpan(ForegroundColorSpan(Color.argb(150, 128, 128, 128)), start, shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(UnderlineSpan(), start, shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        t.setComposing(ssb)
        composingShown = shown
    }

    private fun toast(res: Int) {
        try { Toast.makeText(latinIME, res, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
    }
}
