# Phase 1 — Streaming dictation

Date: 2026-10-01. Device: SM-F966U (Galaxy Z Fold7), Android 16 / One UI 8.5. All device tests over wireless adb.

## What was built

| Piece | File(s) | Role |
|---|---|---|
| Engine | `latin/dictation/DictationEngine.kt` | Process singleton. Mic capture → bounded ring buffer → STT socket. Reconnect with replay, idle timeout, segment ids. No view/IME dependencies. |
| Foreground service | `latin/dictation/DictationService.kt` | `foregroundServiceType="microphone"`, ongoing notification with Stop. |
| STT provider | `latin/dictation/stt/StreamingSttProvider.kt`, `SpeechmaticsProvider.kt` | Small interface + Speechmatics real-time v2 over OkHttp WebSocket. |
| Audio | `latin/dictation/audio/AudioCapture.kt`, `PcmRingBuffer.kt` | 16 kHz mono PCM16, 20 ms chunks, audio focus; fixed 30 s (960 KB) ring with absolute offsets. |
| Text controller | `latin/dictation/DictationController.kt` | The single InputConnection path. Composing soft tail, commits, guards, lifecycle. Owned by `LatinIME`. |
| Transaction | `engine/general/DictationTransactionIME.kt`, `TransactionIME` in `engine/IMEInterface.kt`, `IMEManager.createDictationTransaction` | Input transaction that survives many commits. |
| UI | `latin/uix/actions/DictationAction.kt` | Full-panel mic indicator (red = hot, amber countdown), tap-to-stop, backspace. A view only. |
| Settings | `latin/uix/settings/pages/Dictation.kt`, `latin/dictation/DictationSettings.kt` | max_delay, idle timeout, punctuation sensitivity, vocabulary, debug toggles, key import. |
| Keys | `latin/dictation/SecureKeys.kt` | AES-GCM under an Android Keystore key; import from a pushed file which is then deleted. |
| Logging | `latin/dictation/DictationLog.kt` | JSONL session log (opt-in), optional raw audio (separate opt-in), logcat mirror. |

Changes to existing FUTO files are small: manifest (INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE_MICROPHONE, service),
`build.gradle` (OkHttp 4.12.0), `IMEManager`/`IMEHelper`/`ActionInputTransactionIME` (transaction interface),
`LatinIME` (two lifecycle hooks + controller field), `UixManager.closeActionWindowIf`, `Registry` (action + default pinned),
settings navigation, and the hold-backspace behaviour (`InputLogic`, `PointerTracker`, `Settings`, `SettingsValues`, `Typing.kt`).

## Protocol facts verified against Speechmatics docs (2026-10-01)

- Endpoint `wss://eu.rt.speechmatics.com/v2/`, `Authorization: Bearer <key>`.
- `StartRecognition.transcription_config`: `max_delay` 0.7–4.0, `max_delay_mode`, `enable_partials`, `additional_vocab` (`[{content}]`),
  `punctuation_overrides.sensitivity`, `conversation_config.end_of_utterance_silence_trigger` (0–2, 0 = off).
- Transcript text is at **`metadata.transcript`** in `AddPartialTranscript` / `AddTranscript` (not top level).
- Sessions cannot be resumed; a reconnect is a new session. Idle close after 3 min without audio/pings (moot: silence is streamed).
- Free tier: 2 concurrent sessions.

## Design decisions and why

- **Session lives at service level.** `UixManager.onInputFinishing()` closes any action window and `IMEManager.onFinishInput()` ends
  the input transaction on every view teardown, so neither can own the session.
- **Reconnect = replay from the last received final.** Each final's `end_time` is mapped to an absolute ring-buffer offset; a new socket
  starts sending from there. Bounded at 30 s; older audio is logged as `replay_truncated`.
- **Pause finalization is off** (`end_of_utterance_silence_trigger = 0`). With it on, every ≥1 s pause produced a period. Words now finalize
  only through `max_delay`.
- **User-edit guard verifies the editor directly** (text before cursor == our provisional text) rather than interpreting debounced
  `onUpdateSelection` callbacks, which misfired during rotation and dropped words.
- **Unreachable editor ⇒ hold, don't write.** If `getTextBeforeCursor` returns null (view hidden, shade taking focus) finals go to a backlog
  and are committed when the view returns to the same field.
- **Unfold stops dictation cleanly** (user's call: not worth continuing across displays). Rotation and the notification shade keep it running.

## Verification status

### Builds/compiles
- `assembleStableDebug`: yes.

### Installed and verified on the device
- **Microphone FGS from the IME on Android 16**: `isForeground=true types=0x80`, no start exception; mic levels are real
  (peak RMS 0.03–0.11 speaking, ~0.004 silent), i.e. not silenced by while-in-use restrictions.
- **Live text in the field** (Chrome): provisional tail renders grey + underlined; finals commit in place ~2.0 s behind speech.
- **max_delay doing its job**: partial "heavy coating" → final "heavy coding"; "cooler" → "Kohler"; "L'Oréal" → "lol".
- **Custom vocabulary**: "The Mower Medic", "carburetor", "flywheel key", "Kohler" recognized correctly.
- **"lol" → 😂** spoken replacement.
- **Idle timeout**: stops at 30 s of silence; amber countdown in the last 5 s; a ~28 s pause held the mic and dictation resumed.
- **Rotation mid-dictation** (4 rotations in one session): session continues.
- **Notification shade mid-dictation**: session continues; words spoken while hidden are inserted on return.
- **Airplane mode mid-dictation**: reconnect with backoff, 18.2 s of buffered audio replayed, no lost or duplicated words.
- **Starting while offline**: retries with backoff while buffering audio (observed; then stopped by the user).
- **Backspace while the mic is hot**, including hold → letters → accelerating words; finals for user-deleted provisional text are discarded.
- **Normal keyboard hold-backspace** letters → words ramp (user: "feels fine").
- **Clean stop paths**: panel tap, notification Stop (code path), idle, input finished; `EndOfTranscript` flush observed every time.
- **API keys**: file import → Keystore → pushed file deleted; keys never logged.

### Not yet verified
- Google Messages, Facebook Messenger, a Compose text field (only Chrome was exercised).
- Unfold after the re-open-storm fix (previously the screen appeared to hang; no ANR was recorded).
- Phone call / other app taking audio focus mid-dictation.
- Foreground service being killed while the keyboard view is alive, and vice versa.
- Debug log **file** export and the save-audio toggle (the logcat mirror was used for all testing).
- Settings page rendering and sliders (not opened on the device yet).
- Sessions longer than a few minutes; Speechmatics session limits.

### Not implemented in this phase
- **Hold-to-talk**: the setting exists but does nothing. The action bar only delivers tap / long-press, not press/release; planned with
  the dedicated mic key in Phase 4.
- **Offline fallback to Whisper**: Phase 6. Starting offline currently just retries.
- **Cleanup pass**: Phase 2. The `DICTATION_CLEANUP_ENABLED` setting is inert.

## Known issues carried into Phase 2

- **Pause-periods.** When a mid-sentence pause exceeds `max_delay` (the user's thinking pauses are 2.5–4 s), the recognizer finalizes
  without following context and emits a standalone `"."` token. Every wrong period in testing was one of these; every period attached
  to a word was correct. Plan: keep a standalone period provisional and let the cleanup pass decide with the next words as context.
- Finals arrive as 1–3 word fragments; cleanup needs to work on utterances, not single finals.
- Typing on the keyboard while dictating is not possible (the panel covers it; key events are swallowed during the transaction).

## Assumptions

- EU endpoint is acceptable latency-wise from the US (≈1 s connect; finals ≈2.0 s behind speech with max_delay 2.0).
- "Speech activity" for the idle timer = the recognizer producing non-empty text.
- A restart of input (`restarting=true`) is the same field even if the app reports a different `fieldId` (Chrome does).
- 20 s is an acceptable limit for a hot mic with the keyboard view hidden.
