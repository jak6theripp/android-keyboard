"""Runs the cleanup-pass eval set on the phone through the keyboard's debug receiver.

The keyboard must be showing in a text field: Android blocks the app's network while it is in the
background (every case then fails with 'network' in 1-2 ms). The reported join/out is what the app would
type, i.e. after the PauseHeuristics JOIN override.

Usage:  python run_eval.py [prompt.txt]      (ADB and ADB_SERIAL env vars are honoured)
        Without an argument the working copy of java/assets/dictation/cleanup_prompt.txt is used,
        so a prompt change can be evaluated without rebuilding the app.

Pushes cases.json (and optionally a system-prompt override) to the app's external files dir,
triggers DEBUG_CLEANUP_BATCH, reads the results from logcat (tag DictationDebug) and compares
them with each case's list of acceptable outputs. Uses the Anthropic key stored on the phone.
"""
import io, json, os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = os.environ.get("ADB", r"D:\Android\Sdk\platform-tools\adb.exe")
SERIAL = os.environ.get("ADB_SERIAL", "192.168.1.125:5555")
PKG = "org.futo.inputmethod.latin"
REMOTE = "/sdcard/Android/data/%s/files" % PKG


def adb(*args, **kw):
    return subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace", **kw)


def main():
    cases = json.load(io.open(os.path.join(HERE, "cases.json"), encoding="utf-8"))
    prompt = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "..", "java", "assets", "dictation", "cleanup_prompt.txt")
    adb("shell", "mkdir", "-p", REMOTE)
    adb("push", os.path.join(HERE, "cases.json"), REMOTE + "/eval-cases.json")
    extra = ["--es", "cases", REMOTE + "/eval-cases.json"]
    if prompt:
        adb("push", prompt, REMOTE + "/eval-prompt.txt")
        extra += ["--es", "prompt", REMOTE + "/eval-prompt.txt"]
    adb("logcat", "-c")
    adb("shell", "am", "broadcast", "-n", PKG + "/.dictation.DictationDebugReceiver",
        "-a", PKG + ".dictation.DEBUG_CLEANUP_BATCH", *extra)
    out = ""
    for _ in range(90):
        time.sleep(1)
        out = adb("logcat", "-d", "-v", "raw", "-s", "DictationDebug:V").stdout
        if "BATCH done" in out or "BATCH error" in out:
            break
    results = {}
    for line in out.splitlines():
        m = re.match(r"BATCH id=(\S+) ms=(\d+) verdict=(\S+) edits=(\S+) (?:forced=\S+ )?join=(\S+) out=\[(.*)\]$", line)
        if m:
            results[m.group(1)] = dict(ms=int(m.group(2)), verdict=m.group(3), edits=m.group(4), join=(m.group(5) == "true"), out=m.group(6))
        elif "failed=" in line or "BATCH error" in line:
            print(line)
    passed = 0
    lat = []
    for c in cases:
        r = results.get(c["id"])
        if not r:
            print("%-4s MISSING" % c["id"]); continue
        lat.append(r["ms"])
        join_ok = c.get("join") is None or c["join"] == r["join"]
        ok = r["out"] in c["expect"] and r["verdict"].startswith("accept") and join_ok
        passed += ok
        print("%-4s %s %4dms %-16s %-5s %s" % (c["id"], "ok  " if ok else "FAIL", r["ms"], r["verdict"], "JOIN" if r["join"] else "BREAK", r["out"]))
        if not ok:
            print("       raw:    %s | %s | %s" % (c["context"][-50:], c.get("boundary", ""), c["segment"]))
            print("       expect: %s / %s" % ({True: "JOIN", False: "BREAK", None: "any"}[c.get("join")], c["expect"][0]))
    if lat:
        lat.sort()
        print("\n%d/%d passed   latency ms: median %d, p90 %d, max %d" % (passed, len(cases), lat[len(lat) // 2], lat[int(len(lat) * 0.9)], lat[-1]))


if __name__ == "__main__":
    main()
