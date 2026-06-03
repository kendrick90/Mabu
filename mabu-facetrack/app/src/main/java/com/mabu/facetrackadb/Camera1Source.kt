package com.mabu.facetrackadb

import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera1 wrapper opened on a dedicated background-priority HandlerThread.
 *
 * Why a separate thread (not main):
 *  - Camera1 delivers preview callbacks on whatever thread called Camera.open().
 *    Opening on main means every frame's delivery competes with UI work, GC,
 *    layout, broadcast receivers — causing 180–200ms jitter spikes (observed
 *    in session 18 telemetry).
 *  - ML Kit lazily spawns its inference worker pool on the FIRST detector
 *    .process() call. The workers inherit the calling thread's nice value.
 *    Pinning that first call to a background-priority thread is the canonical
 *    way to make audio (THREAD_PRIORITY_URGENT_AUDIO = -19) preempt the
 *    ~109ms face inference once we add voice/TTS work.
 *  - Priority is THREAD_PRIORITY_BACKGROUND - 5 (nice +5) on purpose: at/above
 *    +10 the platform also moves the thread into the restricted "background"
 *    cpuset (often one core), which would starve detection. Nice +5 keeps all
 *    four A17 cores available but lets URGENT_AUDIO preempt.
 */
@Suppress("DEPRECATION")
class Camera1Source(
    private val activity: Activity,
    private val textureView: TextureView,
    private val onFrame: (InputImage, onDone: () -> Unit) -> Unit
) : TextureView.SurfaceTextureListener {

    @Volatile private var camera: Camera? = null
    private val busy = AtomicBoolean(false)
    private var previewWidth = 0
    private var previewHeight = 0
    private var imageRotation = 0

    // Dedicated camera/vision thread. Opening Camera here makes its preview
    // callbacks (and ML Kit's first process() call) land on this thread.
    private val cameraThread = HandlerThread("mabu-camera-vision").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraPriority = Process.THREAD_PRIORITY_BACKGROUND - 5  // nice +5

    init {
        if (textureView.isAvailable) {
            onSurfaceTextureAvailable(textureView.surfaceTexture!!,
                textureView.width, textureView.height)
        }
        textureView.surfaceTextureListener = this
    }

    private fun pickFrontCamera(): Int {
        val count = Camera.getNumberOfCameras()
        for (i in 0 until count) {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i
        }
        return 0
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        cameraHandler.post { openCamera(surface) }
    }

    private fun openCamera(surface: SurfaceTexture) {
        Process.setThreadPriority(cameraPriority)
        val cameraId = pickFrontCamera()
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)

        val cam = try {
            Camera.open(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Camera.open($cameraId) failed", e); return
        }
        camera = cam

        val params = cam.parameters

        val target = 320 * 240
        val supported = params.supportedPreviewSizes
        val chosen = supported.minByOrNull { Math.abs(it.width * it.height - target) }
            ?: supported.first()
        previewWidth = chosen.width
        previewHeight = chosen.height
        params.setPreviewSize(chosen.width, chosen.height)
        params.previewFormat = ImageFormat.NV21

        val allRanges = params.supportedPreviewFpsRange
        val supportedDesc = allRanges.joinToString(",") { "[${it[0]}-${it[1]}]" }
        allRanges.maxByOrNull { it[1] }?.let {
            params.setPreviewFpsRange(it[0], it[1])
        }
        if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        }
        cam.parameters = params

        // Read back what the HAL actually accepted (mHz; divide by 1000 for fps).
        val accepted = IntArray(2)
        cam.parameters.getPreviewFpsRange(accepted)
        Log.i(TAG, "FpsRange supported=$supportedDesc accepted=[${accepted[0]}-${accepted[1]}] mHz (${accepted[0]/1000.0}-${accepted[1]/1000.0} fps)")

        val rotation = activity.windowManager.defaultDisplay.rotation
        val degrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val displayOrientation = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (360 - (info.orientation + degrees) % 360) % 360
        } else {
            (info.orientation - degrees + 360) % 360
        }
        cam.setDisplayOrientation(displayOrientation)

        imageRotation = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (info.orientation + degrees) % 360
        } else {
            (info.orientation - degrees + 360) % 360
        }

        try {
            cam.setPreviewTexture(surface)
        } catch (e: Exception) {
            Log.e(TAG, "setPreviewTexture failed", e); return
        }

        val bytesPerFrame = previewWidth * previewHeight *
            ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
        // 4 callback buffers (vs the previous 2): if the HAL produces faster than we
        // consume, it has somewhere to put frames instead of dropping them on the floor
        // while waiting for our buffer recycle.
        repeat(4) { cam.addCallbackBuffer(ByteArray(bytesPerFrame)) }
        cam.setPreviewCallbackWithBuffer(::handleFrame)

        cam.startPreview()
        Log.i(TAG, "Camera1 started preview=${previewWidth}x${previewHeight} rot=$imageRotation thread=${Thread.currentThread().name} prio=$cameraPriority")
    }

    // HAL delivery instrumentation. Measures the gap between successive
    // handleFrame() calls (regardless of whether we accept or drop the frame),
    // which is the true camera-HAL arrival cadence — independent of inference
    // and backpressure. If this is ~100ms, the HAL is the wall.
    private var halLastNs = 0L
    private var halSumUs = 0L
    private var halMaxUs = 0L
    private var halCount = 0
    private var halDropped = 0
    private val HAL_LOG_EVERY_N = 50

    private fun handleFrame(data: ByteArray?, cam: Camera) {
        if (data == null) return
        val nowNs = System.nanoTime()
        if (halLastNs != 0L) {
            val gapUs = (nowNs - halLastNs) / 1000L
            halSumUs += gapUs
            if (gapUs > halMaxUs) halMaxUs = gapUs
        }
        halLastNs = nowNs
        halCount++
        val accepted = busy.compareAndSet(false, true)
        if (!accepted) halDropped++
        if (halCount >= HAL_LOG_EVERY_N) {
            val avgMs = (halSumUs / halCount) / 1000.0
            Log.i(TAG, "hal n=%d  arrival avg=%.1fms max=%.1fms (%.1f fps)  dropped=%d/%d".format(
                halCount, avgMs, halMaxUs / 1000.0, 1000.0 / avgMs, halDropped, halCount))
            halCount = 0; halSumUs = 0; halMaxUs = 0; halDropped = 0
        }
        if (!accepted) {
            cam.addCallbackBuffer(data); return
        }
        val image = InputImage.fromByteArray(
            data, previewWidth, previewHeight, imageRotation, InputImage.IMAGE_FORMAT_NV21
        )
        onFrame(image) {
            busy.set(false)
            cam.addCallbackBuffer(data)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        release(); return true
    }

    fun release() {
        val cam = camera ?: return
        camera = null
        // Tear down on the same thread that opened it so we don't release the
        // HAL out from under an in-flight preview-callback delivery.
        cameraHandler.post {
            try {
                cam.setPreviewCallbackWithBuffer(null)
                cam.stopPreview()
            } catch (_: Exception) {}
            cam.release()
        }
    }

    companion object {
        private const val TAG = "Camera1Source"
    }
}
