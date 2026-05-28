"""mabu.py

Python control library for a liberated Mabu robot head.

Protocol cross-verified from ElectroNick's PyPI mabu package and
Catalia's own factorymode.apk decompiled source. See
../notes/motor-protocol.md for the authoritative reference.

Conventions:
  - Logical motor values are 0-100 with 50 = center
  - On the wire, value is `int(v * 2.55)` clamped to 0..255
  - Calibration is per-unit and lives in /data/data/com.catalia.factorymode;
    this library ignores it. Each unit's absolute pose will be slightly off
    until we add a calibration store of our own.

Usage:
    from mabu import Mabu
    with Mabu() as m:
        m.blink()
        m.gaze(0.7, 0.3)         # look right + up (normalized 0..1)
        m.nod()
"""
from __future__ import annotations
import time
import math
from dataclasses import dataclass
from typing import Optional, Iterable

try:
    import serial
except ImportError as e:  # pragma: no cover
    raise SystemExit("pyserial not installed. Run: pip install pyserial") from e


# ---------------------------------------------------------------------------
# Protocol constants
# ---------------------------------------------------------------------------
DEFAULT_PORT = '/dev/ttyS1'
DEFAULT_BAUD = 57600

# Motor bitmask values (MSB to LSB).
# Order MUST match factorymode's MotorRepository.motorNames so that
# multi-motor commands pack values in the right slots.
LDL = 0x40   # EYELID_LEFT
LDR = 0x20   # EYELID_RIGHT
ELR = 0x10   # EYES_LEFT_RIGHT
EUD = 0x08   # EYES_UP_DOWN
NE  = 0x04   # NECK_ELEVATION
NR  = 0x02   # NECK_ROTATION
NT  = 0x01   # NECK_TILT

ALL_MOTORS = (LDL, LDR, ELR, EUD, NE, NR, NT)
MOTOR_NAME = {
    LDL: 'eyelid_left',  LDR: 'eyelid_right',
    ELR: 'eyes_lr',      EUD: 'eyes_ud',
    NE:  'neck_elev',    NR:  'neck_rot',    NT:  'neck_tilt',
}


def fletcher8(data: Iterable[int]) -> int:
    """Fletcher-8 checksum used by the motor board."""
    s1 = s2 = 0
    for b in data:
        s1 = (s1 + b) % 255
        s2 = (s2 + s1) % 255
    return (s2 << 8) | s1


def _frame(payload: bytes) -> bytes:
    """Wrap payload as FA 00 <len> <payload> <fletcher8>."""
    head = bytearray([0xFA, 0x00, len(payload)]) + bytearray(payload)
    ck = fletcher8(head)
    return bytes(head + bytes([ck >> 8, ck & 0xFF]))


def _v(value: float) -> int:
    """Convert logical 0..100 to wire byte 0..255, clamped."""
    return max(0, min(255, int(round(value * 2.55))))


# ---------------------------------------------------------------------------
# Mabu class
# ---------------------------------------------------------------------------
@dataclass
class Pose:
    """A target pose for any subset of the 7 motors. None = leave alone."""
    eyelid_left:  Optional[float] = None
    eyelid_right: Optional[float] = None
    eyes_lr:      Optional[float] = None
    eyes_ud:      Optional[float] = None
    neck_elev:    Optional[float] = None
    neck_rot:     Optional[float] = None
    neck_tilt:    Optional[float] = None


class Mabu:
    """Driver for /dev/ttyS1 motor link."""

    def __init__(self, port: str = DEFAULT_PORT, baud: int = DEFAULT_BAUD, debug: bool = False):
        self.port = port
        self.baud = baud
        self.debug = debug
        self._ser: Optional[serial.Serial] = None
        self._last_target = {bit: 50.0 for bit in ALL_MOTORS}

    # ---- lifecycle ---------------------------------------------------------
    def open(self) -> 'Mabu':
        self._ser = serial.Serial(self.port, self.baud, timeout=0.2)
        self.power_on()
        return self

    def close(self) -> None:
        if self._ser is not None and self._ser.is_open:
            try:
                self.power_off()
            except Exception:
                pass
            self._ser.close()
            self._ser = None

    def __enter__(self) -> 'Mabu':
        return self.open()

    def __exit__(self, *exc):
        self.close()

    # ---- raw protocol ------------------------------------------------------
    def _send(self, frame: bytes) -> None:
        assert self._ser is not None, 'Mabu not open'
        if self.debug:
            print(f"TX: {frame.hex()}")
        self._ser.write(frame)

    def power_on(self) -> None:
        self._send(bytes([0xFA, 0x00, 0x02, 0x4F, 0x7F, 0x0B, 0xCB]))
        time.sleep(0.2)

    def power_off(self) -> None:
        self._send(bytes([0xFA, 0x00, 0x02, 0x4F, 0x8B, 0x4C]))

    # ---- low-level motor moves --------------------------------------------
    def move_one(self, bit: int, value: float) -> None:
        """Move a single motor to logical value 0..100."""
        self._send(_frame(bytes([0x01, bit, 0x01, _v(value)])))
        self._last_target[bit] = float(value)

    def move(self, **kwargs: float) -> None:
        """Move multiple motors at once.

        Accepts keyword args by motor name (eyelid_left, eyes_lr, neck_tilt, etc.)
        Bitmask is OR of all motors mentioned; values pack in MSB-first order.
        """
        mask = 0
        ordered_values: list[int] = []
        for bit in ALL_MOTORS:  # iteration order = command order
            name = MOTOR_NAME[bit]
            if name in kwargs:
                mask |= bit
                ordered_values.append(_v(kwargs[name]))
                self._last_target[bit] = float(kwargs[name])
        if mask == 0:
            return
        payload = bytes([0x01, mask, 0x01]) + bytes(ordered_values)
        self._send(_frame(payload))

    # ---- semantic poses ----------------------------------------------------
    def center(self) -> None:
        """All motors to mechanical center."""
        self.move(eyelid_left=50, eyelid_right=50, eyes_lr=50, eyes_ud=50,
                  neck_elev=50, neck_rot=50, neck_tilt=50)

    def eyes_open(self) -> None:
        self.move(eyelid_left=50, eyelid_right=50)

    def eyes_closed(self) -> None:
        self.move(eyelid_left=15, eyelid_right=15)

    def gaze(self, x: float = 0.5, y: float = 0.5) -> None:
        """Look toward normalized (x, y) in 0..1.
        x: 0=full left,  1=full right
        y: 0=full down, 1=full up
        """
        x = max(0.0, min(1.0, x))
        y = max(0.0, min(1.0, y))
        self.move(eyes_lr=x * 100, eyes_ud=y * 100)

    def head(self, *, rotation: float = 50, elevation: float = 50, tilt: float = 50) -> None:
        self.move(neck_rot=rotation, neck_elev=elevation, neck_tilt=tilt)

    # ---- animations --------------------------------------------------------
    def blink(self, hold: float = 0.08, recovery: float = 0.10) -> None:
        """Quick blink (both eyelids close + reopen)."""
        prev_l = self._last_target[LDL]
        prev_r = self._last_target[LDR]
        self.move(eyelid_left=10, eyelid_right=10)
        time.sleep(hold)
        self.move(eyelid_left=prev_l, eyelid_right=prev_r)
        time.sleep(recovery)

    def wink(self, side: str = 'right', hold: float = 0.18) -> None:
        bit_name = 'eyelid_right' if side == 'right' else 'eyelid_left'
        prev = self._last_target[LDR if side == 'right' else LDL]
        self.move(**{bit_name: 10})
        time.sleep(hold)
        self.move(**{bit_name: prev})

    def nod(self, depth: float = 25, period: float = 0.5, cycles: int = 1) -> None:
        center = 50.0
        for _ in range(cycles):
            self.move(neck_elev=center - depth)
            time.sleep(period / 2)
            self.move(neck_elev=center + depth)
            time.sleep(period / 2)
        self.move(neck_elev=center)

    def shake(self, depth: float = 25, period: float = 0.4, cycles: int = 2) -> None:
        center = 50.0
        for _ in range(cycles):
            self.move(neck_rot=center - depth)
            time.sleep(period / 2)
            self.move(neck_rot=center + depth)
            time.sleep(period / 2)
        self.move(neck_rot=center)

    def look_around(self, period: float = 1.5) -> None:
        """A slow gaze sweep — useful as idle behavior."""
        steps = 8
        for i in range(steps):
            t = i / (steps - 1)
            self.gaze(0.5 + 0.4 * math.sin(t * math.tau), 0.5 + 0.2 * math.cos(t * math.tau))
            time.sleep(period / steps)

    def surprised(self) -> None:
        self.move(eyelid_left=80, eyelid_right=80, neck_elev=70)


# ---------------------------------------------------------------------------
# CLI test
# ---------------------------------------------------------------------------
def _selftest():
    with Mabu(debug=True) as m:
        print("center"); m.center(); time.sleep(0.6)
        print("blink"); m.blink(); time.sleep(0.3)
        print("wink left"); m.wink('left'); time.sleep(0.3)
        print("wink right"); m.wink('right'); time.sleep(0.3)
        print("nod"); m.nod(); time.sleep(0.4)
        print("shake"); m.shake(); time.sleep(0.4)
        print("gaze sweep"); m.look_around(); time.sleep(0.3)
        print("surprised"); m.surprised(); time.sleep(0.6)
        print("recenter"); m.center(); time.sleep(0.5)


if __name__ == '__main__':
    _selftest()
