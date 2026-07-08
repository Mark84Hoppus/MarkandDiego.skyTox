// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
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

        useFingerprint.setOnClickListener { setMode(AppLockMode.Fingerprint) }
        usePin.setOnClickListener { setMode(AppLockMode.Pin) }
        usePattern.setOnClickListener { setMode(AppLockMode.Pattern) }
        removeLock.setOnClickListener { setMode(AppLockMode.None) }
    }

    private fun setMode(mode: AppLockMode) {
        settings.appLockMode = mode
        Toast.makeText(requireContext(), R.string.app_lock_updated, Toast.LENGTH_SHORT).show()
    }
}
