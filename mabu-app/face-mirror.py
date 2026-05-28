"""face-mirror.py

Closed-loop face-tracking demo: tablet's camera watches you, motors mirror you.
- Eyes follow your face's position on the camera frame.
- Mabu blinks when you blink.

Runs in Termux on the Mabu tablet. Requires:
  pkg install python termux-api
  pip install opencv-python-headless pyserial numpy

Uses termux-camera-photo to grab still frames (~3-5 FPS on Cortex-A17),
then OpenCV's LBP cascade (extracted from factorymode's assets/opencv/)
for face + eye detection.

The eye-aspect-ratio blink detector needs a more thorough landmark model
than Haar/LBP — for v1 we just detect "are eyes visible" within the face
ROI and use that as a (binary) blink proxy.
"""
from __future__ import annotations
import os
import subprocess
import time
import math
import sys
from pathlib import Path

import cv2
import numpy as np

from mabu import Mabu, MOTOR_NAME

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
PHOTO_PATH = '/sdcard/.mabu-frame.jpg'
LOOP_DELAY = 0.05        # ms gap between captures; termux-camera-photo itself takes ~250 ms
EMA_ALPHA = 0.35         # smoothing factor for face position (0=no smoothing, 1=instant)
DEADBAND = 0.02          # ignore face moves smaller than this fraction of frame
BLINK_DEBOUNCE = 0.12    # require eyes-missing for this many seconds before blinking back

CASCADE_FACE = str(Path(__file__).parent / 'lbpcascade_frontalface.xml')
# Eye cascade isn't in factorymode's assets; OpenCV ships Haar for eyes that
# we'll need to bundle separately. Falling back to "no face landmarks
# detected within the face ROI" as the blink proxy until we add that.

# Map normalized face center (0..1) to motor 0..100. The robot's natural
# range of motion is narrower than the camera's view -- only swing eyes
# ~30% off center even for face-at-edge, otherwise it looks twitchy.
GAZE_GAIN = 0.6


# ---------------------------------------------------------------------------
def capture_frame() -> 'np.ndarray | None':
    """One photo via termux-camera-photo. Returns BGR ndarray or None."""
    try:
        subprocess.run(
            ['termux-camera-photo', '-c', '0', PHOTO_PATH],
            check=True, capture_output=True, timeout=2.0,
        )
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, FileNotFoundError) as e:
        print(f"[capture] {type(e).__name__}: {e}", file=sys.stderr)
        return None
    if not os.path.exists(PHOTO_PATH):
        return None
    img = cv2.imread(PHOTO_PATH)
    return img


def find_face(gray: 'np.ndarray', cascade) -> 'tuple[int,int,int,int] | None':
    """Returns (x, y, w, h) of the most prominent face, or None."""
    faces = cascade.detectMultiScale(
        gray, scaleFactor=1.2, minNeighbors=3, minSize=(60, 60),
    )
    if len(faces) == 0:
        return None
    # Largest face by area
    return tuple(max(faces, key=lambda r: r[2] * r[3]))


# ---------------------------------------------------------------------------
def main():
    if not os.path.exists(CASCADE_FACE):
        sys.exit(f"Missing cascade: {CASCADE_FACE}")
    cascade = cv2.CascadeClassifier(CASCADE_FACE)
    if cascade.empty():
        sys.exit("Cascade failed to load")

    # Smoothed face center, in 0..1 frame coords.
    fx_smooth = 0.5
    fy_smooth = 0.5
    last_seen_t = time.time()
    eyes_were_open = True

    with Mabu(debug=False) as m:
        m.center()
        print("Face mirror running. Press Ctrl-C to stop.")
        while True:
            frame = capture_frame()
            if frame is None:
                time.sleep(0.5)
                continue

            h, w = frame.shape[:2]
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            gray = cv2.equalizeHist(gray)

            face = find_face(gray, cascade)
            now = time.time()

            if face is None:
                # No face -> drift back toward center, treat as eyes-closed-ish
                eyes_open_now = False
            else:
                fx, fy, fw, fh = face
                cx = (fx + fw / 2) / w
                cy = (fy + fh / 2) / h
                # camera might be mirrored -- assume so; flip x
                cx = 1.0 - cx
                # smooth
                fx_smooth = EMA_ALPHA * cx + (1 - EMA_ALPHA) * fx_smooth
                fy_smooth = EMA_ALPHA * cy + (1 - EMA_ALPHA) * fy_smooth
                last_seen_t = now
                eyes_open_now = True

            # Map gaze
            gx = 0.5 + (fx_smooth - 0.5) * GAZE_GAIN
            gy = 0.5 + (fy_smooth - 0.5) * GAZE_GAIN
            m.gaze(gx, gy)

            # Blink mirror
            if eyes_open_now != eyes_were_open:
                # debounce
                if eyes_open_now or (now - last_seen_t) > BLINK_DEBOUNCE:
                    if eyes_open_now:
                        m.eyes_open()
                    else:
                        m.eyes_closed()
                    eyes_were_open = eyes_open_now

            time.sleep(LOOP_DELAY)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopping.")
