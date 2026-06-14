#!/usr/bin/env python3
"""
Realtime monitor for Mabu's animation/motor state.

Polls the device StatusServer (mabu-android StatusServer.kt, default :7862) and
renders a live terminal dashboard: actual motor positions, motor-command rate,
the raw ML Kit inputs (head pose, pupil, eye-open probs) vs. the tween targets,
and -- the point of this tool -- a rolling JITTER figure per motor (the standard
deviation of its position over the last few seconds). Hold still and watch the
jitter column: deadbands working = near 0; tremor = a few units; that's the
number to drive down when tuning.

No dependencies (stdlib urllib). Usage:

    python mabu_monitor.py                 # host 10.0.0.69, 15 Hz
    python mabu_monitor.py 10.0.0.42       # other device
    python mabu_monitor.py 10.0.0.69 25    # 25 Hz poll

Ctrl-C to quit.
"""
import sys
import time
import json
import math
from collections import deque
from urllib.request import urlopen
from urllib.error import URLError

DEFAULT_HOST = "10.0.0.69"
DEFAULT_HZ = 15
JITTER_WINDOW_SEC = 2.0  # rolling window for the stddev jitter figure

MOTORS = ["eyelid_l", "eyelid_r", "eyes_lr", "eyes_ud",
          "neck_rot", "neck_elev", "neck_tilt"]


def stddev(xs):
    n = len(xs)
    if n < 2:
        return 0.0
    m = sum(xs) / n
    return math.sqrt(sum((x - m) ** 2 for x in xs) / n)


def fmt(v, width=6, prec=1):
    if v is None:
        return "  --  ".rjust(width)
    return f"{v:>{width}.{prec}f}"


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_HOST
    hz = float(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_HZ
    url = f"http://{host}:7862/status"
    period = 1.0 / hz
    window = int(JITTER_WINDOW_SEC * hz)

    # Rolling position history per motor for the jitter (stddev) figure.
    hist = {m: deque(maxlen=window) for m in MOTORS}
    peak = {m: 0.0 for m in MOTORS}
    errors = 0

    print(f"Monitoring {url} at {hz:g} Hz  (jitter = stddev over {JITTER_WINDOW_SEC:g}s; "
          f"Ctrl-C to quit)\n")
    try:
        while True:
            t0 = time.time()
            try:
                with urlopen(url, timeout=period * 4 + 0.5) as r:
                    d = json.loads(r.read().decode("utf-8"))
                errors = 0
            except (URLError, OSError, ValueError) as e:
                errors += 1
                sys.stdout.write(f"\r[no data x{errors}] {str(e)[:60]:<60}")
                sys.stdout.flush()
                time.sleep(period)
                continue

            mot = d.get("motors", {})
            ani = d.get("animation", {})
            for m in MOTORS:
                v = mot.get(m)
                if isinstance(v, (int, float)):
                    hist[m].append(float(v))

            # Build the display. \033[2J\033[H = clear + home (stable in-place view).
            lines = []
            lines.append(
                f"mode={d.get('mode'):<7} blink={ani.get('blink_method'):<11} "
                f"cmd_hz={fmt(mot.get('cmd_hz'),5,1)}   "
                f"mlkit_fps={fmt(d.get('mlkit_fps'),4,1)} "
                f"mean_ms={fmt(d.get('mlkit_mean_ms'),5,1)}   "
                f"transport={d.get('transport_state')}"
            )
            lines.append("")
            lines.append(f"{'motor':<10} {'pos':>7} {'target':>8} {'jitter':>8} {'peakJ':>8}")
            lines.append("-" * 45)
            target_map = {
                "eyes_lr": ani.get("target_eyes_lr"),
                "eyes_ud": ani.get("target_eyes_ud"),
                "neck_rot": ani.get("target_neck_rot"),
                "neck_elev": ani.get("target_neck_elev"),
                "neck_tilt": ani.get("target_neck_tilt"),
            }
            for m in MOTORS:
                j = stddev(list(hist[m]))
                peak[m] = max(peak[m], j)
                pos = mot.get(m)
                tgt = target_map.get(m)
                bar = "#" * min(20, int(j * 4))  # visual jitter bar (~5 units = full)
                lines.append(
                    f"{m:<10} {fmt(pos):>7} {fmt(tgt):>8} {fmt(j,8,2):>8} "
                    f"{fmt(peak[m],8,2):>8}  {bar}"
                )
            lines.append("")
            lines.append(
                f"head  yaw={fmt(ani.get('head_yaw'),6,1)} pitch={fmt(ani.get('head_pitch'),6,1)} "
                f"roll={fmt(ani.get('head_roll'),6,1)}"
            )
            lines.append(
                f"pupil raw=({fmt(ani.get('pupil_raw_x'),5,2)},{fmt(ani.get('pupil_raw_y'),5,2)}) "
                f"filt=({fmt(ani.get('pupil_filt_x'),5,2)},{fmt(ani.get('pupil_filt_y'),5,2)})"
            )
            lines.append(
                f"eyes  openL={fmt(ani.get('eye_open_prob_l'),5,2)} openR={fmt(ani.get('eye_open_prob_r'),5,2)} "
                f"closedL={ani.get('eye_closed_l')} closedR={ani.get('eye_closed_r')}"
            )
            sys.stdout.write("\033[2J\033[H" + "\n".join(lines) + "\n")
            sys.stdout.flush()

            dt = time.time() - t0
            if dt < period:
                time.sleep(period - dt)
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
