// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.sos

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import javax.inject.Inject
import kotlinx.coroutines.launch
import ltd.evilcorp.atox.App
import ltd.evilcorp.atox.MainActivity
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.databinding.FragmentSosBinding
import ltd.evilcorp.atox.ui.BaseFragment

class SosFragment : BaseFragment<FragmentSosBinding>(FragmentSosBinding::inflate) {
    @Inject lateinit var coordinator: SosCoordinator
    private val handler = Handler(Looper.getMainLooper())
    private var completedHold = false
    private var baseToolbarHeight = 0

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) activateIfPossible()
        else Toast.makeText(requireContext(), R.string.sos_permission_required, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        (requireActivity().application as App).component.inject(this)
        super.onCreate(savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (baseToolbarHeight == 0) baseToolbarHeight = toolbar.layoutParams.height
            toolbar.layoutParams = toolbar.layoutParams.apply { height = baseToolbarHeight + bars.top }
            toolbar.updatePadding(top = bars.top)
            view.updatePadding(bottom = bars.bottom)
            insets
        }
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { goBack() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { goBack() }
        sosButton.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    completedHold = false
                    target.animate().scaleX(1.2f).scaleY(1.2f).setDuration(HOLD_MS).start()
                    handler.postDelayed({
                        completedHold = true
                        if (coordinator.active.value) coordinator.deactivate() else requestActivation()
                        target.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                    }, HOLD_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(null)
                    if (!completedHold) target.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                    true
                }
                else -> true
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                coordinator.active.collect { enabled ->
                    status.setText(if (enabled) R.string.sos_active else R.string.sos_inactive)
                    sosButton.setBackgroundResource(if (enabled) R.drawable.sos_button_active else R.drawable.sos_button_inactive)
                }
            }
        }
        }
    }

    private fun requestActivation() {
        if (coordinator.hasLocationPermission()) activateIfPossible()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun activateIfPossible() = coordinator.canActivate { hasContacts ->
        if (!isAdded) return@canActivate
        requireActivity().runOnUiThread {
            if (hasContacts) coordinator.activate()
            else Toast.makeText(requireContext(), R.string.sos_no_contacts, Toast.LENGTH_LONG).show()
        }
    }

    private fun goBack() {
        (activity as? MainActivity)?.openChatsWithWindowMotion()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    companion object { private const val HOLD_MS = 2_000L }
}
