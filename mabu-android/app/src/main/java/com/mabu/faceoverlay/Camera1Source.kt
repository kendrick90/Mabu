package com.mabu.faceoverlay

import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
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
    /** Called on the main thread once the preview size + image rotation
     *  are known, so the host can resize the preview / overlay to match
     *  the camera's aspect ratio and avoid non-uniform stretch. */
    private val onPreviewSizeKnown: (previewW: Int, previewH: Int, imageRotation: Int) -> Unit = { _, _, _ -> }
) : TextureView.SurfaceTextureListener {

    private var camera: Camera? = null
    private var cameraId: Int = 0
    private val cameraInfo = Camera.CameraInfo()
    private val busy = AtomicBoolean(false)
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

    private fun onFrame(data: ByteArray?, cam: Camera) {
        if (data == null) return
        if (!busy.compareAndSet(false, true)) {
            cam.addCallbackBuffer(data); return
        }
        val input = InputImage.fromByteArray(
            data, previewWidth, previewHeight, imageRotation, InputImage.IMAGE_FORMAT_NV21
        )
        val rotW = if (imageRotation == 90 || imageRotation == 270) previewHeight else previewWidth
        val rotH = if (imageRotation == 90 || imageRotation == 270) previewWidth else previewHeight
        // Pass the raw NV21 buffer along so the analyzer can run a pupil-
        // position pass for gaze estimation. data stays valid until our
        // onDone callback recycles it to the camera below.
        analyzer.analyze(input, data, previewWidth, previewHeight, rotW, rotH, imageRotation) {
            busy.set(false)
            cam.addCallbackBuffer(data)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun release() {
        camera?.apply {
            try {
                setPreviewCallbackWithBuffer(null)
                stopPreview()
            } catch (_: Exception) {
            }
            release()
        }
        camera = null
    }

    companion object {
        private const val TAG = "Camera1Source"
    }
}
