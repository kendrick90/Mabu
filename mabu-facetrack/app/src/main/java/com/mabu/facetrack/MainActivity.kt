package com.mabu.facetrack

import android.Manifest
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
    private val EYE_UD_MIN =  5.0   // lower = eyes look UP on this unit; physical stop unknown, tune if grinding
    private val EYE_UD_MAX = 85.0
    private val NECK_MIN = 20.0
    private val NECK_MAX = 80.0
    private val NECK_CENTER = MabuMotors.NR_NEUTRAL
    private val EYE_CENTER = 50.0
    private val SMOOTH    = 0.12  // lower = slower, smoother following
    private val DEADBAND  = 1.5   // motor units; corrections smaller than this are ignored

    // NE (neck elevation = up/down) has a different mechanical center than 50.
    // Community calibration: NE neutral ~25. Hard limits kept conservative.
    // Higher NE value = head looks up (same sign as reference unit 4).
    private val NE_MIN = 18.0   // physical lower stop for this unit
    private val NE_MAX = 100.0  // true ceiling — wire() clamps at 255 = logical 100

    // Eye/neck coordination thresholds.
    // Eyes lead; neck joins at 60%, eyes fully unlock when neck hits 80% or eye hits 90%.
    private val EYE_NECK_TRIGGER = 0.60   // eye at 60% of range → neck starts
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

    private var lastSendMs = 0L
    private val SEND_INTERVAL_MS = 70L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        debugOverlay = findViewById(R.id.debugOverlay)

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
        detector = FaceDetection.getClient(options)

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

    private fun startCamera() {
        cameraSource = Camera1Source(this, previewView) { image, onDone ->
            val w = image.width.toDouble()
            val h = image.height.toDouble()
            detector.process(image)
                // Run on motorExecutor so motor writes (TCP socket) stay off the main thread.
                .addOnSuccessListener(motorExecutor) { faces ->
                    Log.d(TAG, "faces=${faces.size}")
                    if (faces.isEmpty()) {
                        runOnUiThread { debugOverlay.text = "faces=0" }
                        return@addOnSuccessListener
                    }
                    val biggest = faces.maxByOrNull {
                        it.boundingBox.width() * it.boundingBox.height()
                    }!!
                    val xNorm = (biggest.boundingBox.exactCenterX() - w / 2) / (w / 2)
                    val yNorm = (biggest.boundingBox.exactCenterY() - h / 2) / (h / 2)
                    Log.d(TAG, "face xNorm=%.2f yNorm=%.2f".format(xNorm, yNorm))
                    runOnUiThread {
                        debugOverlay.text = "x=%.2f y=%.2f\nelr=%.1f eud=%.1f\nnr=%.1f ne=%.1f".format(
                            xNorm, yNorm, posELR, posEUD, posNR, posNE)
                    }
                    updateAndSendMotors(xNorm, yNorm)
                }
                .addOnFailureListener { Log.e(TAG, "Face detection failed: ${it.message}", it) }
                .addOnCompleteListener { onDone() }
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
            MabuMotors.NE_NEUTRAL, NE_MIN, NE_MAX, neckSign = -1.0, mag2D = mag2D
        )

        posELR += deadbandSmooth(posELR, targetELR)
        posEUD += deadbandSmooth(posEUD, targetEUD)
        posNR  += deadbandSmooth(posNR,  targetNR)
        posNE  += deadbandSmooth(posNE,  targetNE)

        val now = System.currentTimeMillis()
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
        mag2D: Double = 0.0
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
        val neckFrac = if (mag < EYE_NECK_TRIGGER) 0.0
                       else minOf((mag - EYE_NECK_TRIGGER) / (1.0 - EYE_NECK_TRIGGER), 1.0)

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
        cameraSource?.release()
        motorExecutor.shutdown()
        motors.close()
    }

    companion object {
        private const val TAG = "MabuFaceTrack"
        private const val CAMERA_PERMISSION = 100
    }
}
