package org.futo.inputmethod.latin.dictation.cleanup

import android.os.SystemClock
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.runBlocking

/**
 * SPIKE (branch nano-spike): the cleanup pass on the phone's built-in Gemini Nano through the
 * ML Kit GenAI Prompt API, behind the same [Cleaner] interface and the same JOIN/BREAK reply
 * protocol as [CleanupClient], so both can be run over tools/dictation-eval and compared.
 * Not wired into dictation. Reached only from DictationDebugReceiver.
 */
class NanoCleaner(private val systemPrompt: String) : Cleaner {
    companion object {
        private val REPLY = Regex("^\\s*(JOIN|BREAK)\\b[\\s/:\\-]*(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        fun statusName(status: Int) = when (status) {
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING"
            FeatureStatus.AVAILABLE -> "AVAILABLE"
            else -> "status_$status"
        }

        fun errorName(code: Int) = when (code) {
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "BACKGROUND_USE_BLOCKED"
            GenAiException.ErrorCode.BUSY -> "BUSY"
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "PER_APP_BATTERY_USE_QUOTA_EXCEEDED"
            GenAiException.ErrorCode.NOT_AVAILABLE -> "NOT_AVAILABLE"
            GenAiException.ErrorCode.NOT_SUPPORTED -> "NOT_SUPPORTED"
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> "AICORE_INCOMPATIBLE"
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "NEEDS_SYSTEM_UPDATE"
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "NOT_ENOUGH_DISK_SPACE"
            GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR -> "REQUEST_PROCESSING_ERROR"
            GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR -> "RESPONSE_PROCESSING_ERROR"
            GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR -> "RESPONSE_GENERATION_ERROR"
            GenAiException.ErrorCode.REQUEST_TOO_LARGE -> "REQUEST_TOO_LARGE"
            GenAiException.ErrorCode.CANCELLED -> "CANCELLED"
            else -> "error_$code"
        }

        fun describe(e: Throwable): String = when (e) {
            is GenAiException -> "${errorName(e.errorCode)}: ${(e.message ?: "").replace('\n', ' ').take(160)}"
            else -> "${e.javaClass.simpleName}: ${(e.message ?: "").replace('\n', ' ').take(160)}"
        }
    }

    val model: GenerativeModel = Generation.getClient()
    private var useSystemInstruction: Boolean? = null

    /** Availability, model name and limits, as one log line. Never throws. */
    fun statusReport(): String = runBlocking {
        val sb = StringBuilder()
        try { sb.append("status=").append(statusName(model.checkStatus())) } catch (e: Throwable) { sb.append("status_error=[").append(describe(e)).append("]") }
        try { sb.append(" model=").append(model.getBaseModelName()) } catch (e: Throwable) { sb.append(" model_error=[").append(describe(e)).append("]") }
        try { sb.append(" tokenLimit=").append(model.getTokenLimit()) } catch (e: Throwable) { sb.append(" tokenLimit_error=[").append(describe(e)).append("]") }
        try { sb.append(" systemPrompt=").append(model.isSystemPromptAvailable()) } catch (e: Throwable) { sb.append(" systemPrompt_error=[").append(describe(e)).append("]") }
        sb.toString()
    }

    fun status(): Int = runBlocking { model.checkStatus() }

    /** Downloads the model if needed, reporting progress through [log]. Returns true when it is ready. */
    fun download(log: (String) -> Unit): Boolean = runBlocking {
        var ok = false
        try {
            model.download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadStarted -> log("download started bytesToDownload=${s.bytesToDownload}")
                    is DownloadStatus.DownloadProgress -> log("download progress bytes=${s.totalBytesDownloaded}")
                    is DownloadStatus.DownloadCompleted -> { ok = true; log("download completed") }
                    is DownloadStatus.DownloadFailed -> log("download failed ${describe(s.e)}")
                }
            }
        } catch (e: Throwable) { log("download error ${describe(e)}") }
        ok
    }

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
            val text = runBlocking {
                val system = useSystemInstruction ?: (try { model.isSystemPromptAvailable() } catch (e: Throwable) { false }).also { useSystemInstruction = it }
                val request = if (system) {
                    generateContentRequest(SystemInstruction(systemPrompt), TextPart(userMessage(req))) {
                        temperature = 0f; topK = 1; candidateCount = 1; maxOutputTokens = 256
                    }
                } else {
                    generateContentRequest(TextPart(systemPrompt + "\n\n" + userMessage(req))) {
                        temperature = 0f; topK = 1; candidateCount = 1; maxOutputTokens = 256
                    }
                }
                model.generateContent(request).candidates.firstOrNull()?.text ?: ""
            }
            val m = REPLY.find(text)
            if (m == null) return CleanupResponse.Failed("format: [${text.replace('\n', ' ').take(120)}]", ms())
            var body = m.groupValues[2].trim().removePrefix("<segment>").removeSuffix("</segment>").trim()
            if (body.length >= 2 && body.first() == '"' && body.last() == '"' && body.count { it == '"' } == 2) body = body.substring(1, body.length - 1).trim()
            body = body.replace(Regex("\\s*\\n+\\s*"), " ")
            if (body.isEmpty()) CleanupResponse.Failed("format_empty", ms())
            else CleanupResponse.Ok(m.groupValues[1].equals("JOIN", ignoreCase = true), body, ms(), 0, 0)
        } catch (e: Throwable) {
            CleanupResponse.Failed(describe(e), ms())
        }
    }

    override fun warmUp(): CleanupResponse {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            runBlocking { model.warmup() }
            CleanupResponse.Ok(false, "warm", SystemClock.elapsedRealtime() - t0, 0, 0)
        } catch (e: Throwable) {
            CleanupResponse.Failed(describe(e), SystemClock.elapsedRealtime() - t0)
        }
    }

    fun close() { try { model.close() } catch (_: Throwable) {} }
}
