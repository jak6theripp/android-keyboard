"""Analyze the cleanup-gate shadow-eval logs collected during normal dictation.

Pulls the on-device JSONL session logs (written whenever "Cleanup gate shadow test" is on) and reports:
  - how often Claude actually changed the segment,
  - how often the gate would have skipped Claude,
  - FALSE CLEANS: the gate skipped but Claude caught a real change (must be ~0 before shipping),
  - the real per-word confidence distribution (to set thresholds from data),
  - cleanup latency.

Usage:  python analyze_shadow.py            # pulls from the device via run-as
        python analyze_shadow.py *.jsonl    # or analyze already-saved files
"""
import io, json, os, re, subprocess, sys
from collections import Counter

ADB = os.environ.get("ADB", r"D:\Android\Sdk\platform-tools\adb.exe")
SERIAL = os.environ.get("ADB_SERIAL", "192.168.1.125:5555")
PKG = "org.futo.inputmethod.latin"


def adb(*a):
    return subprocess.run([ADB, "-s", SERIAL] + list(a), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def pull_lines():
    listing = adb("shell", "run-as", PKG, "ls", "files/dictation/logs")
    files = sorted(x.strip() for x in listing.splitlines() if x.strip().endswith(".jsonl"))
    if not files:
        print("No shadow logs on the device yet. Dictate with 'Cleanup gate shadow test' on, then re-run.")
        sys.exit(0)
    print("sessions: %d" % len(files))
    out = []
    for f in files:
        out += adb("shell", "run-as", PKG, "cat", "files/dictation/logs/" + f).splitlines()
    return out


def main():
    args = [a for a in sys.argv[1:]]
    lines = []
    if args:
        for p in args:
            lines += io.open(p, encoding="utf-8").read().splitlines()
    else:
        lines = pull_lines()

    shadows, gates = [], {}
    for ln in lines:
        ln = ln.strip()
        if not ln.startswith("{"):
            continue
        try:
            o = json.loads(ln)
        except Exception:
            continue
        if o.get("event") == "shadow":
            shadows.append(o)
        elif o.get("event") == "gate":
            gates.setdefault(o.get("utt"), o)

    n = len(shadows)
    if n == 0:
        print("No 'shadow' rows found.")
        return
    # Only rows where Claude actually produced an authoritative answer can judge a false-clean.
    valid = [s for s in shadows if s.get("claudeOk")]
    clean = [s for s in valid if s.get("gateClean")]
    false_cleans = [s for s in valid if s.get("falseClean")]
    changed = [s for s in valid if s.get("claudeChanged")]
    waits = [s["waitedMs"] for s in shadows if isinstance(s.get("waitedMs"), (int, float))]

    print("\n=== segments ===")
    print("total shadow rows      : %d" % n)
    print("usable (Claude ok)     : %d" % len(valid))
    print("Claude actually changed: %d (%.0f%%)" % (len(changed), 100.0 * len(changed) / max(1, len(valid))))

    print("\n=== gate (strategy A) ===")
    print("would skip Claude      : %d / %d (%.0f%%)" % (len(clean), len(valid), 100.0 * len(clean) / max(1, len(valid))))
    print("FALSE CLEANS           : %d   <-- drive this to zero" % len(false_cleans))
    if clean:
        bad = 100.0 * len(false_cleans) / len(clean)
        print("false-clean rate among skips: %.1f%% (%d of %d)" % (bad, len(false_cleans), len(clean)))

    if false_cleans:
        print("\n=== every false clean (gate skipped, Claude caught a change) ===")
        for s in false_cleans:
            print("  reason=%s" % s.get("gateReason"))
            print("    raw   : %s" % s.get("raw"))
            print("    local : %s   <- would have been committed" % s.get("local"))
            print("    claude: %s   <- correct" % s.get("claude"))

    # Confidence distribution from the per-word gate logs.
    confs = []
    for g in gates.values():
        for tok in str(g.get("confWords", "")).split():
            m = re.search(r":([0-9.]+)$", tok)
            if m:
                confs.append(float(m.group(1)))
    if confs:
        confs.sort()
        buckets = Counter()
        for c in confs:
            b = "1.00" if c >= 0.999 else ("0.90-0.99" if c >= 0.90 else ("0.75-0.90" if c >= 0.75 else ("0.60-0.75" if c >= 0.60 else "<0.60")))
            buckets[b] += 1
        print("\n=== per-word confidence (%d words) ===" % len(confs))
        for b in ["1.00", "0.90-0.99", "0.75-0.90", "0.60-0.75", "<0.60"]:
            if buckets[b]:
                print("  %-9s %6d (%.1f%%)" % (b, buckets[b], 100.0 * buckets[b] / len(confs)))
        print("  min word confidence seen: %.2f" % confs[0])

    if waits:
        waits.sort()
        print("\n=== Claude latency ===")
        print("  median %d ms, p90 %d ms, max %d ms" % (waits[len(waits) // 2], waits[int(len(waits) * 0.9)], waits[-1]))

    print("\n=== projection ===")
    if valid:
        print("  calls that could be skipped (gate clean, no false-clean): %d of %d (%.0f%%)"
              % (len(clean) - len(false_cleans), len(valid), 100.0 * (len(clean) - len(false_cleans)) / len(valid)))
        print("  Collect more sessions if the word count above is small; confidence thresholds should be set")
        print("  from the distribution, not guessed.")


if __name__ == "__main__":
    main()
