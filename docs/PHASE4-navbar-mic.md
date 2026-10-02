# Phase 4 — Navigation-bar mic hook

Date: 2026-10-02 (built unattended). Device: SM-F966U, One UI 8.5, 3-button navigation.

## What One UI actually does (observed on the device)

- With **Samsung Keyboard** active, the nav bar's bottom-left slot shows a **mic** (screenshot taken for reference).
- With **this keyboard** active, the same slot shows the **keyboard-switcher icon**. Tapping or long-pressing it opens the standard
  "Choose input method" dialog (FUTO Keyboard / Google Voice Typing / Samsung Keyboard). There is no "Voice input / Input method"
  choice; that menu exists only while Samsung Keyboard is the active keyboard.
- `dumpsys input_method`: `mShouldShowImeSwitcherWhenImeIsShown=true`, `mCustomImeSwitcherButtonRequestedVisible=false`,
  `mImeDrawsImeNavBar=false`. A third-party IME gets the generic switcher; no public API lets it draw a mic there.
- Relevant secure settings: `default_voice_input_method` = Google's `VoiceInputMethodService` (a voice **IME**),
  `voice_recognition_service` = Google TTS `RecognitionService`, `show_keyboard_button=1`.
  So One UI's "Voice input" is most likely "switch to the default voice IME", i.e. the voice-subtype path, not SpeechRecognizer.

**Conclusion so far:** One UI owns that slot and only gives the mic to Samsung Keyboard. With this keyboard, the mic is the
bottom-row key directly above the slot ("Mic key on keyboard", on by default since Phase 3).

## What was built

| Path | Implementation | Trigger logged as |
|---|---|---|
| 1. `RecognitionService` | `latin/dictation/DictationRecognitionService.kt`, declared with `android.speech.RecognitionService` + `xml/recognition_service.xml`. Streams partials, returns one utterance per session, as the `SpeechRecognizer` contract expects. Uses the same engine (no foreground service: it is bound by the foreground client). | `recognition_service(caller=<package>)` |
| 2. Voice IME subtype | Second `<subtype imeSubtypeMode="voice" isAuxiliary="true">` in `xml/method.xml`; `LatinIME.onCurrentInputMethodSubtypeChanged` → `DictationController.onVoiceSubtypeSelected()` starts dictation, switches back to the keyboard subtype, and returns to the previous keyboard afterwards if another keyboard handed over. | `voice_subtype` |
| Fallback | Bottom-row mic key (Phase 3). | `keyboard_action` |

Every start writes `<path> at <time>` to the setting shown in Settings → Dictation ("Last voice-input path triggered"), and logs
`dictation start trigger=<path>` under the logcat tag `DictationEngine`.

## Verification status

### Builds/compiles
- Yes.

### Installed and verified on the device
- The service is registered: `cmd package query-services -a android.speech.RecognitionService` lists
  `org.futo.inputmethod.latin.dictation.DictationRecognitionService` next to Google's two and the Claude app's.
- **Path 1 works end to end** through the public API: a `SpeechRecognizer` created for our component (debug hook `DEBUG_RECOGNIZE`,
  injected audio) received `onReadyForSpeech → onBeginningOfSpeech → 34 partials (none shrinking) → onEndOfSpeech → onResults`
  with the correct text.
- Adding the voice subtype did not disturb the keyboard: it still binds, shows, and dictates through the foreground-service path.

### Not yet verified (needs the user)
- **Which path One UI fires.** Nothing in One UI was pointed at this app tonight, because that means changing system settings.
  To find out, with the user's OK:
  1. `adb shell settings put secure voice_recognition_service org.futo.inputmethod.latin/.dictation.DictationRecognitionService`,
     switch to Samsung Keyboard, tap the nav-bar mic, and watch `adb logcat -s DictationEngine DictationRecSvc`.
  2. Check whether Samsung Keyboard's own settings (Voice input) or Settings → General management → Keyboard list and default
     offer this keyboard as a voice input.
  AOSP only accepts a *system* app as `default_voice_input_method`, so path 2 from the nav bar may be closed to a sideloaded app.
- Path 2 itself (cannot select a subtype from adb without editing secure settings).
- Returning to the previous keyboard after a hand-over.

### Not implemented
- **Hold-to-talk.** The setting still does nothing. Starting dictation swaps the keyboard for the mic panel, so the key's release
  event never arrives; it needs the panel to own the press. Deferred.

## An option worth deciding on

If One UI will route the Samsung-Keyboard nav-bar mic to a third-party voice IME, there is a hybrid setup: keep **Samsung Keyboard**
for typing (zero relearning) and use this app only as the voice input behind the nav-bar mic. Path 2 already supports that: when
another keyboard hands over, dictation runs and control returns to that keyboard. Whether One UI allows it is the open question above.
