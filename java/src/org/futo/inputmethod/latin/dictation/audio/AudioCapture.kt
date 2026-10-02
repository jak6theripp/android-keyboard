package org.futo.inputmethod.latin.dictation.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import android.util.Log
import kotlin.math.sqrt

/**
 * 16 kHz mono PCM16 microphone capture on a dedicated thread, delivered in 20 ms chunks.
 * Also owns transient audio focus so phone calls / other recorders are noticed.
 */
class AudioCapture(private val context: Context, private val listener: Listener) {
    interface Listener {
        /** Called on the capture thread. [bytes] is reused; copy if retaining. */
        fun onAudio(bytes: ByteArray, len: Int)
        /** RMS in 0..1, roughly every 250 ms. Capture thread. */
        fun onLevel(rms: Float)
        fun onFocusChange(focusChange: Int)
        fun onError(message: String)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        const val CHUNK_SAMPLES = 320 // 20 ms
        private const val TAG = "AudioCapture"
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Synchronized
    fun start(requestFocus: Boolean): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, BYTES_PER_SECOND * 2)
            )
        } catch (e: SecurityException) {
            listener.onError("RECORD_AUDIO not granted"); return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); listener.onError("AudioRecord init failed"); return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { rec.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER) } catch (_: Exception) {}
        }
        if (requestFocus) requestAudioFocus()
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release(); abandonAudioFocus(); listener.onError("AudioRecord did not start recording"); return false
        }
        recorder = rec
        running = true
        thread = Thread({ loop(rec) }, "DictationAudio").also { it.start() }
        return true
    }

    private val LEVEL_INTERVAL_NS = 45_000_000L

    private fun loop(rec: AudioRecord) {
        val samples = ShortArray(CHUNK_SAMPLES)
        val bytes = ByteArray(CHUNK_SAMPLES * 2)
        var acc = 0.0; var accN = 0; var lastLevel = System.nanoTime()
        while (running) {
            val n = rec.read(samples, 0, CHUNK_SAMPLES, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (n < 0) { listener.onError("AudioRecord.read returned $n"); break }
                continue
            }
            for (i in 0 until n) {
                val s = samples[i].toInt()
                bytes[2 * i] = (s and 0xff).toByte()
                bytes[2 * i + 1] = ((s shr 8) and 0xff).toByte()
                acc += (s.toDouble() * s); accN++
            }
            listener.onAudio(bytes, n * 2)
            val now = System.nanoTime()
            // ~20 levels a second: the dictation panel pulses with the voice, so it has to follow syllables.
            if (now - lastLevel > LEVEL_INTERVAL_NS && accN > 0) {
                listener.onLevel((sqrt(acc / accN) / 32768.0).toFloat())
                acc = 0.0; accN = 0; lastLevel = now
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        abandonAudioFocus()
    }

    val isRunning get() = running

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "audio focus change $change")
        listener.onFocusChange(change)
    }

    private fun requestAudioFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } catch (e: Exception) { Log.w(TAG, "focus request failed", e) }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { try { audioManager.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        focusRequest = null
    }
}
