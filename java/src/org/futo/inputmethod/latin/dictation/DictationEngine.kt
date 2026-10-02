package org.futo.inputmethod.latin.dictation

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.futo.inputmethod.latin.dictation.audio.AudioCapture
import org.futo.inputmethod.latin.dictation.audio.PcmRingBuffer
import org.futo.inputmethod.latin.dictation.stt.SpeechmaticsProvider
import org.futo.inputmethod.latin.dictation.stt.SttConfig
import org.futo.inputmethod.latin.dictation.stt.SttEvent
import org.futo.inputmethod.latin.dictation.stt.StreamingSttProvider
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class DictationState { Idle, Starting, Listening, Reconnecting, Paused, Stopping }

/** Events for the text controller. Every text event carries the session and a segment id. */
sealed class DictationEvent {
    data class Partial(val sessionId: Long, val segmentId: Long, val text: String, val audioStartMs: Long, val audioEndMs: Long) : DictationEvent()
    data class Final(val sessionId: Long, val segmentId: Long, val text: String, val speechEndMs: Long, val audioStartMs: Long, val audioEndMs: Long) : DictationEvent()
    data class EndOfUtterance(val sessionId: Long, val lastSegmentId: Long) : DictationEvent()
    data class IdleCountdown(val secondsLeft: Int) : DictationEvent()
    data class Level(val rms: Float) : DictationEvent()
    data class Error(val sessionId: Long, val message: String, val fatal: Boolean) : DictationEvent()
    /** [flushed] = the recognizer confirmed every final was delivered before the session ended. */
    data class Stopped(val sessionId: Long, val reason: String, val flushed: Boolean) : DictationEvent()
}

/**
 * Process-wide dictation session: mic capture, streaming STT, bounded replay buffer, reconnect,
 * idle timeout. Lives independently of IME views; hosted by [DictationService] for the
 * microphone foreground-service type. All state mutation happens on [engineDispatcher].
 */
object DictationEngine {
    private const val TAG = "DictationEngine"
    private const val RING_SECONDS = 30
    private const val IDLE_WARN_S = 5
    private const val REPLAY_CHUNK = 3200 // 100 ms

    private val engineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "DictationEngine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)

    private val _state = MutableStateFlow(DictationState.Idle)
    val state: StateFlow<DictationState> = _state
    private val _events = MutableSharedFlow<DictationEvent>(replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<DictationEvent> = _events
    val isActive get() = _state.value != DictationState.Idle

    private val sessionCounter = AtomicLong(0)
    @Volatile var sessionId = 0L; private set

    // Per-session state (engine thread only unless noted)
    private var appContext: Context? = null
    private var capture: AudioCapture? = null
    private val ring = PcmRingBuffer(AudioCapture.BYTES_PER_SECOND * RING_SECONDS)
    private var provider: StreamingSttProvider? = null
    private var generation = 0            // increments per (re)connect; stale socket events are dropped
    private var sessionBaseOffset = 0L    // ring offset of audio byte 0 for the current socket session
    private var sendCursor = 0L           // next ring offset to send
    private var lastFinalAbsOffset = 0L   // ring offset just after the last received final
    private var segmentCounter = 0L
    private var lastSpeechActivityMs = 0L
    private var lastSpeechAudioOffset = 0L
    private var idleTimeoutS = 30
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var idleJob: Job? = null
    private var stopRequested = false
    private var config = SttConfig()
    private var apiKey: String? = null
    private var sttUrl = ""
    private val audioSignal = Channel<Unit>(Channel.CONFLATED)
    private var senderJob: Job? = null
    private var endOfTranscriptSignal = Channel<Unit>(Channel.CONFLATED)

    // Utterance detection (the recognizer's own end-of-utterance is off: it forces periods at pauses)
    private const val UTTERANCE_QUIET_MS = 700L
    private var lastTranscriptWallMs = 0L
    private var lastPartialBlank = true
    private var lastEouSegment = 0L
    private var quietJob: Job? = null

    /** Debug: feed this raw 16 kHz mono PCM16 file instead of the microphone for the next session. */
    @Volatile var debugAudioPath: String? = null
    @Volatile private var injecting = false
    private var injectThread: Thread? = null
    @Volatile private var reopenSuppressed = false

    /** Set by the controller before it re-opens the status window after a view recreation. */
    fun suppressNextToggle() { reopenSuppressed = true }
    fun consumeToggleSuppression(): Boolean = reopenSuppressed.also { reopenSuppressed = false }

    private fun setState(s: DictationState) {
        if (_state.value != s) { _state.value = s; DictationLog.event("state", "state" to s.name) }
    }

    private var usingFgs = true

    /**
     * Starts a session. From the IME this starts the microphone foreground service and capture
     * begins in [onServiceReady]. A RecognitionService is already bound by a foreground client and
     * passes [useForegroundService] = false.
     */
    fun start(context: Context, triggerPath: String, useForegroundService: Boolean = true) {
        val ctx = context.applicationContext
        scope.launch {
            if (_state.value != DictationState.Idle) { Log.i(TAG, "start ignored: ${_state.value}"); return@launch }
            appContext = ctx
            usingFgs = useForegroundService
            // Which system path started dictation (keyboard key, voice subtype, RecognitionService).
            try {
                val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                ctx.setSetting(DICTATION_LAST_TRIGGER_PATH, "$triggerPath at $stamp")
            } catch (_: Exception) {}
            Log.i(TAG, "dictation start trigger=$triggerPath")
            stopRequested = false
            sessionId = sessionCounter.incrementAndGet()
            segmentCounter = 0
            reconnectAttempt = 0
            generation = 0
            config = SttConfig(
                language = ctx.getSetting(DICTATION_LANGUAGE),
                maxDelay = ctx.getSetting(DICTATION_MAX_DELAY).coerceIn(DICTATION_MAX_DELAY_MIN, DICTATION_MAX_DELAY_MAX),
                enablePartials = true,
                endOfUtteranceSilence = ctx.getSetting(DICTATION_EOU_SILENCE_S),
                punctuationSensitivity = ctx.getSetting(DICTATION_PUNCT_SENSITIVITY),
                vocabulary = ctx.getSetting(DICTATION_VOCAB).toVocabList(),
            )
            idleTimeoutS = ctx.getSetting(DICTATION_IDLE_TIMEOUT_S).coerceAtLeast(5)
            sttUrl = ctx.getSetting(DICTATION_STT_URL)
            DictationLog.startSession(ctx, ctx.getSetting(DICTATION_DEBUG_LOGGING), ctx.getSetting(DICTATION_SAVE_AUDIO), sessionId.toString())
            DictationLog.event("session_config", "trigger" to triggerPath, "maxDelay" to config.maxDelay,
                "eou" to config.endOfUtteranceSilence, "punct" to config.punctuationSensitivity, "idleTimeoutS" to idleTimeoutS, "vocab" to config.vocabulary.size)
            // A freshly pushed keys file is imported (and deleted) automatically.
            if (SecureKeys.importFile(ctx).exists()) {
                val r = SecureKeys.importFromFile(ctx)
                DictationLog.event("keys_import", "ok" to (r is SecureKeys.ImportResult.Ok),
                    "detail" to when (r) { is SecureKeys.ImportResult.Ok -> r.imported.joinToString(); is SecureKeys.ImportResult.Failed -> r.reason })
            }
            apiKey = SecureKeys.get(ctx, SecureKeys.KEY_SPEECHMATICS)
            if (apiKey == null) {
                DictationLog.event("error", "reason" to "no_api_key")
                _events.emit(DictationEvent.Error(sessionId, "no_api_key", fatal = true))
                finishStop("no_api_key"); return@launch
            }
            setState(DictationState.Starting)
            if (!useForegroundService) { beginCapture(); return@launch }
            try {
                ctx.startForegroundService(Intent(ctx, DictationService::class.java).setAction(DictationService.ACTION_START))
                DictationLog.event("fgs_start_requested")
            } catch (e: Exception) {
                DictationLog.event("error", "reason" to "fgs_start_failed", "exc" to e.toString())
                _events.emit(DictationEvent.Error(sessionId, "fgs_start_failed: ${e.javaClass.simpleName}", fatal = true))
                finishStop("fgs_start_failed")
            }
        }
    }

    /** Called by [DictationService] once startForeground succeeded. */
    fun onServiceReady() {
        scope.launch {
            if (_state.value != DictationState.Starting) return@launch
            DictationLog.event("fgs_ready")
            beginCapture()
        }
    }

    private suspend fun beginCapture() {
        run {
            val ctx = appContext ?: return
            // Fresh session: forget audio from earlier sessions.
            sessionBaseOffset = ring.totalWritten
            sendCursor = sessionBaseOffset
            lastFinalAbsOffset = sessionBaseOffset
            lastSpeechActivityMs = SystemClock.elapsedRealtime()
            lastSpeechAudioOffset = sessionBaseOffset
            lastTranscriptWallMs = SystemClock.elapsedRealtime(); lastPartialBlank = true; lastEouSegment = 0
            val inject = debugAudioPath
            debugAudioPath = null
            if (inject != null) {
                startInjection(inject)
                DictationLog.event("capture_started", "injected" to inject)
            } else {
                val cap = AudioCapture(ctx, captureListener)
                if (!cap.start(requestFocus = true)) { finishStop("capture_failed"); return }
                capture = cap
                DictationLog.event("capture_started")
            }
            startIdleTimer()
            startQuietDetector()
            startSender()
            connect()
        }
    }

    /** Called by [DictationService] if startForeground threw (e.g. ForegroundServiceStartNotAllowed). */
    fun onServiceFailed(reason: String) {
        scope.launch {
            DictationLog.event("error", "reason" to "fgs_failed", "detail" to reason)
            _events.emit(DictationEvent.Error(sessionId, "fgs_failed: $reason", fatal = true))
            finishStop("fgs_failed")
        }
    }

    fun stop(reason: String) {
        scope.launch {
            if (_state.value == DictationState.Idle || stopRequested) return@launch
            stopRequested = true
            setState(DictationState.Stopping)
            reconnectJob?.cancel(); reconnectJob = null
            idleJob?.cancel(); idleJob = null
            capture?.stop(); capture = null
            DictationLog.event("stop_requested", "reason" to reason)
            // Flush: send whatever audio is still buffered, then let the server finalize.
            senderJob?.cancel(); senderJob = null
            val p = provider
            var flushed = false
            if (p != null && p.isOpen) {
                drainTo(p)
                endOfTranscriptSignal = Channel(Channel.CONFLATED)
                p.endOfStream()
                flushed = withTimeoutOrNull(2500) { endOfTranscriptSignal.receive() } != null
                DictationLog.event("flush", "gotEndOfTranscript" to flushed)
            }
            finishStop(reason, flushed)
        }
    }

    private suspend fun finishStop(reason: String, flushed: Boolean = false) {
        senderJob?.cancel(); senderJob = null
        provider?.close(); provider = null
        capture?.stop(); capture = null
        injecting = false
        idleJob?.cancel(); idleJob = null
        quietJob?.cancel(); quietJob = null
        reconnectJob?.cancel(); reconnectJob = null
        if (usingFgs) appContext?.let { try { it.startService(Intent(it, DictationService::class.java).setAction(DictationService.ACTION_STOP)) } catch (_: Exception) {} }
        val sid = sessionId
        setState(DictationState.Idle)
        _events.emit(DictationEvent.Stopped(sid, reason, flushed))
        DictationLog.endSession()
        apiKey = null
    }

    // ---- audio ----
    private val captureListener = object : AudioCapture.Listener {
        override fun onAudio(bytes: ByteArray, len: Int) {
            ring.write(bytes, len)
            DictationLog.audio(bytes, len)
            audioSignal.trySend(Unit)
        }
        private var levelN = 0
        private var levelMax = 0f
        override fun onLevel(rms: Float) {
            _events.tryEmit(DictationEvent.Level(rms))
            // Every ~2 s record the peak level: proves the FGS mic is delivering real audio.
            levelMax = maxOf(levelMax, rms)
            if (++levelN % 8 == 0) { DictationLog.event("mic_level", "peakRms" to levelMax); levelMax = 0f }
        }
        override fun onFocusChange(focusChange: Int) {
            scope.launch {
                DictationLog.event("audio_focus", "change" to focusChange)
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> stop("audio_focus_loss")
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (_state.value == DictationState.Listening) setState(DictationState.Paused)
                    AudioManager.AUDIOFOCUS_GAIN -> if (_state.value == DictationState.Paused) setState(DictationState.Listening)
                }
            }
        }
        override fun onError(message: String) {
            scope.launch {
                DictationLog.event("error", "reason" to "capture", "detail" to message)
                _events.emit(DictationEvent.Error(sessionId, "capture: $message", fatal = true))
                stop("capture_error")
            }
        }
    }

    /** Drains the ring buffer to the provider from [sendCursor]. Also performs replay after reconnect. */
    private fun startSender() {
        senderJob?.cancel()
        senderJob = scope.launch {
            while (true) {
                audioSignal.receive()
                val p = provider ?: continue
                if (!p.isOpen) continue
                drainTo(p)
            }
        }
    }

    /** Engine thread. Sends ring[sendCursor..] to [p]; stops early on backpressure or a closed socket. */
    private fun drainTo(p: StreamingSttProvider) {
        while (sendCursor < ring.totalWritten) {
            val (start, bytes) = ring.read(sendCursor, REPLAY_CHUNK)
            if (start > sendCursor) {
                DictationLog.event("audio_dropped", "bytes" to (start - sendCursor))
                sendCursor = start
            }
            if (bytes.isEmpty()) break
            if (!p.sendAudio(bytes, 0, bytes.size)) break // retry on next signal
            sendCursor += bytes.size
        }
    }

    // ---- connection ----
    private fun connect() {
        val key = apiKey ?: return
        generation++
        val gen = generation
        val p = SpeechmaticsProvider(sttUrl)
        provider = p
        // Audio for this socket starts at the last received final (replay anything unconfirmed).
        sessionBaseOffset = lastFinalAbsOffset
        sendCursor = sessionBaseOffset
        if (sendCursor < ring.oldestAvailable) {
            DictationLog.event("replay_truncated", "lost" to (ring.oldestAvailable - sendCursor))
            sendCursor = ring.oldestAvailable; sessionBaseOffset = sendCursor
        }
        DictationLog.event("connect", "gen" to gen, "replayBytes" to (ring.totalWritten - sendCursor), "url" to sttUrl)
        p.connect(key, config) { ev -> scope.launch { if (gen == generation) onSttEvent(ev) else DictationLog.event("stale_socket_event", "gen" to gen, "ev" to ev.javaClass.simpleName) } }
    }

    private fun timeToAbsOffset(seconds: Float): Long =
        sessionBaseOffset + (seconds * AudioCapture.BYTES_PER_SECOND).toLong()

    /** Position on the continuous audio timeline of the engine (survives reconnects), in ms. */
    private fun timeToAbsMs(seconds: Float): Long = timeToAbsOffset(seconds) * 1000 / AudioCapture.BYTES_PER_SECOND

    private suspend fun onSttEvent(ev: SttEvent) {
        when (ev) {
            is SttEvent.Started -> {
                reconnectAttempt = 0
                DictationLog.event("recognition_started", "sttSessionId" to ev.sessionId)
                if (!stopRequested) setState(DictationState.Listening)
                audioSignal.trySend(Unit)
            }
            is SttEvent.Partial -> {
                if (ev.text.isNotBlank()) noteSpeech(ev.endTime)
                // A lone punctuation mark waiting to be finalized does not count as speech in progress.
                lastPartialBlank = ev.text.none { it.isLetterOrDigit() }
                if (!lastPartialBlank) lastTranscriptWallMs = SystemClock.elapsedRealtime()
                DictationLog.event("partial", "seg" to (segmentCounter + 1), "text" to ev.text, "t0" to ev.startTime, "t1" to ev.endTime)
                _events.emit(DictationEvent.Partial(sessionId, segmentCounter + 1, ev.text, timeToAbsMs(ev.startTime), timeToAbsMs(ev.endTime)))
            }
            is SttEvent.Final -> {
                lastFinalAbsOffset = maxOf(lastFinalAbsOffset, timeToAbsOffset(ev.endTime))
                if (ev.text.isBlank()) { DictationLog.event("final_empty", "t1" to ev.endTime); return }
                noteSpeech(ev.endTime)
                lastTranscriptWallMs = SystemClock.elapsedRealtime()
                val id = ++segmentCounter
                // Speech end → now latency: how far behind real time the final arrived.
                val speechEndMs = (ring.totalWritten - timeToAbsOffset(ev.endTime)) * 1000 / AudioCapture.BYTES_PER_SECOND
                DictationLog.event("final", "seg" to id, "text" to ev.text, "t0" to ev.startTime, "t1" to ev.endTime, "forced" to ev.forced, "lagMs" to speechEndMs)
                _events.emit(DictationEvent.Final(sessionId, id, ev.text, speechEndMs, timeToAbsMs(ev.startTime), timeToAbsMs(ev.endTime)))
            }
            is SttEvent.EndOfUtterance -> {
                DictationLog.event("end_of_utterance", "lastSeg" to segmentCounter, "t1" to ev.endTime, "source" to "recognizer")
                lastEouSegment = segmentCounter
                _events.emit(DictationEvent.EndOfUtterance(sessionId, segmentCounter))
            }
            is SttEvent.Warning -> DictationLog.event("warning", "type" to ev.type, "reason" to ev.reason)
            is SttEvent.EndOfTranscript -> { DictationLog.event("end_of_transcript"); endOfTranscriptSignal.trySend(Unit) }
            is SttEvent.Error -> {
                DictationLog.event("stt_error", "type" to ev.type, "reason" to ev.reason, "code" to ev.code, "retryable" to ev.retryable)
                if (stopRequested) return
                if (ev.retryable) scheduleReconnect(ev.type) else {
                    _events.emit(DictationEvent.Error(sessionId, "${ev.type}: ${ev.reason}", fatal = true))
                    stop("stt_error_${ev.type}")
                }
            }
            is SttEvent.Closed -> {
                DictationLog.event("socket_closed", "code" to ev.code, "reason" to ev.reason)
                if (!stopRequested && _state.value != DictationState.Idle) scheduleReconnect("closed_${ev.code}")
            }
        }
    }

    private fun noteSpeech(endTime: Float) {
        lastSpeechActivityMs = SystemClock.elapsedRealtime()
        lastSpeechAudioOffset = timeToAbsOffset(endTime)
    }

    private fun scheduleReconnect(why: String) {
        if (reconnectJob?.isActive == true) return
        provider?.close(); provider = null
        setState(DictationState.Reconnecting)
        val delayMs = (500L shl minOf(reconnectAttempt, 5)).coerceAtMost(10_000L)
        reconnectAttempt++
        DictationLog.event("reconnect_scheduled", "why" to why, "attempt" to reconnectAttempt, "delayMs" to delayMs)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (stopRequested || _state.value == DictationState.Idle) return@launch
            connect()
        }
    }

    // ---- utterance detection ----
    /**
     * Emits EndOfUtterance once the recognizer has gone quiet: at least one new final, nothing
     * provisional outstanding, and no transcript activity for [UTTERANCE_QUIET_MS].
     */
    private fun startQuietDetector() {
        quietJob?.cancel()
        quietJob = scope.launch {
            while (true) {
                delay(150)
                if (segmentCounter > lastEouSegment && lastPartialBlank &&
                    SystemClock.elapsedRealtime() - lastTranscriptWallMs >= UTTERANCE_QUIET_MS) {
                    lastEouSegment = segmentCounter
                    DictationLog.event("end_of_utterance", "lastSeg" to segmentCounter, "source" to "quiet")
                    _events.emit(DictationEvent.EndOfUtterance(sessionId, segmentCounter))
                }
            }
        }
    }

    // ---- debug audio injection ----
    /** Plays a raw PCM file into the pipeline in real time, then silence, exactly like a microphone. */
    private fun startInjection(path: String) {
        injecting = true
        injectThread = Thread({
            try {
                java.io.File(path).inputStream().buffered().use { input ->
                    val chunk = ByteArray(640) // 20 ms
                    var next = System.nanoTime()
                    var eof = false
                    while (injecting) {
                        var len = if (eof) -1 else input.read(chunk)
                        if (len <= 0) {
                            if (!eof) { eof = true; DictationLog.event("inject_eof") }
                            java.util.Arrays.fill(chunk, 0); len = chunk.size
                        }
                        captureListener.onAudio(chunk, len)
                        next += 20_000_000L * len / chunk.size
                        val sleepMs = (next - System.nanoTime()) / 1_000_000
                        if (sleepMs > 0) Thread.sleep(sleepMs)
                    }
                }
            } catch (e: Exception) {
                captureListener.onError("inject: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "DictationInject").also { it.start() }
    }

    // ---- idle timeout ----
    private fun startIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            var lastWarned = -1
            while (true) {
                delay(1000)
                if (_state.value == DictationState.Paused) continue
                // Audio-based silence measure: seconds of audio captured since the last recognized speech.
                val audioSinceSpeechS = ((ring.totalWritten - lastSpeechAudioOffset) / AudioCapture.BYTES_PER_SECOND).toInt()
                val wallSinceSpeechS = ((SystemClock.elapsedRealtime() - lastSpeechActivityMs) / 1000).toInt()
                val silentS = minOf(audioSinceSpeechS, wallSinceSpeechS)
                val left = idleTimeoutS - silentS
                if (left <= IDLE_WARN_S) {
                    if (left != lastWarned) {
                        lastWarned = left
                        _events.emit(DictationEvent.IdleCountdown(left.coerceAtLeast(0)))
                    }
                } else if (lastWarned != -1) {
                    // Speech resumed: withdraw the warning.
                    lastWarned = -1
                    _events.emit(DictationEvent.IdleCountdown(-1))
                }
                if (left <= 0) {
                    DictationLog.event("idle_timeout", "silentS" to silentS)
                    stop("idle_timeout"); return@launch
                }
            }
        }
    }
}
