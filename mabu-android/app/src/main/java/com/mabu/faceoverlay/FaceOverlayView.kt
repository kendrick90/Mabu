package com.mabu.faceoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceLandmark

class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    @Volatile private var result: FaceResult? = null
    @Volatile private var imageFlipped: Boolean = true
    @Volatile private var primaryTrackingId: Int? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.argb(230, 80, 255, 120)  // primary (addressed) face
    }
    private val boxPaintOther = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.argb(180, 160, 160, 160)  // other faces (not addressed)
    }
    private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(220, 90, 220, 255)
    }
    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.YELLOW
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }
    private val insetBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(230, 80, 255, 120)
    }
    private val insetBackgroundPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
    }
    private val insetTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        setShadowLayer(2f, 0f, 0f, Color.BLACK)
    }
    private val gazeArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(240, 255, 90, 90)
    }

    fun setResult(r: FaceResult, isFrontFacing: Boolean, primaryId: Int? = null) {
        result = r
        imageFlipped = isFrontFacing
        primaryTrackingId = primaryId
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return
        if (r.faces.isEmpty()) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val imgW = r.imageWidth.toFloat()
        val imgH = r.imageHeight.toFloat()
        if (imgW <= 0f || imgH <= 0f) return

        // FILL_CENTER scale: choose the larger ratio so the image covers
        // the whole view, then center (excess gets cropped).
        val scale = maxOf(viewW / imgW, viewH / imgH)
        val xOffset = (imgW * scale - viewW) / 2f
        val yOffset = (imgH * scale - viewH) / 2f

        fun mapX(x: Float): Float =
            if (imageFlipped) viewW - (x * scale - xOffset) else x * scale - xOffset
        fun mapY(y: Float): Float = y * scale - yOffset

        val primaryId = primaryTrackingId
        for (face in r.faces) {
            // ML Kit's enableTracking() doesn't always populate trackingId
            // on the first few frames, so fall back to "single face = primary"
            // until the tracker actually assigns IDs.
            val isPrimary = (face.trackingId != null && face.trackingId == primaryId) ||
                            r.faces.size == 1
            val paint = if (isPrimary) boxPaint else boxPaintOther
            val rect = face.boundingBox
            val xa = mapX(rect.left.toFloat())
            val xb = mapX(rect.right.toFloat())
            val l = minOf(xa, xb)
            val rg = maxOf(xa, xb)
            val t = mapY(rect.top.toFloat())
            val b = mapY(rect.bottom.toFloat())
            canvas.drawRect(l, t, rg, b, paint)
            // Only show landmarks + probability text on the primary face;
            // others get just a thin gray box so the scene stays readable.
            if (!isPrimary) continue

            for (ct in CONTOUR_TYPES) {
                val pts = face.getContour(ct)?.points ?: continue
                drawPolyline(canvas, pts, ::mapX, ::mapY, closed = (ct == FaceContour.FACE))
            }

            for (lt in LANDMARK_TYPES) {
                val p = face.getLandmark(lt)?.position ?: continue
                canvas.drawCircle(mapX(p.x), mapY(p.y), 6f, landmarkPaint)
            }

            // Gaze vector: arrow from each eye landmark in the direction
            // the pupil is shifted (pupil-detection done in FaceAnalyzer).
            // Only the primary face gets these so we don't clutter the
            // overlay with everyone's eyes.
            if (isPrimary) {
                val gz = r.gaze
                if (gz != null) {
                    val arrowLen = (rg - l) * 0.30f
                    drawGazeArrow(canvas,
                        face.getLandmark(FaceLandmark.LEFT_EYE)?.position,
                        gz.leftEyeOffset, arrowLen, ::mapX, ::mapY)
                    drawGazeArrow(canvas,
                        face.getLandmark(FaceLandmark.RIGHT_EYE)?.position,
                        gz.rightEyeOffset, arrowLen, ::mapX, ::mapY)
                }
            }

            val sb = StringBuilder()
            face.smilingProbability?.let { sb.append("smile=").append(String.format("%.2f", it)).append("  ") }
            face.leftEyeOpenProbability?.let { sb.append("L=").append(String.format("%.2f", it)).append(" ") }
            face.rightEyeOpenProbability?.let { sb.append("R=").append(String.format("%.2f", it)) }
            if (sb.isNotEmpty()) {
                val ty = (t - 12f).coerceAtLeast(textPaint.textSize)
                canvas.drawText(sb.toString(), l, ty, textPaint)
            }
        }
        drawFaceInset(canvas, r)
    }

    /**
     * Clean side close-up of the primary face -- just the bitmap from
     * FaceAnalyzer (NV21 -> ARGB direct conversion on the crop region),
     * with a small border. The overlay (landmarks, gaze arrows, etc.) all
     * lives on the main view; this view is intentionally unmarked so you
     * can see the face clearly. Mirrored to match the main preview.
     */
    private fun drawFaceInset(canvas: Canvas, r: FaceResult) {
        val crop = r.faceCrop ?: return
        val cropRect = r.cropRect ?: return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val side = (minOf(viewW, viewH) * 0.35f)
        val pad = 18f
        val insetLeft = pad
        val insetTop = pad
        val insetRight = insetLeft + side
        val insetBottom = insetTop + side

        canvas.drawRect(insetLeft - 4f, insetTop - 4f, insetRight + 4f, insetBottom + 4f,
            insetBackgroundPaint)

        val cw = cropRect.width().toFloat()
        val ch = cropRect.height().toFloat()
        val scale = minOf(side / cw, side / ch)
        val drawW = cw * scale
        val drawH = ch * scale
        val drawL = insetLeft + (side - drawW) * 0.5f
        val drawT = insetTop  + (side - drawH) * 0.5f
        val dst = android.graphics.RectF(drawL, drawT, drawL + drawW, drawT + drawH)

        // Mirror only the bitmap to match the selfie-style main preview.
        canvas.save()
        if (imageFlipped) {
            canvas.scale(-1f, 1f, (drawL + drawL + drawW) * 0.5f, 0f)
        }
        canvas.drawBitmap(crop, null, dst, null)
        canvas.restore()
        canvas.drawRect(drawL, drawT, drawL + drawW, drawT + drawH, insetBorderPaint)
    }

    private fun drawGazeArrow(
        canvas: Canvas,
        eyePos: android.graphics.PointF?,
        offset: android.graphics.PointF?,
        length: Float,
        mapX: (Float) -> Float,
        mapY: (Float) -> Float
    ) {
        if (eyePos == null || offset == null) return
        val cx = mapX(eyePos.x)
        val cy = mapY(eyePos.y)
        // The image is mirrored on the main view (selfie display), so the
        // arrow's X direction also needs to flip to match the user's
        // perspective.
        val sx = if (imageFlipped) -1f else 1f
        val ex = cx + offset.x * length * sx
        val ey = cy + offset.y * length
        canvas.drawLine(cx, cy, ex, ey, gazeArrowPaint)
        canvas.drawCircle(ex, ey, 5f, gazeArrowPaint)
    }

    private fun drawPolyline(
        canvas: Canvas,
        pts: List<PointF>,
        mapX: (Float) -> Float,
        mapY: (Float) -> Float,
        closed: Boolean
    ) {
        if (pts.size < 2) return
        var prev = pts[0]
        for (i in 1 until pts.size) {
            val cur = pts[i]
            canvas.drawLine(mapX(prev.x), mapY(prev.y), mapX(cur.x), mapY(cur.y), contourPaint)
            prev = cur
        }
        if (closed) {
            val first = pts[0]
            val last = pts[pts.size - 1]
            canvas.drawLine(
                mapX(last.x), mapY(last.y), mapX(first.x), mapY(first.y), contourPaint
            )
        }
    }

    companion object {
        private val CONTOUR_TYPES = intArrayOf(
            FaceContour.FACE,
            FaceContour.LEFT_EYEBROW_TOP, FaceContour.LEFT_EYEBROW_BOTTOM,
            FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM,
            FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE,
            FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM,
            FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM,
            FaceContour.NOSE_BRIDGE, FaceContour.NOSE_BOTTOM,
            FaceContour.LEFT_CHEEK, FaceContour.RIGHT_CHEEK
        )
        private val LANDMARK_TYPES = intArrayOf(
            FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE,
            FaceLandmark.NOSE_BASE,
            FaceLandmark.LEFT_EAR, FaceLandmark.RIGHT_EAR,
            FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_BOTTOM,
            FaceLandmark.LEFT_CHEEK, FaceLandmark.RIGHT_CHEEK
        )
    }
}
