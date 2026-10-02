# Spike — Gemini Nano (ML Kit GenAI Prompt API) for the cleanup pass

Date: 2026-10-02. Device: SM-F966U (Galaxy Z Fold7), Android 16 / One UI 8.5, AICore `prod_aicore_20260820.00_RC08`.
Branch: `nano-spike` only. Nothing here is wired into dictation; Claude remains the cleanup engine.

## Question

Can the phone's built-in Gemini Nano replace the paid Claude call for the per-utterance cleanup pass?

## Answer

**No, not through this API.** AICore refuses inference from the keyboard, and where it does run it is too slow and not accurate
enough for this task.

| | Gemini Nano (nano-v2, on device) | Claude Haiku 4.5 (current) |
|---|---|---|
| Inference from the active keyboard, Chrome in front | **`BACKGROUND_USE_BLOCKED`** (39/39 calls) | works |
| Same, during a dictation session (microphone foreground service running) | **`BACKGROUND_USE_BLOCKED`** (4/4 calls) | works |
| Latency per phrase (where it runs) | median **4.9–5.0 s**, p90 5.2–5.6 s, one call 19.9 s | median 0.71 s, max 0.85 s |
| 39-phrase eval (`tools/dictation-eval/cases.json`) | **13/39** | 37/39 |
| Usage limit | `BUSY` after 19 calls in ~95 s; needed two 30 s waits to finish 39 | none hit |
| Cost | free | about $0.0007 per call |

## What was measured, and how

- Library: `com.google.mlkit:genai-prompt:1.0.0-beta4`. `checkStatus()` from the keyboard process: `DOWNLOADABLE`, model
  `nano-v2`, token limit 8192, system instructions not supported (the instructions are sent in front of each request).
- Download: `download()` reported **12,377,189 bytes (12.4 MB)**, finished in about 4 s, status then `AVAILABLE`. The base model
  was evidently already on the phone.
- Keyboard test: the adb hook returns first and the work starts 1.5 s later on a plain thread of the keyboard process, with the
  keyboard showing in Chrome. The process was at importance 125 (`procState=BFGS`). Every `warmup()` and `generateContent()`
  call failed in 20–70 ms with error 30, "Background usage is blocked. Please use the API when your app is in the foreground".
  Google's documentation says the same: inference is permitted only for the top foreground application.
- Foreground test (the keyboard's own Settings screen in front, importance 100, `procState=TOP`): inference works. Same prompt
  (`assets/dictation/cleanup_prompt.txt`), same JOIN/BREAK reply protocol, same vocabulary list, temperature 0, topK 1.
- Quality: it answered JOIN on all 39 phrases, so every "keep the real period" case failed. It mostly returned the text
  unchanged: no vocabulary fixes ("the more medic", "hooks varna", "brigs", "x mark", "cooler" all left as they were), pause
  periods inside a segment left in, and in three cases it duplicated or dropped text (caught by the divergence guard). On the
  injected-instruction case it wrote the poem.

## Not tried

- A shorter prompt written for Nano, or the API's prefix caching (`PromptPrefix`), which might cut latency. Neither changes the
  blocking result, which is what rules this path out for a keyboard.
- nano-v3 / nano-v4 (not available on this phone).
- Samsung's own on-device model (`com.samsung.android.aicore`): no public API was looked at.

## Build notes (spike branch only)

- `minSdk` raised from 24 to 26 (library requirement).
- The library is compiled with Kotlin 2.3; this project uses 2.1. Needed `-Xskip-metadata-version-check` and
  `kotlin-stdlib` forced to 2.1.0. One `NoSuchMethodError` (`Job.cancel$default`, a kotlinx-coroutines version mismatch) was
  logged after the download completed; it did not affect the calls measured here, but it means this combination is not safe to ship.
- Code: `latin/dictation/cleanup/NanoCleaner.kt`; adb hooks `DEBUG_NANO --es op status|download` and
  `DEBUG_CLEANUP_BATCH --es engine nano`; `tools/dictation-eval/run_eval.py --engine nano [--from <case id>]`.

## If the cost of Claude is the concern

At roughly $0.0007 per cleanup call and 6–10 calls per minute of continuous speech, an hour of actual talking is about 25–40
cents. The cleanup pass can also be switched off in Settings → Dictation, which falls back to the free local rules.
