package org.futo.inputmethod.latin.dictation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Debug/test-mode session log. One JSON object per line. Only written when the debug-logging
 * setting is on. Raw audio is only written when the separate save-audio setting is on.
 * Never receives API keys; callers must not pass them.
 */
object DictationLog {
    private const val TAG = "Dictation"
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "DictationLog") }
    private var writer: FileOutputStream? = null
    private var audio: FileOutputStream? = null
    private var t0 = 0L
    @Volatile var enabled = false
        private set
    @Volatile private var audioEnabled = false

    fun logDir(context: Context): File = File(File(context.filesDir, "dictation"), "logs").apply { mkdirs() }
    fun exportDir(context: Context): File = File(context.getExternalFilesDir(null), "dictation-logs").apply { mkdirs() }

    private val stamp get() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun startSession(context: Context, debugLogging: Boolean, saveAudio: Boolean, sessionId: String) {
        enabled = debugLogging
        audioEnabled = debugLogging && saveAudio
        t0 = SystemClock.elapsedRealtime()
        if (!enabled) return
        val name = "session-$stamp-$sessionId"
        exec.execute {
            try {
                writer?.close(); audio?.close()
                writer = FileOutputStream(File(logDir(context), "$name.jsonl"))
                audio = if (audioEnabled) FileOutputStream(File(logDir(context), "$name.pcm16le-16k.raw")) else null
            } catch (e: Exception) {
                Log.e(TAG, "log open failed", e); writer = null; audio = null
            }
        }
        event("session_start", "sessionId" to sessionId)
    }

    fun endSession() {
        event("session_end")
        exec.execute {
            try { writer?.close() } catch (_: Exception) {}
            try { audio?.close() } catch (_: Exception) {}
            writer = null; audio = null
        }
        enabled = false
    }

    /** Log an event. Also mirrors to logcat at debug level. */
    fun event(name: String, vararg fields: Pair<String, Any?>) {
        val tMs = SystemClock.elapsedRealtime() - t0
        if (Log.isLoggable(TAG, Log.DEBUG) || enabled) {
            Log.d(TAG, "$name ${fields.joinToString(" ") { "${it.first}=${it.second}" }}")
        }
        if (!enabled) return
        val line = buildJsonObject {
            put("t", JsonPrimitive(tMs))
            put("wall", JsonPrimitive(System.currentTimeMillis()))
            put("event", JsonPrimitive(name))
            fields.forEach { (k, v) ->
                put(k, when (v) {
                    null -> JsonPrimitive(null as String?)
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                })
            }
        }.toString()
        exec.execute {
            try { writer?.write((line + "\n").toByteArray()) } catch (_: Exception) {}
        }
    }

    fun audio(bytes: ByteArray, len: Int) {
        if (!audioEnabled) return
        val copy = bytes.copyOf(len)
        exec.execute { try { audio?.write(copy) } catch (_: Exception) {} }
    }

    fun listLogs(context: Context): List<File> =
        logDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Copies logs to the external files dir (adb-pullable, shareable). Returns count. */
    fun export(context: Context): Pair<Int, File> {
        val dst = exportDir(context)
        var n = 0
        listLogs(context).forEach { f ->
            try { f.copyTo(File(dst, f.name), overwrite = true); n++ } catch (_: Exception) {}
        }
        return n to dst
    }
}
