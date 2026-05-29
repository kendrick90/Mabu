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
    private lateinit var micButton: TextView
    private var muteButton: TextView? = null

    // Safety net: if TTS never reports "done" (e.g. Pico SIGSEGVs mid-speech),
    // the echo-guard mute would stick and Mabu would go deaf. This re-opens the
    // mic after a generous timeout; it's cancelled on a normal speech-done.
    private val safetyUnmute = Runnable {
        Log.w(TAG, "TTS watchdog fired: re-opening mic (TTS likely crashed mid-speech)")
        remoteAsr?.muted = false
        if (tuning.cognitionMode == "streaming") micButton.text = "🎤 listening…"
    }
    private var streamingLlm: StreamingLlama? = null

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

    // Eyelid (PUPPET direct write; FOLLOW via blink only). Tracked
    // independently per side so each robot eyelid mirrors the matching
    // user eye -- closing one eye in PUPPET winks just one robot eye.
    private var lastLdlValue = MabuMotors.EYELID_NEUTRAL
    private var lastLdrValue = MabuMotors.EYELID_NEUTRAL

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

        if (motors.open()) {
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
        if (tuning.cognitionMode == "streaming") {
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
                    remoteAsr?.muted = speaking
                    handler.post {
                        if (!speaking) {
                            handler.removeCallbacks(safetyUnmute)   // normal finish
                            micButton.text = "🎤 listening…"
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
        if (tuning.cognitionMode == "streaming") {
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
        if (tuning.cognitionMode == "streaming") return   // toggle handled on DOWN
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
                remoteAsr?.muted = true
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
                if (!spokeAnything) handler.post { remoteAsr?.muted = false }
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
                        handler.post { onTranscript(text) }
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
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SAY)
            addAction(ACTION_SPEAK)
            addAction(ACTION_MODE)
            addAction(ACTION_STOP)
        }
        registerReceiver(rx, filter)
        debugReceiver = rx
        Log.i(TAG, "debug receiver registered (SAY / SPEAK / MODE / STOP)")
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
        settingsPanel.rebuildAfterPreset()
        Toast.makeText(this, "Mode: ${mode.name}", Toast.LENGTH_SHORT).show()
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
        cameraSource = Camera1Source(this, textureView, analyzer) { pw, ph, rot ->
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
            targetX = (0.5f + avgPupil.x * tuning.eyeGazeGain).coerceIn(0f, 1f)
            targetY = (0.5f + avgPupil.y * tuning.eyeGazeGain).coerceIn(0f, 1f)
        } else {
            targetX = (0.5f + (yaw   * tuning.neckRotSign  / tuning.neckAngleRange) * 0.5f).coerceIn(0f, 1f)
            targetY = (0.5f + (pitch * tuning.neckElevSign / tuning.neckAngleRange) * 0.5f).coerceIn(0f, 1f)
        }
    }

    /**
     * Eyelid mirroring (blinkMethod = "mirror" or "both"). Runs regardless
     * of mode so you can mix it with FOLLOW or any other preset. Mapping
     * is viewer-relative because that's what ML Kit returns on this build.
     */
    private fun maybeMirrorEyelids(result: FaceResult) {
        val face = result.faces.firstOrNull() ?: return
        val lp = face.leftEyeOpenProbability  ?: 1f
        val rp = face.rightEyeOpenProbability ?: 1f
        // Cross-eye coupling: at c=0 each eyelid follows its own raw prob
        // (false single-eye blinks slip through). At c=1 both follow the
        // brighter prob (fully linked). Intermediate values let the clearer
        // eye drag the noisier one up while still permitting deliberate
        // winks (where BOTH probs change).
        val c = tuning.eyelidCoupling
        val brighter = maxOf(lp, rp)
        val coupledL = lp * (1f - c) + brighter * c
        val coupledR = rp * (1f - c) + brighter * c
        val targetLdl = eyelidFromProb(coupledL)
        val targetLdr = eyelidFromProb(coupledR)
        val smoothedLdl = lastLdlValue + (targetLdl - lastLdlValue) * EYELID_ALPHA
        val smoothedLdr = lastLdrValue + (targetLdr - lastLdrValue) * EYELID_ALPHA
        if (kotlin.math.abs(smoothedLdl - lastLdlValue) > 1.5f ||
            kotlin.math.abs(smoothedLdr - lastLdrValue) > 1.5f) {
            motors.move(eyelidLeft = smoothedLdl, eyelidRight = smoothedLdr)
            lastLdlValue = smoothedLdl
            lastLdrValue = smoothedLdr
        }
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

    private fun eyelidFromProb(p: Float): Float {
        val clamped = p.coerceIn(0f, 1f)
        return MabuMotors.EYELID_CLOSED +
            (MabuMotors.EYELID_OPEN - MabuMotors.EYELID_CLOSED) * clamped
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
        Log.i(TAG, "Released camera + motors + tts + asr + remoteTts")
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

    /** Toggle the always-on mic (streaming mode). */
    private fun toggleMute() {
        val r = remoteAsr ?: return
        val nowMuted = !r.muted
        r.muted = nowMuted
        if (nowMuted) { try { tts.stop() } catch (_: Throwable) {}; overlayView.setHeardText(null) }
        updateMuteUi(nowMuted)
    }

    private fun updateMuteUi(muted: Boolean) {
        muteButton?.text = if (muted) "🔇" else "🎤"
        micButton.text = if (muted) "muted" else "🎤 listening…"
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
        private const val EYELID_ALPHA = 0.35f

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
