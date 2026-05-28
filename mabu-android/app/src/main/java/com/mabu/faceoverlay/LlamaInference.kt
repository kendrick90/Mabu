package com.mabu.faceoverlay

/**
 * Kotlin wrapper for the llama.cpp JNI bridge. Single-instance because the
 * tablet only has one CPU's worth of inference budget anyway. All calls
 * are synchronous -- run them off the main thread.
 */
object LlamaInference {
    init { System.loadLibrary("mabuserial") }

    /** Whether llama.cpp was actually compiled in (vs stubbed). */
    @JvmStatic external fun nativeAvailable(): Boolean

    /** Load a GGUF model. Returns false on any failure (logged via android log). */
    @JvmStatic external fun nativeLoadModel(
        path: String, ctxSize: Int, nThreads: Int,
        temperature: Float, topK: Int, topP: Float
    ): Boolean

    /** Run a single completion. KV cache is cleared each call (no chat history yet). */
    @JvmStatic external fun nativeGenerate(prompt: String, maxTokens: Int): String

    @JvmStatic external fun nativeRelease()

    // Kotlin-side wrappers --------------------------------------------------

    @Volatile var isLoaded: Boolean = false
        private set

    fun load(
        path: String,
        ctxSize: Int = 1024,
        threads: Int = 4,
        temperature: Float = 0.8f,
        topK: Int = 40,
        topP: Float = 0.95f
    ): Boolean {
        if (isLoaded) nativeRelease()
        isLoaded = nativeLoadModel(path, ctxSize, threads, temperature, topK, topP)
        return isLoaded
    }

    fun generate(prompt: String, maxTokens: Int = 128): String {
        if (!isLoaded) return ""
        return nativeGenerate(prompt, maxTokens)
    }

    fun release() {
        if (isLoaded) {
            nativeRelease()
            isLoaded = false
        }
    }
}
