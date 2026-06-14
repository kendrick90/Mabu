# Motor Bridge Problem — Handoff Document

Last updated: 2026-05-29. Written for agents picking up this work cold.

---

## Background: Why the bridge exists

Mabu runs Android 8.1 (`ro.build.type=user`). Apps installed normally run as `untrusted_app` in SELinux terms. The motor board is wired to `/dev/ttyS1`, which carries the SELinux label `u:object_r:serial_device:s0`. Android's policy does **not** include an `allow untrusted_app serial_device` rule, so any direct open of `/dev/ttyS1` from the app — whether Java `FileOutputStream` or native JNI — is silently blocked by SELinux.

The shell user (`u:r:shell:s0`) **IS** allowed to open `/dev/ttyS1`. We confirmed this: `adb shell printf '\xFA...' > /dev/ttyS1` moves motors when the device is freshly booted.

### Why we can't fix SELinux permanently over WiFi

- `adb root` → "adbd cannot run as root in production builds"
- No `su` binary anywhere on the device
- `setenforce 0` → "Permission denied"  
- `/system` partition can't be remounted rw without root
- No USB access — rkdeveloptool (Loader mode) is impossible
- `getenforce` may say "Enforcing" but `ro.boot.selinux=permissive` is in kernel params — Android init re-enforces at startup anyway

**Conclusion: there is no WiFi-only path to grant the app direct serial access on this unit.**

---

## The bridge concept

Run a shell script as the `shell` user that:
1. Opens `/dev/ttyS1` at 57600 baud
2. Listens on TCP port 7777
3. Copies bytes from TCP → serial

The app connects to `127.0.0.1:7777` and writes motor frames as if writing directly to the serial port. The kernel sees the shell process (not the app) touching the motors, so SELinux allows it.

**Bridge file:** `MabuFaceTrack/bridge/motor-bridge.sh`  
**Start command (after every reboot):**
```
adb push motor-bridge.sh /data/local/tmp/
adb shell "chmod 755 /data/local/tmp/motor-bridge.sh && busybox dos2unix /data/local/tmp/motor-bridge.sh"
adb shell "nohup /data/local/tmp/motor-bridge.sh > /data/local/tmp/motor-bridge.log 2>&1 &"
```

---

## What has actually worked

- **Directly after a fresh reboot:** `adb shell "busybox stty -F /dev/ttyS1 57600 raw && printf '\xFA...' > /dev/ttyS1"` moves motors. Confirmed multiple times.
- **The test sweep script (`test-dramatic-sweep.ps1`) worked once**, immediately after a reboot, before any other bridge activity. Motors visibly swept through neck rotation, eye L/R, and blinks.
- **The bridge TCP connection itself works:** the app connects successfully, `isConnected` is true, bytes are written without Java exceptions.
- **The app's face detection works:** ML Kit detects faces, motor values are computed correctly, `moveAll()` is called with correct values at 70ms intervals.

---

## The failure modes

### 1. `nc: short write` — ttyS1 state corruption

**Symptom:** After multiple client connect/disconnect cycles, `nc` logs `nc: short write` and fails to write to `/dev/ttyS1`. No motors move. Even direct `adb shell printf > /dev/ttyS1` stops working.

**Root cause:** The original bridge design used `nc -l -p 7777 > /dev/ttyS1`. Every client connect/disconnect caused nc to open and close `/dev/ttyS1` (because the `>` redirect opens the device on nc start and closes it on nc exit). After many cycles (~30+, as produced by the per-command test script), the kernel TTY driver state for `/dev/ttyS1` gets corrupted — baud rate or line discipline settings are lost.

**Fix attempted:** Redesigned bridge to use `exec 3> /dev/ttyS1` to open the device once on fd 3 and never close it; nc writes `>&3` instead of `> /dev/ttyS1`. This prevents repeated open/close. **However, we have not yet confirmed this fixes the problem** — the device was rebooted before the new design could be fully tested.

**Recovery:** Reboot Mabu. The only reliable reset.

### 2. `nc: bind: Address already in use` — stale bridge processes

**Symptom:** After killing nc or the bridge script, a new bridge instance fails immediately with `nc: bind: Address already in use` in a tight loop.

**Root cause:** On this Android version, killed shell processes may leave sockets in TIME_WAIT or the kernel doesn't immediately release the port. More importantly, if only nc is killed (not the parent shell loop), the loop immediately restarts another nc which also fails if the port isn't yet free.

**Workaround:** Find the parent shell loop PID (the `sh` process that spawned nc, not nc itself) and `kill -9` it along with nc. Then wait a few seconds before starting a fresh bridge. Use `cat /proc/<nc_pid>/status | grep PPid` to find the parent.

### 3. Motor board not responding after bridge restart

**Symptom:** Bridge restarts cleanly (listening on 7777, no errors in log), connections are accepted (logged), but motors do not move.

**Root cause under investigation.** Two candidates:
- **Baud rate reset:** Even with the fd3 fix, if the stty settings were corrupted before the bridge was restarted, the new bridge's initial `stty` call at startup should reset them. But if something is interfering (e.g., the kernel resetting line discipline when the device is opened), the 57600 raw setting may not take effect.
- **Motor board protocol state:** The motor board (Holtek HT32F12345) may require a clean power-on frame before accepting position commands. If previous garbage data corrupted the motor board's parser state, new valid frames may be ignored until the board is power-cycled. The power-on frame is `FA 00 02 4F 7F 0B CB`.

**What to try next:**
- After a fresh reboot (not just bridge restart), immediately run the test sweep — does it work? (It did once; confirming this is reproducible is the first diagnostic step.)
- If the sweep works after reboot but not after bridge restarts: the fd3 bridge design should fix this — needs a post-reboot clean test.
- If the sweep does NOT work after a fresh reboot: the motor board or cable has a hardware issue unrelated to software.

---

## Current bridge design (fd3 approach — untested end-to-end)

```sh
#!/system/bin/sh
PORT=7777
TTY=/dev/ttyS1
BUSYBOX=/system/bin/busybox

$BUSYBOX stty -F "$TTY" 57600 raw

# Open ttyS1 ONCE on fd 3. Stays open for the lifetime of this script.
# nc writes to fd 3, not to the device directly — no repeated open/close.
exec 3> "$TTY"
log "Bridge starting..."

while true; do
    $BUSYBOX nc -l -p "$PORT" >&3
    log "Client disconnected, listening again"
done
```

File is at `MabuFaceTrack/bridge/motor-bridge.sh`. **This design has not yet had a clean post-reboot test.** It should be the first thing tried next session.

---

## App-side motor code

**`MabuFaceTrack/app/src/main/java/com/mabu/facetrack/MabuMotors.kt`**

- Uses `Socket("127.0.0.1", 7777)` — TEMP, marked as workaround throughout
- Sends all 7 motors in one `FA 00 09 01 00 [7 bytes] [ck]` frame via `moveAll()`
- Auto-reconnects if connection drops (retries in `writeFrame()`)
- Neutral positions: `NE_NEUTRAL = 25.0`, `EYELID_NEUTRAL = 25.0` (community research shows NE mechanical center is ~25, not 50)

**`MabuFaceTrack/app/src/main/java/com/mabu/facetrack/MainActivity.kt`**

- Face detection confirmed working (ML Kit, Camera1 API)
- Motor sends happen on `motorExecutor` (single background thread) — not main thread
- Calls `motors.moveAll(...)` once per 70ms when a face is detected

---

## The ideal permanent fix

When direct serial is possible (system-signed APK, or SELinux policy patch), revert `MabuMotors.kt`:
- Replace `Socket(...)` / `OutputStream` with `FileOutputStream("/dev/ttyS1")`
- Add back `busybox stty` `ProcessBuilder` call in `open()`
- Drop `INTERNET` permission from `AndroidManifest.xml`
- Remove `BRIDGE_HOST`, `BRIDGE_PORT` constants
- All `// TEMP` comments mark the lines to change

SELinux policy patch is documented in `selinux/` in the main Mabu repo (kendrick90/Mabu). Requires USB access or a system-signed APK to apply.

---

## Protocol reference

Motor frame: `FA 00 <len> <payload> <fletcher8_s2> <fletcher8_s1>`  
Single motor: payload = `01 <bitmask> 01 <wire>`  
All motors:   payload = `01 00 <LDL> <LDR> <ELR> <EUD> <NE> <NR> <NT>`  
Wire value: `int(round(value_0_to_100 * 2.55))`  
Fletcher-8: `s1=(s1+byte)%255; s2=(s2+s1)%255` over full frame including `FA 00`. Append `[s2, s1]`.  
Power on: `FA 00 02 4F 7F 0B CB`  
Bitmasks: LDL=0x40, LDR=0x20, ELR=0x10, EUD=0x08, NE=0x04, NR=0x02, NT=0x01

**Do NOT hand-compute checksums.** Use the PowerShell `Build-Frame` function in the test scripts or the Kotlin `buildFrame()` / `fletcher8()` in `MabuMotors.kt`. Manual calculation has caused bad frames and wasted sessions.
