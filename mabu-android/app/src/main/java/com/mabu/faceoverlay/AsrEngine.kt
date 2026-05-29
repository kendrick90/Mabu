package com.mabu.faceoverlay

import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Offline ASR via Vosk. The model is expected on disk at [modelPath]
 * (a directory containing am/, conf/, graph/, ivector/, ...). The
 * vosk-android library handles AudioRecord internally via SpeechService.
 *
 * Call [startListening] on push-to-talk down, [stopListening] on release.
 * Transcripts are emitted to [onFinal] (one per recognition session) and
 * partials can be observed via [onPartial] for live caption-style UX.
 */
class AsrEngine(
    modelPath: String,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit = {}
) : RecognitionListener {

    @Volatile var isReady: Boolean = false
        private set
    @Volatile var isListening: Boolean = false
        private set

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    init {
        try {
            model = Model(modelPath)
            recognizer = Recognizer(model, SAMPLE_RATE)
            isReady = true
            Log.i(TAG, "Vosk ready from $modelPath")
        } catch (e: Throwable) {
            Log.e(TAG, "Vosk init failed", e)
        }
    }

    fun startListening() {
        if (!isReady) {
            Log.w(TAG, "startListening: not ready")
            return
        }
        if (isListening) return
        try {
            val r = recognizer ?: return
            // Lazy-construct SpeechService once and reuse. Vosk's native
            // code crashes if you new/shutdown around it per press.
            if (speechService == null) {
                speechService = SpeechService(r, SAMPLE_RATE)
            }
            speechService?.startListening(this)
            isListening = true
            Log.i(TAG, "listening")
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission missing", e)
        } catch (e: Throwable) {
            Log.e(TAG, "startListening failed", e)
        }
    }

    fun stopListening() {
        if (!isListening) return
        try {
            speechService?.stop()
        } catch (_: Throwable) {}
        // Don't call recognizer.reset() here -- on this device it races
        // with a still-in-flight audio callback and crashes Vosk's worker
        // thread with a corrupted-size SIGSEGV in memcpy. SpeechService.stop()
        // tears down its own threads; the recognizer's state will be
        // overwritten by the next startListening() session anyway.
        isListening = false
        Log.i(TAG, "stopped")
    }

    fun release() {
        stopListening()
        try { speechService?.shutdown() } catch (_: Throwable) {}
        speechService = null
        try { recognizer?.close() } catch (_: Throwable) {}
        try { model?.close() } catch (_: Throwable) {}
        recognizer = null
        model = null
        isReady = false
    }

    // --- Vosk RecognitionListener ---

    override fun onPartialResult(hypothesis: String?) {
        val text = textFromJson(hypothesis, key = "partial") ?: return
        if (text.isNotBlank()) onPartial(text)
    }

    override fun onResult(hypothesis: String?) {
        val text = textFromJson(hypothesis, key = "text") ?: return
        if (text.isNotBlank()) {
            Log.i(TAG, "result: $text")
            onFinal(text)
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        val text = textFromJson(hypothesis, key = "text") ?: return
        if (text.isNotBlank()) {
            Log.i(TAG, "final: $text")
            onFinal(text)
        }
    }

    override fun onError(e: Exception?) {
        Log.e(TAG, "ASR error", e)
        isListening = false
    }

    override fun onTimeout() {
        Log.i(TAG, "ASR timeout")
        isListening = false
    }

    /** Vosk JSON results look like {"text":"hello"} or {"partial":"hello"} */
    private fun textFromJson(json: String?, key: String): String? {
        if (json == null) return null
        val m = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json) ?: return null
        return m.groupValues[1]
    }

    companion object {
        private const val TAG = "MabuASR"
        private const val SAMPLE_RATE = 16000f
    }
}
