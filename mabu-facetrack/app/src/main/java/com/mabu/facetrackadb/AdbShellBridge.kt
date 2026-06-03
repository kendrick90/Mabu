package com.mabu.facetrackadb

import android.util.Log
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Minimal ADB protocol client that connects to the local adbd daemon
 * (127.0.0.1:5555) and opens a persistent shell session.
 *
 * Used because SELinux policy blocks untrusted_app from opening serial_device
 * on this user-build unit. The adbd daemon was patched during liberation to
 * remove auth requirements. The shell context (u:r:shell:s0) IS allowed to
 * write serial_device.
 *
 * Serial port access uses a persistent fd (exec 3<>/dev/ttyS1) held open for
 * the life of the shell session. All motor writes go through >&3 so the file
 * descriptor is never closed between commands — closing ttyS1 drops DTR and
 * resets the motor board.
 */
class AdbShellBridge {

    private val A_CNXN = 0x4e584e43
    private val A_OPEN = 0x4e45504f
    private val A_OKAY = 0x59414b4f
    private val A_WRTE = 0x45545257
    private val A_AUTH = 0x48545541
    private val LOCAL_ID = 42

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var remoteId = 0
    @Volatile var isConnected = false
        private set

    private val writeLock = Any()

    fun connect(adbHost: String = "127.0.0.1"): Boolean {
        return try {
            // adbd filters loopback (127.0.0.1) connections. Connect via the device's own
            // LAN IP instead — adbd sees a non-loopback peer and processes the handshake.
            Log.i(TAG, "Connecting to $adbHost:5555...")
            val sock = Socket(adbHost, 5555)
            sock.soTimeout = 5000
            Log.i(TAG, "TCP connected")
            val inp = DataInputStream(sock.getInputStream())
            val out = sock.getOutputStream()

            // Handshake: send CONNECT
            val banner = "host::features=shell_v2".toByteArray()
            sendMsg(out, A_CNXN, 0x01000001, 256 * 1024, banner)
            Log.i(TAG, "CNXN sent, waiting for response...")
            Thread.sleep(200)  // give adbd time to process before reading

            // Expect CNXN back (AUTH means liberation patch didn't remove it)
            var msg = readMsg(inp)
            Log.i(TAG, "Got response cmd=0x${msg.cmd.toString(16)}")
            if (msg.cmd == A_AUTH) {
                Log.e(TAG, "adbd requires auth -- liberation patch not applied?")
                sock.close(); return false
            }
            if (msg.cmd != A_CNXN) {
                Log.e(TAG, "Expected CNXN got 0x${msg.cmd.toString(16)}")
                sock.close(); return false
            }
            Log.i(TAG, "ADB connected: ${String(msg.data)}")

            // Open a shell stream
            sendMsg(out, A_OPEN, LOCAL_ID, 0, "shell: ".toByteArray())
            msg = readMsg(inp)
            if (msg.cmd != A_OKAY) {
                Log.e(TAG, "OPEN rejected: 0x${msg.cmd.toString(16)}")
                sock.close(); return false
            }
            val rid = msg.arg0

            socket = sock; input = inp; output = out; remoteId = rid
            isConnected = true

            // Background thread drains all incoming messages (OKAY, WRTE shell output)
            // so the kernel socket buffer never fills and blocks our writes.
            Thread {
                try { while (isConnected) readMsg(inp) }
                catch (_: Exception) { isConnected = false }
            }.also { it.isDaemon = true; it.start() }

            Log.i(TAG, "ADB shell bridge ready, remoteId=$rid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ADB connect failed [${e.javaClass.simpleName}]: ${e.message}", e)
            false
        }
    }

    /** Send a shell command line through the persistent shell session. */
    fun exec(cmd: String) {
        synchronized(writeLock) {
            if (!isConnected) return
            try {
                val data = "$cmd\n".toByteArray()
                sendMsg(output!!, A_WRTE, LOCAL_ID, remoteId, data)
            } catch (e: Exception) {
                Log.w(TAG, "exec failed: ${e.message}")
                isConnected = false
            }
        }
    }

    fun close() {
        isConnected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null; remoteId = 0
    }

    // ADB message framing

    private data class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun readMsg(inp: DataInputStream): Msg {
        val hdr = ByteArray(24)
        inp.readFully(hdr)
        val b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        b.int; b.int  // skip crc32 and magic
        val data = if (len > 0) ByteArray(len).also { inp.readFully(it) } else ByteArray(0)
        return Msg(cmd, a0, a1, data)
    }

    private fun sendMsg(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val crc = if (data.isEmpty()) 0L else CRC32().also { it.update(data) }.value
        val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(cmd); hdr.putInt(arg0); hdr.putInt(arg1)
        hdr.putInt(data.size); hdr.putInt(crc.toInt()); hdr.putInt(cmd.inv())
        out.write(hdr.array())
        if (data.isNotEmpty()) out.write(data)
        out.flush()
    }

    companion object {
        private const val TAG = "AdbShellBridge"
    }
}
