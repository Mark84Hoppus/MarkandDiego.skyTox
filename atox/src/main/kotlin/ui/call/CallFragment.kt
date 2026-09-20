// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.Camera
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.View
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.asLiveData
import androidx.navigation.fragment.findNavController
import ltd.evilcorp.atox.MainActivity
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.databinding.FragmentCallBinding
import ltd.evilcorp.atox.hasPermission
import ltd.evilcorp.atox.requireStringArg
import ltd.evilcorp.atox.ui.BaseFragment
import ltd.evilcorp.atox.ui.chat.CONTACT_PUBLIC_KEY
import ltd.evilcorp.atox.vmFactory
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.feature.CallState
import ltd.evilcorp.domain.feature.SkyToxCrashLogger
import ltd.evilcorp.domain.av.VideoFrameConverter
import kotlin.math.abs

private const val AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO
private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
const val INCOMING_CALL = "incomingCall"
const val REQUEST_VIDEO_CALL = "requestVideoCall"

class CallFragment : BaseFragment<FragmentCallBinding>(FragmentCallBinding::inflate) {
    private val vm: CallViewModel by viewModels { vmFactory }
    private var incomingAccepted = false
    private var localVideoCapture: SkyToxVideoCapture? = null
    private var cameraPermissionRequested = false
    private var localVideoRequested = false
    private var localVideoHeight = 360
    private var callClosing = false
    private val ringbackHandler = Handler(Looper.getMainLooper())
    private var ringbackTone: ToneGenerator? = null
    private var ringbackRunning = false
    private val ringbackRunnable = object : Runnable {
        override fun run() {
            if (!ringbackRunning) return
            ringbackTone?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1500)
            ringbackHandler.postDelayed(this, 3500)
        }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.startSendingAudio()
        } else {
            Toast.makeText(requireContext(), getString(R.string.call_mic_permission_needed), Toast.LENGTH_LONG).show()
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && localVideoRequested) {
            setLocalVideoEnabled(true)
        } else {
            localVideoRequested = false
            cameraPermissionRequested = false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        val incomingCall = arguments?.getBoolean(INCOMING_CALL, false) == true
        if (incomingCall) {
            prepareIncomingCallWindow()
            (activity as? MainActivity)?.allowCallScreenOverAppLock()
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            controlContainer.updatePadding(bottom = insets.bottom + controlContainer.paddingTop)
            compat
        }

        vm.setActiveContact(PublicKey(requireStringArg(CONTACT_PUBLIC_KEY)))
        vm.contact.observe(viewLifecycleOwner) {
            avatarImageView.setFrom(it)
        }

        vm.incomingVideoFrame.asLiveData().observe(viewLifecycleOwner) { frame ->
            if (callClosing) {
                return@observe
            }
            if (frame?.publicKey != PublicKey(requireStringArg(CONTACT_PUBLIC_KEY))) {
                remoteVideo.clear()
                remoteVideo.visibility = View.GONE
                avatarImageView.visibility = View.VISIBLE
                return@observe
            }

            remoteVideo.setFrame(frame)
            remoteVideo.visibility = View.VISIBLE
            avatarImageView.visibility = View.GONE
        }

        vm.localVideoEnabled.asLiveData().observe(viewLifecycleOwner) { enabled ->
            if (callClosing) {
                stopLocalVideo()
                return@observe
            }
            if (enabled) {
                startLocalVideoIfPossible()
            } else {
                stopLocalVideo()
            }
        }

        vm.outgoingVideoHeight.asLiveData().observe(viewLifecycleOwner) { height ->
            if (callClosing) {
                return@observe
            }
            if (height == localVideoHeight) {
                return@observe
            }
            localVideoHeight = height
            if (localVideoCapture != null) {
                stopLocalVideo()
                startLocalVideoIfPossible()
            }
        }

        vm.remoteCallAccepted.asLiveData().observe(viewLifecycleOwner) { accepted ->
            if (accepted) {
                stopOutgoingRingback()
            }
        }

        acceptCall.setOnClickListener {
            acceptIncomingCall()
        }

        endCall.setOnClickListener {
            closeCallScreen(endCurrentCall = true)
        }

        vm.sendingAudio.asLiveData().observe(viewLifecycleOwner) { sending ->
            if (sending) {
                microphoneControl.setImageResource(R.drawable.ic_mic)
            } else {
                microphoneControl.setImageResource(R.drawable.ic_mic_off)
            }
        }

        microphoneControl.setOnClickListener {
            if (vm.sendingAudio.value) {
                vm.stopSendingAudio()
            } else {
                if (requireContext().hasPermission(AUDIO_PERMISSION)) {
                    vm.startSendingAudio()
                } else {
                    requestAudioPermissionLauncher.launch(AUDIO_PERMISSION)
                }
            }
        }

        updateSpeakerphoneIcon()
        speakerphone.setOnClickListener {
            vm.toggleSpeakerphone()
            updateSpeakerphoneIcon()
        }

        videoControl.setOnClickListener {
            switchCamera()
        }

        backToChat.setOnClickListener {
            findNavController().popBackStack()
        }

        if (vm.inCall.value is CallState.InCall) {
            showActiveCallControls()
            vm.inCall.asLiveData().observe(viewLifecycleOwner) { inCall ->
                if (inCall == CallState.NotInCall) {
                    closeCallScreen(endCurrentCall = false)
                }
            }
            return
        }

        if (incomingCall) {
            showIncomingCallControls()
            vm.pendingCalls.asLiveData().observe(viewLifecycleOwner) { calls ->
                if (!incomingAccepted && calls.none { it.publicKey == requireStringArg(CONTACT_PUBLIC_KEY) }) {
                    findNavController().popBackStack()
                }
            }
            return
        }

        showActiveCallControls()
        startCall()
        startOutgoingRingback()

        if (requireContext().hasPermission(AUDIO_PERMISSION)) {
            vm.startSendingAudio()
        }
    }

    override fun onDestroyView() {
        SkyToxCrashLogger.event("call.ui.onDestroyView closing=$callClosing")
        callClosing = true
        stopOutgoingRingback()
        stopLocalVideo()
        binding.remoteVideo.clear()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) {
            binding.remoteVideo.onResume()
        }
    }

    override fun onPause() {
        if (view != null) {
            binding.remoteVideo.onPause()
        }
        super.onPause()
    }

    private fun updateSpeakerphoneIcon() {
        val icon = if (vm.speakerphoneOn) R.drawable.ic_speakerphone else R.drawable.ic_speakerphone_off
        binding.speakerphone.setImageResource(icon)
    }

    private fun startCall() {
        vm.startCall(arguments?.getBoolean(REQUEST_VIDEO_CALL, false) == true)
        vm.inCall.asLiveData().observe(viewLifecycleOwner) { inCall ->
            if (inCall == CallState.NotInCall) {
                stopOutgoingRingback()
                closeCallScreen(endCurrentCall = false)
            }
        }
    }

    private fun acceptIncomingCall() {
        incomingAccepted = true
        showActiveCallControls()
        vm.acceptIncomingCall()
        if (requireContext().hasPermission(AUDIO_PERMISSION)) {
            vm.startSendingAudio()
        } else {
            requestAudioPermissionLauncher.launch(AUDIO_PERMISSION)
        }
        vm.inCall.asLiveData().observe(viewLifecycleOwner) { inCall ->
            if (inCall == CallState.NotInCall) {
                closeCallScreen(endCurrentCall = false)
            }
        }
    }

    private fun startOutgoingRingback() {
        if (ringbackRunning || vm.remoteCallAccepted.value) return
        ringbackTone = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
        }.getOrNull()
        ringbackRunning = ringbackTone != null
        if (ringbackRunning) {
            ringbackRunnable.run()
        }
    }

    private fun stopOutgoingRingback() {
        ringbackRunning = false
        ringbackHandler.removeCallbacks(ringbackRunnable)
        ringbackTone?.release()
        ringbackTone = null
    }

    private fun showIncomingCallControls() = binding.run {
        acceptCall.visibility = View.VISIBLE
        microphoneControl.visibility = View.GONE
        speakerphone.visibility = View.GONE
        videoControl.visibility = View.GONE
        backToChat.visibility = View.GONE
        callDuration.visibility = View.GONE
        callDuration.stop()
    }

    private fun showActiveCallControls() = binding.run {
        acceptCall.visibility = View.GONE
        microphoneControl.visibility = View.VISIBLE
        speakerphone.visibility = View.VISIBLE
        videoControl.visibility = if (vm.localVideoEnabled.value) View.VISIBLE else View.GONE
        backToChat.visibility = View.VISIBLE
        callDuration.visibility = View.VISIBLE
        callDuration.base = SystemClock.elapsedRealtime()
        callDuration.start()
    }

    private fun prepareIncomingCallWindow() {
        val activity = requireActivity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    private fun startLocalVideoIfPossible() {
        if (callClosing) {
            return
        }
        if (!requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            vm.setLocalVideoEnabled(false)
            return
        }
        if (requireContext().hasPermission(CAMERA_PERMISSION)) {
            startLocalVideo()
        } else if (!cameraPermissionRequested) {
            localVideoRequested = true
            cameraPermissionRequested = true
            requestCameraPermissionLauncher.launch(CAMERA_PERMISSION)
        } else {
            localVideoRequested = false
            vm.setLocalVideoEnabled(false)
        }
    }

    private fun setLocalVideoEnabled(enabled: Boolean) {
        if (!vm.setLocalVideoEnabled(enabled)) {
            localVideoRequested = false
            Toast.makeText(requireContext(), getString(R.string.video_toggle_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLocalVideo() {
        if (callClosing) {
            return
        }
        if (localVideoCapture != null) {
            return
        }
        SkyToxCrashLogger.event("call.ui.localVideo.start height=$localVideoHeight")
        binding.videoControl.visibility = View.VISIBLE
        binding.localVideoPreview.visibility = View.VISIBLE
        localVideoCapture = SkyToxVideoCapture(
            preview = binding.localVideoPreview,
            preferredHeight = localVideoHeight,
            shouldSendFrame = {
                !callClosing && vm.inCall.value is CallState.InCall && vm.localVideoEnabled.value
            },
            onFrame = { width, height, y, u, v ->
                vm.sendVideoFrame(width, height, y, u, v)
            },
        ).also { it.start() }
    }

    private fun stopLocalVideo() {
        SkyToxCrashLogger.event("call.ui.localVideo.stop hasCapture=${localVideoCapture != null}")
        localVideoCapture?.stop()
        localVideoCapture = null
        binding.localVideoPreview.visibility = View.GONE
        binding.videoControl.visibility = View.GONE
    }

    private fun switchCamera() {
        if (callClosing) {
            return
        }
        SkyToxCrashLogger.event("call.ui.localVideo.switchCamera")
        localVideoCapture?.switchCamera()
    }

    private fun closeCallScreen(endCurrentCall: Boolean) {
        if (callClosing) {
            return
        }
        SkyToxCrashLogger.event("call.ui.close endCurrentCall=$endCurrentCall")
        callClosing = true
        binding.callDuration.stop()
        stopOutgoingRingback()
        stopLocalVideo()
        if (endCurrentCall) {
            vm.endCall()
        }
        runCatching { findNavController().popBackStack() }
    }
}

@Suppress("DEPRECATION")
private class SkyToxVideoCapture(
    private val preview: android.view.SurfaceView,
    private val preferredHeight: Int,
    private val shouldSendFrame: () -> Boolean,
    private val onFrame: (width: Int, height: Int, y: ByteArray, u: ByteArray, v: ByteArray) -> Unit,
) : SurfaceHolder.Callback, Camera.PreviewCallback {
    private var camera: Camera? = null
    private var cameraId = findCamera(Camera.CameraInfo.CAMERA_FACING_FRONT)
    private var holderReady = false
    private var previewWidth = 0
    private var previewHeight = 0
    private var frameRotation = 0
    private var previewBufferSize = 0
    private var reusableYuvFrame: YuvFrame? = null
    @Volatile private var stopped = false

    fun start() {
        SkyToxCrashLogger.event("camera.capture.start preferredHeight=$preferredHeight")
        stopped = false
        preview.setZOrderOnTop(true)
        preview.holder.setFormat(PixelFormat.TRANSLUCENT)
        preview.holder.addCallback(this)
        if (preview.holder.surface?.isValid == true) {
            holderReady = true
            openCamera()
        }
    }

    fun stop() {
        SkyToxCrashLogger.event("camera.capture.stop")
        stopped = true
        holderReady = false
        closeCamera()
        runCatching { preview.holder.removeCallback(this) }
    }

    fun switchCamera() {
        SkyToxCrashLogger.event("camera.capture.switch")
        val nextFacing = if (currentFacing() == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            Camera.CameraInfo.CAMERA_FACING_BACK
        } else {
            Camera.CameraInfo.CAMERA_FACING_FRONT
        }
        cameraId = findCamera(nextFacing)
        if (holderReady) {
            closeCamera()
            openCamera()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        SkyToxCrashLogger.event("camera.surface.created")
        holderReady = true
        openCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        SkyToxCrashLogger.event("camera.surface.changed ${width}x$height")
        if (holderReady) {
            closeCamera()
            openCamera()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        SkyToxCrashLogger.event("camera.surface.destroyed")
        holderReady = false
        closeCamera()
    }

    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        if (stopped) {
            return
        }
        val frame = data ?: return
        try {
            if (previewWidth <= 0 || previewHeight <= 0) {
                return
            }
            runCatching {
                val converted = nextYuvFrame(previewWidth, previewHeight, frameRotation)
                VideoFrameConverter.nv21ToI420(
                    data = frame,
                    width = previewWidth,
                    height = previewHeight,
                    rotation = frameRotation,
                    y = converted.y,
                    u = converted.u,
                    v = converted.v,
                )
                converted
            }.onSuccess { converted ->
                if (!stopped && shouldSendFrame()) {
                    runCatching {
                        onFrame(converted.width, converted.height, converted.y, converted.u, converted.v)
                    }.onFailure {
                        SkyToxCrashLogger.error("camera.frame callback failed", it)
                        stopped = true
                    }
                }
            }.onFailure {
                SkyToxCrashLogger.error("camera.frame convert failed", it)
            }
        } finally {
            if (!stopped && previewBufferSize > 0) {
                runCatching { camera?.addCallbackBuffer(frame) }
            }
        }
    }

    private fun openCamera() {
        if (!holderReady || camera != null) {
            return
        }

        try {
            SkyToxCrashLogger.event("camera.open id=$cameraId preferredHeight=$preferredHeight")
            val opened = Camera.open(cameraId)
            val params = opened.parameters
            params.previewFormat = ImageFormat.NV21
            val size = choosePreviewSize(params.supportedPreviewSizes, preferredHeight = preferredHeight)
            previewWidth = size.width
            previewHeight = size.height
            params.setPreviewSize(previewWidth, previewHeight)
            choosePreviewFps(params.supportedPreviewFpsRange)?.let {
                params.setPreviewFpsRange(it[0], it[1])
                SkyToxCrashLogger.av(
                    "camera fps selected=${it[0]}..${it[1]} supported=" +
                        params.supportedPreviewFpsRange.joinToString { range -> "${range[0]}..${range[1]}" },
                )
            }
            opened.parameters = params
            opened.setDisplayOrientation(displayOrientation(preview.context, cameraId))
            frameRotation = frameRotation(preview.context, cameraId)
            opened.setPreviewDisplay(preview.holder)
            previewBufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
            repeat(PREVIEW_BUFFER_COUNT) {
                opened.addCallbackBuffer(ByteArray(previewBufferSize))
            }
            opened.setPreviewCallbackWithBuffer(this)
            opened.startPreview()
            camera = opened
            SkyToxCrashLogger.event("camera.opened id=$cameraId preview=${previewWidth}x$previewHeight rotation=$frameRotation")
        } catch (e: Exception) {
            SkyToxCrashLogger.error("camera.open failed id=$cameraId", e)
            closeCamera()
        }
    }

    private fun closeCamera() {
        SkyToxCrashLogger.event("camera.close hasCamera=${camera != null}")
        runCatching { camera?.setPreviewCallbackWithBuffer(null) }
        runCatching { camera?.stopPreview() }
        runCatching { camera?.release() }
        camera = null
        previewBufferSize = 0
        reusableYuvFrame = null
    }

    private fun nextYuvFrame(width: Int, height: Int, rotation: Int): YuvFrame {
        val normalizedRotation = ((rotation % 360) + 360) % 360
        val outWidth = if (normalizedRotation == 90 || normalizedRotation == 270) height else width
        val outHeight = if (normalizedRotation == 90 || normalizedRotation == 270) width else height
        val current = reusableYuvFrame
        if (
            current == null ||
            current.width != outWidth ||
            current.height != outHeight ||
            current.y.size != outWidth * outHeight ||
            current.u.size != outWidth * outHeight / 4 ||
            current.v.size != outWidth * outHeight / 4
        ) {
            return YuvFrame(
                width = outWidth,
                height = outHeight,
                y = ByteArray(outWidth * outHeight),
                u = ByteArray(outWidth * outHeight / 4),
                v = ByteArray(outWidth * outHeight / 4),
            ).also { reusableYuvFrame = it }
        }
        return current
    }

    private fun currentFacing(): Int {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        return info.facing
    }

    private fun choosePreviewSize(sizes: List<Camera.Size>, preferredHeight: Int): Camera.Size =
        sizes
            .filter { it.width <= 1280 && it.height <= 720 }
            .minByOrNull { abs(minOf(it.width, it.height) - preferredHeight) + abs(maxOf(it.width, it.height) - preferredHeight * 16 / 9) }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: sizes.first()

    private fun choosePreviewFps(ranges: List<IntArray>): IntArray? =
        ranges
            .filter { it[0] <= TARGET_PREVIEW_FPS && it[1] >= TARGET_PREVIEW_FPS }
            .minByOrNull { abs(it[1] - TARGET_PREVIEW_FPS) + abs(it[0] - TARGET_PREVIEW_FPS) / 4 }
            ?: ranges
                .filter { it[1] >= TARGET_PREVIEW_FPS }
                .minByOrNull { abs(it[1] - TARGET_PREVIEW_FPS) }
            ?: ranges.maxByOrNull { it[1] }

    private companion object {
        private const val PREVIEW_BUFFER_COUNT = 3
        private const val TARGET_PREVIEW_FPS = 20_000

        fun findCamera(facing: Int): Int {
            val info = Camera.CameraInfo()
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, info)
                if (info.facing == facing) {
                    return i
                }
            }
            return 0
        }

        fun displayOrientation(context: Context, cameraId: Int): Int {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, info)
            val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            val degrees = when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

            return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                (360 - ((info.orientation + degrees) % 360)) % 360
            } else {
                (info.orientation - degrees + 360) % 360
            }
        }

        fun frameRotation(context: Context, cameraId: Int): Int {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, info)
            val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            val degrees = when (rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

            return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                (info.orientation + degrees) % 360
            } else {
                (info.orientation - degrees + 360) % 360
            }
        }

    }
}

private data class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
)
