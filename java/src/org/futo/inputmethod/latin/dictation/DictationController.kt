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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.futo.inputmethod.engine.general.DictationTransactionIME
import org.futo.inputmethod.latin.LatinIME
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.dictation.cleanup.CleanupClient
import org.futo.inputmethod.latin.dictation.cleanup.Cleaner
import org.futo.inputmethod.latin.dictation.cleanup.LocalCleaner
import org.futo.inputmethod.latin.dictation.cleanup.PauseHeuristics
import org.futo.inputmethod.latin.dictation.cleanup.CleanupGuard
import org.futo.inputmethod.latin.dictation.cleanup.CleanupRequest
import org.futo.inputmethod.latin.dictation.cleanup.CleanupResponse
import org.futo.inputmethod.latin.uix.actions.AllActions
import org.futo.inputmethod.latin.uix.actions.DictationAction
import org.futo.inputmethod.latin.uix.getSetting
import java.lang.ref.WeakReference

/**
 * IME-side owner of all dictation text mutations. Exactly one coroutine (on Main.immediate)
 * consumes engine events, and every InputConnection call for dictation goes through here.
 *
 * Text tiers in the editor:
 *   committed    – commitText; never touched again
 *   pending      – composing, plain: final words waiting for (or in) the cleanup pass
 *   partial tail – composing, dimmed + underlined (best-effort styling): still provisional
 *
 * Utterances. Finals accumulate into the pending text. An utterance is closed (sent to cleanup)
 * when the speaker goes quiet, at a sentence end once it is long enough, or at a size cap. One
 * utterance is in flight at a time, so results are applied strictly in order.
 *
 * Sentence-ending punctuation at the end of an utterance is not committed with it: it is carried
 * as the first character of the next utterance, so the cleanup pass sees both sides of a pause and
 * can drop a period the recognizer only wrote because the speaker stopped to think.
 */
class DictationController(private val latinIME: LatinIME) {
    companion object {
        /** How long the mic may stay hot with the keyboard view hidden but the field still bound. */
        private const val VIEW_GONE_GRACE_MS = 20_000L
        /** Cleanup latency budget; past this the raw text is committed. */
        private const val CLEANUP_TIMEOUT_MS = 1500L
        /** Close an utterance at a sentence end once it has at least this many words. */
        private const val SENTENCE_CLOSE_MIN_WORDS = 8
        /** Close an utterance regardless once it reaches this many words. */
        private const val SIZE_CLOSE_WORDS = 40
        private const val CONTEXT_CHARS = 320
        private const val SENTENCE_PUNCT = ".?!"
        private const val LEADING_ATTACH = ".,!?;:)]}%…"

        /** Debug: >0 forces the local (model-free) cleaner, taking this many ms per call. */
        @Volatile var debugFakeCleanupMs = 0L
        /** After this many consecutive hard API failures the cleanup pass is switched off for the session. */
        private const val MAX_HARD_FAILURES = 2

        @Volatile private var instanceRef: WeakReference<DictationController>? = null
        /** For the debug receiver only. */
        val instance: DictationController? get() = instanceRef?.get()
    }

    private val engine = DictationEngine

    private var transaction: DictationTransactionIME? = null
    private var collector: Job? = null
    private var renderJob: Job? = null

    // ---- session text model ----
    private var sessionId = 0L
    private var lastFinalSegment = 0L
    private val seenSegments = HashSet<Long>()
    /** Raw final words of the open utterance (may start with carried sentence punctuation). */
    private val pendingRaw = StringBuilder()
    // SPIKE (cleanup-gate): per-word confidence for the words now in pendingRaw, for the shadow gate.
    private val pendingWords = ArrayList<org.futo.inputmethod.latin.dictation.cleanup.CleanupGate.Word>()
    private var partial = ""                   // provisional tail as displayed (after spoken replacements)
    private var partialRaw = ""                // provisional tail as received
    private var composingShown = ""            // what we believe the editor's composing region holds
    private var lastPartialEndMs = 0L          // audio-timeline end of the newest partial
    private var discardUntilMs = 0L            // finals for audio before this were superseded by a user edit
    private var spaceNeededBeforeComposing = false
    private var lastRenderAt = 0L
    /** Tail of what this session committed, used as cleanup context. */
    private val committedTail = StringBuilder()

    /**
     * [lead] = sentence punctuation carried over from the previous utterance (the boundary the
     * cleanup pass rules on), [raw] = the words, [trail] = sentence punctuation deferred to the next.
     */
    private class Utterance(val id: Int, val lead: String, val raw: String, val trail: String, val isLast: Boolean, val context: String) {
        val sentAt = SystemClock.elapsedRealtime()
        var job: Job? = null
    }
    private var utteranceCounter = 0
    private var inFlight: Utterance? = null
    private var cleanup: Cleaner? = null
    private var claudeCleaner: CleanupClient? = null
    private var cleanupKeyHash = 0
    private var hardFailures = 0
    private var usingClaude = false
    private var cleanupActive = false
    private var vocabulary: List<String> = emptyList()
    /** The engine has stopped and the remaining provisional text is being settled. */
    private var finalizing = false

    // ---- view / field state ----
    private var inputViewActive = false
    val isInputViewActive get() = inputViewActive
    /** The field this session was typing into is gone; ignore the rest of the session. */
    private var detached = false
    private var sessionEditorKey = ""
    /** Text that could not be written because the editor was unreachable (shade, hidden view). */
    private val backlog = ArrayList<String>()
    private var orphanText: String? = null
    private var orphanKey = ""
    private var orphanAtMs = 0L
    /** Provisional text that was on screen when the view went away; removed again on return. */
    private var strandedComposing = ""
    private var viewGoneJob: Job? = null
    /** True while the controller itself re-opens the panel; the panel must not treat that as a stop. */
    var isReattaching = false
        private set

    // ---- UI state ----
    val stateForUi = mutableStateOf(DictationState.Idle)
    val idleSecondsLeft = mutableStateOf(-1)
    val level = mutableStateOf(0f)
    val lastError = mutableStateOf<String?>(null)

    init {
        instanceRef = WeakReference(this)
        latinIME.lifecycleScope.launch(Dispatchers.Main.immediate) {
            engine.state.collect {
                stateForUi.value = it
                if (it == DictationState.Idle) idleSecondsLeft.value = -1
            }
        }
    }

    // =====================================================================================
    // Public API
    // =====================================================================================

    fun toggle(triggerPath: String) {
        if (engine.isActive) { engine.stop("user_toggle"); return }
        start(triggerPath)
    }

    fun start(triggerPath: String) {
        if (engine.isActive || finalizing) return
        lastError.value = null
        detached = false
        if (!inputViewActive || latinIME.currentInputConnection == null) {
            lastError.value = "no input field"; return
        }
        sessionEditorKey = editorKey(latinIME.currentInputEditorInfo)
        backlog.clear()
        resetTextModel()
        ensureTransaction()
        ensureCollector()
        prepareCleanup()
        committedTail.append(transaction?.textBeforeCursor(CONTEXT_CHARS) ?: "")
        engine.start(latinIME, triggerPath)
    }

    fun stop(reason: String) { engine.stop(reason) }

    /** Backspace from the dictation panel; the mic stays hot. */
    fun backspace() {
        val t = transaction ?: return
        if (!engine.isActive || t.isFinished) return
        renderJob?.cancel()
        if (composingShown.isNotEmpty()) freezeForUserEdit("backspace")
        t.sendBackspace()
        trimCommittedTail(1)
        DictationLog.event("backspace")
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
        trimCommittedTail(maxOf(n, 1))
        DictationLog.event("backspace_word", "chars" to n)
    }

    // ---- instruction capture (AI reply): the transcript goes to a callback, not to the editor ----
    private class CaptureSink(val onUpdate: (String) -> Unit, val onDone: (String, String) -> Unit)
    private var captureSink: CaptureSink? = null
    private val captureFinals = StringBuilder()
    val isCapturing get() = captureSink != null

    /** Records speech and reports the transcript to the callbacks. Nothing is written to the field. */
    fun startCapture(triggerPath: String, onUpdate: (String) -> Unit, onDone: (String, String) -> Unit): Boolean {
        if (engine.isActive || finalizing) return false
        captureFinals.clear()
        captureSink = CaptureSink(onUpdate, onDone)
        ensureCollector()
        engine.start(latinIME, triggerPath)
        return true
    }

    fun cancelCapture() {
        captureSink = null
        engine.stop("capture_cancelled")
    }

    /** Returns true if the event belonged to a capture and must not reach the editor path. */
    private fun handleCapture(ev: DictationEvent): Boolean {
        val sink = captureSink
        return when (ev) {
            is DictationEvent.Partial -> { sink?.onUpdate(smartJoin(captureFinals.toString(), ev.text.trim())); sink != null }
            is DictationEvent.Final -> {
                if (sink != null) { appendFragment(captureFinals, ev.text.trim()); sink.onUpdate(captureFinals.toString()) }
                sink != null
            }
            is DictationEvent.EndOfUtterance -> sink != null
            is DictationEvent.Stopped -> {
                if (sink != null) { captureSink = null; sink.onDone(captureFinals.toString().trim(), ev.reason) }
                sink != null
            }
            else -> false
        }
    }

    private var nextTriggerPath: String? = null
    private var startWhenViewReady: String? = null
    private var returnToPreviousIme = false

    /** The panel asks which path started this session ("keyboard_action" unless something else armed it). */
    fun consumeTriggerPath(): String = (nextTriggerPath ?: "keyboard_action").also { nextTriggerPath = null }

    /** The system switched this keyboard to its voice subtype: that is a request to dictate. */
    fun onVoiceSubtypeSelected() {
        android.util.Log.i("Dictation", "voice subtype selected viewActive=$inputViewActive engine=${engine.state.value}")
        // If we were not the keyboard on screen, another keyboard handed over just for dictation:
        // go back to it when the session ends.
        returnToPreviousIme = !inputViewActive
        latinIME.switchToKeyboardSubtype()
        if (engine.isActive) return
        if (inputViewActive) startVia("voice_subtype") else startWhenViewReady = "voice_subtype"
    }

    /** The mic in the system navigation bar: same as the mic key, and it also stops a running session. */
    fun onNavBarMicTapped() {
        android.util.Log.i("Dictation", "navbar mic tapped viewActive=$inputViewActive engine=${engine.state.value}")
        if (engine.isActive) { engine.stop("navbar_mic"); return }
        if (inputViewActive) startVia("navbar_mic")
    }

    private fun startVia(path: String) {
        nextTriggerPath = path
        latinIME.uixManager.triggerActionInternalFromIme(AllActions.indexOf(DictationAction), false)
    }

    /** Debug: start/stop exactly as a tap on the mic action would (opens the panel). */
    fun debugToggle() {
        latinIME.uixManager.triggerActionInternalFromIme(AllActions.indexOf(DictationAction), false)
    }

    /** Debug: re-request the navigation-bar mic with different placement parameters. */
    fun debugNavBar(requestClass: String?, position: Int, priority: Int) =
        NavBarMic.debugReconfigure(latinIME, requestClass, position, priority)

    /** Debug: what the editor holds before the cursor. */
    fun debugTextBeforeCursor(n: Int): String? = latinIME.currentInputConnection?.getTextBeforeCursor(n, 0)?.toString()

    // =====================================================================================
    // Lifecycle from LatinIME
    // =====================================================================================

    fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        inputViewActive = true
        viewGoneJob?.cancel(); viewGoneJob = null
        if (captureSink != null) return
        val key = editorKey(info)
        val sessionAlive = (isLive(engine.state.value) || finalizing) && !detached
        if (!sessionAlive) {
            commitOrphanIfSameField(key)
            // Brought up directly in the voice subtype (or the subtype change arrived before the view).
            val pending = startWhenViewReady ?: if (latinIME.isVoiceSubtypeCurrent()) "voice_subtype" else null
            startWhenViewReady = null
            if (pending != null && !engine.isActive && !finalizing) {
                latinIME.switchToKeyboardSubtype()
                latinIME.lifecycleScope.launch(Dispatchers.Main) { if (inputViewActive && !engine.isActive) startVia(pending) }
            }
            return
        }
        // restarting = the app restarted input on the same view (rotation, shade, focus bounce).
        val sameField = restarting || key == sessionEditorKey
        DictationLog.event("ime_start_input_view", "restarting" to restarting, "sameField" to sameField,
            "backlog" to backlog.size, "key" to key, "sessionKey" to sessionEditorKey)
        if (!sameField) {
            // The keyboard came back on a different field: this session's text does not belong here.
            detached = true
            if (backlog.isNotEmpty()) DictationLog.event("backlog_dropped", "why" to "field_changed", "segments" to backlog.size)
            backlog.clear()
            dropInFlight()
            engine.stop("field_changed")
            return
        }
        // View (re)created mid-session (rotation, shade closed): re-anchor at the current cursor.
        composingShown = ""; partial = ""; partialRaw = ""
        ensureTransaction()
        ensureCollector()
        removeStrandedComposing()
        flushBacklog()
        render()
        if (finalizing) return
        // The action window was closed by UixManager; reopen it without toggling the session.
        isReattaching = true
        try {
            engine.suppressNextToggle()
            latinIME.uixManager.triggerActionInternalFromIme(AllActions.indexOf(DictationAction), false)
        } finally { isReattaching = false }
    }

    /** Must run BEFORE IMEManager.onFinishInput() ends the transaction. */
    fun onFinishInputView(finishingInput: Boolean) {
        inputViewActive = false
        val t = transaction
        if (captureSink != null) return // the AI window is closed by UixManager and cancels its own capture
        if ((engine.isActive || finalizing) && !detached && t != null && !t.isFinished) {
            DictationLog.event("ime_finish_input_view", "finishingInput" to finishingInput)
            if (finishingInput) {
                // The field is going away for good (app/field switch, unfold to the other display).
                // Keep exactly what is on screen, provisional words included, then stop. Results
                // that arrive after this belong to a field that no longer exists: ignore them.
                t.finishComposing()
                detached = true
                dropInFlight()
                resetTextModel()
                if (engine.isActive) engine.stop("input_finished") else endSession("input_finished")
            } else {
                // View recreation (rotation, shade): provisional text is re-rendered after
                // re-attach, so take it out of the editor now. Pending words stay in the model.
                strandedComposing = composingShown
                if (composingShown.isNotEmpty()) t.setComposing("")
                partial = ""; partialRaw = ""; composingShown = ""
                // Keep listening, but don't leave a hot mic indefinitely with nowhere to type.
                viewGoneJob?.cancel()
                viewGoneJob = latinIME.lifecycleScope.launch(Dispatchers.Main.immediate) {
                    delay(VIEW_GONE_GRACE_MS)
                    if (!inputViewActive && engine.isActive) engine.stop("input_view_gone")
                }
            }
        }
        transaction?.end(); transaction = null
    }

    private fun onSelectionUpdate(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, composingStart: Int, composingEnd: Int) {
        if (!engine.isActive && !finalizing) return
        if (composingShown.isEmpty()) return // between segments the user may move the cursor freely
        // Selection callbacks are debounced and can describe an older state of our own edits, so
        // they are only a prompt to check the editor itself.
        if (!composingIntact()) {
            DictationLog.event("user_intervention", "ours" to composingShown.length,
                "sel" to "$newSelStart-$newSelEnd", "comp" to "$composingStart-$composingEnd")
            freezeForUserEdit("selection")
        }
    }

    // =====================================================================================
    // Engine events
    // =====================================================================================

    private fun handle(ev: DictationEvent) {
        if (handleCapture(ev)) return
        when (ev) {
            is DictationEvent.Level -> level.value = ev.rms
            is DictationEvent.IdleCountdown -> idleSecondsLeft.value = ev.secondsLeft
            is DictationEvent.Error -> {
                lastError.value = ev.message
                if (ev.message == "no_api_key") toast(R.string.dictation_error_no_key)
            }
            is DictationEvent.Partial -> {
                if (detached || finalizing || ev.sessionId != currentSession()) return
                if (ev.segmentId <= lastFinalSegment) { DictationLog.event("stale_partial_ignored", "seg" to ev.segmentId); return }
                if (!composingIntact()) freezeForUserEdit("verify_partial")
                lastPartialEndMs = ev.audioEndMs
                if (ev.audioStartMs + 60 < discardUntilMs) return // covers text the user already edited
                partialRaw = ev.text.trim()
                partial = partialRaw.applySpokenReplacements()
                scheduleRender()
            }
            is DictationEvent.Final -> {
                if (detached || ev.sessionId != currentSession()) return
                if (!seenSegments.add(ev.segmentId)) { DictationLog.event("duplicate_final_ignored", "seg" to ev.segmentId); return }
                lastFinalSegment = maxOf(lastFinalSegment, ev.segmentId)
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
                if (ev.words.isNotEmpty()) ev.words.forEach { pendingWords.add(org.futo.inputmethod.latin.dictation.cleanup.CleanupGate.Word(it.content, it.confidence, it.isPunctuation)) }
                if (!cleanupActive && inFlight == null) {
                    // Raw mode: commit as the words arrive (plus anything still waiting from before).
                    appendFragment(pendingRaw, finalRaw)
                    val text = pendingRaw.toString().trim()
                    pendingRaw.clear(); pendingWords.clear()
                    commitText(text.applySpokenReplacements(), "seg" to ev.segmentId)
                    return
                }
                appendFragment(pendingRaw, finalRaw)
                renderJob?.cancel(); render()
                maybeCloseUtterance(if (wordCount(pendingRaw) >= SIZE_CLOSE_WORDS) "size" else "sentence")
            }
            is DictationEvent.EndOfUtterance -> {
                if (detached || ev.sessionId != currentSession()) return
                maybeCloseUtterance("quiet")
            }
            is DictationEvent.Stopped -> {
                if (ev.sessionId != sessionId && sessionId != 0L) return
                idleSecondsLeft.value = -1
                DictationLog.event("controller_stopping", "reason" to ev.reason, "flushed" to ev.flushed,
                    "pendingWords" to wordCount(pendingRaw), "inFlight" to (inFlight != null))
                if (detached) { endSession(ev.reason); return }
                // If the recognizer never confirmed the last words (offline, timeout), keep them as heard.
                if (!ev.flushed && partialRaw.isNotEmpty()) appendFragment(pendingRaw, partialRaw)
                partial = ""; partialRaw = ""
                finalizing = true
                finalizeReason = ev.reason
                proceedFinalize()
            }
        }
    }

    private var finalizeReason = ""

    private fun currentSession(): Long {
        if (sessionId != engine.sessionId) {
            sessionId = engine.sessionId
            seenSegments.clear(); lastFinalSegment = 0
            discardUntilMs = 0; lastPartialEndMs = 0
        }
        return sessionId
    }

    // =====================================================================================
    // Utterances and cleanup
    // =====================================================================================

    private fun prepareCleanup() {
        val ctx = latinIME.applicationContext
        vocabulary = ctx.getSetting(DICTATION_VOCAB).toVocabList()
        hardFailures = 0
        usingClaude = false
        if (debugFakeCleanupMs > 0) {
            cleanup = LocalCleaner(debugFakeCleanupMs); cleanupActive = true
            DictationLog.event("cleanup_local", "why" to "debug", "ms" to debugFakeCleanupMs)
            return
        }
        // Setting off = the recognizer's text exactly as it comes.
        if (!ctx.getSetting(DICTATION_CLEANUP_ENABLED)) { cleanupActive = false; cleanup = null; DictationLog.event("cleanup_off"); return }
        val key = SecureKeys.get(ctx, SecureKeys.KEY_ANTHROPIC)
        if (key == null) {
            // No key: still fix the pause-periods that need no model.
            cleanup = LocalCleaner(); cleanupActive = true
            DictationLog.event("cleanup_local", "why" to "no_key")
            return
        }
        usingClaude = true
        if (claudeCleaner == null || cleanupKeyHash != key.hashCode()) {
            claudeCleaner = CleanupClient(key, CleanupClient.loadPrompt(ctx)); cleanupKeyHash = key.hashCode()
        }
        cleanup = claudeCleaner
        cleanupActive = true
        // Open the connection now so the first real request fits the latency budget.
        val client = cleanup
        latinIME.lifecycleScope.launch(Dispatchers.IO) {
            val r = client?.warmUp()
            DictationLog.event("cleanup_warmup", "result" to (r?.javaClass?.simpleName ?: "none"),
                "ms" to when (r) { is CleanupResponse.Ok -> r.ms; is CleanupResponse.Failed -> r.ms; else -> -1 },
                "detail" to ((r as? CleanupResponse.Failed)?.reason ?: ""))
            // A 4xx here (billing, key, access) will not fix itself mid-session: don't even try per utterance.
            if (r is CleanupResponse.Failed && r.reason.startsWith("http_4")) {
                latinIME.lifecycleScope.launch(Dispatchers.Main.immediate) { disableCleanup(r.reason) }
            }
        }
    }

    /** Claude can't be used this session: fall back to the local cleaner and tell the user why. Main thread. */
    private fun disableCleanup(reason: String) {
        if (!usingClaude) return
        usingClaude = false
        cleanup = LocalCleaner()
        val why = when {
            "credit balance" in reason -> "Anthropic credit balance is too low"
            "http_401" in reason || "http_403" in reason -> "Anthropic API key was rejected"
            else -> reason.take(60)
        }
        lastError.value = "AI cleanup unavailable: $why"
        DictationLog.event("cleanup_local", "why" to why)
        try { Toast.makeText(latinIME, "AI cleanup unavailable: $why. Using basic cleanup.", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
    }

    private fun maybeCloseUtterance(trigger: String) {
        if (!cleanupActive || inFlight != null || finalizing) return
        val raw = pendingRaw.toString().trim()
        val words = wordCount(raw)
        if (words == 0) return
        val ok = when (trigger) {
            "quiet" -> partialRaw.none { it.isLetterOrDigit() }
            "sentence" -> words >= SENTENCE_CLOSE_MIN_WORDS && raw.last() in SENTENCE_PUNCT
            "size" -> words >= SIZE_CLOSE_WORDS
            else -> false
        }
        if (ok) closeUtterance(trigger, isLast = false)
    }

    private fun closeUtterance(trigger: String, isLast: Boolean) {
        val full = pendingRaw.toString().trim()
        // Sentence-ending punctuation is carried into the next utterance (see class comment),
        // except at the very end of the session where there is no next utterance.
        val trail = if (!isLast && full.isNotEmpty() && full.last() in SENTENCE_PUNCT) full.takeLastWhile { it in SENTENCE_PUNCT } else ""
        val withLead = full.dropLast(trail.length).trimEnd()
        val lead = withLead.takeWhile { it in SENTENCE_PUNCT }
        val body = withLead.drop(lead.length).trimStart()
        pendingRaw.clear()
        val context = committedTail.toString().takeLast(CONTEXT_CHARS)
        // SPIKE shadow gate: log what the gate WOULD decide. Production still always calls Claude.
        val gw = ArrayList(pendingWords)
        pendingWords.clear()
        val gate = org.futo.inputmethod.latin.dictation.cleanup.CleanupGate.inspect(body, gw, vocabulary, lead, context)
        val u = Utterance(++utteranceCounter, lead, body, trail, isLast, context)
        inFlight = u
        DictationLog.event("cleanup_request", "utt" to u.id, "trigger" to trigger, "lead" to lead, "raw" to body, "trail" to trail, "words" to wordCount(body))
        DictationLog.event("gate", "utt" to u.id, "clean" to gate.clean, "reason" to gate.reason, "confWords" to gw.size)
        val client = cleanup
        val request = CleanupRequest(context = context, boundary = lead, segment = body, vocabulary = vocabulary)
        u.job = latinIME.lifecycleScope.launch(Dispatchers.Main.immediate) {
            // The network call runs on IO and is merely abandoned on timeout; await() is what's bounded.
            val call: Deferred<CleanupResponse>? = client?.let { c -> latinIME.lifecycleScope.async(Dispatchers.IO) { c.clean(request) } }
            val response = if (call == null) null else withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { call.await() }
            onCleanupResult(u, response)
        }
        render()
    }

    private fun onCleanupResult(u: Utterance, response: CleanupResponse?) {
        if (inFlight !== u) { DictationLog.event("cleanup_stale_result", "utt" to u.id); return } // out of order / superseded
        inFlight = null
        val waited = SystemClock.elapsedRealtime() - u.sentAt
        var decision: String
        // Fallback for every failure mode: exactly what the recognizer wrote.
        var text = smartJoin(u.lead, u.raw)
        when (response) {
            null -> decision = "timeout"
            is CleanupResponse.Failed -> {
                decision = "failed_${response.reason}"
                // 4xx = the request can't succeed as things stand (billing, key, access). Don't keep
                // paying the round trip on every utterance: switch cleanup off and say why.
                if (response.reason.startsWith("http_4") && ++hardFailures >= MAX_HARD_FAILURES) disableCleanup(response.reason)
            }
            is CleanupResponse.Ok -> {
                hardFailures = 0
                val v = CleanupGuard.check(u.raw, response.text)
                decision = if (v.accepted) "accept_${v.reason}" else "reject_${v.reason}"
                DictationLog.event("cleanup_response", "utt" to u.id, "join" to response.join, "cleaned" to response.text, "apiMs" to response.ms,
                    "edits" to v.edits, "allowed" to v.allowed, "rawWords" to v.rawWords, "in" to response.inputTokens, "out" to response.outputTokens)
                if (v.accepted) {
                    // A sentence cannot end on "the"/"because"/…: that is a JOIN whatever the model said.
                    val forced = u.lead.isNotEmpty() && !response.join && PauseHeuristics.endsUnfinished(u.context)
                    val join = response.join || forced
                    // JOIN: the carried punctuation was only a pause, so it is dropped.
                    text = when {
                        u.lead.isEmpty() -> response.text
                        forced -> PauseHeuristics.lowercaseContinuation(response.text, vocabulary)
                        join -> response.text
                        else -> smartJoin(u.lead, response.text)
                    }
                    if (u.lead.isNotEmpty()) decision += if (forced) "_join_forced" else if (join) "_join" else "_break"
                }
            }
        }
        if (response !is CleanupResponse.Ok || !decision.startsWith("accept")) {
            // No usable model answer: still apply the rules that need no model.
            val local = LocalCleaner().clean(CleanupRequest(u.context, u.lead, u.raw, vocabulary))
            if (local is CleanupResponse.Ok) {
                text = if (u.lead.isNotEmpty() && !local.join) smartJoin(u.lead, local.text) else local.text
                decision += if (u.lead.isNotEmpty() && local.join) "+local_join" else "+local"
            }
        }
        // Only apply if the provisional text is still exactly as it was: never overwrite what the user touched.
        if (!composingIntact()) {
            DictationLog.event("cleanup_decision", "utt" to u.id, "decision" to "discard_user_edit", "waitedMs" to waited)
            freezeForUserEdit("verify_cleanup")
            if (finalizing) proceedFinalize()
            return
        }
        // Whatever sentence punctuation ends this utterance is deferred to the next one.
        val modelTrail = if (!u.isLast && text.isNotEmpty() && text.last() in SENTENCE_PUNCT) text.takeLastWhile { it in SENTENCE_PUNCT } else ""
        val body = text.dropLast(modelTrail.length).trimEnd()
        val carry = if (u.isLast) "" else u.trail.ifEmpty { modelTrail }
        val toCommit = if (u.isLast) text + u.trail else body
        DictationLog.event("cleanup_decision", "utt" to u.id, "decision" to decision, "waitedMs" to waited,
            "raw" to smartJoin(u.lead, u.raw), "applied" to toCommit, "carry" to carry)
        if (carry.isNotEmpty() && !(pendingRaw.isNotEmpty() && pendingRaw.first() in SENTENCE_PUNCT)) pendingRaw.insert(0, carry)
        commitText(toCommit.applySpokenReplacements(), "utt" to u.id)
        if (finalizing) proceedFinalize() else maybeCloseUtterance(if (wordCount(pendingRaw) >= SIZE_CLOSE_WORDS) "size" else "sentence")
    }

    private fun dropInFlight() {
        inFlight?.job?.cancel()
        inFlight = null
    }

    /** After the engine stopped: clean the last utterance, commit what is left, end the session. */
    private fun proceedFinalize() {
        if (inFlight != null) return // re-entered from onCleanupResult
        val raw = pendingRaw.toString().trim()
        if (cleanupActive && wordCount(raw) > 0 && !detached) { closeUtterance("stop", isLast = true); return }
        if (raw.isNotEmpty() && !detached) commitText(raw.applySpokenReplacements(), "utt" to "tail")
        pendingRaw.clear()
        endSession(finalizeReason)
    }

    private fun endSession(reason: String) {
        if (backlog.isNotEmpty()) {
            // Ended while the editor was unreachable: hold the unseen text for this same field.
            orphanText = backlog.joinToString(" "); orphanKey = sessionEditorKey
            orphanAtMs = SystemClock.elapsedRealtime()
            DictationLog.event("backlog_orphaned", "segments" to backlog.size)
            backlog.clear()
        }
        val t = transaction
        if (t != null && !t.isFinished) {
            if (composingShown.isNotEmpty() && !detached) t.setComposing("")
            t.end()
        }
        transaction = null
        dropInFlight()
        resetTextModel()
        finalizing = false
        DictationLog.event("controller_stopped", "reason" to reason)
        latinIME.uixManager.closeActionWindowIf(DictationAction)
        if (returnToPreviousIme) {
            returnToPreviousIme = false
            try { if (android.os.Build.VERSION.SDK_INT >= 28) latinIME.switchToPreviousInputMethod() } catch (_: Exception) {}
        }
    }

    private fun resetTextModel() {
        pendingRaw.clear(); pendingWords.clear(); partial = ""; partialRaw = ""; composingShown = ""; strandedComposing = ""
        committedTail.clear()
    }

    // =====================================================================================
    // Writing to the editor (the only place that does)
    // =====================================================================================

    /**
     * Commits [text] at the cursor, replacing the whole composing region, then re-shows whatever
     * is still provisional. If the editor is unreachable the text is held in the backlog.
     */
    private fun commitText(text: String, vararg logFields: Pair<String, Any?>) {
        if (text.isEmpty()) { render(); return }
        val t = transaction
        // A null answer means the editor is not reachable right now (view hidden, shade taking
        // focus). Writing into that would be silently dropped, so hold the text instead.
        val reachable = t != null && !t.isFinished && t.textBeforeCursor(1) != null
        if (t == null || !reachable) {
            backlog.add(text)
            DictationLog.event("backlog_add", *logFields, "text" to text, "hasTransaction" to (t != null))
            return
        }
        if (backlog.isNotEmpty()) flushBacklog()
        renderJob?.cancel()
        // One atomic edit: provisional text out, committed text in, remaining provisional text back.
        t.beginBatch()
        if (composingShown.isNotEmpty()) { t.setComposing(""); composingShown = "" }
        val toCommit = withSpacing(text)
        t.commitText(toCommit)
        rememberCommitted(toCommit)
        render()
        t.endBatch()
        DictationLog.event("commit", *logFields, "text" to toCommit)
    }

    /** Commits what was said while the editor was unreachable. */
    private fun flushBacklog() {
        val t = transaction ?: return
        if (backlog.isEmpty() || t.isFinished) return
        if (t.textBeforeCursor(1) == null) return // still unreachable; keep holding
        val text = backlog.fold("") { acc, s -> smartJoin(acc, s) }
        backlog.clear()
        if (composingShown.isNotEmpty()) { t.setComposing(""); composingShown = "" }
        val toCommit = withSpacing(text)
        t.commitText(toCommit)
        rememberCommitted(toCommit)
        DictationLog.event("backlog_flushed", "text" to toCommit)
    }

    /**
     * If the provisional text could not be removed when the view went away (the editor had
     * stopped answering), it is still sitting before the cursor. Remove it so re-rendering the
     * pending words does not duplicate it.
     */
    private fun removeStrandedComposing() {
        val stranded = strandedComposing
        strandedComposing = ""
        val t = transaction ?: return
        if (stranded.isEmpty()) return
        val before = t.textBeforeCursor(stranded.length)?.toString() ?: return
        if (before == stranded) {
            t.finishComposing()
            t.deleteBefore(stranded.length)
            DictationLog.event("stranded_composing_removed", "chars" to stranded.length)
        }
    }

    /** A session ended while hidden; if the same field is back soon, its unseen text is still wanted. */
    private fun commitOrphanIfSameField(key: String) {
        val text = orphanText ?: return
        val fresh = SystemClock.elapsedRealtime() - orphanAtMs < 120_000
        orphanText = null
        if (!fresh || key != orphanKey) { DictationLog.event("orphan_dropped", "fresh" to fresh); return }
        latinIME.latinIMELegacy.onTextInputWithSpace(text)
        DictationLog.event("orphan_committed", "text" to text)
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
        DictationLog.event("freeze_for_user_edit", "why" to why, "discardUntilMs" to discardUntilMs,
            "pendingWords" to wordCount(pendingRaw), "inFlight" to (inFlight != null))
        rememberCommitted(composingShown)
        dropInFlight()
        pendingRaw.clear(); partial = ""; partialRaw = ""; composingShown = ""
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

    /** Shows everything provisional as one composing region: [in flight][pending][partial tail]. */
    private fun render() {
        val t = transaction ?: return
        if (t.isFinished) return
        lastRenderAt = SystemClock.uptimeMillis()
        val inFlightText = inFlight?.let { smartJoin(it.lead, it.raw) + it.trail } ?: ""
        val solid = smartJoin(inFlightText, pendingRaw.toString().trim()).applySpokenReplacements()
        val tail = smartJoin(solid, partial)
        if (tail.isEmpty()) {
            if (composingShown.isNotEmpty()) { t.setComposing(""); composingShown = "" }
            return
        }
        val shown = withSpacing(tail)
        if (shown == composingShown) return
        val ssb = SpannableStringBuilder(shown)
        // Dim what is still open to change: the partial tail, and a carried period standing alone.
        val dimFrom = if (wordCount(solid) == 0) 0 else shown.length - partial.length
        if (dimFrom < shown.length) {
            ssb.setSpan(ForegroundColorSpan(Color.argb(150, 128, 128, 128)), dimFrom, shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(UnderlineSpan(), dimFrom, shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        t.setComposing(ssb)
        composingShown = shown
    }

    // =====================================================================================
    // Small helpers
    // =====================================================================================

    private fun isLive(st: DictationState) = st == DictationState.Starting || st == DictationState.Listening ||
            st == DictationState.Reconnecting || st == DictationState.Paused

    /** Identity of an editor field. Input type is excluded: apps change its flags when they restart input. */
    private fun editorKey(info: EditorInfo?): String = "${info?.packageName}/${info?.fieldId}"

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

    private fun rememberCommitted(text: String) {
        committedTail.append(text)
        if (committedTail.length > CONTEXT_CHARS * 2) committedTail.delete(0, committedTail.length - CONTEXT_CHARS)
    }

    private fun trimCommittedTail(chars: Int) {
        val n = minOf(chars, committedTail.length)
        if (n > 0) committedTail.delete(committedTail.length - n, committedTail.length)
    }

    private fun wordCount(s: CharSequence): Int = CleanupGuard.words(s.toString()).size

    /** Joins two pieces of running text: a space between them unless the second starts with punctuation. */
    private fun smartJoin(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        b.first() in LEADING_ATTACH -> a + b
        else -> "$a $b"
    }

    /** Appends a recognizer fragment to the pending text, never doubling sentence punctuation. */
    private fun appendFragment(sb: StringBuilder, fragment: String) {
        val f = fragment.trim()
        if (f.isEmpty()) return
        if (sb.isNotEmpty() && sb.last() in SENTENCE_PUNCT && f.all { it in SENTENCE_PUNCT }) return
        val joined = smartJoin(sb.toString(), f)
        sb.clear(); sb.append(joined)
    }

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

    /**
     * Decide whether a space is needed before [text]. The editor is only consulted while our
     * composing region is empty; otherwise the cursor sits after our own provisional text.
     */
    private fun withSpacing(text: String): String {
        if (text.isEmpty()) return text
        if (composingShown.isEmpty()) {
            val before = transaction?.textBeforeCursor(1)
            spaceNeededBeforeComposing = when {
                before == null -> true              // editor didn't answer: we are mid-text, not at field start
                before.isEmpty() -> false           // really at the start of the field
                else -> !before.last().isWhitespace()
            }
        }
        return if (spaceNeededBeforeComposing && text.first() !in LEADING_ATTACH) " $text" else text
    }

    private fun toast(res: Int) {
        try { Toast.makeText(latinIME, res, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
    }
}
