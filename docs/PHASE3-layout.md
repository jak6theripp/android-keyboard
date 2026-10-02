# Phase 3 — Samsung-matching layout and toolbar

Date: 2026-10-02 (built unattended). Reference: a screenshot of the user's Samsung Keyboard on the same phone, same field.

## Result

| Row | Samsung | This keyboard |
|---|---|---|
| Toolbar | AI ✦, emoji, translate, keyboard mode, clipboard, text editing, settings, … | same order, 8 icons sharing the row, "…" at far right |
| Numbers | 1–0, keyed | 1–0, keyed ("classic" number row, on by default) |
| Letters | QWERTY | QWERTY |
| Row 3 | shift, Z–M, backspace | shift, Z–M, backspace |
| Bottom | `!#1`, emoji, space ("English (US)"), `.`, enter | **mic**, `!#1`, emoji, space ("English"), `.`, enter |
| Nav bar bottom-left | mic | keyboard switcher (see Phase 4) |

## How (all through FUTO's existing mechanisms)

- Number row: default of `pref_enable_number_row` → true, `pref_number_row_mode` → classic (`SettingsValues.java`, `Typing.kt`).
- `!#1`: `to_symbol` in `tools/make-keyboard-text-py/locales/DEFAULT.json` (generated `KeyboardTextsTable.java` follows).
- Bottom row (`v2keyboard/Keyboard.kt` `DefaultBottomRow`): comma key removed (the contextual key still appears in URL / e-mail /
  date fields); optional mic key added at the far left (`DictationMicKey` in `TemplateKeys.kt`, setting "Mic key on keyboard").
- Space bar language: upstream hides it when one language is enabled; the fork always shows it (`Subtypes.kt`,
  `LanguageOnSpacebarUtils.java`). "English (US)" is used when it fits; with the mic key present the bar is narrower, so "English".
- Toolbar (`uix/ActionBar.kt`, `uix/UixManager.kt`, `actions/Registry.kt`): the suggestion row *is* the toolbar when the keyboard
  opens; it switches to suggestions once typing starts (the `>` arrow at the left then opens the toolbar as a second row — FUTO's
  existing expand behaviour). Default favourites order = Samsung's. No pinned action.
- New toolbar actions `ai_reply` and `translate` are placeholders here (toast) and are implemented in Phase 5.

## Mapping notes

| Samsung button | FUTO action used |
|---|---|
| AI | `ai_reply` (new) |
| Emoji/stickers | `emoji` (emoji only; FUTO has no stickers/GIFs) |
| Translate | `translate` (new) |
| Keyboard mode | `keyboard_modes` (regular / split / one-handed / floating) — exists, no placeholder needed |
| Clipboard | `clipboard_history` |
| Text editing tools | `text_edit` (cursor pad, select, cut/copy/paste) — exists, not Samsung's exact panel |
| Settings | `settings` |
| … | `more` (All Actions, with "Edit actions") |

## Verification status

### Builds/compiles
- Yes.

### Installed and verified on the device (cover screen, Chrome)
- Layout renders as in the table above (screenshot compared with the Samsung reference).
- Bottom-row mic key starts streaming dictation (tested with injected audio).
- "…" opens All Actions; the toolbar shows on keyboard open even when the field has text.

### Not yet verified
- Main (inner) screen and split keyboard with the new bottom row and toolbar.
- Landscape.
- The symbols page (`!#1`) and its return key label.
- That the "Mic key on keyboard" toggle takes effect without restarting the keyboard (the layout is cached per keyboard id).
- Editing actions in "Edit actions": FUTO removes duplicates across categories, so the emoji icon would drop out of the toolbar
  there (emoji is also the bottom-row action key).

## Assumptions

- The mic key defaults to **on** until the navigation-bar mic is confirmed to reach this keyboard; it costs some space-bar width.
- Key shapes, colours and exact key sizes follow the FUTO theme, not Samsung's; only positions and order were matched.
