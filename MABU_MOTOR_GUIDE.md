# Mabu Motor Guide

> **CRITICAL REFERENCE — load this document at the start of every Mabu session.**
> Covers the motor protocol, wire encoding, per-motor limits and neutrals, movement directions,
> the TCP bridge, and known gotchas. Mistakes here cause silent failures or grinding.

---

## 1. Physical Motors

| Motor | Bitmask | Name               | Controls                    |
|-------|---------|--------------------|-----------------------------|
| LDL   | `0x40`  | Eyelid Left        | Left eyelid open/close      |
| LDR   | `0x20`  | Eyelid Right       | Right eyelid open/close     |
| ELR   | `0x10`  | Eyes Left/Right    | Both eyes pan horizontally  |
| EUD   | `0x08`  | Eyes Up/Down       | Both eyes tilt vertically   |
| NE    | `0x04`  | Neck Elevation     | Head pitch (up/down)        |
| NR    | `0x02`  | Neck Rotation      | Head yaw (left/right)       |
| NT    | `0x01`  | Neck Tilt          | Head roll (side lean)       |

All-motors mask: `0x7F`

---

## 2. Value Encoding

Logical values are **0–100** (50 = nominal center for most motors).

```
wire_byte = clamp(round(logical * 2.55), 0, 255)
```

### Critical: floating-point trap
`50 * 2.55` in IEEE 754 double = **127.4999…**, not 127.5.
Standard "round half up" gives **127**, not 128.
Always verify: `wire(50) = 128 (0x80)`, `wire(25) = 64 (0x40)`.

**PowerShell gotcha:** `[math]::Round(50 * 2.55)` returns `127` due to FP representation.
Safe formula: `[byte][math]::Floor($v * 255.0 / 100.0 + 0.5)`

---

## 3. Serial Frame Format

```
FA 00 <payload_len> <payload_bytes…> <fletcher_s2> <fletcher_s1>
```

- Header is always `FA 00`
- `payload_len` = number of payload bytes (1 byte)
- Checksum is **Fletcher-8 mod 255** (not 256) over the **entire frame including the `FA 00` header**

### Fletcher-8 algorithm
```
s1 = 0, s2 = 0
for each byte b in frame (including FA 00 header):
    s1 = (s1 + b) % 255
    s2 = (s2 + s1) % 255
append s2, then s1
```

### Multi-motor payload (preferred — one atomic frame)
```
[0x01, bitmask, 0x01, val_motor_MSB, val_motor_next, …]
```
Values are listed in **MSB-first bitmask order**: LDL → LDR → ELR → EUD → NE → NR → NT.
Only include values for bits set in the bitmask.

**Wrong bitmask = silent discard by motor board.** No error, no movement.

### Single-motor payload
```
[0x01, single_bitmask, 0x01, wire_value]
```

### Power-on frame (hardcoded)
```
FA 00 02 4F 7F 0B CB
```

#### Cold-boot wake-up sequence (CRITICAL — confirmed 2026-05-29)
After a fresh Mabu boot, **sending power-on ONCE is not enough.** The motor board
will silently ignore subsequent commands even though bytes are reaching `/dev/ttyS1`
and the motors are clearly powered (head stiff, holding position).

**Working wake-up sequence — must do this once per cold boot:**
```
1. Send power-on (FA 00 02 4F 7F 0B CB)
2. Wait 200 ms
3. Repeat steps 1-2 a total of 5 times
4. Wait 1000 ms
5. Send the first movement command
```
All of the above MUST happen inside a single TCP connection to the bridge (or a
single open of `/dev/ttyS1`). Splitting it across multiple connections has been
observed to fail.

Once the board has been woken this way, subsequent connections only need a single
power-on (or none at all) — the board stays alive until the next cold boot.

**Why this is needed:** Empirically determined. Likely the motor-board MCU has a
post-boot init period during which it drops UART bytes, so the first few power-on
frames are lost. Multiple repetitions ensure at least one lands after the MCU is
ready to receive.

### Wait ~500 ms after power-on before sending movement commands
(only applies once the board is already awake — not for the cold-boot sequence above)

### All-motors center frame (pre-computed)
```
FA 00 0A 01 7F 01 40 40 80 80 40 <NR_wire> <NT_wire> <s2> <s1>
```
Recompute checksum if NR_NEUTRAL or NT_NEUTRAL changes.

---

## 4. Neutral Positions (This Unit — Visually confirmed 2026-05-29)

| Motor | Neutral | Notes |
|-------|---------|-------|
| LDL   | 20      | **Updated 2026-05-29 — approved by operator.** wire=0x33=51. Mostly open. For max open drive to 0. Previous value of 25 was incorrect. |
| LDR   | 20      | **Updated 2026-05-29 — approved by operator.** wire=0x33=51. Matches LDL. For max open drive to 0. Previous value of 25 was incorrect. |
| ELR   | 50      | **Confirmed 2026-05-29 — approved by operator.** wire=0x80=128. |
| EUD   | 50      | Confirmed |
| NE    | 25      | Confirmed — head pitch level |
| NR    | 50      | **Updated 2026-05-29 — approved by operator.** wire=0x80=128. Head straight at 50. Previous value of 42 was incorrect. |
| NT    | 45      | Visually confirmed. wire=0x73=115. Direction: lower=right, higher=left (confirmed 2026-05-29). |

Test: from a fresh-boot "head-back + neck-turned-left + eyelids-half + eyes-up"
rest pose, sending all 7 motors at the above neutrals returns the head to
straight-and-centered. Confirmed visually by user 2026-05-29.

---

## 5. Motor Ranges (This Unit)

| Motor | Soft Min | Soft Max | Notes |
|-------|----------|----------|-------|
| LDL   | 0        | 100      | **Full 0–100 confirmed 2026-05-29.** No grinding at either extreme. 0 = mechanical max-open hard stop (lids visibly stop opening here — cannot push further). 100 = fully closed. |
| LDR   | 0        | 100      | **Full 0–100 confirmed 2026-05-29.** Behaves in sync with LDL. Same mechanical max-open stop at 0. |
| ELR   | 0        | 100      | Full 0–100 confirmed 2026-05-29 — approved by operator. No grinding at either extreme. |
| EUD   | 0        | 100      | Full 0–100 confirmed 2026-05-29 — approved by operator. No grinding at either extreme. 0 = max up, 100 = max down (inverted). **Allow ~2s settle time after commanding 0 before sending next command — motor needs time to stop at hard stop or it will oscillate.** |
| NE    | 0        | 100      | Full 0–100 confirmed 2026-05-29 — approved by operator. No grinding at either extreme. Community docs say 50 max — WRONG for this unit. Previous lower limit of 18 was also wrong. |
| NR    | 0        | 100      | Full 0–100 confirmed 2026-05-29 — approved by operator. No grinding at either extreme. |
| NT    | 0        | 100      | Full 0–100 confirmed 2026-05-29 — approved by operator. 0 = fully right, 100 = fully left. No grinding at either extreme. |

> **Community docs warning:** Many online references state NE hard-stops at logical 50, and earlier testing on this unit suggested a lower limit of 18. Both are wrong — full range [0, 100] confirmed 2026-05-29, approved by operator.

---

## 6. Movement Directions (This Unit)

| Motor | Higher value → | Lower value → |
|-------|---------------|---------------|
| LDL   | Eyelid CLOSES   | Eyelid OPENS (0 = max open hard stop) |
| LDR   | Eyelid CLOSES   | Eyelid OPENS (0 = max open hard stop) |
| ELR   | Eyes look RIGHT (100 = max right hard stop) | Eyes look LEFT (0 = max left hard stop) | Both extremes confirmed 2026-05-29, approved by operator |
| EUD   | Eyes look DOWN (100 = max down hard stop) | Eyes look UP ← **INVERTED** (0 = max up hard stop) | Both extremes confirmed 2026-05-29, approved by operator |
| NE    | Head tilts UP   | Head tilts DOWN |
| NR    | Head turns LEFT (100 = max left hard stop) | Head turns RIGHT (0 = max right hard stop) | Both extremes confirmed 2026-05-29, approved by operator |
| NT    | Head tilts RIGHT (0 = max right hard stop) | Head tilts LEFT (100 = max left hard stop) | Both extremes confirmed 2026-05-29, approved by operator |

**Eyelid hold-test result (2026-05-29):** 4s holds at logical 0, 25, 50, 80, 100.
0 visibly most open; eyelids progressively close as the value increases; 100
fully closed. The max-open position at 0 looks slightly less wide than a human's
fully-open eye — this is the mechanical hard stop, not a software limit.

**EUD is inverted on this unit.** Lower logical value = eyes look upward.
All other units in community docs may differ — always test per unit.

---

## 7. TCP Motor Bridge

The app cannot open `/dev/ttyS1` directly (SELinux blocks `untrusted_app → serial_device`).
The bridge runs as shell context which IS allowed, and proxies TCP bytes to the serial port.

**Bridge file:** `/data/local/tmp/motor-bridge.sh`
**Bridge port:** TCP 7777 on `0.0.0.0` (LAN-visible — no firewall currently)

### Starting the bridge (once per reboot)
```bash
adb shell "nohup sh /data/local/tmp/motor-bridge.sh > /data/local/tmp/motor-bridge.log 2>&1 &"
# Wait 2–3 s, then verify:
adb shell "busybox netstat -tlnp | grep 7777"
```

### Full startup sequence (every reboot)
```
1. adb connect 192.168.0.180:5555
2. adb shell "nohup sh /data/local/tmp/motor-bridge.sh > /data/local/tmp/motor-bridge.log 2>&1 &"
3. Wait 3 s — verify port 7777 is LISTEN
4. adb shell "am force-stop com.mabu.facetrack"
5. Wait 2 s
6. adb shell "am start -n com.mabu.facetrack/.MainActivity"
```
The app auto-starts at boot before the bridge is ready, so force-stop + restart is mandatory.

### Stop the app before manual testing
**CRITICAL for manual testing sessions:** The MabuFaceTrack app auto-starts at boot and sends motor commands continuously. If it is running while you send test commands, motors will fight between two senders and oscillate. Always force-stop it before any manual test:
```bash
adb shell "am force-stop com.mabu.facetrack"
```
Note: force-stopping the app also kills the bridge (same process group). Restart the bridge after.

### Critical bridge rules
- **NEVER start the bridge twice.** A second instance opens `/dev/ttyS1` again, resetting
  termios and DTR, killing motor response until the bridge is killed and restarted.
- **Persistent fd required.** The bridge opens fd3 once and never closes it. Opening/closing
  the serial port per-connection resets termios. This is why tcpsvd and per-child approaches fail.
- **`-hupcl` is mandatory.** Without it, closing the last fd drops DTR, resetting the motor board.
  All subsequent commands are silently ignored.

### Sending commands from PowerShell (via TCP)
Build the frame as a single `byte[]` — **do not use `+` to concatenate byte arrays in PowerShell 5.1**,
it returns `Object[]` which breaks `Stream.Write(byte[], int, int)`.

```powershell
function Send-MotorFrame([byte[]]$frame) {
    $tcp = New-Object System.Net.Sockets.TcpClient("192.168.0.180", 7777)
    $stream = $tcp.GetStream()
    $stream.Write($frame, 0, $frame.Length)
    $stream.Flush()
    Start-Sleep -Milliseconds 300
    $stream.Close(); $tcp.Close()
}
```

Send **power-on and movement commands in the same TCP connection** (or with power-on first,
close, then reconnect with movement). A gap between separate connections may cause the motor
board to lose state and ignore commands.

### Sending commands from adb shell (reliable alternative)
```bash
adb shell "busybox printf '\xFA\x00\x0A...' | nc 127.0.0.1 7777"
```
`busybox printf` supports `\xNN` hex escapes. `nc` closes when stdin (printf) exits.
Use `127.0.0.1` (loopback) not `192.168.0.180` to avoid external routing.

---

## 8. CSV Animation Format

7 CSV files on device at `/sdcard/*.csv`.
Columns: `Time(ms), MCB1, MCB2, DATA1, DATA2`

Wire value from CSV: `wire = clamp(int(round(csv_value + 128)), 0, 255)`

---

## 9. SELinux Notes

- `/dev/ttyS1` Unix permissions: `crwxrwxrwx` (wide open)
- SELinux label: `u:object_r:serial_device:s0`
- App context: `u:r:untrusted_app:s0` — **blocked by SELinux even though Unix perms allow it**
- Shell context: `u:r:shell:s0` — **allowed** (bridge runs here)
- `getenforce` may report "Enforcing" regardless of `ro.boot.selinux` property

---

## 10. Known Issues / Gotchas Checklist

- [ ] **Cold boot: send power-on 5x with 200ms gaps + 1s wait** — single power-on does not wake the board (see Section 3 cold-boot wake-up)
- [ ] All wake-up frames must be in ONE TCP connection (or one open of `/dev/ttyS1`) — splitting across connections has failed
- [ ] Verify bridge is running before starting app (`netstat | grep 7777`)
- [ ] Never open the bridge twice
- [ ] NE range is [18, 100] — do NOT limit to 50 based on community docs
- [ ] EUD is inverted — lower value = eyes look UP
- [ ] NR_NEUTRAL ≠ 50 on this unit (causes left twist) — see Section 4
- [ ] NT_NEUTRAL ≠ 50 on this unit (causes right tilt) — see Section 4
- [ ] EUD soft min = 5 may cause slight grinding — raise to 8 if needed
- [ ] PowerShell `[math]::Round(50 * 2.55)` = 127 not 128 — use floor+0.5 formula
- [ ] PowerShell byte[] + byte[] = Object[] — build frames with indexed assignment only

---

## 11. "Motors not responding" — diagnostic order

When motors don't move, work through these in order before suspecting protocol or wiring:

1. **Limp vs stiff test.** Gently push the head with a finger.
   - **Limp** → motor board is unpowered. Wiring/power issue, NOT a software problem.
   - **Stiff** → board is powered and holding position. Continue below.
2. **Bridge alive?** `adb shell "busybox netstat -tlnp | grep 7777"` — must show LISTEN.
3. **Is this a cold boot?** If yes → run the 5x power-on wake-up sequence (Section 3).
4. **Bridge or board?** Kill the bridge and write to `/dev/ttyS1` directly from adb shell.
   If direct write works but the bridge doesn't → bridge bug. If neither works → board issue.
5. **Read from `/dev/ttyS1`.** Some boards send heartbeat bytes. Silence here doesn't prove
   the board is dead (the Mabu motor board appears silent in normal operation), but bytes
   appearing would prove it's alive.

### NEVER reboot Mabu via ADB
`adb reboot` has caused WiFi to not reconnect after boot, leaving the device unreachable with no recovery path (no USB, no physical buttons). **Do not run `adb reboot` under any circumstances.** If a reboot is truly needed, power-cycle the physical hardware instead.

### Rabbit holes to avoid (already investigated, don't re-chase)

- **`/sys/class/gpio_control` / `inhuasoft_gpio_control` driver.** This is a Catalia-custom
  GPIO control interface exposing 3 GPIO pins (controllers 0xA8/0xA9, pins 16/19/9).
  The control file is `/sys/devices/virtual/gpio_control/gpio/gpio_control`, mode
  `-rw-rw-r-- root:root`. Shell user **cannot write to it without root**, and we have
  no root path on this unit. Even if it does enable motor power, it's not reachable
  from our environment. Don't go down this rabbit hole — the motor board has its own
  power that survives reboots, the issue is always wake-up/state, not power-enable.
