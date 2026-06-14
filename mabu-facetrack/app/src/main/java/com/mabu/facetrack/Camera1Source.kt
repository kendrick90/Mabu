package com.mabu.facetrack

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
 * Opens Mabu's front camera using the old Camera1 API.
 *
 * Why Camera1 and not CameraX: Mabu's camera HAL is a Camera1 shim, so the
 * modern CameraX library fails to open it. The old API works fine.
 *
 * For each preview frame we hand an InputImage to [onFrame]. While a frame
 * is in flight we drop new ones (keep-latest backpressure) — face detection
 * is slower than the camera, and queueing would just add lag.
 */
@Suppress("DEPRECATION")
class Camera1Source(
    private val activity: Activity,
    private val textureView: TextureView,
    private val onFrame: (InputImage, onDone: () -> Unit) -> Unit
) : TextureView.SurfaceTextureListener {

    private var camera: Camera? = null
    private val busy = AtomicBoolean(false)
    private var previewWidth = 0
    private var previewHeight = 0
    private var imageRotation = 0

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

        // Small frames keep face detection fast on Mabu's slow CPU.
        val target = 320 * 240
        val supported = params.supportedPreviewSizes
        val chosen = supported.minByOrNull { Math.abs(it.width * it.height - target) }
            ?: supported.first()
        previewWidth = chosen.width
        previewHeight = chosen.height
        params.setPreviewSize(chosen.width, chosen.height)
        params.previewFormat = ImageFormat.NV21

        params.supportedPreviewFpsRange.maxByOrNull { it[1] }?.let {
            params.setPreviewFpsRange(it[0], it[1])
        }
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
        cam.addCallbackBuffer(ByteArray(bytesPerFrame))
        cam.addCallbackBuffer(ByteArray(bytesPerFrame))
        cam.setPreviewCallbackWithBuffer(::handleFrame)

        cam.startPreview()
        Log.i(TAG, "Camera1 started preview=${previewWidth}x${previewHeight} rot=$imageRotation")
    }

    private fun handleFrame(data: ByteArray?, cam: Camera) {
        if (data == null) return
        if (!busy.compareAndSet(false, true)) {
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
        camera?.apply {
            try {
                setPreviewCallbackWithBuffer(null)
                stopPreview()
            } catch (_: Exception) {}
            release()
        }
        camera = null
    }

    companion object {
        private const val TAG = "Camera1Source"
    }
}
