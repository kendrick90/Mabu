package com.mabu.anima

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Streaming LLM client for llama.cpp's `llama-server` (OpenAI-compatible
 * `/v1/chat/completions` with `stream: true`). Tokens stream back as SSE
 * events; we accumulate them and fire whole sentences at the caller so
 * the TTS can start speaking before the model finishes generating.
 *
 * baseUrl example: `http://10.0.0.49:8080`
 */
class StreamingLlama(
    private val baseUrl: String,
    private val systemPrompt: String,
    /** Conversation history. Each chat() appends a user+assistant pair. */
    private val history: MutableList<Pair<String, String>> = mutableListOf()
) {

    interface Listener {
        /** A complete sentence has been assembled and is ready to speak. */
        fun onSentence(sentence: String, isFirst: Boolean) {}
        /** Individual token chunk -- useful for live captions. */
        fun onToken(token: String) {}
        /** Stream finished cleanly; full reply text included for history. */
        fun onDone(fullText: String) {}
        fun onError(e: Throwable) {}
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // SSE keeps the socket open
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private var currentSource: EventSource? = null

    /** Cancel any in-flight stream. */
    fun cancel() {
        currentSource?.cancel()
        currentSource = null
    }

    fun chat(userMessage: String, listener: Listener, maxTokens: Int = 256) {
        cancel()

        // Build messages array: system + history pairs + new user turn.
        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            for ((u, a) in history) {
                put(JSONObject().apply { put("role", "user");      put("content", u) })
                put(JSONObject().apply { put("role", "assistant"); put("content", a) })
            }
            put(JSONObject().apply { put("role", "user"); put("content", userMessage) })
        }

        val body = JSONObject().apply {
            put("messages", messages)
            put("stream", true)
            put("max_tokens", maxTokens)
            put("temperature", 0.8)
            put("top_p", 0.95)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .post(body)
            .addHeader("Accept", "text/event-stream")
            .build()

        val buf = StringBuilder()
        val full = StringBuilder()
        var sentenceCount = 0

        val l = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    // Flush any trailing fragment as a final sentence.
                    if (buf.isNotBlank()) {
                        val tail = buf.toString().trim()
                        buf.setLength(0)
                        if (tail.isNotEmpty()) {
                            sentenceCount++
                            listener.onSentence(tail, isFirst = sentenceCount == 1)
                        }
                    }
                    val fullText = full.toString().trim()
                    if (fullText.isNotEmpty()) {
                        history.add(userMessage to fullText)
                        if (history.size > MAX_HISTORY_TURNS) history.removeAt(0)
                    }
                    listener.onDone(fullText)
                    return
                }
                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices") ?: return
                    if (choices.length() == 0) return
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return
                    // The first delta is typically {"role":"assistant","content":null}.
                    // org.json's optString returns the literal "null" for an explicit
                    // JSON null (the default is only used for missing keys), so guard it.
                    if (delta.isNull("content")) return
                    val token = delta.optString("content", "")
                    if (token.isEmpty()) return
                    full.append(token)
                    buf.append(token)
                    listener.onToken(token)
                    while (true) {
                        val s = extractSentence(buf) ?: break
                        sentenceCount++
                        listener.onSentence(s, isFirst = sentenceCount == 1)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "parse failed for: $data", e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val msg = "SSE failure (code=${response?.code}): ${t?.message}"
                Log.e(TAG, msg, t)
                listener.onError(t ?: RuntimeException(msg))
            }

            override fun onClosed(eventSource: EventSource) {
                currentSource = null
            }
        }
        currentSource = EventSources.createFactory(client).newEventSource(request, l)
    }

    fun clearHistory() {
        history.clear()
    }

    /**
     * Extract the first complete sentence from [buf] if one exists, removing
     * it from the buffer. A sentence ends at `.`, `!`, or `?` followed by
     * whitespace. If the terminator is the last char in the buffer we wait
     * for more data (it might be a number like `3.14` mid-sentence).
     */
    private fun extractSentence(buf: StringBuilder): String? {
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            if ((c == '.' || c == '!' || c == '?') && i + 1 < buf.length) {
                val nxt = buf[i + 1]
                if (nxt.isWhitespace() || nxt == '\n') {
                    val sentence = buf.substring(0, i + 1).trim()
                    buf.delete(0, i + 2)
                    if (sentence.length >= MIN_SENTENCE_CHARS) return sentence
                }
            }
            i++
        }
        return null
    }

    companion object {
        private const val TAG = "MabuStreamLLM"
        private const val MAX_HISTORY_TURNS = 10
        private const val MIN_SENTENCE_CHARS = 3
    }
}
