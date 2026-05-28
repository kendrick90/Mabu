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

    fun speak(text: String, volume: Float = -1f) {
        if (!ready) {
            Log.w(TAG, "TTS not ready, dropping: $text")
            return
        }
        if (volume >= 0f) applyVolume(volume)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mabu-utterance")
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
