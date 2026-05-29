package com.mabu.anima

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thin wrapper around Android's TextToSpeech. On the Mabu only Pico TTS
 * is installed (com.svox.pico) -- works, sounds robotic. Good enough for
 * a first proof of voice. Can swap to a nicer engine later if installed.
 */
class TtsHelper(context: Context) {

    @Volatile var ready: Boolean = false
        private set

    /**
     * Notified when speech starts (true) and fully finishes (false). The
     * "false" edge is debounced by [DRAIN_DEBOUNCE_MS] so the brief gap between
     * consecutive queued sentences (streaming TTS) doesn't flap it. Used to
     * gate the always-on mic so Mabu doesn't transcribe its own voice.
     */
    @Volatile var onSpeakingChanged: ((Boolean) -> Unit)? = null

    @Volatile var isSpeaking: Boolean = false
        private set

    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    // Count of utterances queued-but-not-yet-finished. Goes >0 the moment we
    // queue speech and back to 0 once the queue drains.
    private val pending = AtomicInteger(0)

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = (status == TextToSpeech.SUCCESS)
        Log.i(TAG, "TTS init status=$status (ready=$ready)")
    }

    init {
        // Set language eagerly. If the engine isn't ready yet the call is
        // queued; if it's already ready (race with the listener) it just
        // applies immediately.
        tts.language = Locale.US
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onUtteranceBegan()
            override fun onDone(utteranceId: String?) = onUtteranceEnded()
            @Deprecated("deprecated in API 21") override fun onError(utteranceId: String?) = onUtteranceEnded()
            override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceEnded()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = onUtteranceEnded()
        })
    }

    private fun onUtteranceBegan() {
        if (!isSpeaking) {
            isSpeaking = true
            handler.post { onSpeakingChanged?.invoke(true) }
        }
    }

    private fun onUtteranceEnded() {
        // Decrement; when the queue is empty, debounce before declaring done so
        // a tiny inter-sentence gap doesn't briefly unmute the mic.
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            handler.postDelayed({
                if (pending.get() == 0 && isSpeaking) {
                    isSpeaking = false
                    onSpeakingChanged?.invoke(false)
                }
            }, DRAIN_DEBOUNCE_MS)
        }
    }

    /**
     * Apply a volume to the STREAM_MUSIC level (which is what Pico actually
     * respects, since it ignores TextToSpeech's KEY_PARAM_VOLUME). Call when
     * the slider changes; physical volume buttons on the device still work
     * naturally between calls.
     */
    fun applyVolume(volume: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (max * volume.coerceIn(0f, 1f)).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        Log.i(TAG, "applyVolume $volume -> stream level $level/$max")
    }

    fun speak(text: String, volume: Float = -1f, queueAdd: Boolean = false) {
        if (!ready) {
            Log.w(TAG, "TTS not ready, dropping: $text")
            return
        }
        val clean = sanitize(text)
        if (clean.isBlank()) {
            Log.w(TAG, "sanitized text empty, dropping")
            return
        }
        if (volume >= 0f) applyVolume(volume)
        val queueMode = if (queueAdd) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        // Utterance IDs need to be unique when queueing -- otherwise Pico
        // can drop subsequent fragments thinking they're duplicates.
        val utterId = "mabu-${System.nanoTime()}"
        pending.incrementAndGet()
        val rc = tts.speak(clean, queueMode, null, utterId)
        if (rc != TextToSpeech.SUCCESS) onUtteranceEnded()  // never started; undo
    }

    /**
     * Strip anything Pico's UTF-8 string handling chokes on. Pico is from
     * 2009 and has known SIGSEGVs in picobase_get_next_utf8char on emoji,
     * smart quotes, em/en dashes, non-breaking spaces, and very long
     * inputs. The LLM output is the main offender. Replace common
     * substitutes with ASCII, then drop anything outside printable ASCII.
     */
    private fun sanitize(text: String): String {
        val replaced = text
            .replace('‘', '\'').replace('’', '\'') // smart single quotes
            .replace('“', '"').replace('”', '"')   // smart double quotes
            .replace('–', '-').replace('—', '-')   // en / em dash
            .replace('…', '.')                          // horizontal ellipsis
            .replace(' ', ' ')                          // nbsp
            .replace("\r\n", " ").replace('\n', ' ').replace('\t', ' ')
        // Keep only printable ASCII (0x20..0x7E). Drops emoji, control
        // chars, anything else exotic.
        val cleaned = buildString(replaced.length) {
            for (c in replaced) if (c.code in 0x20..0x7E) append(c)
        }
        // Pico has internal buffer limits; long inputs can trip them.
        val trimmed = cleaned.take(800).trim()
        if (trimmed.isEmpty()) return ""
        // picobase_get_next_utf8char over-reads one token past the end of the
        // buffer (SIGSEGV when the byte there isn't valid UTF-8). A trailing
        // space gives the tokenizer a safe boundary and dodges the crash.
        return "$trimmed "
    }

    fun stop() {
        tts.stop()
        // onStop callbacks may or may not fire for flushed utterances depending
        // on the engine; reset deterministically so the counter can't drift.
        pending.set(0)
        if (isSpeaking) {
            isSpeaking = false
            handler.post { onSpeakingChanged?.invoke(false) }
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        ready = false
    }

    companion object {
        private const val TAG = "MabuTTS"
        // Grace period after the queue drains before declaring speech done,
        // covering inter-sentence gaps in streaming TTS + speaker drain time.
        private const val DRAIN_DEBOUNCE_MS = 500L
    }
}
