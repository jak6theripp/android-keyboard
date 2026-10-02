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

## Update - 2026-10-02: the mic is in the navigation bar

**Result:** with this keyboard open, a mic is drawn in the navigation bar's bottom-left slot. Tap starts dictation, tap again stops
it, and it is removed when the keyboard closes. Trigger is logged as `navbar_mic`.

**How** (`latin/dictation/NavBarMic.kt`): One UI has a Samsung-only call, `SemStatusBarManager.setNavigationBarShortcut(requestClass,
RemoteViews, position, priority)`, which Samsung Keyboard uses for its own mic. Read from the phone's own framework and SystemUI:
the system service forwards the call to SystemUI without checking the caller; the only check is in the client-side wrapper, which
asks the calling app's own Context for the signature permission `STATUS_BAR_SERVICE`. `LatinIME.enforceCallingOrSelfPermission`
answers that check for this one call (flag `NavBarMic.calling`), nothing else. The tap comes back as a PendingIntent to the
non-exported `NavBarMicReceiver`.

**Requirement (the user's own setting):** Settings -> General management -> Keyboard -> "Show input method button on navigation
bar" must be **off**. While it is on, the keyboard-switcher icon occupies the slot and SystemUI stores the request without drawing
it. The user turned it off on 2026-10-02. Side effect: no keyboard-switcher icon in the navigation bar.

**What did not matter:** the request name. Tried with the switcher button on, none drew: own class name (left, right), a name
containing "honeyboard", own package at priority 12 (left, right), and once Samsung Keyboard's exact request
(`com.samsung.android.honeyboard`, left, priority 12; removed immediately, it collides with Samsung Keyboard's own entry and must
not be shipped). The shipped request uses this app's own class name, position 0, priority 5.

**Caveats:** undocumented and intended by Samsung for its own apps; a One UI update can close it without notice, in which case the
button simply does not appear and the bottom-row mic key is the fallback. Toggle: Settings -> Dictation -> "Mic in the navigation
bar". Debug hook (adb only): `DEBUG_NAVBAR --es class .. --ei position .. --ei priority ..`.

### Verification status
- Builds/compiles: yes.
- Installed and verified on the device (cover screen, portrait, Chrome): mic drawn bottom-left; tap starts a session
  (`dictation start trigger=navbar_mic`); tap while listening stops it (`stop_requested reason=navbar_mic`); the mic stays visible
  under the dictation panel; removed when the keyboard closes. Tested with injected audio.
- Not yet verified: inner screen, landscape, dark/light tinting on light apps, behaviour after a reboot, real-voice use.
