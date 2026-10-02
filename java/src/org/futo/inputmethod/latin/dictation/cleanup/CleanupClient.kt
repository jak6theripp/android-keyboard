package org.futo.inputmethod.latin.dictation.cleanup

import android.content.Context
import android.os.SystemClock
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.MessageCreateParams
import java.time.Duration

data class CleanupRequest(
    /** Text already typed before the segment (read-only context, last couple of sentences). */
    val context: String,
    /** Sentence punctuation the recognizer put between context and segment: "", ".", "?" or "!". */
    val boundary: String,
    /** Raw recognizer output to correct, without the boundary punctuation. */
    val segment: String,
    val vocabulary: List<String>,
    /** Reply mode only: the recent conversation, for names and topic. */
    val conversation: String? = null,
)

sealed class CleanupResponse {
    /** [join] = the segment continues the context's last sentence, so the boundary punctuation is dropped. */
    data class Ok(val join: Boolean, val text: String, val ms: Long, val inputTokens: Long, val outputTokens: Long) : CleanupResponse()
    data class Failed(val reason: String, val ms: Long) : CleanupResponse()
}

/** Something that can clean one segment. Blocking; call from an IO thread. */
interface Cleaner {
    fun clean(req: CleanupRequest): CleanupResponse
    fun warmUp(): CleanupResponse
}

/**
 * Minimal-edit correction of one dictated segment with a small, fast Claude model.
 * Blocking; call from an IO thread. The caller owns the latency budget and the divergence guard.
 *
 * The system prompt lives in assets/dictation/cleanup_prompt.txt (also used by tools/dictation-eval).
 * Reply protocol: line 1 "JOIN" or "BREAK", line 2 the corrected segment.
 */
class CleanupClient(apiKey: String, private val systemPrompt: String) : Cleaner {
    companion object {
        /** Haiku-class model, per https://platform.claude.com/docs (verified 2026-10-01). */
        const val MODEL = "claude-haiku-4-5"
        private const val PROMPT_ASSET = "dictation/cleanup_prompt.txt"
        private val REPLY = Regex("^\\s*(JOIN|BREAK)\\b[\\s/:\\-]*(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        fun loadPrompt(context: Context): String =
            context.assets.open(PROMPT_ASSET).bufferedReader().use { it.readText() }.trim()
    }

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .maxRetries(0)                       // the dictation path never waits for a retry
        .timeout(Duration.ofSeconds(6))      // hard bound for a call the caller has already given up on
        .build()

    private fun userMessage(req: CleanupRequest): String = buildString {
        append("<vocabulary>\n").append(req.vocabulary.joinToString("\n")).append("\n</vocabulary>\n")
        if (!req.conversation.isNullOrBlank()) {
            append("<conversation>\n").append(req.conversation.trim()).append("\n</conversation>\n")
        }
        append("<context>\n").append(req.context.trim()).append("\n</context>\n")
        append("<boundary>").append(when (req.boundary.firstOrNull()) {
            '.' -> "period"; '?' -> "question mark"; '!' -> "exclamation mark"; else -> "none"
        }).append("</boundary>\n")
        append("<segment>\n").append(req.segment.trim()).append("\n</segment>")
    }

    override fun clean(req: CleanupRequest): CleanupResponse {
        val t0 = SystemClock.elapsedRealtime()
        fun ms() = SystemClock.elapsedRealtime() - t0
        return try {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(maxOf(256L, req.segment.length.toLong()))   // output is about as long as the segment
                .temperature(0.0)
                .system(systemPrompt)
                .addUserMessage(userMessage(req))
                .build()
            val response = client.messages().create(params)
            val stop = response.stopReason().map { it.toString() }.orElse("")
            val text = response.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
            val m = REPLY.find(text)
            when {
                stop.contains("refusal", ignoreCase = true) -> CleanupResponse.Failed("refusal", ms())
                stop.contains("max_tokens", ignoreCase = true) -> CleanupResponse.Failed("truncated", ms())
                m == null -> CleanupResponse.Failed("format", ms())
                else -> {
                    val body = sanitize(m.groupValues[2])
                    if (body.isEmpty()) CleanupResponse.Failed("format_empty", ms())
                    else CleanupResponse.Ok(m.groupValues[1].equals("JOIN", ignoreCase = true), body, ms(),
                        response.usage().inputTokens(), response.usage().outputTokens())
                }
            }
        } catch (e: RateLimitException) {
            CleanupResponse.Failed("rate_limited", ms())
        } catch (e: AnthropicServiceException) {
            // The API's message says why (bad request shape, billing, model access). It never contains the key.
            CleanupResponse.Failed("http_${e.statusCode()}: ${(e.message ?: "").replace('\n', ' ').take(220)}", ms())
        } catch (e: AnthropicIoException) {
            CleanupResponse.Failed("network", ms())
        } catch (e: Exception) {
            CleanupResponse.Failed(e.javaClass.simpleName, ms())
        }
    }

    /** Opens the TLS connection ahead of the first real request so it fits the latency budget. */
    override fun warmUp(): CleanupResponse = clean(CleanupRequest(context = "", boundary = "", segment = "ok", vocabulary = emptyList()))

    /** Strips wrappers a model occasionally adds despite instructions. Never alters the words. */
    private fun sanitize(s: String): String {
        var t = s.trim()
        t = t.removePrefix("<segment>").removeSuffix("</segment>").trim()
        if (t.length >= 2 && t.first() == '"' && t.last() == '"' && t.count { it == '"' } == 2) t = t.substring(1, t.length - 1).trim()
        return t.replace(Regex("\\s*\\n+\\s*"), " ")
    }
}
