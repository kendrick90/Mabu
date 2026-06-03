package com.mabu.facetrackadb

import android.util.Log
import java.net.NetworkInterface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Motor control over /dev/ttyS1.
//
// Tries native JNI (open + termios) first. The AVC denial we observed is for
// `getattr` (called by Java before open), but native open() may succeed because
// SELinux on this device may allow open/write without allowing getattr.
//
// Falls back to ADB shell bridge if native fails, which routes through the
// local adbd daemon (shell context has serial_device access).
//
// Protocol:
//   FA 00 <len> <payload> <fletcher8 s2> <fletcher8 s1>
//   Fletcher-8 computed mod 255 over the whole frame including FA 00 header.
//   Motor values 0..100; wire value = round(v * 2.55) -> 0..255.
class MabuMotors {

    private var nativeFd: Int = -1
    private var bridge: AdbShellBridge? = null
    private val lock = Any()

    fun open(): Boolean {
        Thread {
            while (!isOpen()) {
                openOnce()
                if (!isOpen()) Thread.sleep(3000)
            }
        }.also { it.isDaemon = true; it.start() }
        return true
    }

    private fun openOnce(): Boolean {
        synchronized(lock) {
            if (isOpen()) return true

            // Try native JNI first — open() bypasses the getattr SELinux check
            // that blocks Java FileOutputStream. On this device, open+write may
            // be allowed even though getattr is not.
            val fd = SerialPort.openTty("/dev/ttyS1", 57600)
            if (fd >= 0) {
                nativeFd = fd
                Log.i(TAG, "Native serial opened fd=$fd")
                repeat(5) { sendPowerOn(); Thread.sleep(200) }
                Thread.sleep(500)
                centerAll()
                Log.i(TAG, "Motors initialized via native serial")
                return true
            }
            Log.w(TAG, "Native serial failed (errno=${-fd}), trying ADB bridge...")

            // Fall back to in-app ADB bridge (shell context has serial_device access).
            // adbd rejects loopback connections on this device; try the LAN IP so it
            // sees a non-loopback source.
            val b = AdbShellBridge()
            val lanIp = getWifiIp() ?: "127.0.0.1"
            Log.i(TAG, "ADB bridge via $lanIp:5555")
            if (!b.connect(lanIp)) {
                Log.w(TAG, "ADB bridge connect failed, will retry in 3s")
                return false
            }

            b.exec("exec 3<>/dev/ttyS1")
            Thread.sleep(100)
            b.exec("busybox stty -F /dev/ttyS1 57600 raw -hupcl")
            Thread.sleep(300)
            bridge = b
            Log.i(TAG, "Motors opened via ADB bridge (persistent fd 3)")

            repeat(5) { sendPowerOn(); Thread.sleep(200) }
            Thread.sleep(500)
            centerAll()
            Log.i(TAG, "Motors initialized via ADB bridge")
            return true
        }
    }

    fun close() {
        synchronized(lock) {
            try { centerAll() } catch (_: Exception) {}
            if (nativeFd >= 0) { SerialPort.closeTty(nativeFd); nativeFd = -1 }
            bridge?.close(); bridge = null
        }
    }

    fun isOpen(): Boolean = nativeFd >= 0 || bridge?.isConnected == true

    fun moveAll(
        ldl: Double, ldr: Double,
        elr: Double, eud: Double,
        ne: Double,
        nr: Double, nt: Double
    ) {
        val payload = byteArrayOf(
            0x01, 0x7F, 0x01,
            wire(ldl), wire(ldr),
            wire(elr), wire(eud),
            wire(ne),
            wire(nr), wire(nt)
        )
        writeFrame(buildFrame(payload))
    }

    fun sendMotor(bitmask: Int, value0to100: Double) {
        val payload = byteArrayOf(0x01, bitmask.toByte(), 0x01, wire(value0to100))
        writeFrame(buildFrame(payload))
    }

    fun centerAll() {
        moveAll(
            ldl = EYELID_NEUTRAL, ldr = EYELID_NEUTRAL,
            elr = 50.0, eud = 50.0,
            ne  = NE_NEUTRAL,
            nr  = NR_NEUTRAL, nt = NT_NEUTRAL
        )
    }

    private fun sendPowerOn() {
        writeFrame(byteArrayOf(0xFA.toByte(), 0x00, 0x02, 0x4F, 0x7F, 0x0B, 0xCB.toByte()))
    }

    private fun writeFrame(frame: ByteArray) {
        synchronized(lock) {
            if (nativeFd >= 0) {
                val n = SerialPort.writeBytes(nativeFd, frame, 0, frame.size)
                if (n < 0) Log.w(TAG, "native write failed errno=${-n}")
            } else {
                val b = bridge ?: return
                if (!b.isConnected) return
                val hex = frame.joinToString("") { "\\x%02x".format(it.toInt() and 0xFF) }
                b.exec("printf '$hex' >&3")
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

    private fun getWifiIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.name.startsWith("wlan") && it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it.address.size == 4 }
                .map { it.hostAddress }
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Could not get WiFi IP: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MabuMotors"

        const val EYELID_L = 0x40
        const val EYELID_R = 0x20
        const val ELR = 0x10
        const val EUD = 0x08
        const val NE  = 0x04
        const val NR  = 0x02
        const val NT  = 0x01

        // Per-unit-4 calibrated neutrals (Alex-confirmed 2026-05-29)
        const val NE_NEUTRAL     = 50.0
        const val NR_NEUTRAL     = 50.0
        const val NT_NEUTRAL     = 50.0
        const val EYELID_NEUTRAL = 20.0
    }
}
