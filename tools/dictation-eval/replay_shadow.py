"""Offline replay of tightened gate rules against the collected shadow rows (no phone time needed).

The logged `shadow` rows carry the gate's decision plus raw / local / claude text, and the matching `gate`
rows carry per-word confidence. Tightening rules can only ADD "suspicious" verdicts, so the replay starts
from the logged decision and applies candidates on top, reporting skip rate and false-cleans per variant.

Usage: python replay_shadow.py            (pulls from the device like analyze_shadow.py)
       python replay_shadow.py *.jsonl
"""
import io, json, os, re, subprocess, sys

ADB = os.environ.get("ADB", r"D:\Android\Sdk\platform-tools\adb.exe")
SERIAL = os.environ.get("ADB_SERIAL", "192.168.1.125:5555")
PKG = "org.futo.inputmethod.latin"


def adb(*a):
    return subprocess.run([ADB, "-s", SERIAL] + list(a), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def load_sessions(args):
    if args:
        return [(p, io.open(p, encoding="utf-8").read().splitlines()) for p in args]
    listing = adb("shell", "run-as", PKG, "ls", "files/dictation/logs")
    files = sorted(x.strip() for x in listing.splitlines() if x.strip().endswith(".jsonl"))
    return [(f, adb("shell", "run-as", PKG, "cat", "files/dictation/logs/" + f).splitlines()) for f in files]


QUESTION_START = re.compile(r"^(so|are|is|do|does|did|can|could|would|will|what|where|when|why|how|who|which|should|have|has|am|were|was)\b", re.I)


def tighten(row, conf, rules):
    """Returns the rule name that makes this row suspicious, or None."""
    raw = row["raw"].strip()
    body = raw.lstrip(".?! ").strip()
    words = [w for w in re.split(r"\s+", body) if w]
    spoken = [c for c in conf if c is not None]
    if "T1" in rules and len(words) <= 1:
        return "T1_single_word"
    if "T2" in rules and body[:1].isupper() and raw[:1] in ".?!":
        return "T2_capital_after_boundary"
    if "T3" in rules and QUESTION_START.match(body) and body[-1:] not in ".?!":
        return "T3_question_no_mark"
    if "T4" in rules and re.search(r"\b(say|said|says|saying|texting|texted|text|equivalent of|like)\b", body, re.I):
        return "T4_reported_speech"
    if "C" in rules and spoken:
        if min(spoken) < 0.70:
            return "C_low_conf"
        if sum(1 for c in spoken if c < 0.85) >= 2:
            return "C_soft_conf"
    return None


def main():
    sessions = load_sessions(sys.argv[1:])
    rows = []
    for name, lines in sessions:
        gates = {}
        for ln in lines:
            ln = ln.strip()
            if not ln.startswith("{"):
                continue
            try:
                o = json.loads(ln)
            except Exception:
                continue
            if o.get("event") == "gate":
                confs = []
                for tok in str(o.get("confWords", "")).split():
                    m = re.search(r":([0-9.]+)$", tok)
                    confs.append(float(m.group(1)) if m else None)
                gates[o.get("utt")] = confs
            elif o.get("event") == "shadow" and o.get("claudeOk"):
                rows.append((o, gates.get(o.get("utt"), [])))
    n = len(rows)
    if not n:
        print("no rows"); return
    print("sessions %d, usable rows %d\n" % (len(sessions), n))
    variants = [
        ("as logged", set()),
        ("+T1 single word", {"T1"}),
        ("+T1 +T3 question", {"T1", "T3"}),
        ("+T1 +T2 +T3", {"T1", "T2", "T3"}),
        ("+T1 +T2 +T3 +C conf(0.70/0.85)", {"T1", "T2", "T3", "C"}),
        ("+T1 +T2 +T3 +T4 reported speech", {"T1", "T2", "T3", "T4"}),
        ("everything", {"T1", "T2", "T3", "T4", "C"}),
    ]
    print("%-36s %8s %8s %12s" % ("variant", "skips", "skip %", "false-clean"))
    details = {}
    for label, rules in variants:
        skips = 0; bad = []
        for o, conf in rows:
            clean = bool(o.get("gateClean")) and tighten(o, conf, rules) is None
            if clean:
                skips += 1
                if o["local"].strip() != o["claude"].strip():
                    bad.append(o)
        details[label] = bad
        print("%-36s %8d %7.0f%% %12d" % (label, skips, 100.0 * skips / n, len(bad)))
    print("\nremaining false-cleans under 'everything':")
    for o in details["everything"]:
        print("  raw   : %s\n  local : %s\n  claude: %s\n" % (o["raw"], o["local"], o["claude"]))
    # which single rule removes which logged false-clean
    print("per-rule coverage of the logged false-cleans:")
    for o, conf in rows:
        if o.get("gateClean") and o["local"].strip() != o["claude"].strip():
            hit = tighten(o, conf, {"T1", "T2", "T3", "T4", "C"})
            print("  %-26s <- %s" % (hit or "NOT CAUGHT", o["raw"][:70]))


if __name__ == "__main__":
    main()
