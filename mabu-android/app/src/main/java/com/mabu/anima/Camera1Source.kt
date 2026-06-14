package com.mabu.anima

import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera1 wrapper, backed by a TextureView so we can apply a horizontal
 * mirror transform for selfie-style preview. SurfaceView is hardware
 * composited and ignores parent transforms on API 27; TextureView routes
 * through GL and respects scaleX/setTransform.
 *
 * RK3288's HAL doesn't expose a usable Camera2 surface to CameraX, so we
 * stick to the deprecated Camera1 API. NV21 preview frames are fed to
 * [FaceAnalyzer] with KEEP_LATEST backpressure (we drop frames while a
 * detection is in flight).
 */
@Suppress("DEPRECATION")
class Camera1Source(
    private val activity: Activity,
    private val textureView: TextureView,
    private val analyzer: FaceAnalyzer,
    /** Optional bbox-only detector running in parallel with [analyzer]. When
     *  set, every preview frame is fed to both detectors at once with their
     *  own backpressure flags; the buffer is returned to the camera only
     *  after both that started have completed (refcount in [onFrame]). */
    private val fastAnalyzer: FastFaceAnalyzer? = null,
    /** Called on the main thread once the preview size + image rotation
     *  are known, so the host can resize the preview / overlay to match
     *  the camera's aspect ratio and avoid non-uniform stretch. */
    private val onPreviewSizeKnown: (previewW: Int, previewH: Int, imageRotation: Int) -> Unit = { _, _, _ -> }
) : TextureView.SurfaceTextureListener {

    // Opened on [cameraThread], read/torn-down from the main thread -> volatile.
    @Volatile private var camera: Camera? = null
    private var cameraId: Int = 0
    private val cameraInfo = Camera.CameraInfo()
    private val busy = AtomicBoolean(false)
    /** Optional start-to-start rate cap on detection. Default 0 = uncapped:
     *  we DON'T throttle (that visibly slows gaze response), we DEPRIORITIZE --
     *  the camera + ML Kit pipeline runs on a background-priority thread (see
     *  [cameraThread]) so it runs full-speed when CPU is free but yields to
     *  WebRTC's real-time audio thread under contention (no more mid-reply
     *  speaker garble). The cap stays available via [setMaxDetectFps] as a
     *  fallback knob if deprioritization alone isn't enough. */
    @Volatile private var minDetectIntervalMs: Long = 0L
    @Volatile private var lastAnalyzeStartMs: Long = 0L
    private val fastBusy = AtomicBoolean(false)

    /**
     * Dedicated thread for camera open + preview-callback delivery, run at a
     * lowered nice so the scheduler favors audio. Opening the camera here (it
     * has a Looper) makes Camera1 deliver onFrame on THIS thread, so
     * [FaceAnalyzer.analyze] -> detector.process() is first invoked here too --
     * ML Kit lazily spawns its inference worker pool on that first call and the
     * workers inherit this thread's nice value, deprioritizing the ~109 ms
     * inference itself (the real CPU hog) relative to audio.
     *
     * Priority is set just BELOW Android's THREAD_PRIORITY_BACKGROUND (10)
     * threshold on purpose: at/above 10 the platform also moves the thread into
     * the restricted "background" cpuset (often a single core), which would
     * starve detection instead of merely lowering its priority. A nice of +5
     * keeps all four A17 cores available but lets URGENT_AUDIO (-19) preempt. */
    private val cameraThread = HandlerThread("anima-camera-vision").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraPriority = Process.THREAD_PRIORITY_BACKGROUND - 5  // +5 nice
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0
    private var displayOrientation: Int = 0
    private var imageRotation: Int = 0

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
        // Open + configure the camera on the background-priority camera thread
        // so its preview callbacks (onFrame) -- and thus ML Kit's first
        // process() and its spawned worker pool -- run deprioritized, not on
        // the main thread at foreground priority.
        cameraHandler.post { openCamera(surface) }
    }

    private fun openCamera(surface: SurfaceTexture) {
        Process.setThreadPriority(cameraPriority)
        cameraId = pickFrontCamera()
        Camera.getCameraInfo(cameraId, cameraInfo)
        val cam = try {
            Camera.open(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Camera.open($cameraId) failed", e); return
        }
        camera = cam

        val params = cam.parameters

        // 320x240 is intentionally small -- ML Kit Face Detection's compute
        // scales with image area, and on RK3288 (Cortex-A17 ~1.6 GHz) a 640x480
        // frame is ~4x more work than 320x240 for no real detection benefit
        // at the head-arm's-length distance the robot operates at.
        val target = 320 * 240
        val supported = params.supportedPreviewSizes
        val chosen = supported.minByOrNull { Math.abs(it.width * it.height - target) }
            ?: supported.first()
        previewWidth = chosen.width
        previewHeight = chosen.height
        params.setPreviewSize(chosen.width, chosen.height)
        params.previewFormat = ImageFormat.NV21

        val fpsRanges = params.supportedPreviewFpsRange
        val fastest = fpsRanges.maxByOrNull { it[1] }
        if (fastest != null) params.setPreviewFpsRange(fastest[0], fastest[1])

        if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        }

        cam.parameters = params

        val rotation = activity.windowManager.defaultDisplay.rotation
        val degrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        displayOrientation = if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            val r = (cameraInfo.orientation + degrees) % 360
            (360 - r) % 360
        } else {
            (cameraInfo.orientation - degrees + 360) % 360
        }
        cam.setDisplayOrientation(displayOrientation)

        imageRotation = if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (cameraInfo.orientation + degrees) % 360
        } else {
            (cameraInfo.orientation - degrees + 360) % 360
        }

        try {
            cam.setPreviewTexture(surface)
        } catch (e: Exception) {
            Log.e(TAG, "setPreviewTexture failed", e); return
        }

        val bytesPerFrame = previewWidth * previewHeight *
            ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
        cam.addCallbackBuffer(ByteArray(bytesPerFrame))
        cam.addCallbackBuffer(ByteArray(bytesPerFrame))
        cam.setPreviewCallbackWithBuffer(::onFrame)

        cam.startPreview()
        Log.i(
            TAG, "Camera1 started. preview=${previewWidth}x${previewHeight} " +
                "sensorOrient=${cameraInfo.orientation} display=$displayOrientation " +
                "imageRot=$imageRotation"
        )
        activity.runOnUiThread {
            onPreviewSizeKnown(previewWidth, previewHeight, imageRotation)
        }
    }

    /** Tune the face-detection rate cap at runtime. fps<=0 removes the cap
     *  (detect as fast as ML Kit + backpressure allow). */
    fun setMaxDetectFps(fps: Int) {
        minDetectIntervalMs = if (fps <= 0) 0L else (1000L / fps)
    }

    private fun onFrame(data: ByteArray?, cam: Camera) {
        if (data == null) return
        // Rate-gate before touching busy flags: most frames hit this path and
        // just bounce the buffer back to the camera, leaving the CPU (and the
        // audio thread) alone.
        val now = SystemClock.uptimeMillis()
        if (now - lastAnalyzeStartMs < minDetectIntervalMs) {
            cam.addCallbackBuffer(data); return
        }
        // Independent backpressure: the fast detector finishes earlier and can
        // start on a frame where the full detector is still chewing on the
        // previous one. Either one (or both) may skip this frame.
        val fullStarted = busy.compareAndSet(false, true)
        val fastStarted = fastAnalyzer != null && fastBusy.compareAndSet(false, true)
        if (!fullStarted && !fastStarted) {
            cam.addCallbackBuffer(data); return
        }
        lastAnalyzeStartMs = now
        val input = InputImage.fromByteArray(
            data, previewWidth, previewHeight, imageRotation, InputImage.IMAGE_FORMAT_NV21
        )
        val rotW = if (imageRotation == 90 || imageRotation == 270) previewHeight else previewWidth
        val rotH = if (imageRotation == 90 || imageRotation == 270) previewWidth else previewHeight

        // Refcount: the buffer is shared by both detectors (ML Kit reads from
        // it asynchronously; the full analyzer also samples the NV21 directly
        // for pupil tracking). Return it to the camera only after every
        // detector that *started* this frame has signaled completion.
        val pending = java.util.concurrent.atomic.AtomicInteger(
            (if (fullStarted) 1 else 0) + (if (fastStarted) 1 else 0)
        )
        val recycle = {
            if (pending.decrementAndGet() == 0) cam.addCallbackBuffer(data)
        }

        if (fullStarted) {
            analyzer.analyze(input, data, previewWidth, previewHeight, rotW, rotH, imageRotation) {
                busy.set(false)
                recycle()
            }
        }
        if (fastStarted) {
            fastAnalyzer!!.analyze(input, rotW, rotH, imageRotation) {
                fastBusy.set(false)
                recycle()
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun release() {
        val cam = camera ?: return
        camera = null
        // Tear down on the camera thread -- the same thread that opened it and
        // receives its callbacks -- so we don't release the HAL out from under
        // an in-flight preview-callback delivery.
        cameraHandler.post {
            try {
                cam.setPreviewCallbackWithBuffer(null)
                cam.stopPreview()
            } catch (_: Exception) {
            }
            cam.release()
        }
    }

    /** Re-acquire the camera after [release]. Safe to call when already open
     *  (no-op). Used by the video-mode toggle, which hands the camera to the
     *  Pipecat SDK and then takes it back. */
    fun start() {
        if (camera != null) return
        val surface = textureView.surfaceTexture ?: return
        onSurfaceTextureAvailable(surface, textureView.width, textureView.height)
    }

    val isOpen: Boolean get() = camera != null

    companion object {
        private const val TAG = "Camera1Source"
    }
}
