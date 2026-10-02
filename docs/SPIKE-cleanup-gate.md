# Spike — reduce Claude cleanup calls with a confidence/heuristic gate

Date: 2026-10-02. Device: SM-F966U. Branch: `cleanup-gate-spike` only. Production dictation is unchanged:
the gate runs in **shadow mode** (it logs what it would decide; Claude is still always called).

## Goal

Skip the Claude cleanup call on segments that are already clean, without ever committing a segment that
actually needed cleanup (a false "clean" is worse than spending $0.0007).

## Two strategies, as asked

**A. Confidence/heuristic gate.** For each finalized segment, keep Speechmatics' own punctuation and
casing, then look for trouble before deciding whether to call Claude:
1. per-word confidence — one word < 0.60, or two words < 0.75 → suspicious;
2. near-vocabulary — a word 1–2 edits from a custom term but not the term ("stater"→stator) → suspicious;
3. known recurring misrecognitions for this speaker (Kohler→"cooler", Exmark→"x mark", …) → suspicious;
4. malformed word boundaries — a stray 1–2 letter fragment ("fly wheel") → suspicious;
5. boundary ambiguity — a carried sentence break where the context is a finished sentence (the JOIN/BREAK
   call Claude is actually good at) → suspicious;
6. a sentence mark **inside** the body (a split thought) → suspicious;
7. a new sentence left lowercase by the recognizer → suspicious.
Code: `latin/dictation/cleanup/CleanupGate.kt`. Clean → commit with the existing local rules
(`PauseHeuristics`/`LocalCleaner`); suspicious → the existing Claude path.

**B. Sentence batching** — accumulate finals until a sentence/turn boundary and make one Claude call for
the whole sentence instead of one per fragment. **This is already how the controller works today:** finals
accumulate in `pendingRaw` and an utterance closes only on a sentence end (≥8 words), 40 words, or a quiet
gap. The spike measured the ratio rather than rebuilding it.

## Results

### Strategy A on the 39-phrase suite (`tools/dictation-eval/run_gate_eval.py`)

| | gate (strategy A) | baseline (always Claude) |
|---|---|---|
| Accuracy | **37/39** | 37/39 |
| False-cleans (skipped a call that was needed) | **0** | — |
| Claude calls avoided | **8/39 (21%)** | 0 |

The suite is built from the hard cases (pause-periods, misheard vocabulary, injected instructions), so 21%
is close to a floor, not a typical rate. An earlier, looser gate avoided 36% but produced 4 false-cleans;
tightening rules 6–7 removed every false-clean at the cost of those extra skips. On this suite the gate's
`local` output matched the baseline on every case it chose to skip.

### Strategy A + B on real-time injected sessions

Two clips fed as live dictation (real Speechmatics finals and confidence):

| clip | finals | Claude calls now (= utterances) | finals per call | gate would skip |
|---|---|---|---|---|
| pauses | 33 | 5 | 6.6 | 2 of 5 (40%) |
| continuous | 32 | 3 | 10.7 | 1 of 3 (33%) |

- **Batching (B) is already doing the heavy lifting:** 32–33 recognizer finals become 3–5 Claude calls.
  There is little left for a separate batching pass to win, except merging pause-split fragments of one
  sentence (e.g. "Can't really test the" + "airplane mode thing because" are two calls today); a
  turn-level close would merge them but hold the text provisional longer.
- **Gate skip rate on these sessions: ~33–40%**, driven almost entirely by `boundary_ambiguous` and
  `internal_sentence` — i.e. the JOIN/BREAK work, not confidence.

### Visible correction delay

A gate-`clean` segment commits with the local rules in ~0 ms. A Claude segment waits for the call:
measured **626–803 ms** (median ~694 ms) in these sessions. So for the skipped fraction the correction
delay drops from ~0.7 s to ~0.

### Cost

At ~$0.0007 per call: avoiding 21% (hard suite) to ~35% (clean speech) of calls is a 21–35% cut on an
already-small bill. Batching (already shipped) is the larger lever — without it these two sessions would
have been ~65 calls instead of 8.

## The caveat that matters

**The confidence heuristic is untested on real speech.** The injected clips are Windows TTS; Speechmatics
returned confidence ≈ 1.00 for essentially every word (one word at 0.89 across 65 finals). So rules 1–4
almost never fired here — the skips came from the text rules. Real human dictation has lower and more
varied confidence, which is exactly what rules 1–4 are for. Whether they raise the skip rate (clean speech
with high confidence) or lower it (noisy audio dipping confidence and routing more to Claude) can only be
measured by the user dictating. The accuracy result (0 false-cleans) is from the text rules only; the
confidence rules have not been accuracy-tested.

## Recommendation (for review — nothing shipped)

- Batching already captures most of the available savings; it is on in production today.
- The gate adds a further ~20–35% call reduction with no accuracy loss on the suite, but its confidence
  rules are unproven. Before wiring it in, run a few real-voice sessions with shadow logging on
  (`gate` lines in logcat) and confirm: (a) no `clean` segment that Claude would have changed, and
  (b) the real confidence distribution so thresholds 0.60/0.75 can be set from data rather than guessed.

## Mechanics (spike only)

- `CleanupGate.kt`; per-word confidence now parsed in `SpeechmaticsProvider` and carried on `SttEvent.Final`
  / `DictationEvent.Final`; shadow logging in `DictationController.closeUtterance` (`gate` and `final_conf`).
- `DEBUG_GATE_BATCH` + `tools/dictation-eval/run_gate_eval.py` for the suite.
- No setting turns the gate on for the live text path; that is deliberately left for after review.
