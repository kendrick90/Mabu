# Mabu motor protocol

Cross-verified from two independent sources:
- **[ElectroNick's `mabu` PyPI package](https://github.com/electronick-co/mabu)** — reverse-engineered by sniffing the UART line with a USB-serial adapter wired directly to the motor controller daughterboard (he bypasses the tablet entirely).
- **factorymode.apk decompiled source** — Catalia's own factory-test app, contains `com.catalia.motortest.MotorController` and `com.catalia.mabu.motors.AnimationSerialConverter`.

The two agree byte-for-byte. This document is the authoritative reference for our own driver.

## Physical link

| | Value | Source |
|---|---|---|
| Tablet → motor board interface | UART (3.3 V) over the 30-pin header pins **TX (pin 13), RX (pin 15)** | README + factorymode |
| Device node (on tablet) | **`/dev/ttyS1`** | `MotorController.java:296` |
| Baud rate | **57600** 8N1 | `MotorController.java:42`; ElectroNick confirms |

The motor board is a 2nd MCU (Catalia custom) speaking this protocol — Vocon SDK / OpenCV libs in factorymode are unrelated to it.

## Frame format

```
FA 00 <len> <payload bytes...> <fletcher8_hi> <fletcher8_lo>
```

- `FA 00` — frame start (constant)
- `<len>` — 1 byte, payload length only (not counting the FA 00 or checksum)
- `<payload>` — `<len>` bytes
- Trailing **Fletcher-8 checksum** over `FA 00 <len> <payload>` (i.e. all bytes from the start through end of payload)

### Fletcher-8 implementation
```python
def fletcher8(data):
    s1 = s2 = 0
    for b in data:
        s1 = (s1 + b) % 255
        s2 = (s2 + s1) % 255
    return (s2 << 8) | s1   # high byte first when sent
```
(From ElectroNick's `mabu.py`. The hi byte goes on the wire first per the power-on command bytes.)

## Known commands

### Power on
```
FA 00 02 4F 7F   0B CB
```
Sends opcode `4F 7F` (no operands). The motor board enables its motor driver IC.

### Power off
```
FA 00 02 4F 8B   4C
```
*Note: only one checksum byte shown in ElectroNick's source for power-off (probably a bug in his code — should likely also be two-byte fletcher). Verify when you implement.*

### Move motor(s)
```
FA 00 04 01 <bitmask> 01 <value> <ck_hi> <ck_lo>
```

- Opcode: `01`
- `<bitmask>` — OR of motor bits below (multi-motor in one command IS supported per factorymode's `AnimationSerialConverter.motorBitField`)
- `01` — likely a "count" or "format" byte (always 1 in observed traffic)
- `<value>` — single byte 0–255 (see value scale below)

### Motor bitmask
Order matters: the bitmask MSB is the first motor listed. When a multi-motor command is sent, motor values follow in this same order after the bitmask byte.

| Motor | Bit | factorymode name | ElectroNick name | Function |
|---|---|---|---|---|
| LDL | 0x40 | EYELID_LEFT | LDL | Left eyelid |
| LDR | 0x20 | EYELID_RIGHT | LDR | Right eyelid |
| ELR | 0x10 | EYES_LEFT_RIGHT | ELR | Eyes pan (left-right) |
| EUD | 0x08 | EYES_UP_DOWN | EUD | Eyes tilt (up-down) |
| NE | 0x04 | NECK_ELEVATION | NE | Neck up-down |
| NR | 0x02 | NECK_ROTATION | NR | Neck rotate (left-right) |
| NT | 0x01 | NECK_TILT | NT | Neck side-tilt (lean) |

## Value scale

Two conventions in use:

1. **ElectroNick (PyPI)**: logical value 0–100, where 50 = center. Mapped to 0–255 on the wire via `int(value * 2.55)`.
2. **factorymode CSVs**: floating-point displacement from calibrated center, can be negative. Range observed roughly ±3.5 in the animation files. `MotorData.convertSetpointToByte(setpoint)` does the unit-conversion (signed FP → 0–255 byte) using per-motor calibration data.

The calibration is **per-unit**: factorymode walks the user through finding each motor's center and storing those zero offsets in `/data/data/com.catalia.factorymode/`. Without calibration, sending raw 0–255 values without bias correction means the motors will move but won't land where the animation intended.

**For our own driver**, simplest start: stay in ElectroNick's 0–100 convention with 50=center, ignore the per-unit calibration entirely. Each unit will have a slightly different absolute pose, but the gross motion will work.

## Animation CSV format

```
Time(ms), MCB1, MCB2, DATA1, DATA2
0.0,-0.29411764705882426,-0.0588235294117645,-0.647058823529412,2.80392156862745
10.0,-0.5294117647058822,-0.0588235294117645,-0.647058823529412,2.84313725490196
...
```

- Sampled at ~10 ms intervals (some rows are 9 ms apart)
- Floats are signed displacements (see "Value scale" above)
- Only **4 of 7 motors are present per CSV**. The columns "MCB1/MCB2/DATA1/DATA2" refer to motor controller buses, not specific motor names — different animations use different motor groups, and which motor each column refers to is per-animation. The mapping must come from the CSV header or filename context. **TODO: confirm by reading more of factorymode's CSV loader.**
- Time encoding on the wire: `(t - prev_t) / 10` → "tens of ms", max 255 per command (2.55s gap)

## Permissions on /dev/ttyS1

Unknown for shell context. Our liberation patches don't change `/dev` node permissions, so this is whatever the stock Rockchip kernel sets. Likely owned by `root:radio` or `system:system`. A regular Android app would need either:
- Open permissions on the node (some Rockchip BSPs ship it 666)
- A native helper running as `system` user
- Run-as `system_app` context (would need /system co-location, signing with platform key)

**Action item**: when next on a tablet, run `ls -laZ /dev/ttyS*` to see exact owner/group/mode/SELinux label. That dictates whether our app needs special handling.

## References

- `firmware/scratch/factorymode-jadx/sources/com/catalia/motortest/MotorController.java`
- `firmware/scratch/factorymode-jadx/sources/com/catalia/motortest/UARTCommand.java`
- `firmware/scratch/factorymode-jadx/sources/com/catalia/mabu/motors/AnimationSerialConverter.java`
- `firmware/scratch/factorymode-jadx/sources/com/catalia/mabu/motors/MotorNames.java`
- ElectroNick's [`mabu.py`](https://github.com/electronick-co/mabu/blob/main/src/mabu/mabu.py)
- ElectroNick's wiki: https://electronick-co.github.io/hacking_mabu2/
