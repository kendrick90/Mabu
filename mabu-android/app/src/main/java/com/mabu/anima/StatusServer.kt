package com.mabu.anima

import android.content.Context
import android.util.Log
import com.mabu.anima.DeviceStats.toJson
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * Tiny LAN-only HTTP server exposing [DeviceStats] as JSON so anyone on the
 * network (the brain PC, an external observer, a debug curl) can poll device
 * health without an ADB shell.
 *
 *   GET /status   -> 200, application/json, full DeviceStats snapshot
 *   GET /healthz  -> 200, text/plain, "ok"
 *   anything else -> 404
 *
 * Single accept thread plus a small thread pool for request handling. No auth,
 * no TLS -- matches the rest of the brain<->device traffic (LAN trust model).
 * Bound to 0.0.0.0; pick a free port in the user range so we don't collide
 * with the brain's 7861 (control_server) or 9090/8123 listeners.
 */
class StatusServer(
    private val context: Context,
    private val port: Int = 7862,
) {
    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(2)
    private var acceptThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        try {
            serverSocket = ServerSocket(port).apply { reuseAddress = true }
            running = true
            acceptThread = Thread({ acceptLoop() }, "StatusServer-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "listening on :$port")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to bind :$port", t)
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        workers.shutdownNow()
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val client = try {
                ss.accept()
            } catch (_: SocketException) {
                return  // socket closed -> we're shutting down
            } catch (t: Throwable) {
                Log.w(TAG, "accept error", t)
                continue
            }
            workers.execute { handle(client) }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine() ?: return
                // Drain headers (we don't care about them, but read so the client
                // doesn't see a half-closed connection on its write side).
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: ""
                val path = parts.getOrNull(1) ?: ""
                if (method != "GET") {
                    write(sock.getOutputStream(), 405, "text/plain", "method not allowed")
                    return
                }
                val pathOnly = path.substringBefore('?')
                when (pathOnly) {
                    "/status", "/" -> {
                        val json = with(DeviceStats) { snapshot(context).toJson() }.toString()
                        write(sock.getOutputStream(), 200, "application/json", json)
                    }
                    "/healthz" -> write(sock.getOutputStream(), 200, "text/plain", "ok")
                    else -> write(sock.getOutputStream(), 404, "text/plain", "not found")
                }
            }
        } catch (e: IOException) {
            // client hung up mid-response; not worth logging at warn
        } catch (t: Throwable) {
            Log.w(TAG, "handler error", t)
        }
    }

    private fun write(out: OutputStream, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Status"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val TAG = "StatusServer"
    }
}
