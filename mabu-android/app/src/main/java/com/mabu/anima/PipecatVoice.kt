package com.mabu.anima

import ai.pipecat.client.PipecatClient
import ai.pipecat.client.PipecatClientOptions
import ai.pipecat.client.PipecatEventCallbacks
import ai.pipecat.client.small_webrtc_transport.SmallWebRTCTransport
import ai.pipecat.client.small_webrtc_transport.SmallWebRTCTransportConnectParams
import ai.pipecat.client.types.APIRequest
import ai.pipecat.client.types.BotReadyData
import ai.pipecat.client.types.Transcript
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import android.content.Context
import android.util.Log

/**
 * Thin wrapper around the Pipecat Android SDK (SmallWebRTC transport, 1.1.0).
 *
 * Replaces the legacy RemoteAsr + RemoteTts + StreamingLlama trio. The PC-side
 * Pipecat pipeline (pc-brain/pipecat_bot.py) now owns VAD, semantic turn-taking,
 * barge-in, STT, LLM and TTS over a single WebRTC session; WebRTC's AEC removes
 * Mabu's own voice from the mic. So the device-side endpointing hacks (800 ms
 * silence debounce, consumedEnd filtering, the echo-guard mute + watchdog,
 * reconnect plumbing) all go away -- the SDK owns mic capture, the speaker, AEC
 * and the data channel. We expose only the hooks Anima's reflex/UI layer needs:
 *
 *   - connect() / disconnect() / release() lifecycle
 *   - setMuted() for the manual mute button (toggles the outbound mic track)
 *   - a [Listener] for transcripts + speaking state (drives the speech bubble
 *     and the mic-status line) and server messages (the PC->device control
 *     channel for agentic tools, wired in a later phase).
 *
 * IMPORTANT: PipecatClient captures the *constructing* thread for all its
 * callbacks and operations (ThreadRef.forCurrent()). Construct this and call
 * its methods from the main thread (which has a Looper). Listener callbacks are
 * therefore delivered on the main thread.
 */
class PipecatVoice(
    context: Context,
    private val offerUrl: String,
    private val listener: Listener,
    enableMic: Boolean = true,
) {
    /** All callbacks are delivered on the thread that constructed PipecatVoice. */
    interface Listener {
        /** WebRTC session established. */
        fun onConnected() {}
        /** Session torn down (graceful disconnect or peer left). */
        fun onDisconnected() {}
        /** A transcript of the user's speech. [isFinal] false = interim. */
        fun onUserTranscript(text: String, isFinal: Boolean) {}
        fun onUserStartedSpeaking() {}
        fun onUserStoppedSpeaking() {}
        fun onBotStartedSpeaking() {}
        fun onBotStoppedSpeaking() {}
        /** PC->device control message over the data channel (agentic tools). */
        fun onServerMessage(data: Value) {}
        fun onError(message: String) {}
    }

    private val callbacks = object : PipecatEventCallbacks() {
        override fun onConnected() {
            Log.i(TAG, "connected")
            listener.onConnected()
        }

        override fun onDisconnected() {
            Log.i(TAG, "disconnected")
            listener.onDisconnected()
        }

        override fun onTransportStateChanged(state: TransportState) {
            Log.i(TAG, "transport state: $state")
        }

        override fun onBotReady(data: BotReadyData) {
            Log.i(TAG, "bot ready")
        }

        override fun onUserTranscript(data: Transcript) {
            listener.onUserTranscript(data.text, data.final)
        }

        override fun onUserStartedSpeaking() = listener.onUserStartedSpeaking()
        override fun onUserStoppedSpeaking() = listener.onUserStoppedSpeaking()
        override fun onBotStartedSpeaking() = listener.onBotStartedSpeaking()
        override fun onBotStoppedSpeaking() = listener.onBotStoppedSpeaking()

        override fun onServerMessage(data: Value) {
            Log.i(TAG, "server message: $data")
            listener.onServerMessage(data)
        }

        override fun onBackendError(message: String) {
            Log.e(TAG, "backend error: $message")
            listener.onError(message)
        }
    }

    private val transport = SmallWebRTCTransport(context.applicationContext)

    private val client: PipecatClient<SmallWebRTCTransport, SmallWebRTCTransportConnectParams> =
        PipecatClient(
            transport,
            PipecatClientOptions(
                callbacks = callbacks,
                enableMic = enableMic,
                enableCam = false,
            )
        )

    /**
     * Open the WebRTC session to the PC brain's offer endpoint. The transport
     * builds and POSTs the SDP offer; WebRTCClient owns mic capture and playback.
     * Call on the main thread.
     */
    fun connect() {
        client.connect(
            SmallWebRTCTransportConnectParams(
                webrtcRequestParams = APIRequest(
                    endpoint = offerUrl,
                    requestData = Value.Object(),
                )
            )
        ).logError(TAG, "connect")
    }

    /**
     * Manual mute. Enables/disables the outbound mic track -- AEC already keeps
     * Mabu's own voice out, so this is purely "stop listening", not echo guard.
     */
    fun setMuted(muted: Boolean) {
        client.enableMic(!muted).logError(TAG, "enableMic")
    }

    val isMicEnabled: Boolean
        get() = client.isMicEnabled

    /**
     * Inject text as if the user had said it (debug SAY path). Appends to the
     * LLM context and triggers a bot turn -- no mic needed.
     */
    fun sendText(text: String) {
        client.sendText(text).logError(TAG, "sendText")
    }

    /** Tear down the WebRTC session but keep the client reusable. */
    fun disconnect() {
        client.disconnect().logError(TAG, "disconnect")
    }

    /** Destroy the client and free WebRTC resources. Call on the main thread. */
    fun release() {
        try {
            client.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release failed", t)
        }
    }

    companion object {
        private const val TAG = "PipecatVoice"
    }
}
