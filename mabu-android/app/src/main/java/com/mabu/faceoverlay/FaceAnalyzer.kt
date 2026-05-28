package com.mabu.faceoverlay

import android.graphics.PointF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark

/**
 * Per-eye gaze estimate. Offsets are in [-1, 1] -- (0, 0) means pupil is
 * centered in the eye landmark's local box. Positive X = pupil shifted
 * toward image-right; positive Y = toward image-bottom. Null fields mean
 * pupil detection failed for that eye (region clipped, no dark cluster).
 */
data class GazeData(
    val leftEyeOffset: PointF?,
    val rightEyeOffset: PointF?
)

data class FaceResult(
    val faces: List<Face>,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
    val gaze: GazeData? = null
)

class FaceAnalyzer(
    private val onResult: (FaceResult) -> Unit
) {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            // CLASSIFICATION_MODE_ALL is needed for PUPPET mode (eyelid
            // openness mirroring). Costs ~1 FPS but worth it.
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.1f)
            .enableTracking()
            .build()
    )

    /**
     * Run detection AND a heuristic pupil-position pass on the raw NV21
     * frame so we can drive the eyes from real gaze direction (in addition
     * to head pose). [rawBytes] must remain valid until [onDone] fires.
     */
    fun analyze(
        input: InputImage,
        rawBytes: ByteArray,
        rawWidth: Int,
        rawHeight: Int,
        rotatedWidth: Int,
        rotatedHeight: Int,
        rotationDegrees: Int,
        onDone: () -> Unit
    ) {
        detector.process(input)
            .addOnSuccessListener { faces ->
                val gaze = computeGaze(faces, rawBytes, rawWidth, rawHeight, rotationDegrees)
                onResult(FaceResult(faces, rotatedWidth, rotatedHeight, rotationDegrees, gaze))
            }
            .addOnFailureListener { e -> Log.w(TAG, "Detection failed", e) }
            .addOnCompleteListener { onDone() }
    }

    private fun computeGaze(
        faces: List<Face>, nv21: ByteArray, w: Int, h: Int, rotation: Int
    ): GazeData? {
        // For pupil sampling we'd need to un-rotate landmark coords back
        // to the buffer's coordinate system. On the Mabu the sensor reports
        // rotation=0 so landmark coords ARE buffer coords -- short-circuit
        // and skip the general rotation case for now.
        if (rotation != 0) return null
        val face = faces.firstOrNull() ?: return null
        val faceW = face.boundingBox.width()
        if (faceW < 30) return null

        // Eye-region half-extents: ~6% of face width horizontal, ~3% vertical.
        // Big enough to cover pupil swing, small enough not to leak into
        // skin / eyebrow.
        val halfW = (faceW * 0.06f).toInt().coerceAtLeast(4)
        val halfH = (faceW * 0.03f).toInt().coerceAtLeast(3)

        val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val lOff = left?.let { pupilOffset(nv21, w, h, it.x.toInt(), it.y.toInt(), halfW, halfH) }
        val rOff = right?.let { pupilOffset(nv21, w, h, it.x.toInt(), it.y.toInt(), halfW, halfH) }
        if (lOff == null && rOff == null) return null
        return GazeData(lOff, rOff)
    }

    /**
     * Locate the darkest cluster in a halfW * 2 by halfH * 2 box around
     * (cx, cy) in the NV21 luma plane. Two-pass: find min luma, then take
     * the centroid of pixels within DARK_THRESHOLD of that min. Return the
     * normalized offset (centroid - center) / half.
     */
    private fun pupilOffset(
        nv21: ByteArray, w: Int, h: Int,
        cx: Int, cy: Int, halfW: Int, halfH: Int
    ): PointF? {
        val x0 = (cx - halfW).coerceAtLeast(0)
        val x1 = (cx + halfW).coerceAtMost(w - 1)
        val y0 = (cy - halfH).coerceAtLeast(0)
        val y1 = (cy + halfH).coerceAtMost(h - 1)
        if (x1 - x0 < 4 || y1 - y0 < 3) return null

        var minLuma = 255
        for (y in y0..y1) {
            val row = y * w
            for (x in x0..x1) {
                val l = nv21[row + x].toInt() and 0xFF
                if (l < minLuma) minLuma = l
            }
        }
        val threshold = minLuma + DARK_THRESHOLD
        var sumX = 0L; var sumY = 0L; var count = 0
        for (y in y0..y1) {
            val row = y * w
            for (x in x0..x1) {
                val l = nv21[row + x].toInt() and 0xFF
                if (l <= threshold) {
                    sumX += x; sumY += y; count++
                }
            }
        }
        if (count < 3) return null
        val cxF = sumX.toFloat() / count
        val cyF = sumY.toFloat() / count
        val dx = ((cxF - cx) / halfW).coerceIn(-1f, 1f)
        val dy = ((cyF - cy) / halfH).coerceIn(-1f, 1f)
        return PointF(dx, dy)
    }

    companion object {
        private const val TAG = "FaceAnalyzer"
        private const val DARK_THRESHOLD = 30
    }
}
