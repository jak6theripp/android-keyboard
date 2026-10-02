package org.futo.inputmethod.latin.dictation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.futo.inputmethod.latin.dictation.cleanup.CleanupClient
import org.futo.inputmethod.latin.dictation.cleanup.CleanupGuard
import org.futo.inputmethod.latin.dictation.cleanup.CleanupRequest
import org.futo.inputmethod.latin.dictation.cleanup.CleanupResponse
import org.futo.inputmethod.latin.uix.getSetting

/**
 * Test hooks for unattended testing over adb. Protected by android.permission.DUMP in the
 * manifest, so only the shell and the system can send these.
 *
 *   am broadcast -n <pkg>/.dictation.DictationDebugReceiver -a <pkg>.dictation.DEBUG_AUDIO --es path <raw pcm>
 *   ... -a <pkg>.dictation.DEBUG_TOGGLE          start/stop like a tap on the mic action
 *   ... -a <pkg>.dictation.DEBUG_STOP
 *   ... -a <pkg>.dictation.DEBUG_FIELD           logs the text before the cursor
 *   ... -a <pkg>.dictation.DEBUG_CLEANUP --es context "..." --es segment "..."
 *
 * Results go to logcat under the tag DictationDebug.
 */
class DictationDebugReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DictationDebug"
        private const val P = "org.futo.inputmethod.latin.dictation."
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            P + "DEBUG_AUDIO" -> {
                DictationEngine.debugAudioPath = intent.getStringExtra("path")
                Log.i(TAG, "AUDIO path=${DictationEngine.debugAudioPath}")
            }
            P + "DEBUG_TOGGLE" -> {
                val c = DictationController.instance
                Log.i(TAG, "TOGGLE controller=${c != null} viewActive=${c?.isInputViewActive} engine=${DictationEngine.state.value}")
                c?.debugToggle()
            }
            P + "DEBUG_FAKE_CLEANUP" -> {
                DictationController.debugFakeCleanupMs = intent.getLongExtra("ms", 0L)
                Log.i(TAG, "FAKE_CLEANUP ms=${DictationController.debugFakeCleanupMs}")
            }
            P + "DEBUG_STOP" -> { DictationEngine.stop("debug"); Log.i(TAG, "STOP") }
            P + "DEBUG_FIELD" -> {
                val text = DictationController.instance?.debugTextBeforeCursor(6000)
                Log.i(TAG, "FIELD len=${text?.length} text=[${text}]")
            }
            P + "DEBUG_CLEANUP_BATCH" -> {
                // cases: JSON array of {id, context, segment}; prompt: optional system-prompt override file.
                val ctx = context.applicationContext
                val casesPath = intent.getStringExtra("cases") ?: return
                val promptPath = intent.getStringExtra("prompt")
                val pending = goAsync()
                Thread {
                    try {
                        val key = SecureKeys.get(ctx, SecureKeys.KEY_ANTHROPIC)
                        if (key == null) { Log.i(TAG, "BATCH error=no_key"); return@Thread }
                        val prompt = promptPath?.let { java.io.File(it).readText().trim() } ?: CleanupClient.loadPrompt(ctx)
                        val client = CleanupClient(key, prompt)
                        val vocab = ctx.getSetting(DICTATION_VOCAB).toVocabList()
                        client.warmUp()
                        val cases = kotlinx.serialization.json.Json.parseToJsonElement(java.io.File(casesPath).readText()) as kotlinx.serialization.json.JsonArray
                        for (c in cases) {
                            val o = c as kotlinx.serialization.json.JsonObject
                            fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                            val segment = s("segment")
                            when (val r = client.clean(CleanupRequest(s("context"), s("boundary"), segment, vocab))) {
                                is CleanupResponse.Ok -> {
                                    val v = CleanupGuard.check(segment, r.text)
                                    Log.i(TAG, "BATCH id=${s("id")} ms=${r.ms} verdict=${if (v.accepted) "accept" else "reject"}:${v.reason} edits=${"%.2f".format(v.edits)}/${v.allowed} join=${r.join} out=[${r.text}]")
                                }
                                is CleanupResponse.Failed -> Log.i(TAG, "BATCH id=${s("id")} ms=${r.ms} failed=${r.reason}")
                            }
                        }
                        Log.i(TAG, "BATCH done n=${cases.size}")
                    } catch (e: Exception) {
                        Log.i(TAG, "BATCH error=${e.javaClass.simpleName}: ${e.message}")
                    } finally { pending.finish() }
                }.start()
            }
            P + "DEBUG_CLEANUP" -> {
                val ctx = context.applicationContext
                val segment = intent.getStringExtra("segment") ?: return
                val before = intent.getStringExtra("context") ?: ""
                val id = intent.getStringExtra("id") ?: "-"
                val pending = goAsync()
                Thread {
                    try {
                        val key = SecureKeys.get(ctx, SecureKeys.KEY_ANTHROPIC)
                        if (key == null) { Log.i(TAG, "CLEANUP id=$id error=no_key"); return@Thread }
                        val client = CleanupClient(key, CleanupClient.loadPrompt(ctx))
                        val vocab = ctx.getSetting(DICTATION_VOCAB).toVocabList()
                        if (intent.getBooleanExtra("warm", true)) client.warmUp()
                        when (val r = client.clean(CleanupRequest(before, intent.getStringExtra("boundary") ?: "", segment, vocab))) {
                            is CleanupResponse.Ok -> {
                                val v = CleanupGuard.check(segment, r.text)
                                Log.i(TAG, "CLEANUP id=$id ms=${r.ms} in=${r.inputTokens} out=${r.outputTokens} verdict=${if (v.accepted) "accept" else "reject"}:${v.reason} edits=${v.edits}/${v.allowed} join=${r.join} out=[${r.text}]")
                            }
                            is CleanupResponse.Failed -> Log.i(TAG, "CLEANUP id=$id ms=${r.ms} failed=${r.reason}")
                        }
                    } finally { pending.finish() }
                }.start()
            }
        }
    }
}
