package com.mabu.facetrack

import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// TEMPORARY WORKAROUND: This class routes motor commands through a TCP bridge
// (motor-bridge.sh running on Mabu, port 7777) instead of writing directly to
// /dev/ttyS1. The bridge exists solely because SELinux blocks untrusted_app from
// opening serial_device on this user-build unit where we have no root access.
//
// When to replace this: if SELinux policy is ever patched (selinux/ in the repo),
// or if the app is signed as a system/privileged app, revert to FileOutputStream
// on /dev/ttyS1 and drop the INTERNET permission from the manifest.
//
// Protocol summary:
//   FA 00 <len> <payload> <fletcher8 s2> <fletcher8 s1>
//   Fletcher-8 computed mod 255 over the whole frame including FA 00.
//   Motor values are 0..100; wire value = round(v * 2.55) → 0..255.
//
// Preferred send path: moveAll() — one 14-byte frame for all 7 motors atomically.
// sendMotor() still exists for single-motor use (calibration, testing, etc.).
class MabuMotors {

    // TEMP: TCP connection to motor-bridge.sh. Replace with FileOutputStream("/dev/ttyS1")
    // once direct serial access is available.
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val lock = Any()

    fun open(): Boolean {
        synchronized(lock) {
            if (isOpen()) return true
            for (attempt in 1..3) {
                if (tryConnect(sendInit = true)) return true
                Thread.sleep(500L * attempt)
            }
            return false
        }
    }

    fun close() {
        synchronized(lock) {
            try { centerAll() } catch (_: Exception) {}
            // TEMP: power-off only meaningful over direct serial; bridge teardown handles shutdown.
            // try { sendPowerOff() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            out = null
        }
    }

    fun isOpen(): Boolean = socket?.let { it.isConnected && !it.isClosed } == true && out != null

    // Send all 7 motors in one 14-byte frame. Preferred over 7x sendMotor() calls —
    // one TCP write, one flush, atomically consistent state on the motor board.
    // Motor order matches the protocol: LDL, LDR, ELR, EUD, NE, NR, NT.
    fun moveAll(
        ldl: Double, ldr: Double,
        elr: Double, eud: Double,
        ne: Double,
        nr: Double, nt: Double
    ) {
        // Protocol: [0x01, mask, 0x01, val_for_each_set_bit_MSB_first]
        // All 7 motors: mask = LDL|LDR|ELR|EUD|NE|NR|NT = 0x7F
        val payload = byteArrayOf(
            0x01, 0x7F, 0x01,
            wire(ldl), wire(ldr),
            wire(elr), wire(eud),
            wire(ne),
            wire(nr), wire(nt)
        )
        writeFrame(buildFrame(payload))
    }

    // Single-motor command — useful for calibration or targeted testing.
    fun sendMotor(bitmask: Int, value0to100: Double) {
        val payload = byteArrayOf(0x01, bitmask.toByte(), 0x01, wire(value0to100))
        writeFrame(buildFrame(payload))
    }

    fun centerAll() {
        moveAll(
            ldl = EYELID_NEUTRAL, ldr = EYELID_NEUTRAL,
            elr = 50.0, eud = 50.0,
            ne  = NE_NEUTRAL,
            nr  = 50.0, nt  = 50.0
        )
    }

    private fun sendPowerOn() {
        writeFrame(byteArrayOf(0xFA.toByte(), 0x00, 0x02, 0x4F, 0x7F, 0x0B, 0xCB.toByte()))
    }

    // TEMP: currently unused — see close(). Keeping for when direct serial is restored.
    @Suppress("unused")
    private fun sendPowerOff() {
        writeFrame(byteArrayOf(0xFA.toByte(), 0x00, 0x02, 0x4F, 0x8B.toByte(), 0x4C))
    }

    // Attempts (re)connection to the bridge. Called from open() and from writeFrame()
    // on failure. sendInit=true sends power-on + center; false just opens the socket.
    // Not synchronized itself — always called from within synchronized(lock).
    private fun tryConnect(sendInit: Boolean): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(BRIDGE_HOST, BRIDGE_PORT), CONNECT_TIMEOUT_MS)
            socket = s
            out = s.getOutputStream()
            Log.i(TAG, "Motor bridge connected: $BRIDGE_HOST:$BRIDGE_PORT") // TEMP
            if (sendInit) {
                sendPowerOn()
                Thread.sleep(500)
                centerAll()
                Log.i(TAG, "Motors initialized")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Motor bridge connect failed: ${e.message}") // TEMP
            socket = null
            out = null
            false
        }
    }

    private fun writeFrame(frame: ByteArray) {
        synchronized(lock) {
            // Auto-reconnect if bridge connection was lost. TEMP: remove when direct serial restored.
            if (out == null) tryConnect(sendInit = false)
            try {
                out?.write(frame)
                out?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Motor bridge write failed: ${e.message}") // TEMP
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                out = null
                // Attempt immediate reconnect so next write succeeds.
                tryConnect(sendInit = false) // TEMP
            }
        }
    }

    private fun buildFrame(payload: ByteArray): ByteArray {
        val header = byteArrayOf(0xFA.toByte(), 0x00, payload.size.toByte()) + payload
        val ck = fletcher8(header)
        return header + byteArrayOf((ck shr 8).toByte(), (ck and 0xFF).toByte())
    }

    private fun fletcher8(data: ByteArray): Int {
        var s1 = 0; var s2 = 0
        for (b in data) {
            s1 = (s1 + (b.toInt() and 0xFF)) % 255
            s2 = (s2 + s1) % 255
        }
        return (s2 shl 8) or s1
    }

    private fun wire(v: Double): Byte = min(255, max(0, (v * 2.55).roundToInt())).toByte()

    companion object {
        private const val TAG = "MabuMotors"

        // TEMP: bridge coordinates — swap for direct serial when available.
        private const val BRIDGE_HOST = "127.0.0.1"
        private const val BRIDGE_PORT = 7777
        private const val CONNECT_TIMEOUT_MS = 2000

        // Motor bitmasks.
        const val EYELID_L = 0x40
        const val EYELID_R = 0x20
        const val ELR = 0x10  // eyes left/right
        const val EUD = 0x08  // eyes up/down
        const val NE  = 0x04  // neck elevation
        const val NR  = 0x02  // neck rotation
        const val NT  = 0x01  // neck tilt

        // Neutral positions — NOT all 50. Community reverse-engineering shows NE's mechanical
        // center is ~25 (sending 50 pushes it to a hard stop). Eyelids at 25 = mostly open.
        // These may need per-unit tuning. Source: electronick-co/hacking_mabu2.
        const val NE_NEUTRAL = 25.0
        const val EYELID_NEUTRAL = 25.0
    }
}
