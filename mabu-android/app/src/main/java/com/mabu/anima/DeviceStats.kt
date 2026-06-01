package com.mabu.anima

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide rolling perf snapshot. Subsystems (FaceAnalyzer, PipecatVoice,
 * MabuMotors, MainActivity lifecycle) call the record* methods; the HUD and
 * the /status HTTP endpoint both read [snapshot].
 *
 * Lock-free on the hot paths -- ML Kit calls [recordMlKitFrame] every frame
 * from a background thread, so it's an atomic increment plus an atomic add.
 * Window stats (FPS, mean/p95) are computed on demand in [snapshot] from
 * cumulative counters; cheap because the HUD only refreshes at 2 Hz.
 */
object DeviceStats {

    private val startMs = SystemClock.uptimeMillis()

    // ML Kit (full: landmarks + classification + gaze + crop)
    private val mlkitFrames = AtomicInteger(0)
    private val mlkitFrameTimeUs = AtomicLong(0L)
    private val mlkitMaxFrameTimeUs = AtomicLong(0L)
    private var lastMlkitFrames = 0
    private var lastMlkitFrameTimeUs = 0L

    // ML Kit fast path (bbox + tracking ID only). Runs in parallel with the
    // full detector; the experiment is to see whether bbox can be returned
    // meaningfully faster than landmarks + classification on this device.
    private val bboxFrames = AtomicInteger(0)
    private val bboxFrameTimeUs = AtomicLong(0L)
    private var lastBboxFrames = 0
    private var lastBboxFrameTimeUs = 0L

    // WebRTC outbound video (populated by PipecatVoice when video is enabled).
    @Volatile var videoSendingKbps: Int = 0
    @Volatile var videoSendingFps: Int = 0
    @Volatile var videoRttMs: Int = 0
    @Volatile var videoPacketsLost: Long = 0L

    // Transport / app state mirrors. Plain volatiles -- writers are
    // single-threaded (main thread for these flags).
    @Volatile var transportState: String = "disconnected"
    @Volatile var micEnabled: Boolean = true
    @Volatile var camEnabled: Boolean = false
    @Volatile var motorLinkOpen: Boolean = false
    @Volatile var mode: String = "FOLLOW"
    @Volatile var blinkMethod: String = "spontaneous"

    // ---- Animation / motor telemetry (for realtime jitter + state monitoring) --
    // Actual last-sent motor positions (0..100; -1 = never sent). Written by
    // MabuMotors.move so this reflects exactly what went on the wire, including
    // scripted blinks and resting poses -- not just the puppet tween.
    @Volatile var motorEyelidL = -1f
    @Volatile var motorEyelidR = -1f
    @Volatile var motorEyesLR = -1f
    @Volatile var motorEyesUD = -1f
    @Volatile var motorNeckRot = -1f
    @Volatile var motorNeckElev = -1f
    @Volatile var motorNeckTilt = -1f
    // Motor-command counter -> windowed Hz in [snapshot]. Bus chatter at rest
    // (deadbands should keep it near 0) is the most direct jitter proxy.
    private val motorCmds = AtomicLong(0L)
    private var lastMotorCmds = 0L

    // Calibration "READY" handshake: the on-device overlay button bumps this;
    // the PC harness polls it to advance self-paced (no countdown to nail).
    private val readySeq = AtomicInteger(0)
    fun bumpReady() { readySeq.incrementAndGet() }

    // Stable rate window. All windowed figures (mlkit/bbox fps, motor cmd Hz)
    // are recomputed at most once per RATE_WINDOW_MS and cached between, so they
    // don't fluctuate with poll cadence or get chopped across concurrent
    // pollers (HUD + browser + PC monitor all call snapshot). Battery is a
    // binder IPC; cache it and refresh infrequently so we never do an IPC under
    // the snapshot lock on every poll (that caused brief telemetry stalls).
    private val RATE_WINDOW_MS = 1000L
    private val BATTERY_WINDOW_MS = 5000L
    private var lastRateWindowMs = startMs
    private var cachedMlkitFps = 0f
    private var cachedMlkitMeanMs = 0f
    private var cachedMlkitMaxMs = 0f
    private var cachedBboxFps = 0f
    private var cachedBboxMeanMs = 0f
    private var cachedMotorHz = 0f
    private var lastBatteryMs = 0L
    private var cachedBatteryPct = -1
    private var cachedBatteryTempC = Float.NaN

    // Animation pipeline values (PUPPET). Raw = straight from ML Kit (noisy);
    // these let the PC see where jitter enters vs the filtered/target values.
    @Volatile var headYaw = 0f
    @Volatile var headPitch = 0f
    @Volatile var headRoll = 0f
    @Volatile var pupilRawX = 0f
    @Volatile var pupilRawY = 0f
    @Volatile var pupilFiltX = 0f
    @Volatile var pupilFiltY = 0f
    // Face bounding-box center + size in the (rotated) image, normalized 0..1.
    // The FOLLOW screen-space signal; lets calibration see the raw face position.
    @Volatile var faceCenterX = 0.5f
    @Volatile var faceCenterY = 0.5f
    @Volatile var faceWidthFrac = 0f
    @Volatile var eyeOpenProbL = -1f
    @Volatile var eyeOpenProbR = -1f
    @Volatile var eyeClosedL = false
    @Volatile var eyeClosedR = false
    // True while a face is currently detected. Distinct from the held sensor
    // values (which keep their last reading on loss) -- this is the honest
    // "is there a face right now" signal the calibration harness gates on.
    @Volatile var facePresent = false
    // Head-pose eyelid reliability: 1 = facing forward (probs trusted),
    // 0 = turned past the limit (lids forced open to avoid false closure).
    @Volatile var eyelidPoseRel = 1f
    // Tween targets (0..100) the renderer is easing toward.
    @Volatile var targetEyesLR = 50f
    @Volatile var targetEyesUD = 50f
    @Volatile var targetNeckRot = 50f
    @Volatile var targetNeckElev = 50f
    @Volatile var targetNeckTilt = 50f

    /** Record one motor frame actually sent to the board. Updates only the
     *  motors present in this command (nulls are skipped, matching the
     *  partial-update protocol) and bumps the command counter. */
    fun recordMotorCommand(
        eyelidLeft: Float?, eyelidRight: Float?, eyesLR: Float?, eyesUD: Float?,
        neckElev: Float?, neckRot: Float?, neckTilt: Float?
    ) {
        eyelidLeft?.let  { motorEyelidL = it }
        eyelidRight?.let { motorEyelidR = it }
        eyesLR?.let      { motorEyesLR = it }
        eyesUD?.let      { motorEyesUD = it }
        neckElev?.let    { motorNeckElev = it }
        neckRot?.let     { motorNeckRot = it }
        neckTilt?.let    { motorNeckTilt = it }
        motorCmds.incrementAndGet()
    }

    /** Record one completed ML Kit detection. Call from the analyzer's
     *  onComplete callback with the elapsed time in microseconds. */
    fun recordMlKitFrame(elapsedUs: Long) {
        mlkitFrames.incrementAndGet()
        mlkitFrameTimeUs.addAndGet(elapsedUs)
        // Cheap max-tracking. Not perfectly atomic across the two ops but
        // good enough for a HUD readout.
        val prev = mlkitMaxFrameTimeUs.get()
        if (elapsedUs > prev) mlkitMaxFrameTimeUs.set(elapsedUs)
    }

    /** Record one completed bbox-only fast-path detection (FastFaceAnalyzer),
     *  elapsed time in microseconds. Separate counter so its FPS can be
     *  compared against the full ML Kit pipeline. */
    fun recordBboxFrame(elapsedUs: Long) {
        bboxFrames.incrementAndGet()
        bboxFrameTimeUs.addAndGet(elapsedUs)
    }

    data class Snapshot(
        val uptimeSec: Long,
        val mlkitFps: Float,
        val mlkitMeanMs: Float,
        val mlkitMaxMs: Float,
        val mlkitTotalFrames: Int,
        val bboxFps: Float,
        val bboxMeanMs: Float,
        val bboxTotalFrames: Int,
        val videoSendingKbps: Int,
        val videoSendingFps: Int,
        val videoRttMs: Int,
        val videoPacketsLost: Long,
        val transportState: String,
        val micEnabled: Boolean,
        val camEnabled: Boolean,
        val motorLinkOpen: Boolean,
        val mode: String,
        val motorCmdHz: Float,
        val heapUsedMb: Int,
        val heapMaxMb: Int,
        val batteryPct: Int,
        val batteryTempC: Float,
    )

    /** Take a snapshot. Windowed rates are recomputed at most every
     *  RATE_WINDOW_MS and cached, so calling this at any cadence (or from
     *  several pollers at once) returns stable figures. Cheap and lock-light:
     *  no per-call binder IPC. */
    @Synchronized
    fun snapshot(ctx: Context?): Snapshot {
        val now = SystemClock.uptimeMillis()
        val totalFrames = mlkitFrames.get()
        val totalBbox = bboxFrames.get()

        // Recompute all windowed rates together, at most once per window.
        val dWindowMs = now - lastRateWindowMs
        if (dWindowMs >= RATE_WINDOW_MS) {
            val frameTimeUs = mlkitFrameTimeUs.get()
            val dFrames = totalFrames - lastMlkitFrames
            val dTimeUs = frameTimeUs - lastMlkitFrameTimeUs
            cachedMlkitFps = if (dFrames > 0) dFrames * 1000f / dWindowMs else 0f
            cachedMlkitMeanMs = if (dFrames > 0) (dTimeUs / dFrames) / 1000f else 0f
            cachedMlkitMaxMs = mlkitMaxFrameTimeUs.getAndSet(0L) / 1000f
            lastMlkitFrames = totalFrames
            lastMlkitFrameTimeUs = frameTimeUs

            val bTimeUs = bboxFrameTimeUs.get()
            val dB = totalBbox - lastBboxFrames
            val dBTimeUs = bTimeUs - lastBboxFrameTimeUs
            cachedBboxFps = if (dB > 0) dB * 1000f / dWindowMs else 0f
            cachedBboxMeanMs = if (dB > 0) (dBTimeUs / dB) / 1000f else 0f
            lastBboxFrames = totalBbox
            lastBboxFrameTimeUs = bTimeUs

            val cmds = motorCmds.get()
            cachedMotorHz = (cmds - lastMotorCmds) * 1000f / dWindowMs
            lastMotorCmds = cmds

            lastRateWindowMs = now
        }

        // Battery is a binder IPC -- refresh it on its own slow cadence, cached.
        if (ctx != null && (now - lastBatteryMs >= BATTERY_WINDOW_MS || lastBatteryMs == 0L)) {
            try {
                val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (intent != null) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) cachedBatteryPct = (level * 100 / scale)
                    val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    if (tempTenths != Int.MIN_VALUE) cachedBatteryTempC = tempTenths / 10f
                }
            } catch (_: Throwable) { /* never crash a poll on a battery read */ }
            lastBatteryMs = now
        }

        val rt = Runtime.getRuntime()
        val heapUsed = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val heapMax = (rt.maxMemory() / (1024 * 1024)).toInt()

        return Snapshot(
            uptimeSec = (now - startMs) / 1000,
            mlkitFps = cachedMlkitFps,
            mlkitMeanMs = cachedMlkitMeanMs,
            mlkitMaxMs = cachedMlkitMaxMs,
            mlkitTotalFrames = totalFrames,
            bboxFps = cachedBboxFps,
            bboxMeanMs = cachedBboxMeanMs,
            bboxTotalFrames = totalBbox,
            videoSendingKbps = videoSendingKbps,
            videoSendingFps = videoSendingFps,
            videoRttMs = videoRttMs,
            videoPacketsLost = videoPacketsLost,
            transportState = transportState,
            micEnabled = micEnabled,
            camEnabled = camEnabled,
            motorLinkOpen = motorLinkOpen,
            mode = mode,
            motorCmdHz = cachedMotorHz,
            heapUsedMb = heapUsed,
            heapMaxMb = heapMax,
            batteryPct = cachedBatteryPct,
            batteryTempC = cachedBatteryTempC,
        )
    }

    fun Snapshot.toJson(): JSONObject = JSONObject().apply {
        put("uptime_sec", uptimeSec)
        put("mlkit_fps", round1(mlkitFps))
        put("mlkit_mean_ms", round1(mlkitMeanMs))
        put("mlkit_max_ms", round1(mlkitMaxMs))
        put("mlkit_total_frames", mlkitTotalFrames)
        put("bbox_fps", round1(bboxFps))
        put("bbox_mean_ms", round1(bboxMeanMs))
        put("bbox_total_frames", bboxTotalFrames)
        put("video_sending_kbps", videoSendingKbps)
        put("video_sending_fps", videoSendingFps)
        put("video_rtt_ms", videoRttMs)
        put("video_packets_lost", videoPacketsLost)
        put("transport_state", transportState)
        put("mic_enabled", micEnabled)
        put("cam_enabled", camEnabled)
        put("motor_link_open", motorLinkOpen)
        put("mode", mode)
        put("ready_seq", readySeq.get())
        put("heap_used_mb", heapUsedMb)
        put("heap_max_mb", heapMaxMb)
        put("battery_pct", batteryPct)
        put("battery_temp_c", if (batteryTempC.isNaN()) JSONObject.NULL else round1(batteryTempC))

        // Motors: actual last-sent positions (what's on the wire) + command
        // rate. Read live (not snapshotted) -- fine for realtime monitoring.
        put("motors", JSONObject().apply {
            put("cmd_hz", round1(motorCmdHz))
            put("eyelid_l", pos(motorEyelidL))
            put("eyelid_r", pos(motorEyelidR))
            put("eyes_lr", pos(motorEyesLR))
            put("eyes_ud", pos(motorEyesUD))
            put("neck_rot", pos(motorNeckRot))
            put("neck_elev", pos(motorNeckElev))
            put("neck_tilt", pos(motorNeckTilt))
        })
        // Animation pipeline: raw ML Kit inputs -> filtered -> targets -> state.
        // Lets the PC chart input noise vs. what reaches the motors.
        put("animation", JSONObject().apply {
            put("blink_method", blinkMethod)
            put("head_yaw", round1(headYaw))
            put("head_pitch", round1(headPitch))
            put("head_roll", round1(headRoll))
            put("pupil_raw_x", round2(pupilRawX))
            put("pupil_raw_y", round2(pupilRawY))
            put("pupil_filt_x", round2(pupilFiltX))
            put("pupil_filt_y", round2(pupilFiltY))
            put("face_center_x", round2(faceCenterX))
            put("face_center_y", round2(faceCenterY))
            put("face_width_frac", round2(faceWidthFrac))
            put("eye_open_prob_l", if (eyeOpenProbL < 0f) JSONObject.NULL else round2(eyeOpenProbL))
            put("eye_open_prob_r", if (eyeOpenProbR < 0f) JSONObject.NULL else round2(eyeOpenProbR))
            put("eye_closed_l", eyeClosedL)
            put("eye_closed_r", eyeClosedR)
            put("face_present", facePresent)
            put("pose_reliability", round2(eyelidPoseRel))
            put("target_eyes_lr", round1(targetEyesLR))
            put("target_eyes_ud", round1(targetEyesUD))
            put("target_neck_rot", round1(targetNeckRot))
            put("target_neck_elev", round1(targetNeckElev))
            put("target_neck_tilt", round1(targetNeckTilt))
        })
    }

    private fun pos(v: Float): Any = if (v < 0f) JSONObject.NULL else round1(v)
    private fun round1(v: Float): Float = (kotlin.math.round(v * 10f) / 10f)
    private fun round2(v: Float): Float = (kotlin.math.round(v * 100f) / 100f)
}
