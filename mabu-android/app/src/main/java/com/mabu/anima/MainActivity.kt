package com.mabu.anima

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var overlayView: FaceOverlayView
    private lateinit var settingsPanel: SettingsPanel
    private lateinit var volLevelView: TextView
    private var cameraSource: Camera1Source? = null
    private val motors = MabuMotors()
    private val handler = Handler(Looper.getMainLooper())
    private val attention = AttentionTracker()
    private val tts by lazy { TtsHelper(this) }
    private var asr: AsrEngine? = null
    private var remoteAsr: RemoteAsr? = null
    private var remoteTts: RemoteTts? = null
    private var pipecatVoice: PipecatVoice? = null
    // Pipecat status tracking, so the mic line reflects REALITY (not just "mic on").
    @Volatile private var pipecatReachable = false      // bot TCP port answering
    @Volatile private var pipecatSessionDead = false    // dropped after connecting -> needs restart
    private var pipecatState: ai.pipecat.client.types.TransportState? = null
    private var botSpeaking = false
    private var lastPipecatConnectMs = 0L              // throttle auto-reconnect attempts
    // Debounced "bot finished speaking" -> drop to listening only after a real
    // pause, so the status doesn't flicker between a reply's TTS sentences.
    private val botSpeakingOff = Runnable {
        botSpeaking = false
        overlayView.setHeardText(null)
        updatePipecatStatus()
    }
    private lateinit var micButton: TextView
    private var muteButton: TextView? = null

    // Perf HUD pinned top-left. Visible by default; long-press to hide for the
    // session. Refreshed at 2 Hz from DeviceStats.
    private var hudView: TextView? = null
    private var hudVisible = true
    private val hudTick = object : Runnable {
        override fun run() {
            hudView?.let { tv ->
                val s = DeviceStats.snapshot(this@MainActivity)
                tv.text = buildString {
                    append("mlkit ").append("%.1f".format(s.mlkitFps)).append(" fps  ")
                    append("mean ").append("%.0f".format(s.mlkitMeanMs)).append("ms  ")
                    append("max ").append("%.0f".format(s.mlkitMaxMs)).append("ms\n")
                    append("bbox  ").append("%.1f".format(s.bboxFps)).append(" fps  ")
                    append("mean ").append("%.0f".format(s.bboxMeanMs)).append("ms\n")
                    append("video ").append(s.videoSendingFps).append(" fps  ")
                    append(s.videoSendingKbps).append(" kbps  ")
                    append("rtt ").append(s.videoRttMs).append("ms\n")
                    append("xport ").append(s.transportState)
                    append("  mic ").append(if (s.micEnabled) "on" else "off")
                    append("  cam ").append(if (s.camEnabled) "on" else "off")
                    append("  motor ").append(if (s.motorLinkOpen) "ok" else "--").append('\n')
                    append("mode ").append(s.mode)
                    append("  heap ").append(s.heapUsedMb).append('/').append(s.heapMaxMb).append("MB")
                    if (s.batteryPct >= 0) {
                        append("  batt ").append(s.batteryPct).append('%')
                        if (!s.batteryTempC.isNaN()) append(" ").append("%.0f".format(s.batteryTempC)).append("°C")
                    }
                    append("  up ").append(s.uptimeSec).append('s')
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    // Mute has two INDEPENDENT sources: manual (user tapped mute — sticky) and
    // auto (echo guard while a reply is in progress — transient). Effective mute
    // = either. Kept separate so a finished reply can't clear a manual mute.
    @Volatile private var manualMute = false
    @Volatile private var responseActive = false

    // Safety net: if TTS never reports "done" (e.g. Pico SIGSEGVs mid-speech),
    // the echo-guard mute would stick and Mabu would go deaf. This re-opens the
    // mic after a generous timeout; it's cancelled on a normal speech-done.
    private val safetyUnmute = Runnable {
        Log.w(TAG, "TTS watchdog fired: re-opening mic (TTS likely crashed mid-speech)")
        responseActive = false
        applyMute()
        if (tuning.cognitionMode == "streaming") micButton.text = if (manualMute) "🔇 muted" else "🎤 listening…"
    }
    private var streamingLlm: StreamingLlama? = null
    private var statusServer: StatusServer? = null

    // Debug control receiver -- lets a host drive the app over ADB without
    // touching the screen. See registerDebugReceiver() for the action set.
    private var debugReceiver: BroadcastReceiver? = null

    private val tuning = TuningSettings()

    @Volatile private var mode = Mode.FOLLOW

    // Detection-side EMA on face center (FOLLOW).
    private var fxSmooth = 0.5f
    private var fySmooth = 0.5f

    // Calibration captured by long-press / settings button.
    private var calibCenterX = 0.5f
    private var calibCenterY = 0.5f

    // Eye target/current (gaze tween). FOLLOW computes effective target
    // each tick from followX/Y + saccade + glance offsets.
    @Volatile private var targetX = 0.5f
    @Volatile private var targetY = 0.5f
    @Volatile private var followX = 0.5f
    @Volatile private var followY = 0.5f
    @Volatile private var saccadeOffsetX = 0f
    @Volatile private var saccadeOffsetY = 0f
    @Volatile private var glanceOffsetX = 0f
    @Volatile private var glanceOffsetY = 0f
    // PUPPET eye-gaze input filter: the pupil dark-cluster heuristic is noisy
    // frame-to-frame, so we low-pass the raw offset here (input smoothing,
    // separate from the output tween) before mapping it to an eye target.
    private var pupilFiltX = 0f
    private var pupilFiltY = 0f
    private var pupilFiltInit = false
    private var currentX = 0.5f
    private var currentY = 0.5f
    private var lastSentX = 0.5f
    private var lastSentY = 0.5f

    // Neck target/current.
    @Volatile private var targetNeckRot = 50f
    @Volatile private var targetNeckElev = 50f
    @Volatile private var targetNeckTilt = 50f
    private var currentNeckRot = 50f
    private var currentNeckElev = 50f
    private var currentNeckTilt = 50f
    private var lastSentNeckRot = 50f
    private var lastSentNeckElev = 50f
    private var lastSentNeckTilt = 50f

    // Eyelid mirroring. lastLdlValue/lastLdrValue are the rendered "current"
    // lid positions tweened by renderEyelids at the 25 Hz tick; the per-eye
    // closed state + blink-hold deadline are set by the Schmitt-trigger
    // detector (maybeMirrorEyelids) at the camera rate. Per side so a wink
    // mirrors one robot eye. (Main-thread only: both the face callback and the
    // tick run on the main looper, so these need no synchronization.)
    private var lastLdlValue = MabuMotors.EYELID_NEUTRAL
    private var lastLdrValue = MabuMotors.EYELID_NEUTRAL
    // Smoothed per-eye openness (0=shut..1=open) for partial closure; blink-
    // hold deadlines force a full closure through a fast blink.
    private var eyeOpenSmoothL = 1f
    private var eyeOpenSmoothR = 1f
    private var eyeOpenInit = false
    private var blinkHoldLUntil = 0L
    private var blinkHoldRUntil = 0L

    private var lastFaceSeenMs = 0L
    private var lastOverlayFaceMs = 0L
    private var gazeLogCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("tuning", Context.MODE_PRIVATE)
        tuning.load(prefs)

        val root = FrameLayout(this)
        rootLayout = root
        textureView = TextureView(this)
        overlayView = FaceOverlayView(this)
        val full = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        root.addView(textureView, full)
        root.addView(overlayView, full)
        textureView.scaleX = -1f
        root.setOnClickListener {
            if (settingsPanel.visibility == android.view.View.VISIBLE) {
                settingsPanel.visibility = android.view.View.GONE
            } else {
                setMode(mode.next())
            }
        }

        // Settings panel (right side, 45 % of screen width)
        settingsPanel = SettingsPanel(this, tuning,
            onChanged = {
                tuning.save(prefs)
                // Apply the latest volume immediately on any slider move.
                // Cheap and idempotent; lets the slider feel live.
                tts.applyVolume(tuning.ttsVolume)
            },
            onCalibrate = { calibrateCenter() },
            onModeSelected = { setMode(it) },
            currentMode = { mode },
            onSpeak = { tts.speak(it) }
        )
        root.addView(settingsPanel, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.45f).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END
        ))

        // Settings gear button (top-right). Single glyph; styling kept
        // small/subtle so it doesn't fight with the camera preview.
        val gearBtn = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(22, 10, 22, 12)
            setOnClickListener { settingsPanel.toggle() }
        }
        val gearLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        gearLp.setMargins(0, 24, 24, 0)
        root.addView(gearBtn, gearLp)

        // Always-visible volume controls under the gear (no physical
        // rocker on this tablet, so we have to provide one).
        root.addView(buildVolumePanel(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(0, 110, 24, 0) })

        // Perf HUD top-left. Tap-through disabled only for long-press toggle.
        hudView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.argb(220, 180, 255, 180))
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(12, 8, 12, 8)
            setOnLongClickListener {
                hudVisible = !hudVisible
                visibility = if (hudVisible) android.view.View.VISIBLE else android.view.View.GONE
                true
            }
        }
        root.addView(hudView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply { setMargins(16, 24, 0, 0) })
        handler.post(hudTick)

        statusServer = StatusServer(applicationContext, object : StatusServer.Hooks {
            override fun configJson(): org.json.JSONObject = org.json.JSONObject().apply {
                put("eyeGazeGain", tuning.eyeGazeGain)
                put("eyeGazeInputAlpha", tuning.eyeGazeInputAlpha)
                put("eyeGazeDeadband", tuning.eyeGazeDeadband)
                put("smoothAlphaEyes", tuning.smoothAlphaEyes)
                put("smoothAlphaNeck", tuning.smoothAlphaNeck)
                put("neckAngleRange", tuning.neckAngleRange)
                put("eyelidCoupling", tuning.eyelidCoupling)
                put("eyelidWinkOpen", tuning.eyelidWinkOpen)
                put("eyelidCloseLevel", tuning.eyelidCloseLevel)
                put("eyelidOpenInputAlpha", tuning.eyelidOpenInputAlpha)
                put("eyelidBlinkHoldMs", tuning.eyelidBlinkHoldMs)
                put("gazeYOffset", tuning.gazeYOffset)
                put("useEyeGaze", tuning.useEyeGaze)
                put("blinkMethod", tuning.blinkMethod)
            }
            override fun applyConfig(params: Map<String, String>) {
                handler.post {
                    params.forEach { (k, v) -> applyTuning(k, v) }
                    tuning.save(getSharedPreferences("tuning", MODE_PRIVATE))
                    DeviceStats.blinkMethod = tuning.blinkMethod
                    runCatching { settingsPanel.rebuildAfterPreset() }
                }
            }
            override fun setMode(mode: String) {
                handler.post {
                    val m = runCatching { Mode.valueOf(mode.uppercase()) }.getOrNull()
                    if (m != null) this@MainActivity.setMode(m)
                }
            }
        }).also { it.start() }

        // Push-to-talk mic button along the bottom-center.
        micButton = TextView(this).apply {
            text = "🎤 hold to talk"
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(180, 30, 30, 35))
            setPadding(48, 22, 48, 22)
            setOnTouchListener { _, ev ->
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { onMicDown(); true }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> { onMicUp(); true }
                    else -> false
                }
            }
        }
        root.addView(micButton, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { setMargins(0, 0, 0, 24) })

        setContentView(root)
        updateVolumeDisplay()
        registerDebugReceiver()

        // Eager TTS init so the first broadcast / button press doesn't get
        // dropped while Pico is still booting. Also set the persisted
        // volume so the device starts at the right level.
        tts.applyVolume(tuning.ttsVolume)

        val motorOk = motors.open()
        DeviceStats.motorLinkOpen = motorOk
        if (motorOk) {
            motors.restingPose()
            handler.post(gazeTickRunnable)
            handler.postDelayed(blinkRunnable, 2500)
            handler.postDelayed(saccadeRunnable, 1500)
            handler.postDelayed(glanceRunnable, 7000)
        } else {
            Toast.makeText(this, "Motor open failed -- face overlay only", Toast.LENGTH_LONG).show()
        }

        // TEMP (audio-pipeline iteration): start in SLEEP so Mabu sits still
        // (eyelids closed, neck centered, no saccades/glances) while we work on
        // the ASR/TTS path. Revert to Mode.FOLLOW default when done. setMode
        // applies the preset (the mode field still defaults to FOLLOW, so this
        // passes the equality guard).
        setMode(Mode.SLEEP)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO
            )
        }
        if (tuning.cognitionMode == "pipecat") {
            // Single WebRTC session to the PC Pipecat pipeline. The SDK owns mic
            // capture, the speaker, AEC and turn-taking, so none of the device-
            // side ASR/TTS/echo-guard plumbing is constructed in this mode.
            startPipecat()
        } else if (tuning.cognitionMode == "streaming") {
            // Remote brain: ASR (WhisperLive WS) and LLM (llama-server SSE)
            // both live on the PC. Skip the on-device LLM preload AND Vosk --
            // neither is needed, and on 2 GB / 32-bit ARM every MB of VA we
            // don't fragment helps. RemoteAsr construction is instant (no
            // model load); the WS connects lazily on first push-to-talk.
            remoteAsr = RemoteAsr(
                baseWsUrl = tuning.asrServerUrl,
                onFinal = { transcript -> handler.post { onTranscript(transcript) } },
                onPartial = { partial -> handler.post {
                    if (remoteAsr?.muted != true) {
                        micButton.text = "🎤 …$partial"
                        overlayView.setHeardText(partial)   // speech bubble by the face
                    }
                } }
            )
            // Remote voice (Chatterbox on the PC). Hands-free echo guard: mute
            // the mic whenever Mabu is speaking so it never transcribes its own
            // TTS into a feedback loop.
            remoteTts = RemoteTts(
                baseUrl = tuning.ttsServerUrl,
                onSpeakingChanged = { speaking ->
                    responseActive = speaking
                    applyMute()
                    handler.post {
                        if (!speaking) {
                            handler.removeCallbacks(safetyUnmute)   // normal finish
                            micButton.text = if (manualMute) "🔇 muted" else "🎤 listening…"
                            overlayView.setHeardText(null)          // clear the bubble
                        }
                    }
                }
            )
            remoteAsr?.start()      // always-on listening, no button
            Log.i(TAG, "streaming mode: RemoteAsr -> ${tuning.asrServerUrl}; " +
                "local LLM + Vosk skipped; always-on listening")
            micButton.text = "🎤 listening…"
        } else {
            // Local mode. Load LLM FIRST, then Vosk. The Qwen GGUF needs
            // ~470 MB of contiguous virtual address space and we're on 32-bit
            // ARM, so it can't be allocated after Vosk + ML Kit + camera have
            // fragmented our VA layout. Pre-loading at startup gets first dibs.
            Thread {
                handler.post { micButton.text = "🎤 loading LLM…" }
                val llmOk = LlamaInference.load(
                    "/data/local/tmp/mabu.gguf", ctxSize = 1024, threads = 4
                )
                Log.i(TAG, "LLM preload: ${if (llmOk) "ok" else "FAILED"}")

                handler.post { micButton.text = "🎤 loading ASR…" }
                asr = AsrEngine(
                    modelPath = ASR_MODEL_PATH,
                    onFinal = { transcript -> handler.post { onTranscript(transcript) } },
                    onPartial = { partial ->
                        handler.post { micButton.text = "🎤 …$partial" }
                    }
                )
                handler.post {
                    micButton.text = if (asr?.isReady == true && llmOk) {
                        "🎤 hold to talk"
                    } else if (asr?.isReady == true) {
                        "🎤 (no LLM)"
                    } else {
                        "🎤 (no ASR)"
                    }
                }
            }.start()
        }
    }

    // ---------- Push-to-talk → ASR → LLM → TTS --------------------------------

    private fun onMicDown() {
        // Streaming mode is hands-free (always-on RemoteAsr). Mute now lives in
        // the volume cluster; the bottom button is a status line that also
        // toggles mute on tap (routed through the same toggleMute()).
        if (tuning.cognitionMode == "streaming" || tuning.cognitionMode == "pipecat") {
            toggleMute()
            return
        }
        // Local mode: classic push-to-talk. Mute TTS first; AudioRecord
        // acquisition can flake if the output stream is still draining, so give
        // the audio framework a short beat before grabbing the mic.
        val a = asr ?: return
        if (!a.isReady) return
        try { tts.stop() } catch (_: Throwable) {}
        micButton.text = "🎤 listening…"
        handler.postDelayed({ a.startListening() }, 150)
    }

    private fun onMicUp() {
        // streaming + pipecat are hands-free; mute toggled on DOWN.
        if (tuning.cognitionMode == "streaming" || tuning.cognitionMode == "pipecat") return
        val a = asr ?: return
        if (!a.isListening) return
        a.stopListening()
        micButton.text = "🎤 thinking…"
    }

    private fun onTranscript(text: String) {
        Log.i(TAG, "user: $text")
        when (tuning.cognitionMode) {
            "streaming" -> {
                overlayView.setHeardText(text)   // keep the heard words on screen
                // Mute the mic for the think + speak window so Mabu doesn't
                // transcribe its own voice; RemoteTts re-opens it when playback
                // drains. The watchdog recovers the mic if TTS never reports done.
                responseActive = true
                applyMute()
                micButton.text = "🎤 thinking…"
                handler.removeCallbacks(safetyUnmute)
                handler.postDelayed(safetyUnmute, SPEAK_WATCHDOG_MS)
                respondStreaming(text)
            }
            else -> {
                micButton.text = "🎤 hold to talk"
                respondLocal(text)
            }
        }
    }

    private fun respondStreaming(text: String) {
        val llm = streamingLlm ?: StreamingLlama(
            baseUrl = tuning.llmServerUrl,
            systemPrompt = MABU_PERSONA
        ).also { streamingLlm = it }

        val t0 = System.currentTimeMillis()
        var spokeAnything = false
        llm.chat(text, object : StreamingLlama.Listener {
            override fun onSentence(sentence: String, isFirst: Boolean) {
                val dt = System.currentTimeMillis() - t0
                Log.i(TAG, "mabu sentence (+${dt}ms, first=$isFirst): $sentence")
                spokeAnything = true
                // Speak via the remote Chatterbox voice (Pico is dead on this
                // device). RemoteTts pipelines synth + playback in order.
                remoteTts?.speak(sentence)
            }
            override fun onDone(fullText: String) {
                Log.i(TAG, "mabu done in ${System.currentTimeMillis() - t0}ms")
                // If the reply produced no speech, RemoteTts won't drain and the
                // echo-guard mute would stick -- re-open the mic ourselves.
                if (!spokeAnything) handler.post { responseActive = false; applyMute() }
            }
            override fun onError(e: Throwable) {
                Log.e(TAG, "stream error", e)
                handler.post {
                    remoteTts?.speak("Sorry, I lost connection to my brain.")
                }
            }
        })
    }

    private fun respondLocal(text: String) {
        if (!LlamaInference.isLoaded) {
            Log.e(TAG, "LLM not loaded; skipping reply")
            return
        }
        Thread {
            val prompt =
                "<|im_start|>system\n${MABU_PERSONA}<|im_end|>\n" +
                "<|im_start|>user\n$text<|im_end|>\n" +
                "<|im_start|>assistant\n"
            val t = System.currentTimeMillis()
            val reply = LlamaInference.generate(prompt, maxTokens = 80).trim()
            val dt = System.currentTimeMillis() - t
            Log.i(TAG, "mabu local (${dt}ms): $reply")
            if (reply.isNotBlank()) handler.post { tts.speak(reply) }
        }.start()
    }

    // ---------- Pipecat (WebRTC) brain ----------------------------------------

    /**
     * Bring up the Pipecat SmallWebRTC client and connect to the PC pipeline.
     * The SDK handles mic/speaker/AEC/turn-taking; we only surface transcripts
     * to the speech bubble and the speaking state to the status line. Manual
     * mute toggles the outbound mic track (see [toggleMute]).
     */
    private fun startPipecat() {
        connectPipecat()
        handler.removeCallbacks(pipecatHealthPoll)
        handler.post(pipecatHealthPoll)
    }

    /** (Re)establish the Pipecat session: release any old client, build a fresh
     *  one and connect. A fresh instance avoids reusing wedged transport state.
     *  Called on first start and by the health poll when the brain returns. */
    private fun connectPipecat() {
        try { pipecatVoice?.release() } catch (_: Throwable) {}
        pipecatState = null
        pipecatSessionDead = false
        botSpeaking = false
        lastPipecatConnectMs = System.currentTimeMillis()
        val voice = PipecatVoice(
            context = this,
            offerUrl = tuning.pipecatOfferUrl,
            enableMic = !manualMute,
            listener = pipecatListener(),
        )
        pipecatVoice = voice
        voice.connect()
        Log.i(TAG, "pipecat: connecting to ${tuning.pipecatOfferUrl}")
        updatePipecatStatus()
    }

    private fun pipecatListener() = object : PipecatVoice.Listener {
        override fun onConnected() {
            pipecatReachable = true
            pipecatSessionDead = false
            updatePipecatStatus()
        }
        override fun onDisconnected() {
            pipecatSessionDead = true        // session gone; poll will auto-reconnect
            overlayView.setHeardText(null)
            updatePipecatStatus()
        }
        override fun onConnectionState(state: ai.pipecat.client.types.TransportState) {
            pipecatState = state
            if (state == ai.pipecat.client.types.TransportState.Ready) pipecatReachable = true
            updatePipecatStatus()
        }
        override fun onUserTranscript(text: String, isFinal: Boolean) {
            if (manualMute) return
            overlayView.setHeardText(text)   // speech bubble by the face
        }
        override fun onBotStartedSpeaking() {
            handler.removeCallbacks(botSpeakingOff)   // cancel the inter-sentence timeout
            botSpeaking = true
            updatePipecatStatus()
        }
        override fun onBotStoppedSpeaking() {
            // A reply is many TTS sentences; each one fires stop/start. Don't flash
            // "listening" in the gaps between them (which get long when Chatterbox
            // is slow synthesizing the next sentence). Only drop back to listening
            // if no new sentence starts within the debounce window.
            handler.removeCallbacks(botSpeakingOff)
            handler.postDelayed(botSpeakingOff, 1200)
        }
        override fun onServerMessage(data: ai.pipecat.client.types.Value) {
            // PC->device control channel for agentic tools (set_mode, launch_app,
            // clone_voice ...). Phase 3 dispatches these into setMode()/MabuMotors.
            Log.i(TAG, "pipecat server message: $data")
        }
        override fun onError(message: String) {
            Log.e(TAG, "pipecat error: $message")
        }
    }

    /** Single source of truth for the pipecat status line. Reflects whether the
     *  brain is actually reachable + the live session state -- not just "mic on". */
    private fun updatePipecatStatus() {
        if (tuning.cognitionMode != "pipecat") return
        micButton.text = when {
            !pipecatReachable && pipecatState == null -> "🔌 connecting…"   // startup, pre-probe
            !pipecatReachable -> "⚠ brain offline"
            pipecatSessionDead -> "🔌 reconnecting…"   // bot back; poll re-establishes
            manualMute -> "🔇 muted"
            botSpeaking -> "🔊 speaking…"
            pipecatState == ai.pipecat.client.types.TransportState.Ready -> "🎤 listening…"
            else -> "🔌 connecting…"
        }
    }

    /** Liveness probe: a silently-killed bot doesn't always surface as a WebRTC
     *  disconnect, so poll the bot's TCP port and reflect reachability. Reschedules
     *  itself every few seconds; stopped by onDestroy's removeCallbacksAndMessages. */
    private val pipecatHealthPoll = object : Runnable {
        override fun run() {
            val hp = parseHostPort(tuning.pipecatOfferUrl)
            if (hp != null) {
                Thread {
                    val ok = try {
                        java.net.Socket().use {
                            it.connect(java.net.InetSocketAddress(hp.first, hp.second), 1500); true
                        }
                    } catch (_: Throwable) { false }
                    handler.post {
                        val was = pipecatReachable
                        pipecatReachable = ok
                        if (was && !ok) pipecatSessionDead = true   // was up, now gone
                        // Auto-reconnect: brain reachable but the session isn't live
                        // (initial connect failed, or it dropped). Throttled.
                        val notLive = pipecatSessionDead ||
                            pipecatState != ai.pipecat.client.types.TransportState.Ready
                        if (ok && notLive &&
                            System.currentTimeMillis() - lastPipecatConnectMs > 6000) {
                            Log.i(TAG, "pipecat: auto-reconnecting (brain reachable, session not live)")
                            connectPipecat()
                        }
                        updatePipecatStatus()
                    }
                }.start()
            }
            handler.postDelayed(this, 4000)
        }
    }

    private fun parseHostPort(url: String): Pair<String, Int>? = try {
        val u = java.net.URI(url)
        val port = if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80
        if (u.host != null) Pair(u.host, port) else null
    } catch (_: Throwable) { null }

    /**
     * Register a debug broadcast receiver so the app is fully drivable over
     * ADB -- no physical buttons needed. All actions are dispatched onto the
     * main thread. Examples (from a host shell):
     *
     *   adb shell am broadcast -a com.mabu.anima.SAY   --es text "how are you?"
     *   adb shell am broadcast -a com.mabu.anima.SPEAK --es text "hello there"
     *   adb shell am broadcast -a com.mabu.anima.MODE  --es mode PUPPET
     *   adb shell am broadcast -a com.mabu.anima.STOP
     *
     * SAY runs the full ASR-equivalent path (LLM -> streaming TTS); SPEAK is
     * TTS-only; MODE switches the behavior mode; STOP cancels in-flight
     * speech + stream.
     */
    private fun registerDebugReceiver() {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_SAY -> {
                        val text = intent.getStringExtra("text")?.trim().orEmpty()
                        if (text.isEmpty()) { Log.w(TAG, "SAY with no text"); return }
                        Log.i(TAG, "debug SAY: $text")
                        handler.post {
                            // In pipecat mode there's no device-side ASR/LLM to
                            // drive; inject the text straight into the PC pipeline.
                            val voice = pipecatVoice
                            if (voice != null) voice.sendText(text) else onTranscript(text)
                        }
                    }
                    ACTION_SPEAK -> {
                        val text = intent.getStringExtra("text")?.trim().orEmpty()
                        if (text.isEmpty()) { Log.w(TAG, "SPEAK with no text"); return }
                        Log.i(TAG, "debug SPEAK: $text")
                        handler.post { tts.speak(text) }
                    }
                    ACTION_MODE -> {
                        val name = intent.getStringExtra("mode")?.trim()?.uppercase().orEmpty()
                        val m = runCatching { Mode.valueOf(name) }.getOrNull()
                        if (m == null) { Log.w(TAG, "MODE invalid: '$name'"); return }
                        Log.i(TAG, "debug MODE: $m")
                        handler.post { setMode(m) }
                    }
                    ACTION_STOP -> {
                        Log.i(TAG, "debug STOP")
                        handler.post {
                            try { streamingLlm?.cancel() } catch (_: Throwable) {}
                            try { tts.stop() } catch (_: Throwable) {}
                        }
                    }
                    ACTION_CAM -> {
                        val on = intent.getStringExtra("on") == "1"
                        Log.i(TAG, "debug CAM: $on")
                        handler.post { setVideoMode(on) }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SAY)
            addAction(ACTION_SPEAK)
            addAction(ACTION_MODE)
            addAction(ACTION_STOP)
            addAction(ACTION_CAM)
        }
        registerReceiver(rx, filter)
        debugReceiver = rx
        Log.i(TAG, "debug receiver registered (SAY / SPEAK / MODE / STOP)")
    }

    /**
     * Toggle "video mode" -- hand the camera off between on-device ML Kit
     * (faces -> motor reflexes) and the Pipecat SDK's WebRTC capturer
     * (frames -> brain VLM). Camera1 on Mabu is single-open, so the two
     * consumers can't run concurrently. The HUD shows which one currently
     * owns the camera via the `cam` and `mode` rows.
     */
    @Volatile private var videoMode = false
    private fun setVideoMode(on: Boolean) {
        if (on == videoMode) return
        videoMode = on
        if (on) {
            Log.i(TAG, "video mode ON -- releasing camera for SDK")
            cameraSource?.release()
            // Brief gap to ensure the camera HAL fully releases before the SDK
            // tries to open. 250 ms is empirical; the camera close path is async.
            handler.postDelayed({ pipecatVoice?.setCamEnabled(true) }, 250)
        } else {
            Log.i(TAG, "video mode OFF -- returning camera to ML Kit")
            pipecatVoice?.setCamEnabled(false)
            handler.postDelayed({ cameraSource?.start() }, 400)
        }
    }

    private fun calibrateCenter() {
        calibCenterX = fxSmooth
        calibCenterY = fySmooth
        val msg = "Calibrated: (${"%.2f".format(calibCenterX)}, ${"%.2f".format(calibCenterY)})"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        Log.i(TAG, msg)
    }

    private fun setMode(newMode: Mode) {
        if (newMode == mode) return
        mode = newMode
        DeviceStats.mode = newMode.name
        // Apply preset behavior flags. User can then override any of them
        // in the settings panel without changing mode.
        when (newMode) {
            Mode.FOLLOW -> {
                tuning.blinkMethod = "spontaneous"
                tuning.enableSaccades = true
                tuning.enableGlances = true
                targetNeckRot = 50f; targetNeckElev = 50f; targetNeckTilt = 50f
                resetEyelidsToNeutral()
            }
            Mode.PUPPET -> {
                tuning.blinkMethod = "mirror"
                tuning.enableSaccades = false
                tuning.enableGlances = false
                // puppet path will continuously drive eyes / neck / eyelids
            }
            Mode.IDLE -> {
                tuning.blinkMethod = "spontaneous"
                tuning.enableSaccades = true
                tuning.enableGlances = true
                targetNeckRot = 50f; targetNeckElev = 50f; targetNeckTilt = 50f
                followX = 0.5f; followY = 0.5f
                resetEyelidsToNeutral()
            }
            Mode.SLEEP -> {
                tuning.blinkMethod = "none"
                tuning.enableSaccades = false
                tuning.enableGlances = false
                targetNeckRot = 50f; targetNeckElev = 50f; targetNeckTilt = 50f
                followX = 0.5f; followY = 0.5f
                targetX = 0.5f; targetY = 0.5f
                saccadeOffsetX = 0f; saccadeOffsetY = 0f
                glanceOffsetX = 0f; glanceOffsetY = 0f
                lastLdlValue = MabuMotors.EYELID_CLOSED
                lastLdrValue = MabuMotors.EYELID_CLOSED
                motors.move(
                    eyelidLeft = MabuMotors.EYELID_CLOSED,
                    eyelidRight = MabuMotors.EYELID_CLOSED
                )
            }
        }
        DeviceStats.blinkMethod = tuning.blinkMethod
        settingsPanel.rebuildAfterPreset()
        Toast.makeText(this, "Mode: ${mode.name}", Toast.LENGTH_SHORT).show()
    }

    /** Apply one tunable from the web config UI (key -> raw string value).
     *  Unknown keys are ignored; values are validated/parsed per type. */
    private fun applyTuning(k: String, v: String) {
        val f = v.toFloatOrNull()
        when (k) {
            "eyeGazeGain"          -> f?.let { tuning.eyeGazeGain = it }
            "eyeGazeInputAlpha"    -> f?.let { tuning.eyeGazeInputAlpha = it.coerceIn(0.02f, 1f) }
            "eyeGazeDeadband"      -> f?.let { tuning.eyeGazeDeadband = it.coerceIn(0f, 0.5f) }
            "smoothAlphaEyes"      -> f?.let { tuning.smoothAlphaEyes = it.coerceIn(0.02f, 1f) }
            "smoothAlphaNeck"      -> f?.let { tuning.smoothAlphaNeck = it.coerceIn(0.02f, 1f) }
            "neckAngleRange"       -> f?.let { tuning.neckAngleRange = it.coerceIn(5f, 90f) }
            "eyelidCoupling"       -> f?.let { tuning.eyelidCoupling = it.coerceIn(0f, 1f) }
            "eyelidWinkOpen"       -> f?.let { tuning.eyelidWinkOpen = it.coerceIn(0f, 1f) }
            "eyelidCloseLevel"     -> f?.let { tuning.eyelidCloseLevel = it.coerceIn(0f, 1f) }
            "eyelidOpenInputAlpha" -> f?.let { tuning.eyelidOpenInputAlpha = it.coerceIn(0.02f, 1f) }
            "eyelidBlinkHoldMs"    -> v.toIntOrNull()?.let { tuning.eyelidBlinkHoldMs = it.coerceIn(0, 1000) }
            "gazeYOffset"          -> f?.let { tuning.gazeYOffset = it.coerceIn(-0.5f, 0.5f) }
            "useEyeGaze"           -> tuning.useEyeGaze = (v == "true" || v == "1")
            "blinkMethod"          -> if (v in setOf("spontaneous", "mirror", "both", "none")) tuning.blinkMethod = v
        }
    }

    private fun resetEyelidsToNeutral() {
        lastLdlValue = MabuMotors.EYELID_NEUTRAL
        lastLdrValue = MabuMotors.EYELID_NEUTRAL
        motors.move(
            eyelidLeft = MabuMotors.EYELID_NEUTRAL,
            eyelidRight = MabuMotors.EYELID_NEUTRAL
        )
    }

    private fun startCamera() {
        val analyzer = FaceAnalyzer { result ->
            if (result.faces.isNotEmpty()) {
                overlayView.setResult(result, isFrontFacing = true)
                lastOverlayFaceMs = SystemClock.uptimeMillis()
            } else if (SystemClock.uptimeMillis() - lastOverlayFaceMs > HOLD_OVERLAY_MS) {
                overlayView.setResult(result, isFrontFacing = true)
            }
            when (mode) {
                Mode.FOLLOW -> updateFollowFrom(result)
                Mode.PUPPET -> updatePuppetFrom(result)
                Mode.IDLE, Mode.SLEEP -> { /* ignore face input */ }
            }
            // Eyelid mirror is independent of mode -- if the user enabled
            // "mirror" or "both" blink method, mirror runs on top of the
            // mode's existing eyelid behavior. SLEEP overrides by keeping
            // eyelids closed (no detection input drives them).
            if (mode != Mode.SLEEP &&
                (tuning.blinkMethod == "mirror" || tuning.blinkMethod == "both")) {
                maybeMirrorEyelids(result)
            }
        }
        // Experiment: bbox-only fast detector running in parallel. Doesn't drive
        // anything yet -- DeviceStats records its FPS so we can compare against
        // the full pipeline's FPS in the HUD / /status. Wire its bbox into
        // FOLLOW later once the perf gap is confirmed.
        val fastAnalyzer = FastFaceAnalyzer { /* result unused for now */ }
        cameraSource = Camera1Source(this, textureView, analyzer, fastAnalyzer) { pw, ph, rot ->
            adjustPreviewAspect(pw, ph, rot)
        }
    }

    /**
     * Size the TextureView (and the overlay sitting on top of it) to the
     * camera preview's aspect ratio, centered with black bars on whichever
     * sides don't fit. Without this, MATCH_PARENT non-uniformly stretches
     * 320x240 to 1024x600 -- the face looks wide and landmarks miss because
     * the overlay's scale math assumes uniform fill-center.
     */
    private fun adjustPreviewAspect(previewW: Int, previewH: Int, imageRotation: Int) {
        // After rotation, the displayed image dimensions may swap.
        val effW = if (imageRotation == 90 || imageRotation == 270) previewH else previewW
        val effH = if (imageRotation == 90 || imageRotation == 270) previewW else previewH
        val parent = rootLayout
        val parentW = parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val parentH = parent.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val previewAspect = effW.toFloat() / effH
        val parentAspect = parentW.toFloat() / parentH
        val (w, h) = if (previewAspect > parentAspect) {
            // preview wider than parent -> letterbox top/bottom
            parentW to (parentW / previewAspect).toInt()
        } else {
            // preview narrower -> pillarbox sides
            (parentH * previewAspect).toInt() to parentH
        }
        val applyTo: (android.view.View) -> Unit = { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            v.layoutParams = lp
        }
        applyTo(textureView)
        applyTo(overlayView)
        Log.i(TAG, "preview aspect: ${effW}x$effH (${"%.3f".format(previewAspect)}) " +
            "-> view ${w}x$h in ${parentW}x$parentH parent")
    }

    // ---------- FOLLOW mode ----------------------------------------------------

    private fun updateFollowFrom(result: FaceResult) {
        val w = result.imageWidth.toFloat()
        val h = result.imageHeight.toFloat()
        if (w <= 0f || h <= 0f) return

        val face = result.faces.firstOrNull()
        val now = SystemClock.uptimeMillis()

        if (face == null) {
            if (now - lastFaceSeenMs > HOLD_LAST_GAZE_MS) {
                val a = tuning.emaAlpha
                fxSmooth = a * 0.5f + (1f - a) * fxSmooth
                fySmooth = a * 0.5f + (1f - a) * fySmooth
                writeFollowTarget()
            }
            return
        }

        lastFaceSeenMs = now
        val rect = face.boundingBox
        val cx = ((rect.left + rect.right) * 0.5f / w).coerceIn(0f, 1f)
        val cy = ((rect.top + rect.bottom) * 0.5f / h).coerceIn(0f, 1f)
        val a = tuning.emaAlpha
        fxSmooth = a * cx + (1f - a) * fxSmooth
        fySmooth = a * cy + (1f - a) * fySmooth
        writeFollowTarget()
    }

    private fun writeFollowTarget() {
        followX = (0.5f + (fxSmooth - calibCenterX) * tuning.gazeGain).coerceIn(0f, 1f)
        // Y offset (hardware mount) is applied uniformly in the gaze tick,
        // not here -- this writes the raw face-tracked target.
        followY = (0.5f + (fySmooth - calibCenterY) * tuning.gazeGain).coerceIn(0f, 1f)

        // Head follows gaze, scaled down so eyes do most of the work.
        // Sign flips: neckRot uses the same unit-4 sign as puppet (the
        // motor is inverted from mabu.py docs). neckElev uses the EYE Y
        // direction *flipped* because neck_elev is NOT inverted on unit 4
        // while the EUD eye motor is.
        val s = tuning.neckFollowGain
        targetNeckRot  = (50f + (followX - 0.5f) * 100f * s * tuning.neckRotSign).coerceIn(0f, 100f)
        targetNeckElev = (50f + (0.5f - followY) * 100f * s * tuning.neckElevSign).coerceIn(0f, 100f)
        targetNeckTilt = 50f
    }

    // ---------- PUPPET mode ---------------------------------------------------

    private fun updatePuppetFrom(result: FaceResult) {
        val face = result.faces.firstOrNull() ?: return
        lastFaceSeenMs = SystemClock.uptimeMillis()

        val yaw   = face.headEulerAngleY
        val pitch = face.headEulerAngleX
        val roll  = face.headEulerAngleZ

        targetNeckRot  = motorFromAngle(yaw   * tuning.neckRotSign)
        targetNeckElev = motorFromAngle(pitch * tuning.neckElevSign)
        targetNeckTilt = motorFromAngle(roll  * tuning.neckTiltSign)

        val gaze = result.gaze
        val avgPupil = pupilAverage(gaze)
        if (tuning.useEyeGaze && avgPupil != null) {
            // 1) Low-pass the raw pupil offset (input smoothing). The dark-
            //    cluster centroid jitters by several percent even when the
            //    user holds still; without this it reaches the eyes as tremor.
            if (!pupilFiltInit) {
                pupilFiltX = avgPupil.x; pupilFiltY = avgPupil.y; pupilFiltInit = true
            } else {
                pupilFiltX += (avgPupil.x - pupilFiltX) * tuning.eyeGazeInputAlpha
                pupilFiltY += (avgPupil.y - pupilFiltY) * tuning.eyeGazeInputAlpha
            }
            val tx = (0.5f + pupilFiltX * tuning.eyeGazeGain).coerceIn(0f, 1f)
            val ty = (0.5f + pupilFiltY * tuning.eyeGazeGain).coerceIn(0f, 1f)
            // 2) Fixation deadband: hold the current eye target unless the new
            //    one moved enough to be a real gaze shift. Kills residual at-
            //    rest jitter and reads as natural fixation (real eyes hold,
            //    then saccade). The output tween still eases into any change,
            //    so crossing the band doesn't snap.
            if (kotlin.math.abs(tx - targetX) > tuning.eyeGazeDeadband) targetX = tx
            if (kotlin.math.abs(ty - targetY) > tuning.eyeGazeDeadband) targetY = ty
        } else {
            pupilFiltInit = false
            targetX = (0.5f + (yaw   * tuning.neckRotSign  / tuning.neckAngleRange) * 0.5f).coerceIn(0f, 1f)
            targetY = (0.5f + (pitch * tuning.neckElevSign / tuning.neckAngleRange) * 0.5f).coerceIn(0f, 1f)
        }

        // Telemetry for the /status animation monitor: raw inputs, filtered
        // pupil, and the resulting tween targets.
        DeviceStats.headYaw = yaw; DeviceStats.headPitch = pitch; DeviceStats.headRoll = roll
        avgPupil?.let { DeviceStats.pupilRawX = it.x; DeviceStats.pupilRawY = it.y }
        DeviceStats.pupilFiltX = pupilFiltX; DeviceStats.pupilFiltY = pupilFiltY
        DeviceStats.targetEyesLR = targetX * 100f
        DeviceStats.targetEyesUD = targetY * 100f
        DeviceStats.targetNeckRot = targetNeckRot
        DeviceStats.targetNeckElev = targetNeckElev
        DeviceStats.targetNeckTilt = targetNeckTilt
    }

    /**
     * Eyelid blink/squint DETECTION (blinkMethod = "mirror" or "both"). Runs
     * per face frame (~10 fps). Does NOT drive the motors -- it updates the
     * smoothed per-eye openness + blink-hold deadlines; the motion is rendered
     * in the 25 Hz tick (renderEyelids) so it's smooth despite the camera rate.
     *
     * Coupling: ML Kit's per-eye probability is noisy and asymmetric, so a real
     * two-eye blink often dips one eye below threshold while the other lags --
     * which naive per-eye mirroring renders as a one-eyed wink. So if NEITHER
     * eye is clearly open (both < eyelidWinkOpen) we treat it as a joint
     * blink/squint and pull both toward the more-closed eye by eyelidCoupling.
     * A deliberate wink keeps one eye clearly open, so it stays independent.
     *
     * Partial closure: the (coupled) openness is low-passed and mapped
     * proportionally to lid position in renderEyelids -- a held half-close
     * gives a steady squint. A fast dip below eyelidCloseLevel additionally
     * latches a full closure so blinks stay crisp.
     */
    private fun maybeMirrorEyelids(result: FaceResult) {
        val face = result.faces.firstOrNull() ?: return
        val oL = (face.leftEyeOpenProbability  ?: 1f).coerceIn(0f, 1f)
        val oR = (face.rightEyeOpenProbability ?: 1f).coerceIn(0f, 1f)
        val coupling = tuning.eyelidCoupling
        val bothEngaged = oL < tuning.eyelidWinkOpen && oR < tuning.eyelidWinkOpen
        val joint = minOf(oL, oR)
        val effL = if (bothEngaged) oL + (joint - oL) * coupling else oL
        val effR = if (bothEngaged) oR + (joint - oR) * coupling else oR
        val a = tuning.eyelidOpenInputAlpha
        if (!eyeOpenInit) {
            eyeOpenSmoothL = effL; eyeOpenSmoothR = effR; eyeOpenInit = true
        } else {
            eyeOpenSmoothL += (effL - eyeOpenSmoothL) * a
            eyeOpenSmoothR += (effR - eyeOpenSmoothR) * a
        }
        val now = SystemClock.uptimeMillis()
        // Latch on the pre-smoothing effective openness so a sharp dip fires
        // immediately (the EMA would otherwise soften the blink edge).
        if (effL < tuning.eyelidCloseLevel) blinkHoldLUntil = now + tuning.eyelidBlinkHoldMs
        if (effR < tuning.eyelidCloseLevel) blinkHoldRUntil = now + tuning.eyelidBlinkHoldMs
        DeviceStats.eyeOpenProbL = oL; DeviceStats.eyeOpenProbR = oR
        DeviceStats.eyeClosedL = now < blinkHoldLUntil || eyeOpenSmoothL < tuning.eyelidCloseLevel
        DeviceStats.eyeClosedR = now < blinkHoldRUntil || eyeOpenSmoothR < tuning.eyelidCloseLevel
    }

    private fun motorFromAngle(angleDeg: Float): Float =
        (50f + (angleDeg / tuning.neckAngleRange) * 50f).coerceIn(0f, 100f)

    private fun pupilAverage(gaze: GazeData?): android.graphics.PointF? {
        gaze ?: return null
        val l = gaze.leftEyeOffset
        val r = gaze.rightEyeOffset
        return when {
            l != null && r != null -> android.graphics.PointF((l.x + r.x) * 0.5f, (l.y + r.y) * 0.5f)
            l != null -> l
            r != null -> r
            else -> null
        }
    }

    /**
     * Render the eyelids on the 25 Hz tick. Target is a full closure while the
     * blink-hold is active (crisp blink), otherwise the smoothed openness
     * mapped proportionally to lid position (partial closure / squint).
     * Asymmetric tween (snappy close, softer reopen) + a deadband so a steady
     * lid stops sending. Mirror modes only; else doBlink owns the lids.
     */
    private fun renderEyelids(now: Long) {
        val tgtL = if (now < blinkHoldLUntil) MabuMotors.EYELID_CLOSED else openToLid(eyeOpenSmoothL)
        val tgtR = if (now < blinkHoldRUntil) MabuMotors.EYELID_CLOSED else openToLid(eyeOpenSmoothR)
        // tgt > current means closing (value rises toward CLOSED=90) -> fast.
        val aL = if (tgtL > lastLdlValue) EYELID_CLOSE_ALPHA else EYELID_OPEN_ALPHA
        val aR = if (tgtR > lastLdrValue) EYELID_CLOSE_ALPHA else EYELID_OPEN_ALPHA
        val nL = lastLdlValue + (tgtL - lastLdlValue) * aL
        val nR = lastLdrValue + (tgtR - lastLdrValue) * aR
        if (kotlin.math.abs(nL - lastLdlValue) > EYELID_DEADBAND ||
            kotlin.math.abs(nR - lastLdrValue) > EYELID_DEADBAND) {
            motors.move(eyelidLeft = nL, eyelidRight = nR)
            lastLdlValue = nL
            lastLdrValue = nR
        }
    }

    /** Map eye openness (0=shut..1=open) to a lid position. Open rests at
     *  NEUTRAL (natural), not wide-open; fully shut = CLOSED. Linear between,
     *  so partial openness gives a proportional squint. */
    private fun openToLid(open: Float): Float {
        val o = open.coerceIn(0f, 1f)
        return MabuMotors.EYELID_NEUTRAL + (MabuMotors.EYELID_CLOSED - MabuMotors.EYELID_NEUTRAL) * (1f - o)
    }

    // ---------- Motor tween ----------------------------------------------------

    private val gazeTickRunnable = object : Runnable {
        override fun run() {
            if (motors.isOpen() && mode != Mode.SLEEP) {
                // FOLLOW + IDLE both compose face/center baseline with the
                // animation offsets. PUPPET sets targetX/Y directly in
                // updatePuppetFrom and bypasses this composition.
                if (mode == Mode.FOLLOW || mode == Mode.IDLE) {
                    targetX = (followX + saccadeOffsetX + glanceOffsetX).coerceIn(0f, 1f)
                    targetY = (followY + saccadeOffsetY + glanceOffsetY).coerceIn(0f, 1f)
                }
                // Hardware mount calibration: the camera sits slightly off
                // from the robot's eye axis, so we bias every eye target
                // upward by gazeYOffset. Applies to ALL modes (FOLLOW eye
                // tracking, PUPPET head-pose-driven eyes, PUPPET pupil
                // gaze) because the offset is about where the robot is
                // physically pointed, not what it's tracking.
                val effectiveTargetY = (targetY - tuning.gazeYOffset).coerceIn(0f, 1f)
                val eyesA = tuning.smoothAlphaEyes
                val neckA = tuning.smoothAlphaNeck
                currentX += (targetX - currentX) * eyesA
                currentY += (effectiveTargetY - currentY) * eyesA
                currentNeckRot  += (targetNeckRot  - currentNeckRot ) * neckA
                currentNeckElev += (targetNeckElev - currentNeckElev) * neckA
                currentNeckTilt += (targetNeckTilt - currentNeckTilt) * neckA

                val eyesChanged =
                    kotlin.math.abs(currentX - lastSentX) > GAZE_EPSILON ||
                    kotlin.math.abs(currentY - lastSentY) > GAZE_EPSILON
                val neckChanged =
                    kotlin.math.abs(currentNeckRot  - lastSentNeckRot ) > NECK_EPSILON ||
                    kotlin.math.abs(currentNeckElev - lastSentNeckElev) > NECK_EPSILON ||
                    kotlin.math.abs(currentNeckTilt - lastSentNeckTilt) > NECK_EPSILON

                if (eyesChanged || neckChanged) {
                    motors.move(
                        eyesLR    = currentX * 100f,
                        eyesUD    = currentY * 100f,
                        neckRot   = currentNeckRot,
                        neckElev  = currentNeckElev,
                        neckTilt  = currentNeckTilt
                    )
                    lastSentX = currentX; lastSentY = currentY
                    lastSentNeckRot  = currentNeckRot
                    lastSentNeckElev = currentNeckElev
                    lastSentNeckTilt = currentNeckTilt
                    if (++gazeLogCounter % 50 == 0) {
                        Log.i(TAG, "${mode.name} gaze=(${"%.2f".format(currentX)}," +
                            "${"%.2f".format(currentY)}) neck=(R${"%.0f".format(currentNeckRot)}," +
                            "E${"%.0f".format(currentNeckElev)},T${"%.0f".format(currentNeckTilt)})")
                    }
                }

                // Eyelid blink rendering, decoupled from the camera rate: the
                // detector (maybeMirrorEyelids) sets the target at ~10 fps, but
                // we tween toward it here at 25 Hz so the blink itself is smooth.
                // Separate motors.move (partial update) so it doesn't re-send
                // the gaze/neck frame. Mirror modes only; else doBlink owns lids.
                if (tuning.blinkMethod == "mirror" || tuning.blinkMethod == "both") {
                    renderEyelids(SystemClock.uptimeMillis())
                }
            }
            handler.postDelayed(this, GAZE_TICK_MS)
        }
    }

    // ---------- Blink + saccades + glances ------------------------------------

    private val blinkRunnable = object : Runnable {
        override fun run() {
            val method = tuning.blinkMethod
            // Spontaneous timer-driven blink fires for "spontaneous" and
            // "both"; "mirror" relies on the user's blinks instead; "none"
            // skips automatic blinking entirely. SLEEP holds eyes closed.
            if (mode != Mode.SLEEP && (method == "spontaneous" || method == "both")) {
                doBlink()
            }
            val mean = tuning.blinkIntervalSec * 1000f
            val nextDelay = (mean * 0.7f + (Math.random() * mean * 0.6f)).toLong()
            handler.postDelayed(this, nextDelay)
        }
    }

    private fun doBlink() {
        if (!motors.isOpen()) return
        motors.move(
            eyelidLeft = MabuMotors.EYELID_CLOSED,
            eyelidRight = MabuMotors.EYELID_CLOSED
        )
        handler.postDelayed({
            motors.move(
                eyelidLeft = MabuMotors.EYELID_NEUTRAL,
                eyelidRight = MabuMotors.EYELID_NEUTRAL
            )
            if (Math.random() < tuning.doubleBlinkChance) {
                handler.postDelayed({
                    motors.move(
                        eyelidLeft = MabuMotors.EYELID_CLOSED,
                        eyelidRight = MabuMotors.EYELID_CLOSED
                    )
                    handler.postDelayed({
                        motors.move(
                            eyelidLeft = MabuMotors.EYELID_NEUTRAL,
                            eyelidRight = MabuMotors.EYELID_NEUTRAL
                        )
                    }, BLINK_HOLD_MS - 20)
                }, 120L)
            }
        }, BLINK_HOLD_MS)
    }

    private val saccadeRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.SLEEP && tuning.enableSaccades) {
                val amp = tuning.saccadeAmplitude
                val dx = ((Math.random() - 0.5) * 2.0 * amp).toFloat()
                val dy = ((Math.random() - 0.5) * 2.0 * amp).toFloat()
                saccadeOffsetX = dx; saccadeOffsetY = dy
                handler.postDelayed({
                    saccadeOffsetX = 0f; saccadeOffsetY = 0f
                }, SACCADE_DURATION_MS)
            }
            val mean = tuning.saccadeIntervalSec * 1000f
            val next = (mean * 0.7f + (Math.random() * mean * 0.6f)).toLong()
            handler.postDelayed(this, next)
        }
    }

    private val glanceRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.SLEEP && tuning.enableGlances) {
                val (gx, gy) = GLANCE_DIRECTIONS.random()
                glanceOffsetX = gx; glanceOffsetY = gy
                val dur = GLANCE_DURATION_MIN_MS +
                    (Math.random() * (GLANCE_DURATION_MAX_MS - GLANCE_DURATION_MIN_MS)).toLong()
                handler.postDelayed({
                    glanceOffsetX = 0f; glanceOffsetY = 0f
                }, dur)
            }
            val mean = tuning.glanceIntervalSec * 1000f
            val next = (mean * 0.7f + (Math.random() * mean * 0.6f)).toLong()
            handler.postDelayed(this, next)
        }
    }

    // ---------- Lifecycle -----------------------------------------------------

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        debugReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        debugReceiver = null
        cameraSource?.release()
        try { motors.sleepPose(); Thread.sleep(400) } catch (_: Throwable) {}
        motors.close()
        try { tts.shutdown() } catch (_: Throwable) {}
        try { asr?.release() } catch (_: Throwable) {}
        try { remoteAsr?.release() } catch (_: Throwable) {}
        try { remoteTts?.release() } catch (_: Throwable) {}
        try { pipecatVoice?.release() } catch (_: Throwable) {}
        try { statusServer?.stop() } catch (_: Throwable) {}
        Log.i(TAG, "Released camera + motors + tts + asr + remoteTts + pipecat")
    }

    // ---------- Always-visible volume controls --------------------------------

    private fun buildVolumePanel(): android.widget.LinearLayout {
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(20, 10, 20, 10)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val plus = TextView(this).apply {
            text = "+"; textSize = 26f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(20, 4, 20, 4)
            setOnClickListener { adjustVolume(+1) }
        }
        volLevelView = TextView(this).apply {
            text = "-/-"; textSize = 14f
            setTextColor(Color.YELLOW); gravity = Gravity.CENTER
        }
        val minus = TextView(this).apply {
            text = "−"; textSize = 26f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(20, 4, 20, 4)
            setOnClickListener { adjustVolume(-1) }
        }
        // Mute toggle lives here with the volume controls (not the bottom
        // status button). Tap to stop/resume listening.
        val mute = TextView(this).apply {
            text = "🎤"; textSize = 24f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(20, 10, 20, 6)
            setOnClickListener { toggleMute() }
        }
        muteButton = mute
        panel.addView(mute)
        panel.addView(plus)
        panel.addView(volLevelView)
        panel.addView(minus)
        return panel
    }

    /** Manual mute toggle (sticky). Separate from the echo-guard auto-mute. */
    private fun toggleMute() {
        // Pipecat owns the mic: just flip the outbound track. No echo guard
        // (AEC handles that) and no responseActive bookkeeping.
        pipecatVoice?.let { voice ->
            manualMute = !manualMute
            voice.setMuted(manualMute)
            if (manualMute) overlayView.setHeardText(null)
            muteButton?.text = if (manualMute) "🔇" else "🎤"
            updatePipecatStatus()
            return
        }
        if (remoteAsr == null) return
        manualMute = !manualMute
        if (manualMute) {
            // Interrupt anything Mabu is currently saying, and clear the bubble.
            try { remoteTts?.stop() } catch (_: Throwable) {}
            try { tts.stop() } catch (_: Throwable) {}
            overlayView.setHeardText(null)
        }
        applyMute()
        updateMuteUi(manualMute)
    }

    /** Effective mute = manual (user) OR auto (reply in progress). */
    private fun applyMute() {
        remoteAsr?.muted = manualMute || responseActive
    }

    private fun updateMuteUi(muted: Boolean) {
        muteButton?.text = if (muted) "🔇" else "🎤"
        micButton.text = if (muted) "🔇 muted" else "🎤 listening…"
    }

    private fun adjustVolume(delta: Int) {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val newLevel = (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) + delta).coerceIn(0, max)
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newLevel, 0)
        tuning.ttsVolume = newLevel.toFloat() / max
        tuning.save(getSharedPreferences("tuning", MODE_PRIVATE))
        updateVolumeDisplay()
    }

    private fun updateVolumeDisplay() {
        if (!::volLevelView.isInitialized) return
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        volLevelView.text = "$cur/$max"
    }

    // ---------- Dev broadcast receiver -- lets adb drive the app -------------
    // Trigger from host:
    //   adb shell am broadcast -a com.mabu.anima.SPEAK --es text "hello"
    //   adb shell am broadcast -a com.mabu.anima.LLM --es prompt "Who are you?" --ez speak true
    //   adb shell am broadcast -a com.mabu.anima.SET_MODE --es mode PUPPET

    private val devReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                "com.mabu.anima.SPEAK" -> {
                    val text = intent.getStringExtra("text") ?: return
                    Log.i(TAG, "dev SPEAK: $text")
                    tts.speak(text, tuning.ttsVolume)
                }
                "com.mabu.anima.LLM" -> {
                    val prompt = intent.getStringExtra("prompt") ?: "Who are you?"
                    val speak = intent.getBooleanExtra("speak", false)
                    runDevLlm(prompt, speak)
                }
                "com.mabu.anima.SET_MODE" -> {
                    val name = intent.getStringExtra("mode") ?: return
                    val m = runCatching { Mode.valueOf(name.uppercase()) }.getOrNull() ?: return
                    setMode(m)
                }
                "com.mabu.anima.SET_TTS_VOLUME" -> {
                    val v = intent.getFloatExtra("volume", -1f)
                    if (v >= 0f) {
                        tuning.ttsVolume = v
                        tts.applyVolume(v)
                        settingsPanel.rebuildAfterPreset()
                        updateVolumeDisplay()
                    }
                }
            }
        }
    }

    private fun runDevLlm(userPrompt: String, alsoSpeak: Boolean) {
        Thread {
            val modelPath = "/data/local/tmp/mabu.gguf"
            if (!LlamaInference.isLoaded) {
                if (!LlamaInference.load(modelPath, ctxSize = 1024, threads = 4)) {
                    Log.e(TAG, "dev LLM: model load failed")
                    return@Thread
                }
            }
            val full = "<|im_start|>system\nYou are Mabu, a small yellow social robot. " +
                "Reply in one short sentence.<|im_end|>\n" +
                "<|im_start|>user\n${userPrompt}<|im_end|>\n" +
                "<|im_start|>assistant\n"
            val t = System.currentTimeMillis()
            val out = LlamaInference.generate(full, maxTokens = 64).trim()
            Log.i(TAG, "dev LLM (${System.currentTimeMillis() - t}ms): $out")
            if (alsoSpeak && out.isNotBlank()) {
                handler.post { tts.speak(out, tuning.ttsVolume) }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        val f = android.content.IntentFilter().apply {
            addAction("com.mabu.anima.SPEAK")
            addAction("com.mabu.anima.LLM")
            addAction("com.mabu.anima.SET_MODE")
            addAction("com.mabu.anima.SET_TTS_VOLUME")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(devReceiver, f, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(devReceiver, f)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(devReceiver) } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "MabuFaceOverlay"
        private const val REQ_CAMERA = 10
        private const val REQ_RECORD_AUDIO = 11

        // Debug control broadcast actions (drive the app over ADB).
        private const val ACTION_SAY   = "com.mabu.anima.SAY"
        private const val ACTION_SPEAK = "com.mabu.anima.SPEAK"
        private const val ACTION_MODE  = "com.mabu.anima.MODE"
        private const val ACTION_STOP  = "com.mabu.anima.STOP"
        private const val ACTION_CAM   = "com.mabu.anima.CAM"

        // Pure backstop: RemoteTts reliably reports "done" when playback drains
        // (even on synth failure), so this only fires if TTS truly hangs. Sized
        // long so it never clips a legit long reply (e.g. Mabu telling a story).
        private const val SPEAK_WATCHDOG_MS = 90000L

        private const val ASR_MODEL_PATH = "/sdcard/vosk-model-en"
        private const val MABU_PERSONA =
            "You are Mabu, a small yellow social robot watching the user from a tabletop. " +
            "Speak in one short sentence -- warm, curious, a bit quirky. " +
            "Never lecture or hedge. If you don't know, say so briefly."

        private const val HOLD_LAST_GAZE_MS = 1000L
        private const val HOLD_OVERLAY_MS = 500L

        private const val GAZE_TICK_MS = 40L
        private const val GAZE_EPSILON = 0.003f
        private const val NECK_EPSILON = 0.5f
        // Eyelid render tween (25 Hz). Detection thresholds + coupling +
        // partial-closure smoothing live in TuningSettings (live-tunable).
        // Asymmetric: close fast, reopen softer -- natural blink dynamics; the
        // deadband stops sending once a lid is steady.
        private const val EYELID_CLOSE_ALPHA = 0.60f
        private const val EYELID_OPEN_ALPHA  = 0.35f
        private const val EYELID_DEADBAND = 1.5f

        private const val BLINK_HOLD_MS = 100L
        private const val SACCADE_DURATION_MS = 150L
        private const val GLANCE_DURATION_MIN_MS = 600L
        private const val GLANCE_DURATION_MAX_MS = 1400L

        private val GLANCE_DIRECTIONS = listOf(
            Pair(-0.30f, -0.10f), Pair( 0.30f, -0.10f),
            Pair(-0.30f,  0.15f), Pair( 0.30f,  0.15f),
            Pair(-0.40f,  0f   ), Pair( 0.40f,  0f   ),
            Pair( 0f   , -0.25f), Pair( 0f   ,  0.20f)
        )
    }
}
