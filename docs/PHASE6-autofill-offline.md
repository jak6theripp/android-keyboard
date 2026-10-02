# Phase 6 — Inline autofill (Samsung Pass) and offline Whisper fallback

Date: 2026-10-02 (built unattended). Device: SM-F966U.

## Offline fallback

- `latin/dictation/NetworkStatus.kt`: `isOnline()` = the active network has `NET_CAPABILITY_INTERNET`.
- `ActionRegistry.getActionOverride` (FUTO's existing hook that already swaps built-in vs system voice input): when the dictation
  action is triggered with no network and no session running, it returns FUTO's on-device Whisper `VoiceInputAction` instead and
  shows a toast "Offline: using on-device voice input". Every trigger (bottom-row mic key, voice subtype) goes through this hook.
- Logged as `dictation start trigger=offline_whisper_fallback` (tag `DictationEngine`).
- A connection lost **during** a session is not this path: the engine keeps recording into its 30 s buffer and replays on reconnect
  (verified in Phase 1).

## Inline autofill

Nothing had to be built: FUTO already declares `supportsInlineSuggestions="true"`, implements
`onCreateInlineSuggestionsRequest` / `onInlineSuggestionsResponse`, renders suggestions in the action bar, and has a setting for it
(Settings → Typing → inline autofill). The Samsung-style toolbar added in Phase 3 yields to inline suggestions when there are any.

Added two log lines (tag `InlineAutofill`, no field contents):
- `request created for <package>` — the system asked this keyboard for an inline-suggestion spec for that app's field.
- `response suggestions=<n> shown=<bool>` — what the autofill provider sent back.

## Verification status

### Builds/compiles
- Yes.

### Installed and verified on the device
- `NetworkStatus.isOnline()` reads the real state (reported online over Wi-Fi; `ACCESS_NETWORK_STATE` works).
- Regression on the final build: streaming dictation (two clips), RecognitionService, foreground service released afterwards.

### Not yet verified
- **Offline routing on the device.** Not exercised: opening the Whisper window turns on the real microphone, and the only ways to
  be offline are airplane mode / Wi-Fi off (which would also cut the adb link). Test: airplane mode on, tap the mic key → the
  "Voice Input" (Whisper) window should open with the toast.
- **Samsung Pass inline suggestions.** Needs a login field in an app (not Chrome, which uses its own autofill by default), a saved
  Samsung Pass entry, and the user's biometric. To test: open an app's login screen, focus the username field, then run
  `adb logcat -s InlineAutofill`.
  - `request created` + `response suggestions>0` and chips in the toolbar row → Samsung Pass supports inline suggestions here.
  - `request created` but no `response` (or `suggestions=0`) → Samsung Pass does not provide inline suggestions to third-party
    keyboards; the normal autofill dropdown on the field still works, which the spec accepts.

## Assumptions

- "No network" means no active network with internet capability. A captive or dead Wi-Fi still counts as online; in that case the
  streaming session starts, fails to connect, and retries (it does not switch to Whisper mid-session).
