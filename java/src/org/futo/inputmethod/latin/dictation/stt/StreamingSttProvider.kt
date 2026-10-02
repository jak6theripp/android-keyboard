package org.futo.inputmethod.latin.dictation.stt

/** Provider-neutral configuration for one streaming recognition session. */
data class SttConfig(
    val language: String = "en",
    /** Seconds the recognizer may wait for context before finalizing. */
    val maxDelay: Float = 2.0f,
    val enablePartials: Boolean = true,
    /** 0 disables end-of-utterance detection. */
    val endOfUtteranceSilence: Float = 0f,
    /** 0..1; lower = fewer punctuation marks. */
    val punctuationSensitivity: Float = 0.5f,
    val vocabulary: List<String> = emptyList(),
    val sampleRate: Int = 16000,
)

/** Events from a provider. [generation] is stamped by the engine, not the provider. */
sealed class SttEvent {
    data class Started(val sessionId: String) : SttEvent()
    /** Provisional text for audio after the last final. Whole-partial replace semantics. */
    data class Partial(val text: String, val startTime: Float, val endTime: Float) : SttEvent()
    /** Final text for [startTime, endTime]. Never changes afterwards. */
    data class Final(val text: String, val startTime: Float, val endTime: Float, val forced: Boolean) : SttEvent()
    data class EndOfUtterance(val endTime: Float) : SttEvent()
    data class Warning(val type: String, val reason: String) : SttEvent()
    /** Session is over after this. [retryable] = reconnect makes sense. */
    data class Error(val type: String, val reason: String, val code: Int?, val retryable: Boolean) : SttEvent()
    /** Server acknowledged all audio after EndOfStream. */
    object EndOfTranscript : SttEvent()
    data class Closed(val code: Int, val reason: String) : SttEvent()
}

/**
 * A single streaming STT session over one connection. Not reusable: create a new instance per
 * (re)connect. Implementations must be safe to call from any thread and must deliver events
 * sequentially (OkHttp guarantees per-socket ordering).
 */
interface StreamingSttProvider {
    val isOpen: Boolean
    fun connect(apiKey: String, config: SttConfig, listener: (SttEvent) -> Unit)
    /** Returns false if the socket is not open or the send was refused (backpressure/closed). */
    fun sendAudio(bytes: ByteArray, offset: Int, len: Int): Boolean
    /** Ask the server to flush finals. Expect EndOfTranscript then Closed. */
    fun endOfStream()
    fun close()
}
