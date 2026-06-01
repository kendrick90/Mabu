package com.mabu.anima

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

    // Live transcript ("what Mabu hears"). Shown as a speech bubble by the face
    // only when exactly one face is detected.
    @Volatile private var heardText: String? = null

    // What Mabu is saying back. Drawn as a separate (yellow-tinted) bubble so
    // it's visually distinct from the user's (green-tinted) one. Set by
    // [setBotText]; rolled over by [setHeardText] when a new user turn lands so
    // we don't pile two replies on top of each other.
    @Volatile private var botText: String? = null

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
    private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(225, 25, 25, 32)
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(235, 80, 255, 120)
    }
    // Mabu's reply gets a warm-yellow stroke so it reads as "from the robot",
    // not "what the robot just heard from you" (which is the green bubble).
    private val botBubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(235, 255, 210, 63)
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
    }

    fun setResult(r: FaceResult, isFrontFacing: Boolean, primaryId: Int? = null) {
        result = r
        imageFlipped = isFrontFacing
        primaryTrackingId = primaryId
        postInvalidate()
    }

    /** Set the live "what Mabu hears" text (null/blank hides the bubble).
     *  A new heard turn implicitly clears Mabu's previous reply so the screen
     *  doesn't carry both. */
    fun setHeardText(text: String?) {
        val t = text?.takeIf { it.isNotBlank() }
        heardText = t
        if (t != null) botText = null
        postInvalidate()
    }

    /** Set "what Mabu is saying" text. New sentences within a single reply
     *  REPLACE the previous (rather than concatenate) so the bubble stays
     *  short -- the brain emits one bot-transcription per sentence. Pass null
     *  or blank to clear (e.g. when the user starts a new turn). */
    fun setBotText(text: String?) {
        botText = text?.takeIf { it.isNotBlank() }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Mabu's reply bubble is anchored to the SCREEN, not the face -- the
        // robot's head physically sits just above the tablet, so its words
        // float at the top edge regardless of where the user is looking.
        // Drawn first so face overlays paint over its tail if they overlap.
        // We capture the bot bubble's bottom Y so the user bubble can avoid
        // colliding with it when the face is near the top of the frame.
        var botBubbleBottom = 0f
        val bot = botText
        if (!bot.isNullOrBlank()) {
            botBubbleBottom = drawTopBubble(canvas, bot, width.toFloat())
        }

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

            // Heard bubble (green) follows the face. Mabu's reply bubble is
            // drawn screen-anchored above onDraw's face loop (see drawTopBubble).
            // minTop forces the heard bubble below the bot bubble when both
            // are on screen and the face is high in frame, so they don't
            // stack/overlap.
            val heard = heardText
            if (r.faces.size == 1 && !heard.isNullOrBlank()) {
                drawSpeechBubble(canvas, l, t, rg, b, heard, viewW, viewH,
                    preferAbove = true, stroke = bubbleStrokePaint,
                    minTop = botBubbleBottom + 12f)
            }
        }
        drawFaceInset(canvas, r)
    }

    /**
     * A rounded speech bubble above the face (flips below if there's no room),
     * horizontally clamped to the view, with a small tail pointing at the face.
     * Text is word-wrapped to [BUBBLE_MAX_WIDTH_FRAC] of the view width and
     * capped at [BUBBLE_MAX_LINES].
     */
    /**
     * Mabu's reply bubble. Anchored to the top of the screen (Mabu's head is
     * physically above the tablet), tail points UP toward where her head is.
     * Independent of whether a face is detected -- Mabu can talk to no one in
     * particular.
     */
    /** Returns the Y coordinate of the bubble's bottom edge so callers can
     *  push other UI below it (used to keep the user-heard bubble from
     *  overlapping when both are on screen). */
    private fun drawTopBubble(canvas: Canvas, text: String, viewW: Float): Float {
        val padH = 22f
        val padV = 16f
        // Mabu's bubble spans nearly the full screen width so the reply wraps
        // to as few lines as possible -- vertical space at the top is precious
        // (it covers the camera preview). The bubble itself is always full
        // width (not shrunk to the text) so short and long replies share a
        // stable footprint.
        val sideMargin = 16f
        val bubbleW = viewW - 2 * sideMargin
        val maxTextW = bubbleW - 2 * padH
        val lines = wrapText(text, bubbleTextPaint, maxTextW, TOP_BUBBLE_MAX_LINES)
        if (lines.isEmpty()) return 0f

        val lineH = bubbleTextPaint.fontSpacing
        val bubbleH = lines.size * lineH + 2 * padV

        val left = sideMargin
        val top = 14f
        val rect = android.graphics.RectF(left, top, left + bubbleW, top + bubbleH)
        canvas.drawRoundRect(rect, 20f, 20f, bubbleBgPaint)
        canvas.drawRoundRect(rect, 20f, 20f, botBubbleStrokePaint)

        // Tail: a small triangle pointing UP off the top edge -- toward
        // Mabu's actual head (just above the tablet's top bezel).
        val tailX = left + bubbleW / 2f
        val tail = android.graphics.Path()
        tail.moveTo(tailX - 14f, rect.top + 1f)
        tail.lineTo(tailX + 14f, rect.top + 1f)
        tail.lineTo(tailX, rect.top - 18f)
        tail.close()
        canvas.drawPath(tail, bubbleBgPaint)
        canvas.drawPath(tail, botBubbleStrokePaint)

        var ty = top + padV + bubbleTextPaint.textSize
        for (line in lines) {
            canvas.drawText(line, left + padH, ty, bubbleTextPaint)
            ty += lineH
        }
        return rect.bottom
    }

    private fun drawSpeechBubble(
        canvas: Canvas,
        faceL: Float, faceT: Float, faceR: Float, faceB: Float,
        text: String, viewW: Float, viewH: Float,
        preferAbove: Boolean = true,
        stroke: Paint = bubbleStrokePaint,
        /** Hard lower bound on the bubble's top edge -- typically the bottom
         *  of the bot bubble + a small gap, so the two never overlap. */
        minTop: Float = 8f
    ) {
        val padH = 22f
        val padV = 16f
        val maxTextW = viewW * BUBBLE_MAX_WIDTH_FRAC - 2 * padH
        val lines = wrapText(text, bubbleTextPaint, maxTextW, BUBBLE_MAX_LINES)
        if (lines.isEmpty()) return

        val lineH = bubbleTextPaint.fontSpacing
        val textW = lines.maxOf { bubbleTextPaint.measureText(it) }
        val bubbleW = textW + 2 * padH
        val bubbleH = lines.size * lineH + 2 * padV

        val faceCx = (faceL + faceR) / 2f
        var left = (faceCx - bubbleW / 2f).coerceIn(8f, viewW - bubbleW - 8f)
        val gap = 18f
        // Above-the-face is only allowed if it clears minTop (the bot bubble's
        // lower edge). Otherwise force below. If even below would clip the view
        // bottom, fall back to coerceIn -- that's the worst case but at least
        // the bubble stays visible.
        val fitsAbove = (faceT - gap - bubbleH) >= minTop
        val above = if (preferAbove) fitsAbove
                    else fitsAbove && !(faceB + gap + bubbleH <= viewH - 8f)
        var top = if (above) faceT - gap - bubbleH else faceB + gap
        top = top.coerceIn(minTop, viewH - bubbleH - 8f)

        val rect = android.graphics.RectF(left, top, left + bubbleW, top + bubbleH)
        canvas.drawRoundRect(rect, 20f, 20f, bubbleBgPaint)
        canvas.drawRoundRect(rect, 20f, 20f, stroke)

        // Tail: a small triangle from the bubble edge toward the face center.
        val tailX = faceCx.coerceIn(left + 24f, left + bubbleW - 24f)
        val tail = android.graphics.Path()
        if (above) {
            tail.moveTo(tailX - 14f, rect.bottom - 1f)
            tail.lineTo(tailX + 14f, rect.bottom - 1f)
            tail.lineTo(tailX, rect.bottom + 18f)
        } else {
            tail.moveTo(tailX - 14f, rect.top + 1f)
            tail.lineTo(tailX + 14f, rect.top + 1f)
            tail.lineTo(tailX, rect.top - 18f)
        }
        tail.close()
        canvas.drawPath(tail, bubbleBgPaint)

        var ty = top + padV + bubbleTextPaint.textSize
        for (line in lines) {
            canvas.drawText(line, left + padH, ty, bubbleTextPaint)
            ty += lineH
        }
    }

    /**
     * Greedy word-wrap into at most [maxLines]. Only ellipsizes the last line
     * if there were leftover words that didn't fit (so text that fits exactly
     * isn't spuriously truncated).
     */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var cur = ""
        var truncated = false
        for (w in words) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(candidate) <= maxWidth || cur.isEmpty()) {
                cur = candidate
            } else {
                lines.add(cur)
                cur = w
                if (lines.size == maxLines) { truncated = true; cur = ""; break }
            }
        }
        if (!truncated && cur.isNotEmpty() && lines.size < maxLines) lines.add(cur)
        if (truncated && lines.isNotEmpty()) {
            var last = lines.last() + "…"
            while (paint.measureText(last) > maxWidth && last.length > 1) {
                last = last.dropLast(2) + "…"
            }
            lines[lines.size - 1] = last
        }
        return lines
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
        // Bottom-left: the top of the screen is reserved for Mabu's speech
        // bubble (Mabu's head physically sits above the tablet, so the bubble
        // belongs there). Keep clear of the bottom mic-status pill.
        val insetLeft = pad
        val insetBottom = viewH - pad - 100f
        val insetTop = insetBottom - side
        val insetRight = insetLeft + side

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
        // Wider + taller so a multi-sentence turn fits without aggressive
        // truncation. 65% of the view leaves room for the face on either side
        // of the user bubble; 6 lines covers most short conversational turns.
        private const val BUBBLE_MAX_WIDTH_FRAC = 0.65f
        private const val BUBBLE_MAX_LINES = 6
        // Mabu's top bubble is full width, so the same text wraps to far fewer
        // lines -- cap it lower to keep its vertical footprint small.
        private const val TOP_BUBBLE_MAX_LINES = 4

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
