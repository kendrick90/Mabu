package com.mabu.anima

import android.util.Log

/**
 * Kotlin port of mabu-app/mabu.py. Speaks the Catalia motor-board protocol
 * over /dev/ttyS1 at 57600 8N1. See notes/motor-protocol.md for the
 * byte-level reference -- this file is intentionally line-for-line
 * comparable to the Python version so behavior stays in sync.
 *
 * Serial access: tries the native JNI path (serial.c) first. If that fails
 * due to SELinux policy, falls back to AdbShellBridge which routes commands
 * through the local adbd daemon (which has the required shell context).
 * See selinux/ in the repo root for the permanent policy fix.
 */
class MabuMotors(
    private val port: String = "/dev/ttyS1",
    private val baud: Int = 57600
) {
    private var fd: Int = -1
    private var adb: AdbShellBridge? = null
    private val lock = Any()

    fun open(): Boolean {
        synchronized(lock) {
            if (isOpen()) return true

            // Try native serial first (requires proper SELinux policy)
            val r = SerialPort.openTty(port, baud)
            if (r >= 0) {
                fd = r
                powerOn()
                Log.i(TAG, "Mabu motors opened native fd=$fd")
                return true
            }
            Log.w(TAG, "Native serial failed (errno=${-r}), falling back to ADB bridge")

            // Fall back to ADB-over-localhost (temporary until SELinux patch applied)
            val bridge = AdbShellBridge()
            if (bridge.connect()) {
                adb = bridge
                // Configure serial port through the shell
                bridge.exec("busybox stty -F $port $baud raw")
                Thread.sleep(300)
                powerOn()
                Log.i(TAG, "Mabu motors opened via ADB bridge")
                return true
            }

            Log.e(TAG, "Both native serial and ADB bridge failed")
            return false
        }
    }

    fun close() {
        synchronized(lock) {
            if (fd >= 0) {
                try { powerOff() } catch (_: Throwable) {}
                SerialPort.closeTty(fd)
                fd = -1
            }
            adb?.let {
                try { powerOff() } catch (_: Throwable) {}
                it.close()
                adb = null
            }
        }
    }

    fun isOpen(): Boolean = fd >= 0 || adb?.isConnected == true

    // ── low-level I/O ──────────────────────────────────────────────────────

    private fun send(bytes: ByteArray) {
        synchronized(lock) {
            val bridge = adb
            if (bridge != null && bridge.isConnected) {
                // ADB path: convert frame bytes to a printf shell command
                val hex = bytes.joinToString("") { "\\x%02x".format(it.toInt() and 0xFF) }
                bridge.exec("printf '$hex' > $port")
            } else if (fd >= 0) {
                val n = SerialPort.writeBytes(fd, bytes, 0, bytes.size)
                if (n < 0) Log.w(TAG, "writeBytes failed errno=${-n}")
            }
        }
    }

    private fun frame(payload: ByteArray): ByteArray {
        val head = ByteArray(3 + payload.size)
        head[0] = 0xFA.toByte()
        head[1] = 0x00.toByte()
        head[2] = payload.size.toByte()
        System.arraycopy(payload, 0, head, 3, payload.size)
        val ck = fletcher8(head)
        return head + byteArrayOf((ck ushr 8).toByte(), ck.toByte())
    }

    // ── protocol primitives ────────────────────────────────────────────────

    fun powerOn() {
        send(byteArrayOf(0xFA.toByte(), 0x00, 0x02, 0x4F, 0x7F, 0x0B, 0xCB.toByte()))
    }

    fun powerOff() {
        send(byteArrayOf(0xFA.toByte(), 0x00, 0x02, 0x4F, 0x8B.toByte(), 0x4C))
    }

    /**
     * Move any subset of the 7 motors. Values are 0..100 with 50 = center;
     * null entries are skipped. Motor bits MUST be applied MSB-first because
     * the motor board reads the payload's value bytes in that order.
     */
    fun move(
        eyelidLeft: Float? = null,
        eyelidRight: Float? = null,
        eyesLR: Float? = null,
        eyesUD: Float? = null,
        neckElev: Float? = null,
        neckRot: Float? = null,
        neckTilt: Float? = null
    ) {
        val pairs = arrayOf(
            LDL to eyelidLeft, LDR to eyelidRight, ELR to eyesLR, EUD to eyesUD,
            NE  to neckElev,   NR  to neckRot,     NT  to neckTilt
        )
        var mask = 0
        val vals = ArrayList<Byte>(7)
        for ((bit, v) in pairs) {
            if (v != null) {
                mask = mask or bit
                vals.add(v100ToByte(v))
            }
        }
        if (mask == 0) return
        val payload = ByteArray(3 + vals.size)
        payload[0] = 0x01
        payload[1] = mask.toByte()
        payload[2] = 0x01
        for (i in vals.indices) payload[3 + i] = vals[i]
        send(frame(payload))
    }

    /**
     * Neutral pose: eyelids at natural rest, all other motors centered.
     */
    fun restingPose() = move(
        eyelidLeft = EYELID_NEUTRAL, eyelidRight = EYELID_NEUTRAL,
        eyesLR = 50f, eyesUD = 50f,
        neckElev = 50f, neckRot = 50f, neckTilt = 50f
    )

    /**
     * Sleep pose: eyelids closed, neck lowered and tilted slightly down.
     * Used on shutdown so Mabu looks like it's resting rather than frozen.
     */
    fun sleepPose() = move(
        eyelidLeft = EYELID_CLOSED, eyelidRight = EYELID_CLOSED,
        eyesLR = 50f, eyesUD = 50f,
        neckElev = 30f, neckRot = 50f, neckTilt = 60f
    )

    fun eyesOpen()    = move(eyelidLeft = EYELID_OPEN,    eyelidRight = EYELID_OPEN)
    fun eyesNeutral() = move(eyelidLeft = EYELID_NEUTRAL, eyelidRight = EYELID_NEUTRAL)
    fun eyesClosed()  = move(eyelidLeft = EYELID_CLOSED,  eyelidRight = EYELID_CLOSED)

    /**
     * Look toward normalized (x, y) in 0..1.
     * x=0 full left, x=1 full right. y=0 full down, y=1 full up.
     */
    fun gaze(x: Float, y: Float) {
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        move(eyesLR = cx * 100f, eyesUD = cy * 100f)
    }

    companion object {
        private const val TAG = "MabuMotors"

        // Motor bitmask values, MSB first (must match factorymode's MotorRepository order)
        const val LDL = 0x40  // EYELID_LEFT
        const val LDR = 0x20  // EYELID_RIGHT
        const val ELR = 0x10  // EYES_LEFT_RIGHT
        const val EUD = 0x08  // EYES_UP_DOWN
        const val NE  = 0x04  // NECK_ELEVATION
        const val NR  = 0x02  // NECK_ROTATION
        const val NT  = 0x01  // NECK_TILT

        // Per-unit-4 eyelid calibration: inverted from mabu.py's labels.
        // Higher value = more closed on this unit.
        const val EYELID_OPEN    = 5f   // wide open (surprised)
        const val EYELID_NEUTRAL = 25f  // natural rest
        const val EYELID_CLOSED  = 90f  // blink / closed

        private fun v100ToByte(v: Float): Byte {
            val n = (v * 2.55f + 0.5f).toInt().coerceIn(0, 255)
            return n.toByte()
        }

        fun fletcher8(data: ByteArray): Int {
            var s1 = 0; var s2 = 0
            for (b in data) {
                s1 = (s1 + (b.toInt() and 0xFF)) % 255
                s2 = (s2 + s1) % 255
            }
            return (s2 shl 8) or s1
        }
    }
}
