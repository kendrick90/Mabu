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

### Power-on frame (hardcoded — send once after cold start)
```
FA 00 02 4F 7F 0B CB
```
Must be sent before any movement commands after a motor board cold start.
Wait ~500 ms after power-on before sending movement commands.

### All-motors center frame (pre-computed)
```
FA 00 0A 01 7F 01 40 40 80 80 40 <NR_wire> <NT_wire> <s2> <s1>
```
Recompute checksum if NR_NEUTRAL or NT_NEUTRAL changes.

---

## 4. Neutral Positions (This Unit — Unit confirmed 2026-05-29)

| Motor | Neutral | Notes |
|-------|---------|-------|
| LDL   | 25      | ~Mostly open |
| LDR   | 25      | ~Mostly open |
| ELR   | 50      | Confirmed |
| EUD   | 50      | Confirmed |
| NE    | 25      | Mechanical rest position at boot |
| NR    | 42      | Community observed wire=0x6B=107≈42. **50 causes left twist on this unit.** |
| NT    | 45      | Community observed wire=0x72=114≈45. **50 causes right tilt on this unit.** |

---

## 5. Motor Ranges (This Unit)

| Motor | Soft Min | Soft Max | Notes |
|-------|----------|----------|-------|
| LDL   | 15       | 85       | Untested beyond soft limits |
| LDR   | 15       | 85       | Untested beyond soft limits |
| ELR   | 15       | 85       | Confirmed safe |
| EUD   | 5        | 85       | **Bottoms out slightly at 5** — raise if grinding |
| NE    | 18       | 100      | **Community docs say 50 max — WRONG for this unit.** Confirmed [18, 100]. |
| NR    | 20       | 80       | Confirmed safe |
| NT    | ?        | ?        | Direction and limits not yet tested |

> **Community docs warning:** Many online references state NE hard-stops at logical 50.
> This is wrong for this unit. Full range [18, 100] confirmed working.

---

## 6. Movement Directions (This Unit)

| Motor | Higher value → | Lower value → |
|-------|---------------|---------------|
| ELR   | Eyes look RIGHT | Eyes look LEFT |
| EUD   | Eyes look DOWN  | Eyes look UP ← **INVERTED** |
| NE    | Head tilts UP   | Head tilts DOWN |
| NR    | Head turns LEFT | Head turns RIGHT |
| NT    | Direction not confirmed | Direction not confirmed |

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

- [ ] Always send power-on (`FA 00 02 4F 7F 0B CB`) after motor board cold start
- [ ] Verify bridge is running before starting app (`netstat | grep 7777`)
- [ ] Never open the bridge twice
- [ ] NE range is [18, 100] — do NOT limit to 50 based on community docs
- [ ] EUD is inverted — lower value = eyes look UP
- [ ] NR_NEUTRAL ≠ 50 on this unit (causes left twist) — see Section 4
- [ ] NT_NEUTRAL ≠ 50 on this unit (causes right tilt) — see Section 4
- [ ] EUD soft min = 5 may cause slight grinding — raise to 8 if needed
- [ ] PowerShell `[math]::Round(50 * 2.55)` = 127 not 128 — use floor+0.5 formula
- [ ] PowerShell byte[] + byte[] = Object[] — build frames with indexed assignment only
