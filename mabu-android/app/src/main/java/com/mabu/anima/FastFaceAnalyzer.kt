package com.mabu.anima

import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Bbox-only face detector for the FOLLOW reflex fast path.
 *
 * The default [FaceAnalyzer] runs landmarks + classification + heuristic pupil
 * sampling + face crop on every detection -- great for puppeting and gaze, but
 * the bounding-box center (the only thing FOLLOW needs for "point the eyes at
 * the head") sits behind the slowest stage in a single ML Kit inference.
 *
 * This analyzer turns landmarks + classification + contours OFF, so the model
 * only emits the box + a tracking ID. On the RK3288 it's expected to run
 * meaningfully faster; the experiment in [Camera1Source] runs both in parallel
 * and exposes separate FPS via DeviceStats so we can compare.
 */
data class FastFaceResult(
    val faces: List<Face>,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
)

class FastFaceAnalyzer(
    private val onResult: (FastFaceResult) -> Unit
) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.1f)
            .enableTracking()
            .build()
    )

    fun analyze(
        input: InputImage,
        rotatedWidth: Int,
        rotatedHeight: Int,
        rotationDegrees: Int,
        onDone: () -> Unit
    ) {
        val startNs = System.nanoTime()
        detector.process(input)
            .addOnSuccessListener { faces ->
                onResult(FastFaceResult(faces, rotatedWidth, rotatedHeight, rotationDegrees))
            }
            .addOnFailureListener { e -> Log.w(TAG, "fast detect failed", e) }
            .addOnCompleteListener {
                DeviceStats.recordBboxFrame((System.nanoTime() - startNs) / 1000L)
                onDone()
            }
    }

    companion object {
        private const val TAG = "FastFaceAnalyzer"
    }
}
