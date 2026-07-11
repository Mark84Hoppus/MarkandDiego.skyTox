// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.call

import android.Manifest
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.View
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

private const val PERMISSION = Manifest.permission.RECORD_AUDIO
const val INCOMING_CALL = "incomingCall"

class CallFragment : BaseFragment<FragmentCallBinding>(FragmentCallBinding::inflate) {
    private val vm: CallViewModel by viewModels { vmFactory }
    private var incomingAccepted = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.startSendingAudio()
        } else {
            Toast.makeText(requireContext(), getString(R.string.call_mic_permission_needed), Toast.LENGTH_LONG).show()
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

        acceptCall.setOnClickListener {
            acceptIncomingCall()
        }

        endCall.setOnClickListener {
            vm.endCall()
            findNavController().popBackStack()
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
                if (requireContext().hasPermission(PERMISSION)) {
                    vm.startSendingAudio()
                } else {
                    requestPermissionLauncher.launch(PERMISSION)
                }
            }
        }

        updateSpeakerphoneIcon()
        speakerphone.setOnClickListener {
            vm.toggleSpeakerphone()
            updateSpeakerphoneIcon()
        }

        backToChat.setOnClickListener {
            findNavController().popBackStack()
        }

        if (vm.inCall.value is CallState.InCall) {
            showActiveCallControls()
            vm.inCall.asLiveData().observe(viewLifecycleOwner) { inCall ->
                if (inCall == CallState.NotInCall) {
                    findNavController().popBackStack()
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

        if (requireContext().hasPermission(PERMISSION)) {
            vm.startSendingAudio()
        }
    }

    private fun updateSpeakerphoneIcon() {
        val icon = if (vm.speakerphoneOn) R.drawable.ic_speakerphone else R.drawable.ic_speakerphone_off
        binding.speakerphone.setImageResource(icon)
    }

    private fun startCall() {
        vm.startCall()
        vm.inCall.asLiveData().observe(viewLifecycleOwner) { inCall ->
            if (inCall == CallState.NotInCall) {
                findNavController().popBackStack()
            }
        }
    }

    private fun acceptIncomingCall() {
        incomingAccepted = true
        showActiveCallControls()
        vm.acceptIncomingCall()
        if (requireContext().hasPermission(PERMISSION)) {
            vm.startSendingAudio()
        } else {
            requestPermissionLauncher.launch(PERMISSION)
        }
        vm.inCall.asLiveData().observe(viewLifecycleOwner) { inCall ->
            if (inCall == CallState.NotInCall) {
                findNavController().popBackStack()
            }
        }
    }

    private fun showIncomingCallControls() = binding.run {
        acceptCall.visibility = View.VISIBLE
        microphoneControl.visibility = View.GONE
        speakerphone.visibility = View.GONE
        backToChat.visibility = View.GONE
    }

    private fun showActiveCallControls() = binding.run {
        acceptCall.visibility = View.GONE
        microphoneControl.visibility = View.VISIBLE
        speakerphone.visibility = View.VISIBLE
        backToChat.visibility = View.VISIBLE
    }

    private fun prepareIncomingCallWindow() {
        val activity = requireActivity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(activity, null)
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
}
