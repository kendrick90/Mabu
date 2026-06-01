#!/usr/bin/env python3
"""
Auto-tuning harness for Mabu's animation "magic numbers".

Drives the device config API (POST /config, POST /mode) and reads the telemetry
API (GET /status) to measure how each tunable affects the system, so we can pick
values from data instead of by feel. Three commands:

  baseline [secs]                 measure the current config (a reference point)
  sweep <param> <v1> <v2> ...     set PUPPET, sweep <param> over the values,
                                  measure jitter/tracking/rate at each, recommend
  blink [secs]                    measure blink-detection symmetry (both-eyes vs
                                  one-eye) while you blink/wink on cue

Metrics (per measurement window):
  jitter   = stddev of each motor's position (lower = steadier; the headline number)
  track    = mean |pos - target| for the eyes (how far the lid/eye lags its target)
  raw/filt = stddev of pupil raw vs filtered offset (shows how much the input
             filter is actually removing)
  cmd_hz   = median motor-command rate (near 0 at rest = deadbands working)

IMPORTANT: jitter only exists when a FACE is in view. The script prompts you to
sit still / blink; follow the prompts and keep your face framed. No deps (stdlib).

Examples:
  python mabu_tune.py sweep eyeGazeDeadband 0.02 0.04 0.06 0.10 0.15
  python mabu_tune.py sweep eyeGazeInputAlpha 0.20 0.35 0.50 0.70
  python mabu_tune.py sweep smoothAlphaNeck 0.06 0.12 0.20 0.30
  python mabu_tune.py blink 25
  python mabu_tune.py 10.0.0.42 baseline
"""
import sys
import time
import json
import statistics
from urllib.request import urlopen, Request
from urllib.parse import urlencode
from urllib.error import URLError

HOST = "10.0.0.69"
MOTORS = ["eyes_lr", "eyes_ud", "neck_rot", "neck_elev", "neck_tilt", "eyelid_l", "eyelid_r"]
TARGET = {"eyes_lr": "target_eyes_lr", "eyes_ud": "target_eyes_ud", "neck_rot": "target_neck_rot",
          "neck_elev": "target_neck_elev", "neck_tilt": "target_neck_tilt"}


def base():
    return "http://" + HOST + ":7862"


def get(path):
    with urlopen(base() + path, timeout=3) as r:
        return json.loads(r.read().decode("utf-8"))


def post(path, params):
    data = urlencode(params).encode("utf-8")
    req = Request(base() + path, data=data,
                  headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urlopen(req, timeout=3) as r:
        return r.read()


def reachable():
    try:
        with urlopen(base() + "/healthz", timeout=2) as r:
            return r.read().strip() == b"ok"
    except Exception:
        return False


def collect(secs, hz=20):
    """Poll /status for `secs` seconds; return the list of samples."""
    out = []
    period = 1.0 / hz
    t_end = time.time() + secs
    while time.time() < t_end:
        t0 = time.time()
        try:
            out.append(get("/status"))
        except (URLError, OSError, ValueError):
            pass
        dt = time.time() - t0
        if dt < period:
            time.sleep(period - dt)
    return out


def series(samples, section, key):
    vals = []
    for s in samples:
        v = (s.get(section) or {}).get(key)
        if isinstance(v, (int, float)):
            vals.append(float(v))
    return vals


def sd(xs):
    return statistics.pstdev(xs) if len(xs) > 1 else 0.0


def analyze(samples):
    m = {}
    for k in MOTORS:
        m["jit_" + k] = sd(series(samples, "motors", k))
    # eye tracking error (how far pos lags target)
    for k in ("eyes_lr", "eyes_ud"):
        pos = series(samples, "motors", k)
        tgt = series(samples, "animation", TARGET[k])
        n = min(len(pos), len(tgt))
        m["track_" + k] = (sum(abs(pos[i] - tgt[i]) for i in range(n)) / n) if n else 0.0
    m["raw_x"] = sd(series(samples, "animation", "pupil_raw_x"))
    m["raw_y"] = sd(series(samples, "animation", "pupil_raw_y"))
    m["filt_x"] = sd(series(samples, "animation", "pupil_filt_x"))
    m["filt_y"] = sd(series(samples, "animation", "pupil_filt_y"))
    cmd = series(samples, "motors", "cmd_hz")
    m["cmd_hz"] = statistics.median(cmd) if cmd else 0.0
    # honest face-presence: the device reports face_present per frame (distinct
    # from held sensor values). Fall back to eye-open prob for older builds.
    present = [s for s in samples
               if (s.get("animation") or {}).get("face_present") is True
               or (s.get("animation") or {}).get("eye_open_prob_l") is not None]
    m["face_frac"] = len(present) / len(samples) if samples else 0.0
    return m


def warn_no_face(m):
    if m["face_frac"] < 0.5:
        print("  !! face seen in only %d%% of samples -- results are meaningless."
              " Sit in frame and re-run." % (m["face_frac"] * 100))


def cmd_baseline(args):
    secs = float(args[0]) if args else 6.0
    print("Measuring current config for %gs -- hold still, face the robot..." % secs)
    time.sleep(1.5)
    m = analyze(collect(secs))
    warn_no_face(m)
    print("  eyes jitter  LR=%.2f  UD=%.2f   neck jitter R=%.2f E=%.2f T=%.2f"
          % (m["jit_eyes_lr"], m["jit_eyes_ud"], m["jit_neck_rot"], m["jit_neck_elev"], m["jit_neck_tilt"]))
    print("  eye track err LR=%.2f UD=%.2f   cmd_hz=%.1f" % (m["track_eyes_lr"], m["track_eyes_ud"], m["cmd_hz"]))
    print("  pupil noise  raw=(%.3f,%.3f)  filtered=(%.3f,%.3f)" % (m["raw_x"], m["raw_y"], m["filt_x"], m["filt_y"]))


def cmd_sweep(args):
    if len(args) < 2:
        print("usage: sweep <param> <v1> <v2> ..."); return
    param = args[0]
    values = args[1:]
    cfg = get("/config")
    if param not in cfg:
        print("unknown param '%s'. known: %s" % (param, ", ".join(sorted(cfg.keys())))); return
    original = cfg[param]
    post("/mode", {"mode": "PUPPET"})
    print("Sweeping %s over %s  (current=%s). PUPPET mode." % (param, values, original))
    print("Sit still and face the robot for the whole sweep.\n")
    time.sleep(2.0)

    hdr = "%-10s %8s %8s %8s %8s %8s %8s" % (param, "jitLR", "jitUD", "trkLR", "trkUD", "cmd_hz", "filtX")
    print(hdr); print("-" * len(hdr))
    rows = []
    for v in values:
        post("/config", {param: v})
        time.sleep(1.5)  # let tweens + filters settle to the new value
        m = analyze(collect(5.0))
        rows.append((v, m))
        print("%-10s %8.2f %8.2f %8.2f %8.2f %8.1f %8.3f"
              % (v, m["jit_eyes_lr"], m["jit_eyes_ud"], m["track_eyes_lr"],
                 m["track_eyes_ud"], m["cmd_hz"], m["filt_x"]))
    post("/config", {param: original})
    print("\nrestored %s=%s" % (param, original))
    if rows and rows[0][1]["face_frac"] < 0.5:
        warn_no_face(rows[0][1]); return
    # recommend: lowest combined eye jitter
    best = min(rows, key=lambda r: r[1]["jit_eyes_lr"] + r[1]["jit_eyes_ud"])
    print("lowest eye jitter at %s=%s (LR=%.2f UD=%.2f). NOTE: more smoothing/deadband"
          % (param, best[0], best[1]["jit_eyes_lr"], best[1]["jit_eyes_ud"]))
    print("also adds lag -- prefer the smallest setting whose jitter is 'good enough' (~<1.0).")


def cmd_blink(args):
    secs = float(args[0]) if args else 25.0
    post("/mode", {"mode": "PUPPET"})
    print("Blink test for %gs. On my go: do 10 NORMAL two-eye blinks (slow), then 5 WINKS"
          " (one eye). Keep your face framed." % secs)
    for c in (3, 2, 1):
        print("  starting in %d..." % c); time.sleep(1)
    print("  GO")
    samples = collect(secs, hz=30)
    # rising edges of closed state per eye
    def edges(key):
        evs = []
        prev = False
        for s in samples:
            c = bool((s.get("animation") or {}).get(key))
            if c and not prev:
                evs.append(time.time())  # index proxy; we use ordering only
            prev = c
        return evs
    # re-derive with sample index timestamps for co-occurrence
    closedL = [bool((s.get("animation") or {}).get("eye_closed_l")) for s in samples]
    closedR = [bool((s.get("animation") or {}).get("eye_closed_r")) for s in samples]
    def rising(seq):
        return [i for i in range(1, len(seq)) if seq[i] and not seq[i - 1]]
    eL, eR = rising(closedL), rising(closedR)
    # joint = an L edge with an R edge within 6 samples (~200ms @30Hz)
    joint = 0
    for i in eL:
        if any(abs(i - j) <= 6 for j in eR):
            joint += 1
    onlyL = len(eL) - joint
    onlyR = sum(1 for j in eR if not any(abs(i - j) <= 6 for i in eL))
    print("\nclose events: left=%d right=%d" % (len(eL), len(eR)))
    print("  both-eyes (coupled blink): %d" % joint)
    print("  one-eye only: L=%d R=%d  (these are winks OR mis-coupled blinks)" % (onlyL, onlyR))
    print("If your two-eye blinks show up as one-eye events, raise eyelidCoupling /")
    print("eyelidWinkOpen. If winks fail to register, lower them.")


def show_prompt(title, phase, upcoming=""):
    """Mirror the instruction onto Mabu's own screen (POST /prompt)."""
    try:
        post("/prompt", {"title": title, "phase": phase, "upcoming": upcoming})
    except Exception:
        pass


def ready_seq():
    """Current value of the device READY counter, or None on error."""
    for _ in range(3):
        try:
            return int(get("/status").get("ready_seq", 0))
        except Exception:
            time.sleep(0.2)
    return None


CAPTURE_FIELDS = ["head_yaw", "head_pitch", "head_roll", "pupil_raw_x", "pupil_raw_y",
                  "eye_open_prob_l", "eye_open_prob_r", "face_center_x", "face_center_y"]


def wait_for_ready(prompt, upcoming, phase, wait_secs=180):
    start = ready_seq() or 0
    show_prompt(prompt, phase, upcoming)
    waited = 0.0
    while True:
        s = ready_seq()
        if s is not None and s > start:
            return
        time.sleep(0.2)
        waited += 0.2
        if waited >= wait_secs:
            print("   (no READY in %ds -- continuing)" % wait_secs)
            return


def capture_pose(prompt, upcoming="", secs=2.5, max_tries=3):
    """Self-paced: wait for READY, capture, and RETRY the same pose if the face
    was lost (so a bad pose re-prompts instead of poisoning the data)."""
    print("\n>> " + prompt)
    phase = "tap READY on Mabu when you're holding it"
    med, ff = {}, 0.0
    for attempt in range(1, max_tries + 1):
        print("   get into the pose%s, then tap READY..."
              % ("" if attempt == 1 else " (keep your face in view!)"))
        wait_for_ready(prompt, upcoming, phase)
        show_prompt(prompt, "capturing -- HOLD STILL", upcoming)
        print("   capturing %gs -- hold still..." % secs)
        samples = collect(secs, hz=20)
        med = {}
        for f in CAPTURE_FIELDS:
            vals = series(samples, "animation", f)
            med[f] = statistics.median(vals) if vals else None
        ff = analyze(samples)["face_frac"]
        if ff >= 0.6 and len(samples) >= secs * 6:
            return med, ff
        print("   !! face lost (%d%% present) / few samples (%d) -- redo this pose"
              % (ff * 100, len(samples)))
        if attempt < max_tries:
            phase = "FACE LOST -- re-frame, tap READY to RETRY (%d/%d)" % (attempt + 1, max_tries)
            show_prompt("Redo: " + prompt, phase, upcoming)
    print("   (kept best of %d tries -- mark this pose suspect)" % max_tries)
    return med, ff


def cmd_calibrate(args):
    sub = args[0] if args else "sense"
    if sub != "sense":
        print("only 'sense' is implemented"); return
    if not reachable():
        print("Device not reachable at %s -- is the Anima app running and on the LAN?" % base())
        return
    post("/mode", {"mode": "PUPPET"})
    print("=== Stage-1 SENSE capture ===")
    print("Follow each prompt; hold the pose still during 'capturing'. Keep your")
    print("face framed at a steady distance the whole time.\n")
    # (key, full prompt, short label for the "coming up" list, seconds)
    poses = [
        ("straight", "Look STRAIGHT at Mabu -- eyes centered, head level", "look straight", 4.0),
        ("yaw_l", "Turn your HEAD to YOUR LEFT (comfortable)", "head LEFT", 3.0),
        ("yaw_r", "Turn your HEAD to YOUR RIGHT", "head RIGHT", 3.0),
        ("pitch_d", "NOD your head DOWN (chin down)", "nod DOWN", 3.0),
        ("pitch_u", "NOD your head UP (chin up)", "nod UP", 3.0),
        ("roll_l", "TILT your head toward your LEFT shoulder", "tilt LEFT", 3.0),
        ("roll_r", "TILT your head toward your RIGHT shoulder", "tilt RIGHT", 3.0),
        ("eyes_l", "Head STRAIGHT -- look with your EYES to your LEFT", "eyes LEFT", 3.0),
        ("eyes_r", "Head STRAIGHT -- look with your EYES to your RIGHT", "eyes RIGHT", 3.0),
        ("eyes_u", "Head STRAIGHT -- look UP with your eyes", "eyes UP", 3.0),
        ("eyes_d", "Head STRAIGHT -- look DOWN with your eyes", "eyes DOWN", 3.0),
        ("closed", "CLOSE both eyes", "close eyes", 3.0),
    ]
    shorts = [p[2] for p in poses]
    show_prompt("Calibration starting", "get ready...", "\n".join(shorts[:4]))
    time.sleep(2)
    data = {}
    for i, (key, prompt, _short, secs) in enumerate(poses):
        upcoming = "\n".join(shorts[i + 1:i + 4])  # next 3 so you can plan ahead
        med, ff = capture_pose(prompt, upcoming, secs)
        data[key] = med
        if ff < 0.5:
            print("   !! face seen only %d%% of the time -- consider re-running framed better" % (ff * 100))
    show_prompt("Done -- thanks!", "calibration capture complete", "")
    time.sleep(2)
    show_prompt("", "", "")  # clear the overlay

    base = data["straight"]

    def rep(name, lowkey, lowlabel, highkey, highlabel, field):
        b = base.get(field); lo = data[lowkey].get(field); hi = data[highkey].get(field)
        if None in (b, lo, hi):
            print("  %-9s (missing data)" % name); return
        span = abs(hi - lo)
        grows = highlabel if hi >= lo else lowlabel
        print("  %-9s rest=%+6.2f   %s=%+6.2f  %s=%+6.2f   span=%5.2f  (rises toward %s)"
              % (name, b, lowlabel, lo, highlabel, hi, span, grows))

    print("\n--- derived: resting bias, sensor sign, usable span ---")
    rep("yaw", "yaw_l", "L", "yaw_r", "R", "head_yaw")
    rep("pitch", "pitch_d", "D", "pitch_u", "U", "head_pitch")
    rep("roll", "roll_l", "L", "roll_r", "R", "head_roll")
    rep("pupil_x", "eyes_l", "L", "eyes_r", "R", "pupil_raw_x")
    rep("pupil_y", "eyes_u", "U", "eyes_d", "D", "pupil_raw_y")
    print("  eye_open  rest L/R=%s/%s   closed L/R=%s/%s"
          % (fmt2(base.get("eye_open_prob_l")), fmt2(base.get("eye_open_prob_r")),
             fmt2(data["closed"].get("eye_open_prob_l")), fmt2(data["closed"].get("eye_open_prob_r"))))
    print("  face_ctr  rest=(%s, %s)  <- expect ~0.50,0.50; a stray value here is the 'offset'"
          % (fmt2(base.get("face_center_x")), fmt2(base.get("face_center_y"))))
    print("\nNext: in CALIBRATION.md Stage 2, pick signs for MIRROR and set each")
    print("inputRange a bit SMALLER than 'span' to exaggerate and fill Mabu's range.")


def fmt2(v):
    return "--" if v is None else ("%.2f" % v)


def main():
    global HOST
    argv = sys.argv[1:]
    if argv and ("." in argv[0]) and not argv[0].replace(".", "").isalpha():
        HOST = argv[0]; argv = argv[1:]
    if not argv:
        print(__doc__); return
    cmd = argv[0]
    if cmd == "baseline":
        cmd_baseline(argv[1:])
    elif cmd == "sweep":
        cmd_sweep(argv[1:])
    elif cmd == "blink":
        cmd_blink(argv[1:])
    elif cmd == "calibrate":
        cmd_calibrate(argv[1:])
    else:
        print("unknown command '%s'" % cmd); print(__doc__)


if __name__ == "__main__":
    main()
