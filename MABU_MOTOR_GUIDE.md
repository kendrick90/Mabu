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

---

## 4. Neutral Positions (This Unit — Visually confirmed 2026-05-29)

| Motor | Neutral | Notes |
|-------|---------|-------|
| LDL   | 20      | **Updated 2026-05-29 — approved by operator.** wire=0x33=51. Mostly open. For max open drive to 0. Previous value of 25 was incorrect. |
| LDR   | 20      | **Updated 2026-05-29 — approved by operator.** wire=0x33=51. Matches LDL. For max open drive to 0. Previous value of 25 was incorrect. |
| ELR   | 50      | **Confirmed 2026-05-29 — approved by operator.** wire=0x80=128. |
| EUD   | 50      | Confirmed |
| NE    | 50      | **Updated 2026-05-29 — approved by operator.** wire=0x80=128. Head level at 50. Previous value of 25 was incorrect. |
| NR    | 50      | **Updated 2026-05-29 — approved by operator.** wire=0x80=128. Head straight at 50. Previous value of 42 was incorrect. |
| NT    | 50      | **Updated 2026-05-29 — approved by operator.** wire=0x80=128. Head level at 50. Previous value of 45 was incorrect. Direction: lower=right tilt, higher=left tilt. |

Test: from a fresh-boot "head-back + neck-turned-left + eyelids-half + eyes-up"
rest pose, sending all 7 motors at the above neutrals returns the head to
straight-and-centered. Confirmed visually by user 2026-05-29.

---

## 5. Motor Ranges (This Unit)

| Motor | Soft Min | Soft Max | Notes |
|-------|----------|----------|-------|
| LDL   | 0        | 100      | Full 0–100 confirmed 2026-05-29 — approved by operator. 0 = max open hard stop, 100 = fully closed. No grinding at either extreme. |
| LDR   | 0        | 100      | Full 0–100 confirmed 2026-05-29 — approved by operator. Matches LDL. 0 = max open hard stop, 100 = fully closed. No grinding at either extreme. |
| ELR   | 0        | 100      | Full 0–100 confirmed 2026-05-29 — approved by operator. No grinding at either extreme. |
| EUD   | 0        | 100      | Full 0–100 confirmed 2026-05-29 — approved by operator. No grinding at either extreme. 0 = max up, 100 = max down (inverted). **KNOWN BUG: commanding EUD=0 then moving away in under ~2000ms causes eye oscillation. Always allow 2000ms+ settle at EUD=0. See Section 12.** |
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
| NE    | Head tilts UP (100 = max up) | Head tilts DOWN (0 = max down) | Both extremes + direction confirmed 2026-05-29, approved by operator |
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

### Stop the app before manual testing

**CRITICAL:** The app sends motor commands continuously while tracking. Neutralize it before any
manual test or it will contend for the bridge slot and cause short-write storms.

**Preferred method — PAUSE tracking (app stays alive but silent):**
```bash
adb shell "am broadcast -a com.mabu.facetrack.PAUSE_TRACKING --ez paused true -p com.mabu.facetrack"
```

**If a full restart is needed:** force-stop kills the bridge too — always restart the bridge AFTER
the app, never before. See "Clean motor-control establishment / re-establishment" below.

### Clean motor-control establishment / re-establishment (verified 2026-05-31)

The proven, repeatable sequence for taking reliable manual control. Verified twice back-to-back
(head full-left, then full-right) with 0 short writes each time.

**Key facts that drive the ordering:**
- Force-stopping the app **also takes the bridge down**, so always (re)start the bridge AFTER you
  have dealt with the app, never before.
- The app is the HOME launcher: force-stop relaunches it with tracking ACTIVE, and on startup its
  `MabuMotors.open()` connects to the bridge once (you'll see a brief `127.0.0.1:7777` connection,
  then `FIN_WAIT2`). Pause its tracking so it stops sending and can't contend for the slot.
- One sender at a time. Single-frame commands relay cleanly. The old `nc: short write` storms were
  a symptom of a hung motor board (clears on power-cycle) plus app contention - not a relay bug.

**Procedure (after an app restart, or any time you need to (re)establish control):**
```bash
A="/path/to/adb"   # e.g. "X:/Claude/android platform-tools/adb.exe"

# 1. (Optional) completely restart the app. This ALSO kills the bridge.
$A shell "am force-stop com.mabu.facetrack"; sleep 2
$A shell "am start -n com.mabu.facetrack/.MainActivity"

# 2. Pause the app so it can't contend for the bridge slot.
$A shell "am broadcast -a com.mabu.facetrack.PAUSE_TRACKING --ez paused true -p com.mabu.facetrack"

# 3. Kill any stray bridge/nc, then start exactly ONE bridge.
$A shell 'for p in /proc/[0-9]*; do c=$(cat $p/cmdline 2>/dev/null | tr "\0" " ");
  case "$c" in *cmdline*) continue;; esac;
  case "$c" in *motor-bridge.sh*|*"nc -l -p 7777"*) kill ${p#/proc/} 2>/dev/null;; esac; done'
$A shell "nohup sh /data/local/tmp/motor-bridge.sh >/dev/null 2>&1 &"
sleep 3

# 4. Verify the slot is clean BEFORE sending.
$A shell "busybox netstat -tlnp | busybox grep 7777"                          # must show LISTEN
$A shell "busybox netstat -tn | busybox grep 7777 | busybox grep ESTABLISHED" # must show nothing

# 5. Send your command as a SINGLE frame over one connection (reliable loopback form):
$A shell "busybox printf '<frame-hex>' | busybox timeout -t 3 busybox nc 127.0.0.1 7777"
```

**Confirm the move WITHOUT a camera (telemetry):** capture the board's position reports around the
send and read the target motor's byte.
```bash
# start capture, send at ~1s, wait for capture to finish
$A shell 'busybox timeout -t 6 cat /dev/ttyS1 > /data/local/tmp/mv.bin 2>/dev/null &
  sleep 1; busybox printf "<frame-hex>" | busybox timeout -t 3 busybox nc 127.0.0.1 7777; wait'
$A shell "busybox hexdump -C /data/local/tmp/mv.bin | busybox head -4; busybox hexdump -C /data/local/tmp/mv.bin | busybox tail -4"
```
In each `FA 00 09 01 00 ..` position frame the 7 motor bytes are `LDL LDR ELR EUD NE NR NT` at
frame offsets 5..11. A clean move shows the target byte ramp to the commanded wire value, and the
bridge log gains 0 new `short write` lines. (To pull the capture to the PC, prefix the adb command
with `MSYS_NO_PATHCONV=1`.)

**Single-motor frame cheat sheet:** `FA 00 04 01 <mask> 01 <wire> <s2> <s1>`. Examples verified on
hardware 2026-05-31:
| Command | Effect | Frame |
|---|---|---|
| NR=100 | head full LEFT  | `FA 00 04 01 02 01 FF FC 03` |
| NR=0   | head full RIGHT | `FA 00 04 01 02 01 00 FC 03` |
| ELR=80 | eyes right      | `FA 00 04 01 10 01 CC F3 DD` |

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

### Connection failures and the `nc: short write` storm (investigated 2026-05-31)

**Symptom:** a PC TCP client to port 7777 fails mid-burst with "An established connection
was aborted by the software in your host machine." The bridge log shows repeated
`nc: short write` immediately followed by `Client disconnected, listening again`.

**Mechanism:** busybox nc (v1.22.1 on this unit) relays socket bytes to `/dev/ttyS1` (fd3).
When that serial `write()` returns fewer bytes than asked (a short write), this build treats it
as FATAL and EXITS, tearing down the TCP connection. The PC side then sees the abort. The app
makes it worse: on any write failure it immediately reconnects (MabuMotors.kt re-`tryConnect`s in
its `writeFrame` catch), so the cycle becomes a self-reinforcing storm.

**Confirmed NOT the cause (ruled out 2026-05-31, tested one variable at a time):**
- *Bridge stdout polluting the motor wire (refuted).* `/proc/<bridge-pid>/fd/1` shows
  `-> /dev/ttyS1`, which looks alarming, but it is a TRANSIENT mksh artifact: the loop's
  `nc -l -p 7777 >&3` borrows the parent shell's fd1 onto fd3 for the duration of the (blocking)
  nc, then restores it. Proof: every `log()` line ("Bridge starting", "Client disconnected")
  lands in the LOGFILE, not on the wire. The bridge never sprays ASCII onto `/dev/ttyS1`.
  Hardening the script with `exec 1>>"$LOG" 2>&1` did not change this (fd1 still reads as ttyS1
  in a /proc snapshot because of the transient borrow).
- *busybox nc being inherently unfit for a single relay (refuted).* A clean single relayed write
  - one client, one 7-byte power-on via loopback, no app connected, no concurrent reader -
  produced ZERO short writes. The relay works fine in isolation.

**Actual trigger: contention on the single-client bridge slot.** The bridge serves ONE client at
a time. The app is the HOME launcher (auto-restarts on force-stop) and reconnects on every write
failure, so when it is tracking-active it hammers port 7777; combined with a PC client or any
churn, nc short-writes and the storm sustains.

**Rule:** before ANY manual testing, neutralize the app so it cannot contend for the slot - pause
tracking via the `PAUSE_TRACKING` broadcast (or force-stop, though it relaunches as launcher).
Verify `busybox netstat -tn | grep 7777` shows NO established client before sending. One sender
at a time.

### Hardened bridge (deployed 2026-05-31)
The on-device `/data/local/tmp/motor-bridge.sh` now adds, vs the original: `exec 1>>"$LOG" 2>&1`
at the top (force log output to the file) and `clocal` in the stty (so a fresh fd3 open does not
block on carrier). These are hygiene improvements; they do NOT fix the short-write storm - the fix
for that is removing app/sender contention (above). Local copy: `X:\Claude\Mabu\motor-bridge-hardened.sh`.

### Environment gotchas found this session
- **`adb push` path mangling.** `adb push <local> /data/local/tmp/...` from the Bash tool (Git
  Bash / MSYS) rewrites the device path into `C:/Program Files/Git/data/local/tmp/...` and fails
  with `remote secure_mkdirs failed: No such file or directory`. Prefix the command with
  `MSYS_NO_PATHCONV=1` to disable the conversion. (PowerShell does not have this problem.)
- **WiFi ADB drops mid-command** ("error: closed" or "device offline"). Recover with
  `adb disconnect 192.168.0.180:5555 && adb connect 192.168.0.180:5555`; may take 2-3 tries.

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

- [ ] **ASCII only in PowerShell scripts (.ps1).** NEVER use em-dashes (or any non-ASCII char) in a `.ps1` file. PowerShell 5.1 reads a UTF-8-without-BOM file as Windows-1252, so an em-dash's third byte `0x94` becomes a curly double-quote that silently terminates/reopens strings and desyncs the whole parse (cascading "Unexpected token" errors far from the real line). Use `-` or `--` instead. If a script must contain non-ASCII, save it with a UTF-8 BOM. This has bitten us multiple times.

- [ ] **Command-latch: distinguish two states (Section 11).** STIFF + MUTE (zero telemetry) = hung MCU → power-cycle likely helps. STIFF + heartbeat-only + commands ignored = State B → **use direct exec+stty recovery (Section 11), NOT the bridge**. Power-cycle is not needed for State B. Read telemetry first.
- [ ] **Cold boot: send power-on 5x with 200ms gaps + 1s wait** — single power-on does not wake the board (see Section 3 cold-boot wake-up)
- [ ] All wake-up frames must be in ONE TCP connection (or one open of `/dev/ttyS1`) — splitting across connections has failed
- [ ] Start bridge AFTER the app is running and paused (force-stop kills the bridge). Verify LISTEN + no ESTABLISHED client before sending.
- [ ] Never open the bridge twice
- [ ] NE range is [0, 100] — do NOT limit to 50 (community docs wrong) or 18 (also wrong). Confirmed 2026-05-29.
- [ ] EUD is inverted — lower value = eyes look UP
- [ ] EUD soft min = 5 may cause slight grinding — raise to 8 if needed
- [ ] PowerShell `[math]::Round(50 * 2.55)` = 127 not 128 — use floor+0.5 formula
- [ ] PowerShell byte[] + byte[] = Object[] — build frames with indexed assignment only

---

## 11. "Motors not responding" — diagnostic order

### ⚠️ FIRST: read telemetry to classify the stuck state.

There are **two distinct stuck states** with different recovery paths. Read `/dev/ttyS1` before doing anything else:

```bash
adb shell "busybox timeout -t 5 cat /dev/ttyS1 | busybox hexdump -C"
```

**State A — STIFF + MUTE (zero telemetry, not even a heartbeat):** the MCU is hung.
A physical power-cycle recovered this state in session 8 (2026-05-31). After cycling: reconnect ADB, run the clean-control procedure (Section 7), do the 5× cold-boot wake, then a validation move with Alex watching.

**State B — STIFF + heartbeat-only (`FA 00 01 00 ED FB`) + commands ignored:** recovery confirmed 2026-05-31.

**Recovery — direct exec+stty (NOT the bridge):**
```bash
adb shell "exec 3<>/dev/ttyS1; busybox stty -F /dev/ttyS1 57600 raw -hupcl; \
  busybox printf '\xFA\x00\x02\x4F\x7F\x0B\xCB' >&3; sleep 1; \
  busybox printf '<motor-frame>' >&3; sleep 0.5"
```
Replace `<motor-frame>` with the target move frame (e.g. `\xFA\x00\x04\x01\x02\x01\xFF\xFC\x03` for NR=100). Confirmed: board produced `FA 00 09` position frames and head moved after this, with the bridge approach having just failed in the same session.

**What does NOT clear State B:** bridge/nc relay path (any form), 5× wake via bridge, single `> /dev/ttyS1` redirect, force-stopping app, device-loopback via bridge.

**Mechanism hypothesis (unconfirmed):** opening a second fd to `/dev/ttyS1` via `exec 3<>` while the bridge already holds fd3 may assert a UART signal (DTR or break) that resets the board's command-accept state. The persistent-fd form is required — the simple `>` redirect (which opens and immediately closes) does not work.

**Boot-time contention (investigation incomplete):** `com.catalia.factorymode` (installed, has `RECEIVE_BOOT_COMPLETED` permission and references `/dev/ttyS1` in its DEX) is a candidate cause of State B on boot. If this or any process writes to the serial port during the board's post-boot init window, it may trigger State B.

#### Post-power-cycle recovery (State A)
1. `adb disconnect 192.168.0.180:5555 && adb connect 192.168.0.180:5555` (may take a couple of tries).
2. Follow the clean-control procedure in Section 7.
3. Run the 5× cold-boot wake (Section 3), then a validation move **with Alex watching**.
4. Confirm engagement via telemetry: a successful move streams `FA 00 09 …` position frames (Section 13).

### Full diagnostic order

When motors don't move, work through these in order:

1. **Limp vs stiff test.** Gently push the head with a finger.
   - **Limp** → motor board is unpowered. Wiring/power issue, NOT a software problem.
   - **Stiff** → board is powered and holding position. Continue below.
2. **Read telemetry** (see above) — classify State A (mute) vs State B (heartbeat-only) vs working (see `FA 00 09` on commands).
3. **Is this a cold boot?** If yes → run the 5× power-on wake-up sequence (Section 3).
4. **App contention?** Verify no established client on 7777 before sending. Pause tracking if needed.
5. **Bridge or board?** Try writing directly to `/dev/ttyS1` using the exec+stty form (NOT a simple redirect):
   ```bash
   adb shell "exec 3<>/dev/ttyS1; busybox stty -F /dev/ttyS1 57600 raw -hupcl; busybox printf '<frame>' >&3; sleep 0.5"
   ```
   - Direct exec+stty works but bridge doesn't → bridge relay issue.
   - **State B:** direct exec+stty IS the recovery, not just a diagnostic. Try it before declaring the board stuck.
   - Neither works → board is in State A (hung MCU) → power-cycle.

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

---

## 12. Known Bugs

### EUD=0 hard-stop oscillation (discovered 2026-05-29)

**Symptom:** After commanding EUD=0 (max up), if a subsequent EUD command is sent
within ~2000ms, the eyes oscillate up and down before settling.

**Trigger:** `EUD=0` (hold < 2000ms) → any EUD command → oscillation.

**Does NOT trigger:** `EUD=0` (hold ≥ 2000ms) → any EUD command → clean movement.

**Not observed at:** EUD=100 (max down) under same conditions.

**Hypothesis:** Mechanical rebound at the hard stop. The motor overshoots/bounces when
commanded away from the hard mechanical limit before fully settling.

**Workaround:** Always allow ≥2000ms at EUD=0 before the next command. The app's
smoothing (SMOOTH factor) may be sufficient in practice since it approaches limits
gradually, but rapid programmatic sequences must respect this delay.

**TODO:**
- Confirm minimum safe settle time (2000ms sufficient, or more needed?)
- Test whether other hard stops (ELR=0/100, NE=0/100, NR=0/100, NT=0/100) are affected

#### Update 2026-05-29 (session 7) — narrowed and partially characterized

Re-tested over a single persistent socket (transport ruled out) with per-trial visual
confirmation by Alex:

- **The oscillation is specific to the UPPER stop, EUD=0 (max up).** The lower stop EUD=100
  did **not** oscillate in isolation (5/5-style checks). Earlier "EUD=100 also oscillates"
  reports were residual motion from a prior phase in a combined run — isolating each test
  corrected this.
- **Abrupt reversal from EUD=0 → 50 oscillates 100% (5/5 trials).** Duration varies run to run
  (short to long) but it always fires. This is the most reliable trigger/identifier.
- **Ramping back from EUD=0 (12 small steps) is NOT a reliable fix — 2/5 oscillated**, and the
  5 trials alternated cleanly C,O,C,O,C (suggests a mechanical backlash/settle state that flips
  between runs). When the ramped return did oscillate it was as bad or worse than abrupt.
- **Best identifier for a code guard:** "EUD was just at the hard stop (≈0) and the next EUD
  command is a large step away." Most robust mitigation is to **never command EUD to the
  mechanical stop** — clamp `EUD_MIN` a few units above 0 (e.g. 5–8) so the eye never rides the
  top stop. (Floor-fix not yet hardware-validated — see TODO.)
- The app's per-frame `SMOOTH` interpolation approaches limits in tiny deltas, so the live app
  is largely immune; the blunt "2000ms settle" rule only matters for crude step scripts.
- **TODO:** validate the `EUD_MIN ≈ 5` floor fix on hardware (abrupt-reverse from 5, ×5).

---

## 13. Serial Telemetry / Readback (board → host)

The motor board is **NOT silent** — it continuously transmits status frames on `/dev/ttyS1`.
This is a useful, camera-free signal for detecting movement, oscillation, and the latch state.

### How to read it
The TCP bridge is **one-way** (`nc -l -p 7777 >&3` only copies TCP→serial). To see board output,
read the device directly from adb shell (safe while the bridge holds the port, because `-hupcl`
is set so the read open/close won't drop DTR):
```bash
adb shell "busybox timeout -t 5 cat /dev/ttyS1 | busybox hexdump -C"
```
**Reader-vs-bridge race:** if you start a `cat` capture at the same moment the bridge does its
`exec 3<>` re-open, the reader can attach to nothing (0-byte capture). Start the capture and wait
~1.5 s before sending commands.

`busybox timeout` on this unit uses `-t SECONDS` (e.g. `-t 5`), NOT `timeout 5 …`.

### Frame types
| Frame | Meaning |
|-------|---------|
| `FA 00 01 00 ED FB` | **Idle heartbeat** (payload `00`). Streamed continuously when the board is not moving any motor. |
| `FA 00 09 01 00 [LDL LDR ELR EUD NE NR NT] [s2 s1]` | **Position report.** Streamed while the board is engaged/moving. The 7 payload bytes are the live wire positions of all motors. |
| `FA 00 02 4F 7F 0B CB` | Power-on frame **echoed back** (appears right after you send a power-on). |

### Using telemetry as a marker
- **Engagement check:** a successful move streams `FA 00 09 …` frames. If you send commands and
  see **only** heartbeat (`FA 00 01 00`), the board did not engage → cold boot needs the 5× wake,
  or it is in the command-latch state — see Section 11 to classify and choose recovery path.
- **Oscillation detector (closed-loop):** during an oscillation, the EUD byte (4th payload byte)
  in successive `FA 00 09` frames rings up and down before settling. Polling that byte gives a
  programmatic oscillation signal with no camera needed — captured live during the EUD=0 bug
  (the EUD byte cycled ~`7c 78 7b 7a …` around center). This is the basis for future
  closed-loop tuning.
