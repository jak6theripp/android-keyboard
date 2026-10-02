package org.futo.inputmethod.latin.dictation

import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * System voice-recognition entry point (path 1 of the nav-bar mic hook). Any client that uses
 * SpeechRecognizer with this service as the recognizer gets streaming recognition from the same
 * engine the keyboard uses. One utterance per session, as the SpeechRecognizer contract expects:
 * results are delivered when the speaker goes quiet or the client calls stopListening.
 *
 * Results go to the calling client (this is not the keyboard typing). The cleanup pass is not
 * applied here; text is the recognizer's own.
 */
class DictationRecognitionService : RecognitionService() {
    companion object { private const val TAG = "DictationRecSvc" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null
    private var callback: Callback? = null
    private var session = 0L
    private val finals = StringBuilder()
    private var wantPartials = false
    private var speechBegun = false
    private var cancelled = false
    private var lastPartial = ""

    override fun onStartListening(intent: Intent, cb: Callback) {
        val caller = try { packageManager.getNameForUid(cb.callingUid) } catch (_: Exception) { null }
        Log.i(TAG, "onStartListening caller=$caller engine=${DictationEngine.state.value}")
        if (DictationEngine.isActive) {
            try { cb.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) } catch (_: RemoteException) {}
            return
        }
        callback = cb
        finals.clear(); speechBegun = false; cancelled = false; lastPartial = ""
        wantPartials = intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        collector?.cancel()
        collector = scope.launch { DictationEngine.events.collect { handle(it) } }
        // Bound by a foreground client, so no foreground service of our own is needed (or allowed).
        DictationEngine.start(this, "recognition_service(caller=$caller)", useForegroundService = false)
        session = 0L
        try { cb.readyForSpeech(Bundle()) } catch (_: RemoteException) {}
    }

    private fun currentText(partial: String = ""): String {
        val base = finals.toString().trim()
        val p = partial.trim()
        return when {
            base.isEmpty() -> p
            p.isEmpty() -> base
            p.first() in ".,!?;:" -> base + p
            else -> "$base $p"
        }
    }

    /** If [partial] begins with the words of [final], the words after them; else "". */
    private fun remainder(partial: String, final: String): String {
        fun norm(w: String) = w.lowercase().filter { it.isLetterOrDigit() }
        val p = partial.split(' ').filter { it.isNotEmpty() }
        val f = final.split(' ').filter { it.isNotEmpty() }
        if (f.isEmpty() || f.size > p.size) return ""
        for (i in f.indices) if (norm(p[i]) != norm(f[i])) return ""
        return p.drop(f.size).joinToString(" ")
    }

    private fun bundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1.0f))
    }

    private fun handle(ev: DictationEvent) {
        val cb = callback ?: return
        try {
            when (ev) {
                is DictationEvent.Partial -> {
                    if (ev.text.isBlank()) return
                    if (!speechBegun) { speechBegun = true; cb.beginningOfSpeech() }
                    lastPartial = ev.text.trim()
                    if (wantPartials) cb.partialResults(bundle(currentText(lastPartial)))
                }
                is DictationEvent.Final -> {
                    if (!speechBegun) { speechBegun = true; cb.beginningOfSpeech() }
                    val t = ev.text.trim()
                    if (finals.isNotEmpty() && t.isNotEmpty() && t.first() !in ".,!?;:") finals.append(' ')
                    finals.append(t)
                    // The words just finalized were the head of the last partial; keep showing its
                    // tail so the client's text never shrinks.
                    lastPartial = remainder(lastPartial, t)
                    if (wantPartials) cb.partialResults(bundle(currentText(lastPartial)))
                }
                is DictationEvent.EndOfUtterance -> {
                    // One utterance per SpeechRecognizer session.
                    if (finals.isNotBlank()) DictationEngine.stop("recognition_service_end_of_utterance")
                }
                is DictationEvent.Error -> if (ev.fatal) {
                    cb.error(if ("no_api_key" in ev.message) SpeechRecognizer.ERROR_CLIENT else SpeechRecognizer.ERROR_SERVER)
                    callback = null
                }
                is DictationEvent.Stopped -> {
                    Log.i(TAG, "stopped reason=${ev.reason} chars=${finals.length} cancelled=$cancelled")
                    if (!cancelled) {
                        if (finals.isNotBlank()) { cb.endOfSpeech(); cb.results(bundle(currentText())) }
                        else cb.error(if (ev.reason == "idle_timeout") SpeechRecognizer.ERROR_SPEECH_TIMEOUT else SpeechRecognizer.ERROR_NO_MATCH)
                    }
                    callback = null
                    collector?.cancel(); collector = null
                }
                else -> {}
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "client went away")
            callback = null
            DictationEngine.stop("recognition_service_client_gone")
        }
    }

    override fun onStopListening(cb: Callback) {
        Log.i(TAG, "onStopListening")
        DictationEngine.stop("recognition_service_stop")
    }

    override fun onCancel(cb: Callback) {
        Log.i(TAG, "onCancel")
        cancelled = true
        DictationEngine.stop("recognition_service_cancel")
    }

    override fun onDestroy() {
        if (callback != null) DictationEngine.stop("recognition_service_destroyed")
        scope.cancel()
        super.onDestroy()
    }
}
