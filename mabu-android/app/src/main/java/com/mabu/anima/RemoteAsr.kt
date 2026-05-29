package com.mabu.anima

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Always-on streaming ASR over the LAN via a WhisperLive WebSocket server
 * (faster-whisper / CTranslate2 on the PC brain). Hands-free: there is no
 * push-to-talk. [start] opens one persistent connection and streams the mic
 * continuously; the server's VAD segments speech and pushes transcripts. When
 * transcription goes quiet for [SILENCE_MS] we treat the utterance as finished
 * and fire [onFinal] (which drives the LLM). Live text goes to [onPartial].
 *
 * Echo guard: while Mabu is speaking, the caller sets [muted] = true so we stop
 * streaming the mic and don't transcribe Mabu's own voice into a feedback loop.
 *
 * Audio is 16 kHz mono int16 PCM, sent as raw bytes -- the server must run with
 * `--raw_pcm_input`.
 *
 * Protocol (WhisperLive whisper_live/client.py + server.py):
 *  - On open, send JSON config {uid, language, task, model, use_vad, ...}.
 *  - Server replies {message:"SERVER_READY"} once the model is loaded.
 *  - Stream raw int16 PCM binary frames.
 *  - Server pushes {segments:[{start,end,text,completed}, ...]}; completed=true
 *    segments are final, the trailing non-completed one is the live partial.
 *
 * baseWsUrl example: `ws://10.0.0.49:9090`
 */
class RemoteAsr(
    private val baseWsUrl: String,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit = {},
    private val model: String = "large-v3-turbo",
    private val language: String? = "en"
) {

    @Volatile var isReady: Boolean = true       // no local model to load
        private set
    /** True from [start] until [stop] -- i.e. the mic loop is alive. */
    @Volatile var isListening: Boolean = false
        private set

    /** When true, the mic is streamed; when false (Mabu speaking), it isn't. */
    @Volatile var muted: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) resetUtterance()        // drop anything heard so far
            Log.i(TAG, if (value) "muted" else "unmuted")
        }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)       // WS stays open
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val uid = UUID.randomUUID().toString()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var serverReady = false
    private var recordThread: Thread? = null
    private var endpointThread: Thread? = null

    // Utterance assembly. Guarded by [lock]; WS + endpoint threads both touch.
    private val lock = Any()
    private val completed = ArrayList<String>()
    private var partial = ""
    @Volatile private var utteranceActive = false
    @Volatile private var lastActivityMs = 0L
    // WhisperLive keeps a rolling per-connection transcript and resends its
    // last-N segments every message. To get discrete utterances we ignore any
    // segment that ends at/before [consumedEnd] (seconds since connect) -- the
    // point up to which we've already emitted. [maxEndSeen] tracks the newest
    // segment end so the endpoint can advance consumedEnd past this utterance.
    @Volatile private var consumedEnd = 0.0
    @Volatile private var maxEndSeen = 0.0

    /** Begin always-on listening (idempotent). */
    fun start() {
        if (running) return
        running = true
        isListening = true
        serverReady = false
        resetUtterance()
        val request = Request.Builder().url("$baseWsUrl/").build()
        webSocket = client.newWebSocket(request, SocketListener())
        startEndpointWatcher()
        Log.i(TAG, "connecting to $baseWsUrl (uid=$uid)")
    }

    fun stop() {
        if (!running) return
        running = false
        isListening = false
        try { webSocket?.send(END_OF_AUDIO) } catch (_: Throwable) {}
        try { recordThread?.join(500) } catch (_: Throwable) {}
        recordThread = null
        endpointThread = null
        try { webSocket?.close(1000, "stop") } catch (_: Throwable) {}
        Log.i(TAG, "stopped")
    }

    fun release() {
        stop()
        try { webSocket?.cancel() } catch (_: Throwable) {}
        webSocket = null
        isReady = false
    }

    private fun resetUtterance() {
        synchronized(lock) { completed.clear(); partial = "" }
        utteranceActive = false
    }

    // --- continuous audio capture ---

    private fun startCapture(ws: WebSocket) {
        if (recordThread != null) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, FRAME_BYTES * 2)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufBytes
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing", e); return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed"); recorder.release(); return
        }

        recordThread = Thread {
            val buf = ByteArray(FRAME_BYTES)
            try {
                recorder.startRecording()
                Log.i(TAG, "recording @ ${SAMPLE_RATE}Hz int16 mono (continuous)")
                while (running) {
                    val n = recorder.read(buf, 0, buf.size)
                    // Send to the CURRENT socket (the field, not a captured ref)
                    // so this single long-lived thread keeps working across
                    // reconnects. Keep draining the mic even when muted; just
                    // don't send, so Mabu's own TTS never reaches the transcriber.
                    if (n > 0 && !muted && serverReady) {
                        webSocket?.send(buf.copyOf(n).toByteString())
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "capture loop error", e)
            } finally {
                try { recorder.stop() } catch (_: Throwable) {}
                recorder.release()
            }
        }.apply { isDaemon = true; start() }
    }

    /** Fires onFinal once an utterance has gone quiet for SILENCE_MS. */
    private fun startEndpointWatcher() {
        if (endpointThread != null) return
        endpointThread = Thread {
            while (running) {
                try { Thread.sleep(ENDPOINT_POLL_MS) } catch (_: InterruptedException) { break }
                if (muted || !utteranceActive) continue
                val quietFor = System.currentTimeMillis() - lastActivityMs
                if (quietFor >= SILENCE_MS) {
                    val text = currentText()
                    // Everything up to the newest segment is now consumed, so
                    // the next utterance starts fresh.
                    consumedEnd = maxEndSeen
                    resetUtterance()
                    if (text.isNotEmpty()) {
                        Log.i(TAG, "endpoint (${quietFor}ms quiet) -> final: $text")
                        onFinal(text)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            val cfg = JSONObject().apply {
                put("uid", uid)
                put("language", language ?: JSONObject.NULL)
                put("task", "transcribe")
                put("model", model)
                put("use_vad", true)
                put("send_last_n_segments", 10)
                put("no_speech_thresh", 0.45)
                put("clip_audio", false)
                put("same_output_threshold", 5)
                // Bias recognition toward the robot's name (Whisper tends to
                // hear "Maru"/"Mabo" otherwise).
                put("hotwords", "Mabu")
            }
            ws.send(cfg.toString())
            Log.i(TAG, "sent handshake (model=$model)")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            val msg = try { JSONObject(text) } catch (e: Throwable) {
                Log.w(TAG, "non-JSON message: $text"); return
            }
            if (msg.has("uid") && msg.optString("uid") != uid) return

            when {
                msg.has("status") ->
                    Log.i(TAG, "status: ${msg.optString("status")} ${msg.optString("message")}")
                msg.optString("message") == "SERVER_READY" -> {
                    serverReady = true
                    // Fresh server session: its segment timestamps restart at 0.
                    consumedEnd = 0.0; maxEndSeen = 0.0
                    Log.i(TAG, "SERVER_READY (backend=${msg.optString("backend")})")
                    if (running) startCapture(ws)
                }
                msg.optString("message") == "DISCONNECT" ->
                    Log.i(TAG, "server DISCONNECT")
                msg.has("language") ->
                    Log.i(TAG, "detected lang=${msg.optString("language")} " +
                        "p=${msg.optDouble("language_prob", 0.0)}")
                msg.has("segments") -> if (!muted) handleSegments(msg.getJSONArray("segments"))
            }
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) { /* server sends text only */ }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            try { ws.close(1000, null) } catch (_: Throwable) {}
        }

        // A clean server-initiated close (e.g. WhisperLive's max_connection_time
        // overtime) lands here, NOT onFailure -- reconnect from both.
        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            scheduleReconnect("closed code=$code")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure (code=${response?.code}): ${t.message}", t)
            scheduleReconnect("failure: ${t.message}")
        }
    }

    /**
     * Reopen the socket while we're still meant to be listening. Deduped so a
     * failure+close pair doesn't spawn two reconnects. The persistent record
     * thread automatically streams to the new socket (it reads the webSocket
     * field), so we don't touch capture here.
     */
    @Volatile private var reconnecting = false
    private fun scheduleReconnect(reason: String) {
        if (!running || reconnecting) return
        reconnecting = true
        serverReady = false
        Thread {
            try { Thread.sleep(RECONNECT_DELAY_MS) } catch (_: InterruptedException) {}
            if (running) {
                Log.i(TAG, "reconnecting ($reason)")
                webSocket = client.newWebSocket(
                    Request.Builder().url("$baseWsUrl/").build(), SocketListener()
                )
            }
            reconnecting = false
        }.apply { isDaemon = true; start() }
    }

    /**
     * Rebuild [completed]/[partial] from the server's current segment list,
     * keeping only segments newer than [consumedEnd] (i.e. this utterance). We
     * REPLACE rather than accumulate, since the server resends an authoritative
     * rolling list each message -- accumulating would duplicate text.
     */
    private fun handleSegments(segs: JSONArray) {
        val before = currentText()
        synchronized(lock) {
            completed.clear()
            partial = ""
            for (i in 0 until segs.length()) {
                val s = segs.optJSONObject(i) ?: continue
                val t = s.optString("text").trim()
                if (t.isEmpty()) continue
                val end = s.optDouble("end", 0.0)
                if (end > maxEndSeen) maxEndSeen = end
                if (end <= consumedEnd) continue   // already emitted in a prior utterance
                if (s.optBoolean("completed", false)) {
                    completed.add(t)
                } else if (i == segs.length() - 1) {
                    partial = t
                }
            }
        }
        val live = currentText()
        if (live.isNotEmpty() && live != before) {
            utteranceActive = true
            lastActivityMs = System.currentTimeMillis()
            onPartial(live)
        }
    }

    private fun currentText(): String = synchronized(lock) {
        (completed + if (partial.isNotEmpty()) listOf(partial) else emptyList())
            .joinToString(" ").trim()
    }

    companion object {
        private const val TAG = "MabuRemoteASR"
        private const val SAMPLE_RATE = 16000
        // ~128 ms of int16 mono audio per frame (2048 samples * 2 bytes).
        private const val FRAME_BYTES = 4096
        private const val END_OF_AUDIO = "END_OF_AUDIO"
        // Quiet gap after which an utterance is considered finished.
        private const val SILENCE_MS = 800L
        private const val ENDPOINT_POLL_MS = 150L
        private const val RECONNECT_DELAY_MS = 1500L
    }
}
