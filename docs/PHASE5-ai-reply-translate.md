# Phase 5 — AI reply mode and Translate

Date: 2026-10-02 (built unattended). Device: SM-F966U.

## AI reply (toolbar ✦)

Flow (`latin/uix/actions/AiActions.kt`, `AiReplyWindow`):

1. **Tap ✦.** The conversation on screen is read once through `ScreenReaderService` — only if the user enabled it. If it is off or
   nothing is readable, a toast says so and the draft is made from the instruction alone.
2. **Dictate the instruction.** The same engine records it, but the transcript goes to the panel (`DictationController.startCapture`),
   not into the text field. "Draft reply" ends the recording (so does the idle timeout).
3. **Claude drafts** (`latin/dictation/assist/AssistClient.kt`): casual, direct, short; matches the style of the user's own messages on
   screen; says only what the instruction says; conversation text is treated as untrusted context.
4. **Review.** The draft is put in the field as **composing text**. Keep commits it, Discard removes it, Redo records a new instruction.
   Closing the panel during review keeps the draft as ordinary text.

**Never auto-send:** the feature only writes text into the field through the input connection. It has no code path that performs an
editor action, a key event for Enter, or an accessibility click; the accessibility service is read-only (no gestures, no actions).

### Screen reader (`latin/dictation/assist/ScreenReaderService.kt`)

- Opt-in accessibility service. `eventTypes` is cleared when it connects, so it receives **no** accessibility events; it looks at the
  window only inside `readNow()`, which only the ✦ button calls. Nothing is stored.
- Takes the largest application window, collects visible non-editable text, drops the header strip (first header text = thread title),
  timestamps and status words, and keeps the last 10 items.
- Sender: bubbles hugging the right edge → ME, left edge → THEM, otherwise unknown. This is a geometry heuristic.

## Translate (toolbar 文A)

`TranslateWindow`: uses the selection if there is one, otherwise the whole field. Language chips (Spanish default; the last choice is
remembered in `dictation_translate_language`), preview, and **Replace** only on confirm. Changing the language re-translates.

## Model

Both use `claude-opus-5-5` at low effort through the beta messages endpoint with server-side refusal fallbacks
(`AssistClient.MODEL`). The cleanup pass stays on `claude-haiku-4-5` as specified; nothing was specified for these two, and drafting
in the user's voice benefits from the stronger model. One constant to change if cost or speed matters more.

## Settings (Settings → Dictation)

- **Setup**: screen-reading status, the Android 13+ "Allow restricted settings" steps for sideloaded apps, the battery "Unrestricted"
  pointer, buttons to App info and Accessibility settings.
- **Translate: default language**.

## Verification status

### Builds/compiles
- Yes.

### Installed and verified on the device
- **Translate window**: opens from the toolbar, shows the language chips and the "whole field" scope, and with the API rejecting
  calls shows "Anthropic credit balance is too low." with Replace disabled. The field was not modified.
- **AI reply window**: opens, shows "Screen reading is off. Drafting from your instruction only.", captures a spoken instruction
  (injected audio) into the panel while the text field stays untouched, ends on "Draft reply" or idle timeout, and on API failure
  shows the error with "Try again".
- **Settings page**: the whole Dictation page renders (keys status, setup helper, language, sliders, toggles, vocabulary).

### Not yet verified
- **Any Claude output**: no reply draft or translation has been produced, because the Anthropic account has no credit.
  The prompts are untested against the model.
- **Screen reading** in Google Messages / Messenger: the service cannot be enabled without the user (restricted settings).
  The sender heuristic and the header/timestamp filtering are untested on real apps.
- The review step (draft as composing text; Keep / Discard / Redo) and Replace (selection and whole-field).
- Behaviour on the inner screen / split keyboard.

## Assumptions

- The first text in the top 14 % of the app window is the conversation title.
- Right-aligned text is the user's own message.
- Replacing the whole field uses `setSelection(0, length)` + `commitText`, which assumes the editor exposes its full text.
