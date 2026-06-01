package com.mabu.anima

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide rolling perf snapshot. Subsystems (FaceAnalyzer, PipecatVoice,
 * MabuMotors, MainActivity lifecycle) call the record* methods; the HUD and
 * the /status HTTP endpoint both read [snapshot].
 *
 * Lock-free on the hot paths -- ML Kit calls [recordMlKitFrame] every frame
 * from a background thread, so it's an atomic increment plus an atomic add.
 * Window stats (FPS, mean/p95) are computed on demand in [snapshot] from
 * cumulative counters; cheap because the HUD only refreshes at 2 Hz.
 */
object DeviceStats {

    private val startMs = SystemClock.uptimeMillis()

    // ML Kit
    private val mlkitFrames = AtomicInteger(0)
    private val mlkitFrameTimeUs = AtomicLong(0L)
    private val mlkitMaxFrameTimeUs = AtomicLong(0L)
    private var lastMlkitFrames = 0
    private var lastMlkitFrameTimeUs = 0L
    private var lastMlkitWindowMs = startMs

    // WebRTC outbound video (populated by PipecatVoice when video is enabled).
    @Volatile var videoSendingKbps: Int = 0
    @Volatile var videoSendingFps: Int = 0
    @Volatile var videoRttMs: Int = 0
    @Volatile var videoPacketsLost: Long = 0L

    // Transport / app state mirrors. Plain volatiles -- writers are
    // single-threaded (main thread for these flags).
    @Volatile var transportState: String = "disconnected"
    @Volatile var micEnabled: Boolean = true
    @Volatile var camEnabled: Boolean = false
    @Volatile var motorLinkOpen: Boolean = false
    @Volatile var mode: String = "FOLLOW"

    /** Record one completed ML Kit detection. Call from the analyzer's
     *  onComplete callback with the elapsed time in microseconds. */
    fun recordMlKitFrame(elapsedUs: Long) {
        mlkitFrames.incrementAndGet()
        mlkitFrameTimeUs.addAndGet(elapsedUs)
        // Cheap max-tracking. Not perfectly atomic across the two ops but
        // good enough for a HUD readout.
        val prev = mlkitMaxFrameTimeUs.get()
        if (elapsedUs > prev) mlkitMaxFrameTimeUs.set(elapsedUs)
    }

    data class Snapshot(
        val uptimeSec: Long,
        val mlkitFps: Float,
        val mlkitMeanMs: Float,
        val mlkitMaxMs: Float,
        val mlkitTotalFrames: Int,
        val videoSendingKbps: Int,
        val videoSendingFps: Int,
        val videoRttMs: Int,
        val videoPacketsLost: Long,
        val transportState: String,
        val micEnabled: Boolean,
        val camEnabled: Boolean,
        val motorLinkOpen: Boolean,
        val mode: String,
        val heapUsedMb: Int,
        val heapMaxMb: Int,
        val batteryPct: Int,
        val batteryTempC: Float,
    )

    /** Take a snapshot and roll the ML Kit FPS window forward. Call from
     *  the HUD tick or the HTTP endpoint -- not from the hot path. */
    @Synchronized
    fun snapshot(ctx: Context?): Snapshot {
        val now = SystemClock.uptimeMillis()
        val frames = mlkitFrames.get()
        val frameTimeUs = mlkitFrameTimeUs.get()
        val maxUs = mlkitMaxFrameTimeUs.getAndSet(0L)

        val dFrames = frames - lastMlkitFrames
        val dTimeUs = frameTimeUs - lastMlkitFrameTimeUs
        val dWindowMs = (now - lastMlkitWindowMs).coerceAtLeast(1L)
        lastMlkitFrames = frames
        lastMlkitFrameTimeUs = frameTimeUs
        lastMlkitWindowMs = now

        val fps = if (dFrames > 0) dFrames * 1000f / dWindowMs else 0f
        val meanMs = if (dFrames > 0) (dTimeUs / dFrames) / 1000f else 0f
        val maxMs = maxUs / 1000f

        val rt = Runtime.getRuntime()
        val heapUsed = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val heapMax = (rt.maxMemory() / (1024 * 1024)).toInt()

        var batteryPct = -1
        var batteryTempC = Float.NaN
        if (ctx != null) {
            try {
                val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (intent != null) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) batteryPct = (level * 100 / scale)
                    val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                    if (tempTenths != Int.MIN_VALUE) batteryTempC = tempTenths / 10f
                }
            } catch (_: Throwable) { /* HUD shouldn't crash on a battery read */ }
        }

        return Snapshot(
            uptimeSec = (now - startMs) / 1000,
            mlkitFps = fps,
            mlkitMeanMs = meanMs,
            mlkitMaxMs = maxMs,
            mlkitTotalFrames = frames,
            videoSendingKbps = videoSendingKbps,
            videoSendingFps = videoSendingFps,
            videoRttMs = videoRttMs,
            videoPacketsLost = videoPacketsLost,
            transportState = transportState,
            micEnabled = micEnabled,
            camEnabled = camEnabled,
            motorLinkOpen = motorLinkOpen,
            mode = mode,
            heapUsedMb = heapUsed,
            heapMaxMb = heapMax,
            batteryPct = batteryPct,
            batteryTempC = batteryTempC,
        )
    }

    fun Snapshot.toJson(): JSONObject = JSONObject().apply {
        put("uptime_sec", uptimeSec)
        put("mlkit_fps", round1(mlkitFps))
        put("mlkit_mean_ms", round1(mlkitMeanMs))
        put("mlkit_max_ms", round1(mlkitMaxMs))
        put("mlkit_total_frames", mlkitTotalFrames)
        put("video_sending_kbps", videoSendingKbps)
        put("video_sending_fps", videoSendingFps)
        put("video_rtt_ms", videoRttMs)
        put("video_packets_lost", videoPacketsLost)
        put("transport_state", transportState)
        put("mic_enabled", micEnabled)
        put("cam_enabled", camEnabled)
        put("motor_link_open", motorLinkOpen)
        put("mode", mode)
        put("heap_used_mb", heapUsedMb)
        put("heap_max_mb", heapMaxMb)
        put("battery_pct", batteryPct)
        put("battery_temp_c", if (batteryTempC.isNaN()) JSONObject.NULL else round1(batteryTempC))
    }

    private fun round1(v: Float): Float = (kotlin.math.round(v * 10f) / 10f)
}
