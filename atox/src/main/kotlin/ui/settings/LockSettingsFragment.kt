// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.settings

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.databinding.FragmentLockSettingsBinding
import ltd.evilcorp.atox.settings.AppLockMode
import ltd.evilcorp.atox.settings.Settings
import ltd.evilcorp.atox.ui.BaseFragment

class LockSettingsFragment : BaseFragment<FragmentLockSettingsBinding>(FragmentLockSettingsBinding::inflate) {
    private lateinit var settings: Settings
    private var desiredMode: AppLockMode? = null
    private var changingProgrammatically = false
    private val lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val mode = desiredMode
        desiredMode = null
        if (result.resultCode == Activity.RESULT_OK && mode != null) {
            settings.appLockMode = mode
            Toast.makeText(requireContext(), R.string.app_lock_updated, Toast.LENGTH_SHORT).show()
        }
        syncSwitch()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        settings = Settings(requireContext())

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(top = insets.top)
            v.updatePadding(left = insets.left, right = insets.right, bottom = insets.bottom)
            compat
        }

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        syncSwitch()
        appLockEnabled.setOnCheckedChangeListener { _, checked ->
            if (changingProgrammatically) return@setOnCheckedChangeListener
            requestMode(if (checked) AppLockMode.DeviceCredential else AppLockMode.None)
        }
    }

    private fun requestMode(mode: AppLockMode) {
        val keyguard = requireContext().getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardSecure != true) {
            Toast.makeText(requireContext(), R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            startActivity(Intent(AndroidSettings.ACTION_SECURITY_SETTINGS))
            syncSwitch()
            return
        }

        val intent = keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.app_lock_unlock_title),
            "",
        )
        if (intent == null) {
            Toast.makeText(requireContext(), R.string.app_lock_unavailable, Toast.LENGTH_LONG).show()
            syncSwitch()
            return
        }

        desiredMode = mode
        lockLauncher.launch(intent)
    }

    private fun syncSwitch() {
        changingProgrammatically = true
        binding.appLockEnabled.isChecked = settings.appLockMode != AppLockMode.None
        changingProgrammatically = false
    }
}
