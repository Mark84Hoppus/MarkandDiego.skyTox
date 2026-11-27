// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

class SkyToxCameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onReady: (hasFront: Boolean, hasBack: Boolean, hasFlash: Boolean) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var legacyPhotoMode = false

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                provider = future.get()
                bind(photoAndVideo = true)
            }.onFailure(onError)
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera() {
        val cameraProvider = provider ?: return
        val next = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val selector = CameraSelector.Builder().requireLensFacing(next).build()
        if (!cameraProvider.hasCamera(selector)) return
        lensFacing = next
        bind(photoAndVideo = !legacyPhotoMode)
    }

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
        imageCapture?.flashMode = if (enabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    fun takePhoto(file: File, onSaved: () -> Unit, onFailure: (Throwable) -> Unit) {
        val capture = imageCapture ?: return onFailure(IllegalStateException("Image capture is not ready"))
        capture.targetRotation = previewView.display?.rotation ?: capture.targetRotation
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved()
                override fun onError(exception: ImageCaptureException) = onFailure(exception)
            },
        )
    }

    fun startVideo(
        file: File,
        withAudio: Boolean,
        listener: (VideoRecordEvent) -> Unit,
    ): Recording? {
        if (legacyPhotoMode) bind(photoAndVideo = false)
        val capture = videoCapture ?: return null
        capture.targetRotation = previewView.display?.rotation ?: capture.targetRotation
        var pending: PendingRecording = capture.output.prepareRecording(
            context,
            FileOutputOptions.Builder(file).build(),
        )
        if (withAudio) pending = pending.withAudioEnabled()
        return pending.start(ContextCompat.getMainExecutor(context), listener)
    }

    fun restorePhotoModeIfNeeded() {
        if (legacyPhotoMode) bind(photoAndVideo = true)
    }

    fun release() {
        camera?.cameraControl?.enableTorch(false)
        provider?.unbindAll()
        camera = null
    }

    private fun bind(photoAndVideo: Boolean) {
        val cameraProvider = provider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val image = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .build()
        val video = VideoCapture.withOutput(recorder)

        cameraProvider.unbindAll()
        if (photoAndVideo) {
            try {
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, image, video)
                imageCapture = image
                videoCapture = video
                legacyPhotoMode = false
            } catch (_: IllegalArgumentException) {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, image)
                imageCapture = image
                videoCapture = video
                legacyPhotoMode = true
            }
        } else {
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, video)
            imageCapture = null
            videoCapture = video
        }

        val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val hasBack = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        onReady(hasFront, hasBack, camera?.cameraInfo?.hasFlashUnit() == true)
    }
}
