"""SPIKE: benchmark the cleanup gate (strategy A) against always-Claude on the 39-phrase suite.

For each case the gate decides clean/suspicious. Clean -> committed with the local rules; suspicious
-> Claude. This script compares the committed output to the case's accepted outputs and reports how
many Claude calls the gate avoided and whether any avoided call was wrong (a false-clean).

The cases carry no per-word confidence, so only the gate's TEXT heuristics fire here; the confidence
heuristics are exercised on real sessions instead. The keyboard must be foreground (its Settings
screen) so the Claude calls for the suspicious cases can reach the network.

Usage:  python run_gate_eval.py          (ADB / ADB_SERIAL honoured)
"""
import io, json, os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = os.environ.get("ADB", r"D:\Android\Sdk\platform-tools\adb.exe")
SERIAL = os.environ.get("ADB_SERIAL", "192.168.1.125:5555")
PKG = "org.futo.inputmethod.latin"
REMOTE = "/sdcard/Android/data/%s/files" % PKG


def adb(*a):
    return subprocess.run([ADB, "-s", SERIAL] + list(a), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def main():
    cases = json.load(io.open(os.path.join(HERE, "cases.json"), encoding="utf-8"))
    adb("shell", "mkdir", "-p", REMOTE)
    adb("push", os.path.join(HERE, "cases.json"), REMOTE + "/eval-cases.json")
    adb("push", os.path.join(HERE, "..", "..", "java", "assets", "dictation", "cleanup_prompt.txt"), REMOTE + "/eval-prompt.txt")
    adb("logcat", "-c")
    adb("shell", "am", "broadcast", "-n", PKG + "/.dictation.DictationDebugReceiver",
        "-a", PKG + ".dictation.DEBUG_GATE_BATCH", "--es", "cases", REMOTE + "/eval-cases.json",
        "--es", "prompt", REMOTE + "/eval-prompt.txt")
    out = ""
    for _ in range(120):
        time.sleep(1)
        out = adb("logcat", "-d", "-v", "raw", "-s", "DictationDebug:V")
        if "GATE done" in out or "GATE error" in out:
            break
    res = {}
    for line in out.splitlines():
        m = re.match(r"GATE id=(\S+) clean=(\S+) reason=(\S+) engine=(\S+)(?: ms=(\d+) join=(\S+) out=\[(.*)\])?", line)
        if m:
            res[m.group(1)] = dict(clean=m.group(2) == "true", reason=m.group(3), engine=m.group(4),
                                   ms=int(m.group(5)) if m.group(5) else 0, join=m.group(6) == "true",
                                   out=m.group(7) if m.group(7) is not None else "<failed>")
        elif "GATE error" in line or "error=no_key" in line:
            print(line)
    passed = clean = false_clean = 0
    for c in cases:
        r = res.get(c["id"])
        if not r:
            print("%-4s MISSING" % c["id"]); continue
        join_ok = c.get("join") is None or c["join"] == r["join"]
        ok = r["out"] in c["expect"] and join_ok
        passed += ok
        clean += r["clean"]
        bad_skip = r["clean"] and not ok
        false_clean += bad_skip
        flag = "FALSE-CLEAN" if bad_skip else ("skip " if r["clean"] else "claude")
        mark = "ok  " if ok else "FAIL"
        print("%-4s %s %-11s %-18s %s" % (c["id"], mark, flag, r["reason"], r["out"]))
    n = len(cases)
    print("\nStrategy A (gate):  accuracy %d/%d   Claude calls avoided %d/%d (%.0f%%)   false-cleans %d"
          % (passed, n, clean, n, 100.0 * clean / n, false_clean))
    print("Baseline always-Claude: 37/%d, %d Claude calls." % (n, n))


if __name__ == "__main__":
    main()
