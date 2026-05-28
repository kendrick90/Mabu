package com.mabu.faceoverlay

import com.google.mlkit.vision.face.Face

/**
 * Picks which detected face Mabu should address when multiple people are
 * in frame. Uses ML Kit's tracking IDs (so identity is stable across
 * frames as long as ML Kit's tracker holds) plus a small set of social
 * cues -- size, center-proximity, looking-at-the-robot -- with a sticky
 * hysteresis on the currently-addressed face so a brief score blip on a
 * second person doesn't yank attention away.
 *
 * Next-stage signal (not implemented yet): voice activity + per-face
 * mouth-movement to bias toward the actual speaker.
 */
class AttentionTracker {

    private var currentId: Int? = null
    private var challengerId: Int? = null
    private var challengerStartMs: Long = 0L

    fun select(faces: List<Face>, imageW: Int, imageH: Int, now: Long): Face? {
        if (faces.isEmpty()) {
            currentId = null; challengerId = null
            return null
        }
        if (faces.size == 1) {
            currentId = faces[0].trackingId
            challengerId = null
            return faces[0]
        }

        val scored = faces.map { it to score(it, imageW, imageH, it.trackingId == currentId) }
        val best = scored.maxByOrNull { it.second }!!

        // No incumbent yet -> just take the best
        if (currentId == null) {
            currentId = best.first.trackingId
            return best.first
        }

        // Incumbent dropped out of frame -> promote the best new candidate
        val incumbent = faces.firstOrNull { it.trackingId == currentId }
        if (incumbent == null) {
            currentId = best.first.trackingId
            challengerId = null
            return best.first
        }

        // Incumbent is still highest -> hold
        if (best.first.trackingId == currentId) {
            challengerId = null
            return incumbent
        }

        // Best is a challenger; it must beat the incumbent by SWITCH_DELTA
        // and sustain that lead for SWITCH_HOLD_MS before we yield attention.
        val incumbentScore = scored.first { it.first.trackingId == currentId }.second
        val delta = best.second - incumbentScore
        if (delta < SWITCH_DELTA) {
            challengerId = null
            return incumbent
        }
        if (challengerId != best.first.trackingId) {
            challengerId = best.first.trackingId
            challengerStartMs = now
            return incumbent
        }
        if (now - challengerStartMs >= SWITCH_HOLD_MS) {
            currentId = best.first.trackingId
            challengerId = null
            return best.first
        }
        return incumbent
    }

    private fun score(face: Face, w: Int, h: Int, isCurrent: Boolean): Float {
        val rect = face.boundingBox
        val faceArea = (rect.width() * rect.height()).toFloat()
        val imageArea = (w * h).toFloat().coerceAtLeast(1f)
        // Saturate around 30% of image area -- beyond that we don't get
        // more "attention-worthiness", just a full close-up.
        val sizeScore = (faceArea / imageArea / 0.30f).coerceIn(0f, 1f)

        val cx = (rect.left + rect.right) * 0.5f / w
        val cy = (rect.top + rect.bottom) * 0.5f / h
        val dCenter = kotlin.math.hypot(cx - 0.5f, cy - 0.5f)
        val centerScore = (1f - dCenter / 0.7f).coerceIn(0f, 1f)

        val absYaw = kotlin.math.abs(face.headEulerAngleY)
        val absPitch = kotlin.math.abs(face.headEulerAngleX)
        // Sum of head-deviation, saturating at 60 deg (very off-axis).
        val gazeScore = (1f - (absYaw + absPitch) / 60f).coerceIn(0f, 1f)

        val sticky = if (isCurrent) STICKY_BONUS else 0f
        return sizeScore * W_SIZE + centerScore * W_CENTER + gazeScore * W_GAZE + sticky
    }

    fun primaryId(): Int? = currentId

    companion object {
        // Weights -- tuned so size dominates (closeness is a strong cue)
        // but a centered, camera-facing face still tends to win.
        private const val W_SIZE = 0.40f
        private const val W_CENTER = 0.20f
        private const val W_GAZE = 0.20f
        private const val STICKY_BONUS = 0.20f
        // Hysteresis: challenger must beat incumbent by this much, for
        // this long, before we switch attention.
        private const val SWITCH_DELTA = 0.15f
        private const val SWITCH_HOLD_MS = 600L
    }
}
