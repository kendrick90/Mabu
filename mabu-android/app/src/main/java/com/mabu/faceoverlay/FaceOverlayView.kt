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

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(220, 80, 255, 120)
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

    fun setResult(r: FaceResult, isFrontFacing: Boolean) {
        result = r
        imageFlipped = isFrontFacing
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

        for (face in r.faces) {
            val rect = face.boundingBox
            val xa = mapX(rect.left.toFloat())
            val xb = mapX(rect.right.toFloat())
            val l = minOf(xa, xb)
            val rg = maxOf(xa, xb)
            val t = mapY(rect.top.toFloat())
            val b = mapY(rect.bottom.toFloat())
            canvas.drawRect(l, t, rg, b, boxPaint)

            for (ct in CONTOUR_TYPES) {
                val pts = face.getContour(ct)?.points ?: continue
                drawPolyline(canvas, pts, ::mapX, ::mapY, closed = (ct == FaceContour.FACE))
            }

            for (lt in LANDMARK_TYPES) {
                val p = face.getLandmark(lt)?.position ?: continue
                canvas.drawCircle(mapX(p.x), mapY(p.y), 6f, landmarkPaint)
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
