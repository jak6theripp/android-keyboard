package org.futo.inputmethod.latin.dictation.stt

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * Speechmatics real-time WebSocket API v2.
 * Protocol per https://docs.speechmatics.com/rt-api-ref (verified 2026-10-01):
 *   client → StartRecognition{audio_format, transcription_config}, AddAudio (binary), EndOfStream{last_seq_no}
 *   server → RecognitionStarted, AudioAdded{seq_no}, AddPartialTranscript, AddTranscript,
 *            EndOfUtterance, EndOfTranscript, Info, Warning, Error
 */
class SpeechmaticsProvider(private val url: String) : StreamingSttProvider {
    companion object {
        private const val TAG = "Speechmatics"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }
        /** Cap on OkHttp's outbound queue before we start dropping (never block the audio thread). */
        private const val MAX_QUEUED_BYTES = 4L * 1024 * 1024
    }

    private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var started = false
    private var seqNo = 0L
    private var listener: ((SttEvent) -> Unit)? = null

    override val isOpen: Boolean get() = open && started

    override fun connect(apiKey: String, config: SttConfig, listener: (SttEvent) -> Unit) {
        this.listener = listener
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                webSocket.send(startRecognitionMessage(config))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val wasOpen = open
                open = false
                val code = response?.code
                Log.w(TAG, "failure: ${t.javaClass.simpleName}: ${t.message} http=$code")
                val type = when (code) {
                    401, 403 -> "not_authorised"
                    404 -> "not_found"
                    null -> if (wasOpen) "connection_lost" else "connect_failed"
                    else -> "http_$code"
                }
                emit(SttEvent.Error(type, t.message ?: t.javaClass.simpleName, code, retryable = code == null || code >= 500))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
                emit(SttEvent.Closed(code, reason))
            }
        })
    }

    private fun emit(e: SttEvent) { listener?.invoke(e) }

    private fun startRecognitionMessage(c: SttConfig): String = buildJsonObject {
        put("message", "StartRecognition")
        putJsonObject("audio_format") {
            put("type", "raw"); put("encoding", "pcm_s16le"); put("sample_rate", c.sampleRate)
        }
        putJsonObject("transcription_config") {
            put("language", c.language)
            put("max_delay", c.maxDelay.coerceIn(0.7f, 4.0f))
            put("max_delay_mode", "flexible")
            put("enable_partials", c.enablePartials)
            put("enable_entities", true)
            putJsonObject("punctuation_overrides") {
                put("sensitivity", c.punctuationSensitivity.coerceIn(0f, 1f))
            }
            if (c.endOfUtteranceSilence > 0f) {
                putJsonObject("conversation_config") {
                    // Must stay below max_delay per docs; cap at 2.0 (API max).
                    put("end_of_utterance_silence_trigger",
                        minOf(c.endOfUtteranceSilence, c.maxDelay - 0.1f).coerceIn(0.1f, 2.0f))
                }
            }
            if (c.vocabulary.isNotEmpty()) {
                put("additional_vocab", buildJsonArray {
                    c.vocabulary.take(1000).forEach { term ->
                        add(buildJsonObject { put("content", term) })
                    }
                })
            }
        }
    }.toString()

    private fun handle(text: String) {
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) {
            Log.w(TAG, "unparseable message"); return
        }
        when (obj.str("message")) {
            "RecognitionStarted" -> { started = true; emit(SttEvent.Started(obj.str("id") ?: "")) }
            "AudioAdded" -> { /* seq ack; not needed beyond EndOfStream bookkeeping */ }
            "AddPartialTranscript" -> emit(SttEvent.Partial(
                obj.transcript(), obj.meta("start_time"), obj.meta("end_time")))
            "AddTranscript" -> emit(SttEvent.Final(
                obj.transcript(), obj.meta("start_time"), obj.meta("end_time"),
                obj["forced"]?.jsonPrimitive?.booleanOrNull == true, obj.words()))
            "EndOfUtterance" -> emit(SttEvent.EndOfUtterance(obj.meta("end_time")))
            "EndOfTranscript" -> emit(SttEvent.EndOfTranscript)
            "Info" -> Log.i(TAG, "info ${obj.str("type")}: ${obj.str("reason")}")
            "Warning" -> emit(SttEvent.Warning(obj.str("type") ?: "", obj.str("reason") ?: ""))
            "Error" -> {
                val type = obj.str("type") ?: "unknown_error"
                val retryable = type in setOf("idle_timeout", "session_timeout", "timelimit_exceeded",
                    "job_error", "quota_exceeded", "unknown_error", "internal_error")
                emit(SttEvent.Error(type, obj.str("reason") ?: "", obj["code"]?.jsonPrimitive?.intOrNull, retryable))
            }
            else -> Log.d(TAG, "unhandled ${obj.str("message")}")
        }
    }

    /** Per-word confidence from results[].alternatives[0]; empty when the server did not send them. */
    private fun JsonObject.words(): List<SttWord> {
        val results = this["results"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val out = ArrayList<SttWord>(results.size)
        results.forEach { r ->
            val o = r.jsonObject
            val alt = (o["alternatives"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject ?: return@forEach
            val content = alt["content"]?.jsonPrimitive?.content ?: return@forEach
            val conf = alt["confidence"]?.jsonPrimitive?.floatOrNull ?: 1f
            out.add(SttWord(content, conf, o["type"]?.jsonPrimitive?.content == "punctuation"))
        }
        return out
    }

    private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.let { if (it.isString) it.content else it.content }
    /** The formatted text lives at metadata.transcript; fall back to top-level, then to joining results. */
    private fun JsonObject.transcript(): String {
        this["metadata"]?.jsonObject?.get("transcript")?.jsonPrimitive?.content?.let { return it }
        this["transcript"]?.jsonPrimitive?.content?.let { return it }
        val results = this["results"] as? kotlinx.serialization.json.JsonArray ?: return ""
        val sb = StringBuilder()
        results.forEach { r ->
            val o = r.jsonObject
            val content = (o["alternatives"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()?.jsonObject?.get("content")?.jsonPrimitive?.content ?: return@forEach
            val isPunct = o["type"]?.jsonPrimitive?.content == "punctuation"
            if (sb.isNotEmpty() && !isPunct) sb.append(' ')
            sb.append(content)
        }
        return sb.toString()
    }

    private fun JsonObject.meta(k: String): Float =
        this["metadata"]?.jsonObject?.get(k)?.jsonPrimitive?.floatOrNull ?: 0f

    override fun sendAudio(bytes: ByteArray, offset: Int, len: Int): Boolean {
        val socket = ws ?: return false
        if (!isOpen) return false
        if (socket.queueSize() > MAX_QUEUED_BYTES) return false
        val ok = socket.send(bytes.toByteString(offset, len))
        if (ok) seqNo++
        return ok
    }

    override fun endOfStream() {
        val socket = ws ?: return
        if (!open) return
        socket.send(buildJsonObject { put("message", "EndOfStream"); put("last_seq_no", seqNo) }.toString())
    }

    override fun close() {
        open = false
        try { ws?.close(1000, "client closing") } catch (_: Exception) {}
        ws = null
    }
}
