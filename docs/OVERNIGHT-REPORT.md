# Overnight report — 2026-10-02

Phases 2–6 were built unattended after Phase 1 was signed off. Each has its own doc; this is the index and the to-do list.

| Phase | Commit | Doc |
|---|---|---|
| 2 Cleanup pass | `7e8aa4904` | `PHASE2-cleanup.md` |
| 3 Samsung layout | `0c0aab6fa` | `PHASE3-layout.md` |
| 4 Nav-bar mic hook | `472a09031` | `PHASE4-navbar-mic.md` |
| 5 AI reply + translate | `43b72a8eb` | `PHASE5-ai-reply-translate.md` |
| 6 Offline fallback + autofill | `f9b9187de` | `PHASE6-autofill-offline.md` |

## Things that happened that the user should know

1. **The Anthropic account ran out of credit** during cleanup-prompt tuning (after roughly 150 small Haiku calls, on the order of
   ten cents). Every Claude call since returns "Your credit balance is too low". Consequences: the cleanup prompt's last revision,
   reply drafting and translation are untested against the model. The keyboard handles it: it says so in a toast and falls back to
   a local rule-based cleaner.
2. **The live microphone was on for about 6 seconds at 12:54 AM.** A test tap landed on "Try again" in the AI-reply panel after the
   one-shot test audio had been used up, so a capture started on the real mic until the panel was closed. Nothing was transcribed or
   typed, but room audio was streamed to Speechmatics for those seconds. After that, test audio was made "sticky" so no later test
   could open the mic, and every hook was disarmed at the end (verified in the log: `AUDIO path=null sticky=false`).
3. **Test text in the Chrome search box.** The box was used as the scratch field all night (cleared at the end). One stray "in" was
   typed by a mis-aimed tap during Phase 3 and removed by a later clear.
4. No system settings were changed. The keyboard was switched to Samsung Keyboard and back several times (to grab a reference
   screenshot and to make One UI re-bind the keyboard after each install).

## What needs the user (in order of value)

1. **Add Anthropic credit**, then say so. I re-run the 35-case eval (`python tools/dictation-eval/run_eval.py`) and finish the prompt.
2. **Dictate normally** for a minute with thinking pauses, in Chrome. This is the first real-voice run of the cleanup path. Look for:
   pause-periods gone, real sentence ends kept, text turning solid 2–3.5 s behind speech, anything that reshuffles when it shouldn't.
3. **Layout check**: the keyboard should now look like the Samsung one (toolbar, number row, `!#1`, mic key bottom-left). Unfold once
   to see the inner screen.
4. **Nav-bar mic**: with this keyboard the slot is the keyboard switcher (One UI only gives the mic to Samsung Keyboard). Decide
   whether to try the two experiments in `PHASE4-navbar-mic.md` (they change system settings, so they were left alone).
5. **AI reply**: App info → ⋮ → Allow restricted settings, then enable "Keyboard AI reply: read conversation" in Accessibility
   (Settings → Dictation → Setup has the buttons). Then try ✦ in Google Messages. Sending stays the user's own tap.
6. **Offline**: airplane mode on, tap the mic key → the Whisper "Voice Input" window should open.
7. **Samsung Pass**: focus a login field in an app while `adb logcat -s InlineAutofill` runs.
8. Battery → Unrestricted for the keyboard app.

## Known gaps

- Hold-to-talk is not implemented (the toggle says so).
- Fold/unfold stops dictation (by the user's choice); the re-check that it no longer hangs is still open.
- Nothing has been tested in Google Messages, Messenger or a Compose text field yet; all device testing was in Chrome.
- The debug receiver (`DictationDebugReceiver`) is still in the build. It is callable only from adb/system
  (`android.permission.DUMP`) and all its switches are off.

## Test tooling left in the repo

- `tools/dictation-eval/run_eval.py` — cleanup prompt eval on the phone (uses the key stored on the phone).
- `tools/dictation-eval/run_audio_test.py <clip.wav>` — end-to-end dictation with an injected clip instead of the mic.
- Clips used: `D:\VoiceTextProject\testaudio\` (Windows TTS; not in the repo).

## Update — 2026-10-02 morning (Anthropic credit restored)

Verified on the device with real Claude calls (cover screen, Chrome search box, injected TTS audio; the live mic was never opened):

- **Cleanup eval: 32/35**, latency median 713 ms, p90 825 ms, max 918 ms. The three misses:
  - p02 (context ends "because") and p05 (context ends with a lone "So"): model said BREAK. The app overrides both to JOIN
    (`PauseHeuristics.endsUnfinished`), so the typed result is right; effective score in the app is 34/35.
  - p06 ("I can talk naturally" + "Pause. Naturally. And the…"): the model kept a sentence break. Left as is; ambiguous even for a reader.
- **End-to-end dictation with the real cleaner** (`run_audio_test.py t1_pauses.wav`): four utterances, each cleaned in 600–770 ms,
  pause-periods removed, "The Mower Medic" / "carburetor" kept, final period committed at stop. Result:
  "Can't really test the airplane mode thing because opening the top menu by swiping down stops the dictation, so that is not a test
  that I can do. It looks to me like The Mower Medic needs a new carburetor."
- **Translate**: English → Spanish preview appeared in ~5 s; Replace swapped the whole field; nothing changed before Replace.
- **AI reply** (screen reading off): spoken instruction "Tell him I will have the mower done Friday. But the carburetor needs
  replacing." → draft "I'll have the mower done Friday, but the carburetor needs replacing" as composing text; Discard removed it.
  Keep and Redo were not pressed.

Finding: **Android blocks this app's network while the keyboard is not on screen** (`netpolicy` shows `blocked=APP_BACKGROUND`; DNS
fails instantly, reported as `failed=network`). This only affects the adb eval tool, which must be run with the keyboard showing in
a text field. Dictation itself always runs with the keyboard up or under the microphone foreground service.

Still not verified: real voice through the cleanup path, AI reply with screen reading on, Keep/Redo, inner screen, offline Whisper
route, Samsung Pass.
