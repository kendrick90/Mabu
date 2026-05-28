package com.mabu.faceoverlay

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
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
    val gaze: GazeData? = null,
    /** Cropped face bitmap (raw camera coords, un-mirrored) for the inset close-up view. */
    val faceCrop: Bitmap? = null,
    /** Source-image rect that [faceCrop] covers, so the overlay can map landmarks into it. */
    val cropRect: Rect? = null
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
                val (crop, cropRect) = computeFaceCrop(faces, rawBytes, rawWidth, rawHeight, rotationDegrees)
                onResult(FaceResult(faces, rotatedWidth, rotatedHeight, rotationDegrees, gaze, crop, cropRect))
            }
            .addOnFailureListener { e -> Log.w(TAG, "Detection failed", e) }
            .addOnCompleteListener { onDone() }
    }

    /**
     * Crop the primary face out of the NV21 frame and convert directly to
     * ARGB. Avoids the YuvImage -> JPEG -> Bitmap path which would be too
     * expensive at our detection rate.
     */
    private fun computeFaceCrop(
        faces: List<Face>, nv21: ByteArray, w: Int, h: Int, rotation: Int
    ): Pair<Bitmap?, Rect?> {
        if (rotation != 0) return Pair(null, null)
        val face = faces.firstOrNull() ?: return Pair(null, null)
        val bb = face.boundingBox
        // Expand 25% to include hair / chin context.
        val expand = (kotlin.math.max(bb.width(), bb.height()) * 0.25f).toInt()
        val left   = (bb.left   - expand).coerceAtLeast(0)
        val top    = (bb.top    - expand).coerceAtLeast(0)
        val right  = (bb.right  + expand).coerceAtMost(w)
        val bottom = (bb.bottom + expand).coerceAtMost(h)
        val cw = right - left
        val ch = bottom - top
        if (cw < 8 || ch < 8) return Pair(null, null)

        val pixels = IntArray(cw * ch)
        val frameSize = w * h
        // NV21: full Y plane, then interleaved VU pairs at half-resolution.
        for (dy in 0 until ch) {
            val y = top + dy
            val yRow = y * w
            val uvRow = frameSize + (y / 2) * w
            val outRow = dy * cw
            for (dx in 0 until cw) {
                val x = left + dx
                val Y = nv21[yRow + x].toInt() and 0xFF
                val uvOff = uvRow + (x and 0xFFFFFFFE.toInt())
                val V = nv21[uvOff].toInt() and 0xFF
                val U = nv21[uvOff + 1].toInt() and 0xFF
                val yy = Y - 16
                val uu = U - 128
                val vv = V - 128
                var r = (1192 * yy + 1634 * vv) shr 10
                var g = (1192 * yy -  833 * vv - 400 * uu) shr 10
                var b = (1192 * yy + 2066 * uu) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                pixels[outRow + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(pixels, cw, ch, Bitmap.Config.ARGB_8888)
        return Pair(bmp, Rect(left, top, right, bottom))
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
