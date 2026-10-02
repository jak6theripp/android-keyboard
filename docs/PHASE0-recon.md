# Phase 0 — Recon report

Date: 2026-10-01. Branch: `voice-keyboard`. Upstream HEAD at fork time: `70a5d390c` (0.1.30-8-g70a5d390c, versionCode 11759).

## Source and license (verified)

- Canonical repo: https://gitlab.futo.org/keyboard/latinime; official mirror + issue tracker:
  https://github.com/futo-org/android-keyboard (cloned from the mirror).
- License: FUTO Source First License 1.1-kb. Personal, non-commercial modification is expressly
  permitted. Obligations honoured here: prominent "modified" notice (README), payment-related
  functionality left intact, no distribution.
- Submodules (7): `libs` (prebuilt AARs), `voiceinput-shared/src/main/ml` (Whisper models),
  `java/assets/layouts` (Apache-2.0 YAML layouts), `translations`, `java/assets/themes`,
  `java/res-large`, `java/assets/futo-swipe` (Hugging Face swipe model). Checkout is ~1.2 GB.

## Toolchain (Windows, everything on D:)

| Item | Location / version |
|---|---|
| JDK | `D:\Android\jdk-17` (Microsoft OpenJDK 17.0.20.1) |
| Android SDK | `D:\Android\Sdk` — platform-tools 37.0.1 (adb), platforms;android-35, build-tools;35.0.0, ndk;28.2.13676358, cmake;3.22.1 |
| Python | `D:\Android\python` (3.12 embeddable; the `updateLocales` gradle task shells out to `python`) |
| Gradle | wrapper 8.14.3; AGP 8.10.1; Kotlin 2.1.0; Compose BOM 2025.06 |

Build command (bash):

```
cd /d/VoiceTextProject/android-keyboard
JAVA_HOME=/d/Android/jdk-17 PATH="/d/Android/python:$PATH" PYTHONUTF8=1 ./gradlew assembleStableDebug
```

`PYTHONUTF8=1` is required on Windows: the locale generator reads UTF-8 JSON and Python defaults to cp1252.
`local.properties` holds `sdk.dir` and is gitignored. Debug builds are signed with the checked-in `java/shared.keystore`.
Clean build time: ~6.5 min. APK: `build/outputs/apk/stable/debug/android-keyboard-stable-debug.apk` (159 MB; bundles Whisper models).

## Device

- `ro.product.model` = **SM-F966U** (Samsung Galaxy Z Fold7), Android 16 / API 36, One UI 8.5,
  build `BP4A.251205.006.F966USQSCBZH3`, arm64-v8a. Serial RFCY61XB6ZZ.
- Displays: cover = display 0, 1080×2520 @420dpi (physical id `local:4630946872173396372`);
  main = display 1, 1968×2184 @420dpi (`local:4630946449689556883`).
  `adb exec-out screencap -d <physical id> -p` is needed; logical ids fail with "Display Id not valid".
- Pre-existing state: default IME Samsung HoneyBoard; Google `VoiceInputMethodService` is the
  installed voice IME; default `RecognitionService` = Google TTS; three RecognitionService providers
  enumerate (Google AS, Google TTS, Claude app) so One UI does see third-party ones; autofill =
  Samsung Pass; no accessibility services enabled.
- Samsung **Auto Blocker** had to be turned off (Settings → Security and privacy) before USB debugging
  or sideloading would work.
- Observation for Phase 4: with FUTO active, the One UI nav bar's bottom-left slot shows the
  keyboard-switcher icon, not a mic. The mic appears there only when the current IME advertises a
  voice path. To be investigated in Phase 4.

## Verification status

### Builds/compiles
- `assembleStableDebug` on unmodified source: **yes**.

### Installed and verified on the device
- Installed via `adb install`, enabled and set as default IME (`org.futo.inputmethod.latin/.LatinIME`), RECORD_AUDIO granted.
- Keyboard shows in a Chrome text field on the **cover screen**.
- **Swipe typing**: a synthetic h→e→y gesture produced "hey" with suggestions "they / her / Herr".
- **Emoji**: emoji panel opens from the bottom-row key, categories render.
- **Clipboard**: Clipboard Manager opens from the action bar; history is off by default — enabled it;
  copied text appears as a clipboard chip in the suggestion strip.
- **Voice (on-device Whisper)**: action-bar mic opens the Voice Input window, records, transcribes
  and inserts into the field. The user said "a lot less heavy coding" → it typed
  **"a lot less heavy coating."** (the exact error that motivates this project). A run with nobody
  speaking hallucinated "♪♪ ♪♪".
- Tap typing: implicitly verified (keys respond; suggestions appear).

### Not yet verified
- Main (inner) screen rendering — see addendum below if filled in.
- Google Messages, Facebook Messenger, Compose-app text fields.
- Fold/unfold mid-session, long pauses, airplane mode (these are Phase 1+ behaviours).

## Codebase layout (what matters for this project)

Paths below are under `java/src/org/futo/inputmethod/` unless noted.

### IME core
- `latin/LatinIME.kt` — the `InputMethodService` (Compose-hosting). Delegates to `IMEManager`, `LatinIMELegacy`, `UixManager`.
  `onUpdateSelection` (~L666), `onFinishInputView` (~L637), `onFinishInput` (~L644), `onConfigurationChanged` (~L524).
- `engine/IMEManager.kt` — picks the active engine (General/Chinese/Japanese); **`createInputTransaction()` / `endInputTransaction()`** —
  the mechanism that lets an action (e.g. voice) temporarily own the input connection.
- `engine/general/ActionInputTransactionIME.kt` — implements `ActionInputTransaction`:
  `updatePartial()` → `setComposingText`, `commit()` → `commitText`, `cancel()`. **This is the seam for our streaming dictation.**
- `latin/RichInputConnection.java` — single InputConnection wrapper (`setComposingText`, `commitText`, `setComposingRegion`, batch edits).
- `latin/InputConnectionInternalComposingWrapper.kt` — emulates composing for editors that mishandle it; already wired for voice.

### Voice input (existing)
- Module `voiceinput-shared/` — `AudioRecognizer.kt` (the only `AudioRecord` user: VOICE_RECOGNITION, 16 kHz mono PCM16, 1600-sample chunks),
  `RecognizerView.kt` (`RecognizerViewListener`: `partialResult`, `finished`, `cancelled`, `requestPermission`), `whisper/` (ModelManager, MultiModelRunner, GGML JNI).
- `latin/uix/actions/VoiceInputAction.kt` — the toolbar action; its `VoiceInputActionWindow` creates an input transaction and
  routes `partialResult` → `updatePartial`, `finished` → `commit`. `SystemVoiceInputAction` triggers the system voice IME via `CODE_SHORTCUT`.
- `latin/uix/VoiceInputSettingKeys.kt` — existing voice settings (DataStore keys).
- **No foreground service exists** (manifest already declares `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`, unused).
- **No `RecognitionService`**, no voice IME subtype (`java/res/xml/method.xml` has one `keyboard` subtype).

### Toolbar ("action bar")
- `latin/uix/Action.kt` — `Action` data class, `ActionWindow`, `KeyboardManagerForAction`.
- `latin/uix/actions/Registry.kt` — `AllActionsMap` (string id → Action; order is ABI-stable because key code = `CODE_ACTION_0 + index`).
  Existing ids: emoji, settings, paste, text_edit, themes, undo, redo, voice_input, system_voice_input, switch_language, clipboard_history,
  mem_dbg, cut, copy, select_all, more, bugs, keyboard_modes, up/down/left/right, font_typer.
  `DefaultActionSettings`: action key = emoji, pinned = voice_input, favorites = switch_language, undo, redo, text_edit, clipboard_history, themes, keyboard_modes.
  Order/visibility persisted in DataStore (`pinned_actions_s`, `favorite_actions_s`); editor UI in `latin/uix/settings/pages/Actions.kt`.
- `latin/uix/ActionBar.kt` — composables; `latin/uix/UixManager.kt` — hosts action windows.
- Already present for Phase 3 mapping: emoji, clipboard, text_edit, keyboard_modes, settings, "more" overflow. Missing: AI reply, translate.

### Layouts
- `java/assets/layouts/` (YAML; `LayoutSpec.md` documents it). Rows: `numbers`, `letters`, `bottom`. Template keys `$shift $delete $space $enter $symbols $action $number $period …`.
- `v2keyboard/Keyboard.kt` — `DefaultNumberRow`, `DefaultBottomRow` (= symbols, contextual ",", action key, space, period, enter).
- `v2keyboard/TemplateKeys.kt` — `ActionKey` template; `keyboard/internal/KeyboardCodesSet.java` — `!code/action_<id>` resolves to an action.
- Number row is a layout option (`numberRowMode`), currently off → long-press hints on QWERTY.
- A mic key can be added as `!icon/action_voice_input|!code/action_<id>` in a YAML row or `DefaultBottomRow` — no new mechanism needed.

### Settings
- DataStore Preferences already used: `latin/uix/Settings.kt` (`SettingsKey<T>`, `getSetting/setSetting/getSettingFlow`).
- Compose settings framework: `latin/uix/settings/UserSettings.kt` (`UserSettingsMenu`, toggle/nav helpers), `SettingsNavigator.kt` (`SettingsMenus` list + NavHost routes),
  `Components.kt` (widgets). Existing voice screen: `settings/pages/VoiceInput.kt`.
- Legacy SharedPreferences also exist (`latin/settings/Settings.java`); new work uses DataStore + Keystore.

### Foldable / config changes
- `UixManager.onCreate` collects `WindowInfoTracker.windowLayoutInfo` → `foldingOptions`; `LatinIME.currentSizeState` distinguishes FoldableInnerDisplay / Portrait / Landscape.
- `v2keyboard/KeyboardSizingCalculator.kt` — `KeyboardMode` Regular/Split/OneHanded/Floating with per-kind size settings.
- `LatinIME.onConfigurationChanged` recomputes size and invalidates the keyboard; the IME views are recreated → dictation state must live outside them (Phase 1 design point).

### Networking / libs
- **No INTERNET permission**; `ACCESS_NETWORK_STATE` is explicitly removed. No OkHttp/Ktor/WebSocket client. kotlinx-serialization-json 1.7.1 present.
  Phase 1 must add INTERNET (+ ACCESS_NETWORK_STATE for the offline check) and a WebSocket client.
- `androidx.autofill:autofill:1.1.0` is present and `method.xml` declares `supportsInlineSuggestions="true"` → inline autofill support already exists (Phase 6 is a test, not a build).

## Things in the spec that fight the codebase (flagging now, not working around)

1. **Whisper's model-free dictation path is not streaming.** FUTO's `AudioRecognizer` buffers up to 30 s and runs Whisper on `finish()`; partials come from
   periodic re-decodes, not a streaming recognizer. Our Speechmatics path will be a separate recognizer feeding the same `ActionInputTransaction` seam, not a change to `AudioRecognizer`.
2. **The input transaction is tied to an open action window.** `VoiceInputActionWindow` closes the window on `finished`, which ends the transaction. Our session must keep
   the transaction open across many finals and across view recreation (fold). That means the session object lives in the service layer (`LatinIME`/`UixManager` lifetime), not in the window.
3. **No foreground service, no INTERNET.** Both are additive manifest/code changes, not rewrites.

## Assumptions made

- Using the `stable` flavor (canonical package id `org.futo.inputmethod.latin`) rather than `unstable` (`.unstable` suffix). Either works; stable keeps the package id predictable for `adb`.
- Debug signing with the shared keystore is acceptable for a personal sideload (no Play upload).
- The GitHub mirror is at parity with the GitLab canonical repo (README says so).
