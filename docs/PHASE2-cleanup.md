# Phase 2 — Cleanup pass and vocabulary

Date: 2026-10-02 (built unattended overnight). Device: SM-F966U.

## What was built

| Piece | File(s) |
|---|---|
| Claude cleanup client (official Anthropic Java SDK 2.68.0, model `claude-haiku-4-5`) | `latin/dictation/cleanup/CleanupClient.kt` |
| System prompt (single copy, shipped as an asset and used by the eval tool) | `java/assets/dictation/cleanup_prompt.txt` |
| Divergence guard (weighted word edit distance, 25 %) | `latin/dictation/cleanup/CleanupGuard.kt` |
| Model-free fallback rules and cleaner | `latin/dictation/cleanup/PauseHeuristics.kt` |
| Utterance model, deferred sentence punctuation, apply/validity logic | `latin/dictation/DictationController.kt` (rewritten) |
| Quiet-based utterance detection, test-audio injection | `latin/dictation/DictationEngine.kt` |
| adb test hooks (shell-only, protected by `android.permission.DUMP`) | `latin/dictation/DictationDebugReceiver.kt` |
| Eval set + runners | `tools/dictation-eval/` (`cases.json`, `run_eval.py`, `run_audio_test.py`) |

## How it works

- **Tiers.** committed → never touched. pending (plain composing) = final words waiting for cleanup. partial (dim composing) = provisional.
- **Utterances.** Finals accumulate; an utterance closes when the recognizer has been quiet for 0.7 s, at a sentence end once ≥ 8 words, or
  at 40 words. One utterance is in flight at a time, so results are applied in order by construction; a result whose utterance is no
  longer the in-flight one is discarded (`cleanup_stale_result`).
- **Pause-periods.** Sentence-ending punctuation at the end of an utterance is not committed with it. It is carried (shown dim) as the
  *boundary* of the next utterance. The model gets `context | boundary | segment` and answers `JOIN` or `BREAK` on line 1 and the corrected
  segment on line 2. The app, not the model, writes or drops the period. At stop/idle the carried period is committed as-is.
- **Guards.**
  - Latency: 1.5 s (`CLEANUP_TIMEOUT_MS`). On timeout the raw text is committed; a late answer is ignored.
  - Divergence: weighted word-level edit distance > 25 % of the raw word count (minimum allowance 1) → raw text. Similar-word
    substitutions, merges/splits and stutter deletions are cheap; unrelated substitutions/insertions/deletions cost a full word.
  - Validity: applied only if the provisional text in the editor is still exactly what was sent (`composingIntact`). Otherwise the
    result is discarded and the text the user touched is left alone.
  - Format: a reply that is not `JOIN|BREAK` + text is a failure → raw text.
- **Failure handling.** A 4xx from the API (billing, key, access) at warm-up, or twice in a row later, switches the session to the local
  cleaner and shows a toast with the reason. The local cleaner applies only `PauseHeuristics`: a period after a word that cannot end a
  sentence ("the", "a", "because", "and", "of", …) or after a lone connective ("So.") is a pause. The same rule overrides a model `BREAK`.
- **Setting off** (`Dictation → LLM cleanup pass`) = raw recognizer text committed word by word, exactly as in Phase 1.
- **"lol" → 😂** is applied after cleanup; the prompt tells the model to leave slang alone.

## Verification status

### Builds/compiles
- Yes, including the Anthropic SDK and its transitive dependencies (Jackson, kotlin-reflect).

### Installed and verified on the device
- **Model quality on the eval set** (`tools/dictation-eval/cases.json`, 35 cases built from the user's real dictation logs):
  **31/35** with the JOIN/BREAK protocol; median latency 690 ms, max 772 ms (well inside the 1.5 s budget).
  All "keep the real period" cases, all vocabulary fixes (Mower Medic, carburetor, flywheel key, Kohler, Briggs, Exmark, Husqvarna,
  stator), stutter removal, fillers kept, and all four "dictated request / injected instruction" cases passed.
- **End-to-end pipeline with injected audio** (Windows TTS clips fed in place of the microphone; the recognizer reproduces the
  standalone pause-period exactly as with a human voice):
  - utterance segmentation, in-order application, carried period dropped on JOIN, final period committed at stop;
  - 1.5 s timeout → raw text, late result ignored (`decision=timeout`, waited 1500–1503 ms);
  - API returning 400 → local fallback at warm-up, toast reason, no stall, no lost or duplicated words;
  - continuous speech (three sentences) → three utterances, correct periods.
- **Divergence guard**: rejected a model reply that added a commentary sentence; accepts the heavy-but-legitimate vocabulary fixes.

### Not yet verified
- **The final prompt revision with the real model.** The Anthropic account ran out of credit ("Your credit balance is too low")
  during the third eval run. The last complete run was 31/35; the prompt was then tightened (linking-word rule, never drop a word,
  three more examples) and that revision has only a partial run. Known misses at 31/35: context ending in "because" / lone "So" judged
  BREAK (now also covered by the local override), and one case where a one-word fragment was dropped.
  → Re-run `python tools/dictation-eval/run_eval.py` after adding credit.
- **Real voice through the cleanup path.** All Phase 2 device tests used synthesized audio.
- User editing/backspacing while an utterance is in flight (code path exists; exercised only in Phase 1 form).
- Rotation / notification shade while text is pending cleanup (pending words are kept in the model and re-rendered; a
  `strandedComposing` check removes a stale copy on return — untested on the device).
- Reply-mode conversation context (wired through `CleanupRequest.conversation`; used in Phase 5).

## Cost note

One cleanup call is ~600 input + ~20 output tokens on Haiku 4.5 (≈ $0.0007). A minute of continuous dictation is roughly 6–10 calls.

## Assumptions

- A quiet gap of 0.7 s after the last final (with nothing but punctuation pending) is a good utterance boundary.
- Text staying provisional until its utterance is cleaned (typically 2–3.5 s after the words are spoken) is acceptable, since it is
  visible in the field the whole time.
- Word changes are allowed only when context or the vocabulary makes the intended word clear; "heavy coating" → "heavy coding" with no
  context is accepted as correct for this user.
