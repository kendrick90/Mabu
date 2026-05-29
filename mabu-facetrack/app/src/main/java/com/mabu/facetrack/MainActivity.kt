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

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: TextureView
    private lateinit var debugOverlay: TextView
    private var cameraSource: Camera1Source? = null
    private val motors = MabuMotors()
    private lateinit var detector: FaceDetector
    private val motorExecutor = Executors.newSingleThreadExecutor()

    // Soft limits: eyes do most of the work in this range; outside it we
    // start moving the neck. Keeps Mabu looking natural rather than
    // jittering the neck on every micro-movement.
    private val EYE_SOFT_MIN = 15.0
    private val EYE_SOFT_MAX = 85.0
    private val NECK_MIN = 20.0
    private val NECK_MAX = 80.0
    private val NECK_CENTER = 50.0
    private val EYE_CENTER = 50.0
    private val SMOOTH = 0.15

    // NE (neck elevation = up/down) has a different mechanical center than 50.
    // Community calibration: NE neutral ~25. Hard limits kept conservative.
    // Higher NE value = head looks up (same sign as reference unit 4).
    private val NE_MIN = 18.0   // physical lower stop for this unit
    private val NE_MAX = 100.0  // true ceiling — wire() clamps at 255 = logical 100

    // Neck always follows face at this gain (reference neckFollowGain=0.4).
    // NR range: xNorm ±1 → posNR ±NECK_FOLLOW_RANGE from center.
    // NE gain maps ±0.5 ay → full upward travel of 15 units.
    private val NECK_FOLLOW_RANGE = 20.0
    private val NE_FOLLOW_RANGE   = 68.0

    // Calibration offsets — tune these to compensate for camera mounting angle.
    // Positive Y_OFFSET shifts tracking center down (face appears high in frame);
    // negative shifts it up (face appears low — the current Mabu unit needs ~-0.35).
    // X_OFFSET works the same horizontally.
    private val Y_OFFSET = -0.70  // pixel center correction + upward camera tilt compensation
    private val X_OFFSET = 0.0

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
        val ax = xNorm + X_OFFSET
        val ay = yNorm + Y_OFFSET

        // Eyes: track face directly, clamped to soft limits.
        val eyeLR = clamp(EYE_CENTER + ax * (EYE_SOFT_MAX - EYE_CENTER), EYE_SOFT_MIN, EYE_SOFT_MAX)
        val eyeUD = clamp(EYE_CENTER + ay * (EYE_SOFT_MAX - EYE_CENTER), EYE_SOFT_MIN, EYE_SOFT_MAX)

        // Neck: always follows face proportionally every frame (reference neckFollowGain=0.4).
        // NR: face right (ax>0) → NR decreases (reference neckRotSign = -1).
        // NE: face up (ay<0) → NE increases above neutral (NE not inverted on this unit).
        val targetNR = clamp(NECK_CENTER - ax * NECK_FOLLOW_RANGE, NECK_MIN, NECK_MAX)
        val targetNE = clamp(MabuMotors.NE_NEUTRAL - ay * NE_FOLLOW_RANGE, NE_MIN, NE_MAX)

        posELR += (eyeLR - posELR) * SMOOTH
        posEUD += (eyeUD - posEUD) * SMOOTH
        posNR  += (targetNR - posNR) * SMOOTH
        posNE  += (targetNE - posNE) * SMOOTH

        val now = System.currentTimeMillis()
        if (now - lastSendMs >= SEND_INTERVAL_MS && motors.isOpen()) {
            motors.moveAll(
                ldl = MabuMotors.EYELID_NEUTRAL,
                ldr = MabuMotors.EYELID_NEUTRAL,
                elr = posELR,
                eud = posEUD,
                ne  = posNE,
                nr  = posNR,
                nt  = 50.0
            )
            lastSendMs = now
        }
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
