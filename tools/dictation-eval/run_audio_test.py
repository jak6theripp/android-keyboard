"""End-to-end dictation test without a human voice.

Usage: python run_audio_test.py <clip.wav> [--fake MS] [--tail SECONDS] [--keep]

Pushes a 16 kHz mono 16-bit WAV (converted to raw PCM) to the phone, tells the keyboard to use
it instead of the microphone for the next session, starts dictation exactly as a tap on the mic
would, waits for the clip plus a tail, stops, and prints the dictation log's key events and the
resulting text in the focused field.

The keyboard must be showing in a harmless text field (the Chrome search box). The field is
cleared first unless --keep is given.  --fake MS swaps the Claude cleanup for the local stand-in.
"""
import os, re, subprocess, sys, time, wave

ADB = os.environ.get("ADB", r"D:\Android\Sdk\platform-tools\adb.exe")
SERIAL = os.environ.get("ADB_SERIAL", "192.168.1.125:5555")
PKG = "org.futo.inputmethod.latin"
REMOTE = "/sdcard/Android/data/%s/files" % PKG
RCV = PKG + "/.dictation.DictationDebugReceiver"


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def bc(action, *extra):
    return adb("shell", "am", "broadcast", "-n", RCV, "-a", PKG + ".dictation." + action, *extra)


def field():
    adb("logcat", "-c")
    bc("DEBUG_FIELD")
    time.sleep(1.0)
    out = adb("logcat", "-d", "-v", "raw", "-s", "DictationDebug:V")
    m = re.search(r"FIELD len=(\S+) text=\[(.*)\]", out, re.S)
    return m.group(2) if m else None


def main():
    args = sys.argv[1:]
    clip = args[0]
    fake = int(args[args.index("--fake") + 1]) if "--fake" in args else 0
    tail = float(args[args.index("--tail") + 1]) if "--tail" in args else 7.0
    keep = "--keep" in args

    with wave.open(clip, "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, "need 16 kHz mono 16-bit"
        frames = w.readframes(w.getnframes())
        seconds = w.getnframes() / 16000.0
    raw = os.path.splitext(clip)[0] + ".raw"
    open(raw, "wb").write(frames)
    remote = REMOTE + "/" + os.path.basename(raw)
    adb("shell", "mkdir", "-p", REMOTE)
    adb("push", raw, remote)

    shown = "mInputShown=true" in adb("shell", "dumpsys", "input_method")
    if not shown:
        print("keyboard is not showing; focus a text field first"); sys.exit(2)
    if not keep:
        adb("shell", "input", "keycombination", "113", "29")   # ctrl+A
        adb("shell", "input", "keyevent", "67")                 # DEL
        time.sleep(0.5)
    before = field()

    adb("shell", "setprop", "log.tag.Dictation", "DEBUG")
    bc("DEBUG_FAKE_CLEANUP", "--el", "ms", str(fake))
    bc("DEBUG_AUDIO", "--es", "path", remote)
    adb("logcat", "-c")
    bc("DEBUG_TOGGLE")
    time.sleep(seconds + tail)
    bc("DEBUG_STOP")
    time.sleep(3.5)
    log = adb("logcat", "-d", "-v", "time", "-s", "Dictation:V", "DictationDebug:V", "AndroidRuntime:E")
    text = field()

    keep_events = ("final seg", "cleanup_", "commit", "end_of_utterance", "backlog", "freeze", "user_interv", "stt_error",
                   "controller_", "state=", "error", "FATAL", "Exception", "inject_eof", "TOGGLE")
    print("=== clip %.1fs, fake cleanup %s ===" % (seconds, ("%d ms" % fake) if fake else "off (Claude)"))
    for line in log.splitlines():
        if any(k in line for k in keep_events):
            print(re.sub(r"^\d\d-\d\d (\S+) \S+\(\s*\d+\): ", r"\1 ", line)[:230])
    print("\nFIELD BEFORE: [%s]" % before)
    print("FIELD AFTER:  [%s]" % text)


if __name__ == "__main__":
    main()
