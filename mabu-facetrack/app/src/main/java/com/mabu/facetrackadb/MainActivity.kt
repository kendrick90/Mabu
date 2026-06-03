package com.mabu.facetrackadb

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: TextureView
    private lateinit var debugOverlay: TextView
    private var cameraSource: Camera1Source? = null
    private val motors = MabuMotors()
    private lateinit var detector: FaceDetector
    private val motorExecutor = Executors.newSingleThreadExecutor()

    // Soft limits: eyes do most of the work in this range.
    // LR and UD are separate because EUD is inverted + the large Y_OFFSET
    // biases the resting position well below 50, leaving very little upward
    // headroom if EYE_UD_MIN is too conservative.
    private val EYE_LR_MIN = 15.0
    private val EYE_LR_MAX = 85.0
    private val EYE_UD_MIN = 5.0    // physical stop at ~wire=0; EUD_MAX_RATE cap handles overshoot now
    private val EYE_UD_MAX = 85.0
    private val NECK_MIN = 20.0
    private val NECK_MAX = 80.0
    private val NECK_CENTER = MabuMotors.NR_NEUTRAL
    private val EYE_CENTER = 50.0
    private val SMOOTH       = 0.30  // lower = slower, smoother following
    private val DEADBAND     = 1.5   // motor units; corrections smaller than this are ignored
    private val EUD_MAX_RATE = 1.0   // max EUD change per tick on RETURN to center only (prevents PID overshoot)
    private val NE_MAX_RATE  = 1.0   // max NE change per tick on RETURN to neutral (mirror of EUD treatment — NE was 16% reversal rate in session 18 telemetry)
    // After a new tracking ID is confirmed, slew-limit all motors for this many ticks
    // to soak the input jump from snapping to a new face position. ~10 ticks @ 50ms = 500ms.
    private val ID_TRANSITION_FRAMES = 10
    private val ID_TRANSITION_MAX_RATE = 2.0
    private var idTransitionRemaining = 0

    // NE (neck elevation = up/down) has a different mechanical center than 50.
    // Community calibration: NE neutral ~25. Hard limits kept conservative.
    // Higher NE value = head looks up (same sign as reference unit 4).
    private val NE_MIN = 18.0   // physical lower stop for this unit
    private val NE_MAX = 100.0  // true ceiling — wire() clamps at 255 = logical 100

    // Eye/neck coordination thresholds.
    // Eyes lead; neck joins at 60%, eyes fully unlock when neck hits 80% or eye hits 90%.
    private val EYE_NECK_TRIGGER = 0.60   // eye at 60% of range → neck starts (LR axis)
    private val UD_NECK_TRIGGER  = 0.05   // lower trigger for UD: Y_OFFSET=-0.70 caps downward ay at ~0.30
    private val NECK_FULL_UNLOCK = 0.80   // neck at 80% of range → eye unlocks to 100%
    private val EYE_FULL_UNLOCK  = 0.90   // eye at 90% → both unlock to 100%

    // Calibration offsets — tune these to compensate for camera mounting angle.
    // Positive Y_OFFSET shifts tracking center down (face appears high in frame);
    // negative shifts it up (face appears low — the current Mabu unit needs ~-0.35).
    // X_OFFSET works the same horizontally.
    private val Y_OFFSET = -0.70  // pixel center correction + upward camera tilt compensation
    private val X_OFFSET = 0.0
    // Face center xNorm tops out around ±0.7 in practice (never at the literal pixel edge).
    // This gain maps that practical range to the full ±1.0 effort, using the full eye range.
    // Clamped to ±1.0 after scaling so it can't overshoot the motor limits.
    private val ELR_GAIN = 1.4

    private var posELR = EYE_CENTER
    private var posEUD = EYE_CENTER
    private var posNR  = NECK_CENTER
    private var posNE  = MabuMotors.NE_NEUTRAL  // elevation (up/down), NOT tilt

    @Volatile private var trackingPaused = false

    private val pauseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trackingPaused = intent.getBooleanExtra("paused", false)
            Log.i(TAG, "PAUSE_TRACKING: paused=$trackingPaused")
        }
    }

    private var lastSendMs = 0L
    private val SEND_INTERVAL_MS = 50L
    private var lastFaceMs = 0L
    private val FACE_LOSS_GRACE_MS = 750L
    private var confirmedTrackingId: Int = -1
    private var candidateTrackingId: Int = -1
    private var candidateFrameCount: Int = 0
    private val TRACKING_CONFIRM_FRAMES = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        debugOverlay = findViewById(R.id.debugOverlay)

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)
            .enableTracking()
            .build()
        detector = FaceDetection.getClient(options)

        registerReceiver(pauseReceiver, IntentFilter("com.mabu.facetrackadb.PAUSE_TRACKING"))

        Thread {
            if (!motors.open()) {
                Log.e(TAG, "Motors did not open — face tracking will run but Mabu won't move")
            }
        }.start()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    // Perf instrumentation — measure ML Kit inference time and frame inter-arrival
    // separately so we can tell hardware-ceiling latency from thread-contention jitter.
    // Reported in a single log line every PERF_LOG_EVERY_N frames (~5s at 10fps).
    private val PERF_LOG_EVERY_N = 50
    private var perfFrameCount = 0
    private var perfInferenceUsSum = 0L
    private var perfInferenceUsMax = 0L
    private var perfIntervalUsSum = 0L
    private var perfIntervalUsMax = 0L
    private var perfLastFrameNs = 0L
    // Bucket inference times to see distribution shape, not just avg+max.
    private val perfBuckets = IntArray(8)  // <20, <30, <40, <50, <60, <80, <120, ≥120 ms
    private fun bucketIndex(ms: Long): Int = when {
        ms < 20 -> 0; ms < 30 -> 1; ms < 40 -> 2; ms < 50 -> 3
        ms < 60 -> 4; ms < 80 -> 5; ms < 120 -> 6; else -> 7
    }
    // Overlay update rate-limit: writing to TextView triggers main-thread re-layout
    // every frame, which competes with our background-priority camera thread for CPU.
    private var lastOverlayMs = 0L
    private val OVERLAY_INTERVAL_MS = 250L  // 4 Hz update is plenty for human inspection

    private fun startCamera() {
        cameraSource = Camera1Source(this, previewView) { image, onDone ->
            val w = image.width.toDouble()
            val h = image.height.toDouble()
            val inferStartNs = System.nanoTime()
            detector.process(image)
                // Run on motorExecutor so motor writes (TCP socket) stay off the main thread.
                .addOnSuccessListener(motorExecutor) { faces ->
                    Log.d(TAG, "faces=${faces.size}")
                    if (faces.isEmpty()) {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastOverlayMs >= OVERLAY_INTERVAL_MS) {
                            lastOverlayMs = nowMs
                            runOnUiThread { debugOverlay.text = "faces=0" }
                        }
                        val now = nowMs
                        if (now - lastFaceMs > FACE_LOSS_GRACE_MS) {
                            confirmedTrackingId = -1
                            candidateTrackingId = -1
                            candidateFrameCount = 0
                            posELR += deadbandSmooth(posELR, EYE_CENTER)
                            val dEUD = deadbandSmooth(posEUD, EYE_CENTER)
                            posEUD += if (dEUD > 0) dEUD.coerceAtMost(EUD_MAX_RATE) else dEUD
                            posNR  += deadbandSmooth(posNR,  NECK_CENTER)
                            posNE  += deadbandSmooth(posNE,  MabuMotors.NE_NEUTRAL)
                            if (!trackingPaused && now - lastSendMs >= SEND_INTERVAL_MS && motors.isOpen()) {
                                motors.moveAll(MabuMotors.EYELID_NEUTRAL, MabuMotors.EYELID_NEUTRAL,
                                    posELR, posEUD, posNE, posNR, MabuMotors.NT_NEUTRAL)
                                lastSendMs = now
                            }
                        }
                        return@addOnSuccessListener
                    }
                    // Prefer the confirmed face if it's still visible; else pick biggest.
                    val selected = faces.firstOrNull { it.trackingId == confirmedTrackingId }
                        ?: faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                    val xNorm = (selected.boundingBox.exactCenterX() - w / 2) / (w / 2)
                    val yNorm = (selected.boundingBox.exactCenterY() - h / 2) / (h / 2)
                    if (kotlin.math.abs(xNorm) > 1.0 || kotlin.math.abs(yNorm) > 1.0) {
                        Log.d(TAG, "face out of bounds xNorm=%.2f yNorm=%.2f — skipped".format(xNorm, yNorm))
                        return@addOnSuccessListener
                    }
                    // Hysteresis: a new face ID must appear for TRACKING_CONFIRM_FRAMES
                    // consecutive frames before we accept it. Ghost detections fire for
                    // only 1–3 frames, so this threshold filters them without delaying
                    // legitimate re-acquisition meaningfully (~400ms at 10Hz).
                    val id = selected.trackingId ?: -1
                    if (id != confirmedTrackingId) {
                        if (id == candidateTrackingId) candidateFrameCount++
                        else { candidateTrackingId = id; candidateFrameCount = 1 }
                        if (candidateFrameCount >= TRACKING_CONFIRM_FRAMES) {
                            Log.d(TAG, "tracking ID $id confirmed (slew-limit ${ID_TRANSITION_FRAMES}f)")
                            confirmedTrackingId = id
                            candidateTrackingId = -1
                            candidateFrameCount = 0
                            idTransitionRemaining = ID_TRANSITION_FRAMES
                        } else {
                            return@addOnSuccessListener
                        }
                    }
                    lastFaceMs = System.currentTimeMillis()
                    Log.d(TAG, "face xNorm=%.2f yNorm=%.2f".format(xNorm, yNorm))
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastOverlayMs >= OVERLAY_INTERVAL_MS) {
                        lastOverlayMs = nowMs
                        runOnUiThread {
                            debugOverlay.text = "x=%.2f y=%.2f\nelr=%.1f eud=%.1f\nnr=%.1f ne=%.1f".format(
                                xNorm, yNorm, posELR, posEUD, posNR, posNE)
                        }
                    }
                    updateAndSendMotors(xNorm, yNorm)
                }
                .addOnFailureListener { Log.e(TAG, "Face detection failed: ${it.message}", it) }
                .addOnCompleteListener {
                    val nowNs = System.nanoTime()
                    val inferUs = (nowNs - inferStartNs) / 1000L
                    val intervalUs = if (perfLastFrameNs == 0L) 0L else (nowNs - perfLastFrameNs) / 1000L
                    perfLastFrameNs = nowNs
                    perfInferenceUsSum += inferUs
                    if (inferUs > perfInferenceUsMax) perfInferenceUsMax = inferUs
                    if (intervalUs > 0) {
                        perfIntervalUsSum += intervalUs
                        if (intervalUs > perfIntervalUsMax) perfIntervalUsMax = intervalUs
                    }
                    perfBuckets[bucketIndex(inferUs / 1000L)]++
                    perfFrameCount++
                    if (perfFrameCount >= PERF_LOG_EVERY_N) {
                        val avgInfMs = (perfInferenceUsSum / perfFrameCount) / 1000.0
                        val avgIntMs = (perfIntervalUsSum / (perfFrameCount - 1).coerceAtLeast(1)) / 1000.0
                        val jitterMs = (perfIntervalUsMax / 1000.0) - avgIntMs
                        val nativeHeapKb = android.os.Debug.getNativeHeapAllocatedSize() / 1024L
                        Log.i(TAG, "perf n=%d  inference avg=%.1fms max=%.1fms | interval avg=%.1fms max=%.1fms jitter=+%.1fms | fps=%.1f | nativeHeap=%dKB".format(
                            perfFrameCount, avgInfMs, perfInferenceUsMax / 1000.0,
                            avgIntMs, perfIntervalUsMax / 1000.0, jitterMs,
                            1000.0 / avgIntMs, nativeHeapKb))
                        Log.i(TAG, "infer-histogram <20:%d <30:%d <40:%d <50:%d <60:%d <80:%d <120:%d ≥120:%d".format(
                            perfBuckets[0], perfBuckets[1], perfBuckets[2], perfBuckets[3],
                            perfBuckets[4], perfBuckets[5], perfBuckets[6], perfBuckets[7]))
                        perfFrameCount = 0
                        perfInferenceUsSum = 0; perfInferenceUsMax = 0
                        perfIntervalUsSum = 0; perfIntervalUsMax = 0
                        for (i in perfBuckets.indices) perfBuckets[i] = 0
                    }
                    onDone()
                }
        }
    }

    private fun updateAndSendMotors(xNorm: Double, yNorm: Double) {
        val ax = clamp((xNorm + X_OFFSET) * ELR_GAIN, -1.0, 1.0)
        val ay = yNorm + Y_OFFSET

        // 2D magnitude: used to share the unlock condition across axes.
        // Prevents the case where one axis is at its extreme (fully unlocked) while
        // the other axis is still in zone 1 and capped at 60% range.
        val mag2D = sqrt(ax * ax + ay * ay)

        // LR axis: face right → ELR up, NR down (neckSign = -1)
        val (targetELR, targetNR) = computeEyeNeckAxis(
            ax, EYE_CENTER, EYE_LR_MIN, EYE_LR_MAX,
            NECK_CENTER, NECK_MIN, NECK_MAX, neckSign = -1.0, mag2D = mag2D
        )
        // UD axis: face up (ay<0) → EUD down (inverted, lower=up), NE up (neckSign = -1)
        val (targetEUD, targetNE) = computeEyeNeckAxis(
            ay, EYE_CENTER, EYE_UD_MIN, EYE_UD_MAX,
            MabuMotors.NE_NEUTRAL, NE_MIN, NE_MAX, neckSign = -1.0, mag2D = mag2D,
            neckTrigger = UD_NECK_TRIGGER
        )

        val dELR = deadbandSmooth(posELR, targetELR)
        val dEUD = deadbandSmooth(posEUD, targetEUD)
        val dNR  = deadbandSmooth(posNR,  targetNR)
        val dNE  = deadbandSmooth(posNE,  targetNE)

        // EUD return-to-center cap (delta > 0 = moving away from "up" toward neutral 50)
        val dEUDcapped = if (dEUD > 0) dEUD.coerceAtMost(EUD_MAX_RATE) else dEUD
        // NE return-to-neutral cap (delta < 0 = head coming down from elevated)
        val dNEcapped  = if (dNE < 0) dNE.coerceAtLeast(-NE_MAX_RATE) else dNE

        // Slew-limit window after a new tracking ID is confirmed — soaks the input jump
        // when the tracker swaps to a face that may be in a different reported position.
        var sELR = dELR; var sEUD = dEUDcapped; var sNR = dNR; var sNE = dNEcapped
        if (idTransitionRemaining > 0) {
            idTransitionRemaining--
            val cap = ID_TRANSITION_MAX_RATE
            sELR = sELR.coerceIn(-cap, cap)
            sEUD = sEUD.coerceIn(-cap, cap)
            sNR  = sNR.coerceIn(-cap, cap)
            sNE  = sNE.coerceIn(-cap, cap)
        }
        posELR += sELR
        posEUD += sEUD
        posNR  += sNR
        posNE  += sNE

        val now = System.currentTimeMillis()
        Log.d(TAG, "motor ax=%.2f ay=%.2f tELR=%.1f tEUD=%.1f tNR=%.1f tNE=%.1f pELR=%.1f pEUD=%.1f pNR=%.1f pNE=%.1f idSlew=%d".format(
            ax, ay, targetELR, targetEUD, targetNR, targetNE, posELR, posEUD, posNR, posNE, idTransitionRemaining))
        if (trackingPaused) return
        if (now - lastSendMs >= SEND_INTERVAL_MS && motors.isOpen()) {
            motors.moveAll(
                ldl = MabuMotors.EYELID_NEUTRAL,
                ldr = MabuMotors.EYELID_NEUTRAL,
                elr = posELR,
                eud = posEUD,
                ne  = posNE,
                nr  = posNR,
                nt  = MabuMotors.NT_NEUTRAL
            )
            lastSendMs = now
        }
    }

    /**
     * Computes eye and neck targets for one axis using a three-zone model:
     *   Zone 1 (effort 0–60%): eyes track alone, neck stays at neutral.
     *   Zone 2 (60–90%): neck ramps in; eye cap rises from 60% → 100% proportional to neck.
     *   Unlock: neck >= 80% of range OR eye effort >= 90% → both use 100% of range.
     *
     * neckSign = +1 if neck moves same direction as eye, -1 if opposite.
     * mag2D = combined 2D face magnitude; if it crosses EYE_FULL_UNLOCK, both axes unlock
     *         regardless of their individual effort. Keeps axes independent in movement
     *         while sharing the global "far from center" unlock signal.
     */
    private fun computeEyeNeckAxis(
        effort: Double,
        eyeCenter: Double, eyeMin: Double, eyeMax: Double,
        neckNeutral: Double, neckMin: Double, neckMax: Double,
        neckSign: Double,
        mag2D: Double = 0.0,
        neckTrigger: Double = EYE_NECK_TRIGGER
    ): Pair<Double, Double> {
        val sign    = if (effort >= 0.0) 1.0 else -1.0
        val mag     = kotlin.math.abs(effort)
        val neckDir = sign * neckSign

        // Max displacement from neutral toward the active pole
        val eyeMaxDisp  = if (effort >= 0.0) eyeMax - eyeCenter else eyeCenter - eyeMin
        val neckMaxDisp = if (neckDir > 0.0) neckMax - neckNeutral else neckNeutral - neckMin

        // Neck: zero until per-axis trigger, then linearly ramps to 100% of its range.
        // Neck trigger is intentionally per-axis — neck should only engage in a direction
        // when the face is actually displaced in that direction.
        val neckFrac = if (mag < neckTrigger) 0.0
                       else minOf((mag - neckTrigger) / (1.0 - neckTrigger), 1.0)

        // Eye unlock: per-axis thresholds OR combined 2D magnitude exceeds the full-unlock
        // threshold. The 2D check prevents one axis from being capped at 60% while the other
        // is fully unlocked (e.g. face at top of frame → UD unlocked, LR should also unlock).
        val eyeClampFrac = when {
            mag >= EYE_FULL_UNLOCK || mag2D >= EYE_FULL_UNLOCK -> 1.0
            neckFrac >= NECK_FULL_UNLOCK                        -> 1.0
            mag < EYE_NECK_TRIGGER                              -> EYE_NECK_TRIGGER
            else -> EYE_NECK_TRIGGER + (neckFrac / NECK_FULL_UNLOCK) * (1.0 - EYE_NECK_TRIGGER)
        }

        val eyeDisp  = minOf(mag * eyeMaxDisp, eyeMaxDisp * eyeClampFrac)
        val neckDisp = neckFrac * neckMaxDisp

        return Pair(
            clamp(eyeCenter + sign    * eyeDisp,  eyeMin,  eyeMax),
            clamp(neckNeutral + neckDir * neckDisp, neckMin, neckMax)
        )
    }

    // Skip corrections smaller than DEADBAND to suppress face-detection noise.
    // Full SMOOTH gain is applied once the threshold is crossed — the soft version
    // was subtracting DEADBAND from every correction, killing range on large moves too.
    private fun deadbandSmooth(current: Double, target: Double): Double {
        val error = target - current
        if (kotlin.math.abs(error) < DEADBAND) return 0.0
        return error * SMOOTH
    }

    private fun clamp(v: Double, lo: Double, hi: Double) = max(lo, min(hi, v))

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(pauseReceiver)
        cameraSource?.release()
        motorExecutor.shutdown()
        motors.close()
    }

    companion object {
        private const val TAG = "MabuFaceTrack"
        private const val CAMERA_PERMISSION = 100
    }
}
