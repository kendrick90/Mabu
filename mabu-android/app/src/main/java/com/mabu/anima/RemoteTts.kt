package com.mabu.anima

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Streaming TTS client for the Chatterbox server on the PC brain. Replaces the
 * crash-prone on-device Pico. The LLM streams sentences; each is POSTed to the
 * server, which returns raw int16 mono PCM. Synthesis and playback are
 * pipelined on two FIFO queues so sentence N+1 is generated while sentence N
 * plays, while strict order is preserved.
 *
 * Reports speaking start/stop via [onSpeakingChanged] (debounced by playout
 * estimate) so the caller can mute the always-on mic and avoid Mabu hearing
 * itself. Mirrors the TtsHelper.onSpeakingChanged contract.
 *
 * baseUrl example: `http://10.0.0.49:8123`
 */
class RemoteTts(
    private val baseUrl: String,
    private val onSpeakingChanged: ((Boolean) -> Unit)? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var sampleRate = 24000
    @Volatile private var running = true
    @Volatile private var ready = false
    @Volatile private var speaking = false
    @Volatile private var synthInFlight = false
    @Volatile private var playoutEndsAtMs = 0L

    private val synthQueue = LinkedBlockingQueue<String>()
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private var audioTrack: AudioTrack? = null

    private val synthThread: Thread
    private val playThread: Thread

    init {
        // Probe the server for its sample rate; default 24k until known.
        Thread {
            try {
                val resp = client.newCall(Request.Builder().url("$baseUrl/health").build()).execute()
                resp.use {
                    val body = it.body?.string().orEmpty()
                    val sr = JSONObject(body).optInt("sample_rate", 24000)
                    if (sr > 0) sampleRate = sr
                    Log.i(TAG, "health ok: sample_rate=$sampleRate")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "health probe failed (${e.message}); assuming $sampleRate Hz")
            }
            ready = true
        }.apply { isDaemon = true; start() }

        synthThread = Thread { synthLoop() }.apply { isDaemon = true; start() }
        playThread = Thread { playLoop() }.apply { isDaemon = true; start() }
    }

    /** Queue a sentence to be spoken (FIFO). */
    fun speak(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        setSpeaking(true)
        synthQueue.put(t)
    }

    /** Barge-in / interrupt: drop everything pending and stop playback. */
    fun stop() {
        synthQueue.clear()
        playQueue.clear()
        try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Throwable) {}
        playoutEndsAtMs = 0L
        setSpeaking(false)
    }

    fun release() {
        running = false
        stop()
        synthThread.interrupt(); playThread.interrupt()
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
    }

    private fun synthLoop() {
        while (running) {
            val text = try { synthQueue.poll(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
            synthInFlight = true
            try {
                val pcm = synthesize(text)
                if (pcm != null && pcm.isNotEmpty()) playQueue.put(pcm)
            } catch (e: Throwable) {
                Log.e(TAG, "synth failed: ${e.message}")
            } finally {
                synthInFlight = false
            }
        }
    }

    private fun synthesize(text: String): ByteArray? {
        val body = JSONObject().apply { put("text", text) }
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$baseUrl/tts").post(body).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Log.e(TAG, "tts HTTP ${resp.code}"); return null }
            return resp.body?.bytes()
        }
    }

    private fun playLoop() {
        while (running) {
            val pcm = try { playQueue.poll(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
            if (pcm != null) {
                val track = ensureTrack()
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                writeFully(track, pcm)
                val durMs = (pcm.size / 2) * 1000L / sampleRate
                playoutEndsAtMs = maxOf(System.currentTimeMillis(), playoutEndsAtMs) + durMs
            } else {
                // Idle: if nothing is queued or in flight and the buffered audio
                // has played out, the turn is over -- re-open the mic.
                if (speaking && synthQueue.isEmpty() && !synthInFlight && playQueue.isEmpty()
                    && System.currentTimeMillis() >= playoutEndsAtMs) {
                    setSpeaking(false)
                }
            }
        }
    }

    private fun writeFully(track: AudioTrack, pcm: ByteArray) {
        var off = 0
        while (off < pcm.size && running) {
            val n = track.write(pcm, off, pcm.size - off)
            if (n <= 0) break
            off += n
        }
    }

    private fun ensureTrack(): AudioTrack {
        val existing = audioTrack
        if (existing != null) return existing
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate)) // ~0.5s buffer
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        Log.i(TAG, "AudioTrack ready @ ${sampleRate}Hz")
        return track
    }

    private fun setSpeaking(value: Boolean) {
        if (speaking == value) return
        speaking = value
        Log.i(TAG, if (value) "speaking" else "done")
        onSpeakingChanged?.invoke(value)
    }

    companion object {
        private const val TAG = "MabuRemoteTTS"
    }
}
