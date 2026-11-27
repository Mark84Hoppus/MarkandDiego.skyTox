// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ltd.evilcorp.atox.App
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.databinding.ActivitySkytoxCameraBinding
import ltd.evilcorp.domain.feature.CallManager
import ltd.evilcorp.domain.feature.CallState

private const val VIDEO_HOLD_MS = 380L
private const val TIMER_TICK_MS = 250L

class SkyToxCameraActivity : AppCompatActivity() {
    @Inject lateinit var callManager: CallManager

    private lateinit var binding: ActivitySkytoxCameraBinding
    private var controller: SkyToxCameraController? = null
    private var currentOutput: File? = null
    private var activeRecording: Recording? = null
    private var pointerDown = false
    private var longTriggered = false
    private var stopRequested = false
    private var discardRecording = false
    private var captureBusy = false
    private var flashEnabled = false
    private var cameraReady = false
    private var recordingStartedAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finishWithError(getString(R.string.camera_permission_required))
    }

    private val requestMicrophonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pointerDown && longTriggered) {
            startVideo()
        } else if (!granted) {
            Toast.makeText(this, R.string.camera_microphone_required, Toast.LENGTH_LONG).show()
        }
    }

    private val longPress = Runnable {
        if (!pointerDown || captureBusy || !cameraReady) return@Runnable
        longTriggered = true
        binding.cameraShutter.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            startVideo()
        } else {
            requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private val timerTick = object : Runnable {
        override fun run() {
            if (activeRecording == null) return
            val elapsed = (System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L)
            val seconds = elapsed / 1000L
            val label = "%02d:%02d".format(seconds / 60L, seconds % 60L)
            binding.cameraTimer.text = getString(R.string.camera_recording, label)
            handler.postDelayed(this, TIMER_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as App).component.inject(this)
        super.onCreate(savedInstanceState)
        if (callManager.inCall.value is CallState.InCall) {
            finishWithError(getString(R.string.camera_active_call))
            return
        }

        binding = ActivitySkytoxCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CameraCaptureStore.cleanupStale(this)
        bindUi()
        watchCalls()

        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindUi() = binding.run {
        cameraClose.setOnClickListener { cancelAndFinish() }
        cameraSwitch.setOnClickListener {
            if (captureBusy || activeRecording != null) return@setOnClickListener
            flashEnabled = false
            controller?.setTorch(false)
            cameraFlash.setImageResource(R.drawable.ic_camera_flash_off)
            controller?.switchCamera()
        }
        cameraFlash.setOnClickListener {
            if (!cameraFlash.isVisible || activeRecording != null) return@setOnClickListener
            flashEnabled = !flashEnabled
            controller?.setTorch(flashEnabled)
            cameraFlash.setImageResource(
                if (flashEnabled) R.drawable.ic_camera_flash_on else R.drawable.ic_camera_flash_off,
            )
            cameraFlash.contentDescription = getString(
                if (flashEnabled) R.string.camera_flash_off else R.string.camera_flash_on,
            )
        }
        cameraShutter.setOnTouchListener { _, event -> handleShutter(event) }
        cameraSwitch.isEnabled = false
        cameraFlash.isVisible = false
    }

    private fun startCamera() {
        controller?.release()
        controller = SkyToxCameraController(
            context = this,
            lifecycleOwner = this,
            previewView = binding.cameraPreview,
            onReady = { hasFront, hasBack, hasFlash ->
                cameraReady = true
                binding.cameraSwitch.isEnabled = hasFront && hasBack
                binding.cameraSwitch.alpha = if (binding.cameraSwitch.isEnabled) 1f else 0.35f
                binding.cameraFlash.isVisible = hasFlash
                if (!hasFlash) flashEnabled = false
            },
            onError = {
                finishWithError(getString(R.string.camera_unavailable))
            },
        ).also { it.start() }
    }

    private fun handleShutter(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!cameraReady || captureBusy) return true
                pointerDown = true
                longTriggered = false
                stopRequested = false
                handler.postDelayed(longPress, VIDEO_HOLD_MS)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasLong = longTriggered
                pointerDown = false
                handler.removeCallbacks(longPress)
                if (wasLong) stopVideo() else takePhoto()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerDown = false
                handler.removeCallbacks(longPress)
                if (longTriggered) {
                    discardRecording = true
                    stopVideo()
                }
                return true
            }
        }
        return false
    }

    private fun takePhoto() {
        if (captureBusy || !CameraCaptureStore.hasRoom(this)) {
            Toast.makeText(this, R.string.camera_low_storage, Toast.LENGTH_LONG).show()
            return
        }
        captureBusy = true
        val file = CameraCaptureStore.createPhoto(this).also { currentOutput = it }
        binding.cameraShutter.isEnabled = false
        controller?.takePhoto(
            file = file,
            onSaved = {
                if (file.length() > 0L) finishWithCapture(file) else captureFailed(file)
            },
            onFailure = { captureFailed(file) },
        )
    }

    private fun startVideo() {
        if (captureBusy || activeRecording != null) return
        if (!CameraCaptureStore.hasRoom(this)) {
            Toast.makeText(this, R.string.camera_low_storage, Toast.LENGTH_LONG).show()
            return
        }
        captureBusy = true
        discardRecording = false
        val file = CameraCaptureStore.createVideo(this).also { currentOutput = it }
        val recording = controller?.startVideo(file, withAudio = true) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordingStartedAt = System.currentTimeMillis()
                    binding.cameraTimer.isVisible = true
                    binding.cameraShutter.setBackgroundResource(R.drawable.camera_shutter_recording)
                    binding.cameraSwitch.isEnabled = false
                    handler.post(timerTick)
                    if (stopRequested) activeRecording?.stop()
                }
                is VideoRecordEvent.Finalize -> {
                    handler.removeCallbacks(timerTick)
                    activeRecording = null
                    binding.cameraTimer.isVisible = false
                    binding.cameraShutter.setBackgroundResource(R.drawable.camera_shutter_idle)
                    if (!event.hasError() && !discardRecording && file.length() > 0L) {
                        finishWithCapture(file)
                    } else {
                        captureFailed(file, showMessage = !discardRecording)
                    }
                }
            }
        }
        if (recording == null) {
            captureFailed(file)
            controller?.restorePhotoModeIfNeeded()
            return
        }
        activeRecording = recording
    }

    private fun stopVideo() {
        val recording = activeRecording
        if (recording == null) {
            stopRequested = true
        } else {
            recording.stop()
        }
    }

    private fun captureFailed(file: File?, showMessage: Boolean = true) {
        CameraCaptureStore.delete(file)
        currentOutput = null
        activeRecording = null
        captureBusy = false
        stopRequested = false
        handler.removeCallbacks(timerTick)
        if (::binding.isInitialized) {
            binding.cameraTimer.isVisible = false
            binding.cameraShutter.isEnabled = true
            binding.cameraShutter.setBackgroundResource(R.drawable.camera_shutter_idle)
        }
        controller?.restorePhotoModeIfNeeded()
        if (showMessage && !isFinishing) {
            Toast.makeText(this, R.string.camera_capture_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun finishWithCapture(file: File) {
        val token = CameraCaptureStore.token(file)
        currentOutput = null
        setResult(Activity.RESULT_OK, Intent().putExtra(CameraCaptureResult.EXTRA_TOKEN, token))
        finish()
    }

    private fun finishWithError(message: String) {
        cancelCapture()
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(CameraCaptureResult.EXTRA_ERROR, message))
        finish()
    }

    private fun cancelAndFinish() {
        cancelCapture()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun cancelCapture() {
        discardRecording = true
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(timerTick)
        activeRecording?.stop()
        activeRecording = null
        controller?.setTorch(false)
        CameraCaptureStore.delete(currentOutput)
        currentOutput = null
    }

    private fun watchCalls() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                callManager.inCall.collect { state ->
                    if (state is CallState.InCall && !isFinishing) {
                        finishWithError(getString(R.string.camera_active_call))
                    }
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) cancelAndFinish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller?.release()
        if (isFinishing) CameraCaptureStore.delete(currentOutput)
        super.onDestroy()
    }
}
