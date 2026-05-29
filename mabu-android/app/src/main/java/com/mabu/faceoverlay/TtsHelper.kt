package com.mabu.faceoverlay

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Thin wrapper around Android's TextToSpeech. On the Mabu only Pico TTS
 * is installed (com.svox.pico) -- works, sounds robotic. Good enough for
 * a first proof of voice. Can swap to a nicer engine later if installed.
 */
class TtsHelper(context: Context) {

    @Volatile var ready: Boolean = false
        private set

    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = (status == TextToSpeech.SUCCESS)
        Log.i(TAG, "TTS init status=$status (ready=$ready)")
    }

    init {
        // Set language eagerly. If the engine isn't ready yet the call is
        // queued; if it's already ready (race with the listener) it just
        // applies immediately.
        tts.language = Locale.US
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
        tts.speak(clean, queueMode, null, utterId)
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
        return cleaned.take(800).trim()
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        ready = false
    }

    companion object {
        private const val TAG = "MabuTTS"
    }
}
