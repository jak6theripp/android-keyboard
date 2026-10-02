# Cleanup-gate shadow evaluation (live, in the normal build)

This build carries a **shadow test** of the cleanup gate. Claude still cleans every eligible segment and
its result is what you see typed — the gate only watches and records what it *would* have done. Typed text
is unchanged (verified byte-identical to the build without it).

Setting: **Settings → Dictation → "Cleanup gate shadow test"** (on by default). While it is on, the
on-device dictation log is also turned on so the comparison can be recorded. Turn it off to stop recording.

## What it records, per cleaned segment (in the on-device JSONL log)

- `raw` — the Speechmatics text (with its own punctuation/casing)
- `confWords` — every word with its confidence (in the matching `gate` line)
- `gateClean` / `gateReason` — the gate's decision and the exact rule that fired
- `claude` — what Claude committed (authoritative)
- `local` — what a gate *skip* would have committed (local rules only)
- `claudeChanged` — did Claude change anything vs the raw text
- `falseClean` — **the key metric**: the gate said skip, but Claude's result differs from the local one,
  i.e. a skip would have been wrong
- `waitedMs` — Claude latency

Nothing leaves the phone. The log holds your dictated text and the cleanup results; it is the same on-device
log the "Debug logging" toggle writes. To stop and erase it: turn the toggle off, then delete
`files/dictation/logs/*.jsonl` (the analyzer's `--wipe` is not provided; use the command below).

## How to collect and read

1. Dictate normally for a while (days is fine — every session appends).
2. Run the analyzer from `D:\VoiceTextProject\android-keyboard`:

   ```
   python tools/dictation-eval/analyze_shadow.py
   ```

   It pulls the logs via `adb run-as` and prints: Claude-changed rate, gate skip rate, **false cleans (with
   the raw/local/claude text of each)**, the real per-word confidence distribution, and latency.

3. To erase the collected logs when done:

   ```
   for f in $(adb -s 192.168.1.125:5555 shell run-as org.futo.inputmethod.latin ls files/dictation/logs); do \
     adb -s 192.168.1.125:5555 shell run-as org.futo.inputmethod.latin rm -f "files/dictation/logs/$f"; done
   ```

## Decision rule (before anything ships)

The gate is wired into the live cleanup path **only** once real-speech data shows essentially zero false
cleans, and the confidence thresholds (currently 0.60 / 0.75, guessed) are reset from the measured
distribution. Call reduction is secondary to that.
