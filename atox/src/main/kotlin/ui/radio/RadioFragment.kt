// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.radio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.launch
import ltd.evilcorp.atox.MainActivity
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.databinding.FragmentRadioBinding
import ltd.evilcorp.atox.ui.BaseFragment
import markanddiego.skytox.radio.RadioChannel
import markanddiego.skytox.radio.RadioLocalNetworkState
import markanddiego.skytox.radio.RadioPeer
import markanddiego.skytox.radio.RadioSnapshot
import markanddiego.skytox.radio.RadioTransportState
import kotlin.math.min

class RadioFragment : BaseFragment<FragmentRadioBinding>(FragmentRadioBinding::inflate) {
    private var snapshot = RadioSnapshot()
    private var hotspotState = RadioHotspotState()
    private var serviceStarted = false
    private var baseToolbarHeight = 0
    private var pendingHotspotStart = false
    private var lastHotspotError: Int? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val microphoneGranted = granted(Manifest.permission.RECORD_AUDIO)
        if (microphoneGranted && !serviceStarted) startRadio()
        if (pendingHotspotStart) {
            pendingHotspotStart = false
            if (hotspotPermissions().all(::granted)) command(RadioService.ACTION_START_HOTSPOT)
            else Toast.makeText(requireContext(), R.string.radio_hotspot_permission_required, Toast.LENGTH_LONG).show()
        } else if (!microphoneGranted) {
            binding.statusTitle.setText(R.string.radio_permissions_required)
        }
    }

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.trim()?.takeIf { it.isNotBlank() }?.let {
            handleScannedQr(it)
        }
    }

    private val addWifiLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAdded) command(RadioService.ACTION_REFRESH_NETWORK)
        }, 2_500L)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (baseToolbarHeight == 0) baseToolbarHeight = toolbar.layoutParams.height
            toolbar.layoutParams = toolbar.layoutParams.apply { height = baseToolbarHeight + bars.top }
            toolbar.updatePadding(top = bars.top)
            view.updatePadding(bottom = bars.bottom)
            insets
        }
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { requestExit() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.create_radio_channel -> {
                    if (snapshot.channel == null && snapshot.network.state == RadioLocalNetworkState.READY) {
                        command(RadioService.ACTION_CREATE)
                    }
                    true
                }
                R.id.manual_radio_endpoint -> { showManualEndpointDialog(); true }
                R.id.refresh_radio_network -> { command(RadioService.ACTION_REFRESH_NETWORK); true }
                else -> false
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { requestExit() }
        hotspotButton.setOnClickListener {
            if (hotspotState.active) confirmStopHotspot() else requestHotspotStart()
        }
        showConnectionButton.setOnClickListener {
            if (hotspotState.active) showHotspotCredentials(hotspotState) else scanRadioQr()
        }
        pttFrame.isClickable = true
        pttFrame.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (snapshot.canTalk) {
                    target.parent?.requestDisallowInterceptTouchEvent(true)
                    target.isPressed = true
                    command(RadioService.ACTION_PTT_DOWN)
                    true
                } else false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.parent?.requestDisallowInterceptTouchEvent(false)
                    target.isPressed = false
                    command(RadioService.ACTION_PTT_UP)
                    true
                }
                else -> true
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { RadioRuntime.state.collect(::render) }
                launch { RadioRuntime.hotspot.collect(::renderHotspot) }
            }
        }
        ensurePermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()
        if (serviceStarted) command(RadioService.ACTION_VISIBLE)
    }

    override fun onStop() {
        if (serviceStarted) {
            if (snapshot.localPending || snapshot.localSpeaking) command(RadioService.ACTION_PTT_UP)
            command(RadioService.ACTION_HIDDEN)
        }
        super.onStop()
    }

    private fun ensurePermissionsAndStart() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filterNot(::granted)
        if (missing.isEmpty()) startRadio() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestHotspotStart() {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(requireContext(), R.string.radio_hotspot_unsupported, Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            return
        }
        val missing = hotspotPermissions().filterNot(::granted)
        if (missing.isEmpty()) command(RadioService.ACTION_START_HOTSPOT)
        else {
            pendingHotspotStart = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hotspotPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED

    private fun startRadio() {
        serviceStarted = true
        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), RadioService::class.java).setAction(RadioService.ACTION_START)
                .putExtra(RadioService.EXTRA_NICKNAME, RadioRuntime.nickname),
        )
    }

    private fun command(action: String, channel: Int? = null, endpoint: String? = null) {
        val intent = Intent(requireContext(), RadioService::class.java).setAction(action)
        channel?.let { intent.putExtra(RadioService.EXTRA_CHANNEL, it) }
        endpoint?.let { intent.putExtra(RadioService.EXTRA_ENDPOINT, it) }
        requireContext().startService(intent)
    }

    private fun showManualEndpointDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.radio_manual_endpoint_hint)
            isSingleLine = true
            setPadding(24.dp, 8.dp, 24.dp, 8.dp)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.radio_manual_endpoint)
            .setMessage(R.string.radio_manual_endpoint_explanation)
            .setView(input)
            .setPositiveButton(R.string.yes) { _, _ ->
                input.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let {
                    command(RadioService.ACTION_MANUAL_ENDPOINT, endpoint = it)
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun scanRadioQr() {
        runCatching {
            scanQrLauncher.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false).setOrientationLocked(false).setPrompt(""),
            )
        }.onFailure { showManualEndpointDialog() }
    }

    private fun handleScannedQr(value: String) {
        val wifi = parseWifiQr(value)
        if (wifi == null) {
            command(RadioService.ACTION_MANUAL_ENDPOINT, endpoint = value)
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val suggestion = WifiNetworkSuggestion.Builder().setSsid(wifi.first).apply {
                if (wifi.second.isNotEmpty()) setWpa2Passphrase(wifi.second)
                setIsAppInteractionRequired(true)
            }.build()
            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(
                Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion),
            )
            runCatching { addWifiLauncher.launch(intent) }.onFailure { showWifiCredentials(wifi) }
        } else {
            showWifiCredentials(wifi)
        }
    }

    private fun parseWifiQr(value: String): Pair<String, String>? {
        if (!value.startsWith("WIFI:", ignoreCase = true)) return null
        fun field(name: String): String? = Regex("(?:^|;)$name:((?:\\\\.|[^;])*)", RegexOption.IGNORE_CASE)
            .find(value.substring(5))?.groupValues?.getOrNull(1)?.let(::wifiQrUnescape)
        val ssid = field("S")?.takeIf { it.isNotBlank() } ?: return null
        return ssid to field("P").orEmpty()
    }

    private fun wifiQrUnescape(value: String): String = value.replace(Regex("\\\\([\\\\;,:])"), "\$1")

    private fun showWifiCredentials(wifi: Pair<String, String>) {
        AlertDialog.Builder(requireContext()).setTitle(R.string.radio_join_hotspot)
            .setMessage(getString(R.string.radio_hotspot_credentials, wifi.first, wifi.second))
            .setPositiveButton(R.string.radio_open_wifi_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun render(value: RadioSnapshot) = binding.run {
        snapshot = value
        val networkReady = value.network.state == RadioLocalNetworkState.READY
        toolbar.menu.findItem(R.id.create_radio_channel)?.isEnabled = value.channel == null && networkReady
        toolbar.menu.findItem(R.id.manual_radio_endpoint)?.isEnabled = value.channel == null && networkReady
        updateConnectionButtons()
        networkEndpoint.text = value.network.endpoint?.let {
            getString(R.string.radio_local_endpoint, it, value.network.interfaceName.orEmpty())
        }.orEmpty()
        when {
            value.network.state == RadioLocalNetworkState.UNAVAILABLE -> {
                statusTitle.setText(R.string.radio_no_local_network)
                statusSubtitle.setText(R.string.radio_no_local_network_hint)
            }
            value.network.state == RadioLocalNetworkState.RESTRICTED -> {
                statusTitle.setText(R.string.radio_network_restricted)
                statusSubtitle.setText(R.string.radio_network_restricted_hint)
            }
            value.transportState == RadioTransportState.ERROR || value.transportState == RadioTransportState.LOST -> {
                statusTitle.setText(R.string.radio_connection_lost)
                statusSubtitle.setText(R.string.radio_lan_hint)
            }
            value.channel == null -> {
                statusTitle.setText(R.string.radio_scanning)
                statusSubtitle.setText(R.string.radio_lan_hint)
            }
            else -> {
                statusTitle.text = getString(R.string.radio_channel_number, value.channel)
                statusSubtitle.text = resources.getQuantityString(
                    R.plurals.radio_participants_count, value.peers.size, value.peers.size,
                )
            }
        }
        listTitle.setText(if (value.channel == null) R.string.radio_channels else R.string.radio_participants)
        items.removeAllViews()
        if (value.channel == null) value.channels.forEach(::addChannelRow) else value.peers.forEach(::addPeerRow)
        if (items.childCount == 0) addEmptyRow(
            if (value.channel == null) R.string.radio_no_channels else R.string.radio_waiting_participant,
        )
        val pressed = value.localPending || value.localSpeaking
        pttButton.setBackgroundResource(if (pressed) R.drawable.radio_ptt_pressed else R.drawable.radio_ptt_theme)
        pttButton.alpha = if (value.activeSpeaker != null && !value.localSpeaking) 0.42f
            else if (value.canTalk || pressed) 1f else 0.55f
        pttButton.isEnabled = value.canTalk || pressed
        pttFrame.isEnabled = value.canTalk || pressed
        pttButton.text = when {
            value.localSpeaking -> getString(R.string.radio_speaking)
            value.localPending -> getString(R.string.radio_wait)
            value.activeSpeaker != null -> getString(R.string.radio_receiving)
            else -> "PTT"
        }
    }

    private fun renderHotspot(value: RadioHotspotState) {
        val becameActive = !hotspotState.active && value.active
        hotspotState = value
        binding.hotspotButton.setText(
            if (value.active) R.string.radio_stop_hotspot_short else if (value.starting) R.string.radio_hotspot_starting
            else R.string.radio_hotspot_short,
        )
        binding.showConnectionButton.setText(
            if (value.active) R.string.radio_show_qr_short else R.string.radio_scan_qr_short,
        )
        updateConnectionButtons()
        if (becameActive) showHotspotCredentials(value)
        if (value.errorCode != null && value.errorCode != lastHotspotError) {
            lastHotspotError = value.errorCode
            AlertDialog.Builder(requireContext()).setMessage(R.string.radio_hotspot_failed)
                .setPositiveButton(R.string.radio_open_wifi_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun updateConnectionButtons() = binding.run {
        val joined = snapshot.channel != null
        hotspotButton.isEnabled = !joined && !hotspotState.starting
        showConnectionButton.isEnabled = !joined
        hotspotButton.alpha = if (hotspotButton.isEnabled) 1f else 0.45f
        showConnectionButton.alpha = if (showConnectionButton.isEnabled) 1f else 0.45f
    }

    private fun confirmStopHotspot() {
        AlertDialog.Builder(requireContext()).setMessage(R.string.radio_stop_hotspot_question)
            .setPositiveButton(R.string.yes) { _, _ -> command(RadioService.ACTION_STOP_HOTSPOT) }
            .setNegativeButton(R.string.no, null).show()
    }

    private fun showHotspotCredentials(value: RadioHotspotState) {
        val ssid = value.ssid ?: getString(R.string.radio_unknown)
        val password = value.passphrase ?: getString(R.string.radio_unknown)
        val text = getString(
            R.string.radio_hotspot_credentials,
            ssid, password,
        )
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32.dp, 24.dp, 32.dp, 8.dp)
            addView(ImageView(requireContext()).apply {
                setImageBitmap(asQr("WIFI:T:WPA;S:${wifiQrEscape(ssid)};P:${wifiQrEscape(password)};;"))
                adjustViewBounds = true
            })
            addView(TextView(requireContext()).apply { this.text = text; setPadding(0, 16.dp, 0, 0) })
        }
        AlertDialog.Builder(requireContext()).setTitle(R.string.radio_hotspot_ready).setView(content)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun wifiQrEscape(value: String): String = value
        .replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")

    private fun showConnectionDialog() {
        val endpoint = snapshot.network.endpoint ?: return
        val channel = snapshot.channel ?: return
        val payload = "skytox-radio://$endpoint?channel=$channel&v=1"
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32.dp, 32.dp, 32.dp, 32.dp)
            addView(ImageView(requireContext()).apply { setImageBitmap(asQr(payload)); adjustViewBounds = true })
            addView(TextView(requireContext()).apply { text = payload; setPadding(0, 18.dp, 0, 0) })
        }
        AlertDialog.Builder(requireContext()).setTitle(R.string.radio_show_connection).setView(content)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun asQr(payload: String): Bitmap {
        val data = QrCode.encodeText(payload, QrCode.Ecc.LOW)
        val target = (min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.65f)
            .toInt().coerceAtLeast(320)
        val pixel = (target / data.size).coerceAtLeast(1)
        val padding = pixel * 4
        return Bitmap.createBitmap(data.size * pixel + padding * 2, data.size * pixel + padding * 2, Bitmap.Config.RGB_565).apply {
            eraseColor(Color.WHITE)
            for (x in 0 until data.size) for (y in 0 until data.size) {
                val color = if (data.getModule(x, y)) Color.BLACK else Color.WHITE
                for (dx in 0 until pixel) for (dy in 0 until pixel) setPixel(padding + x * pixel + dx, padding + y * pixel + dy, color)
            }
        }
    }

    private fun addChannelRow(channel: RadioChannel) {
        val row = row(getString(R.string.radio_channel_row, channel.number, channel.signalPercent))
        row.setOnClickListener {
            AlertDialog.Builder(requireContext()).setMessage(R.string.radio_join_question)
                .setPositiveButton(R.string.yes) { _, _ -> command(RadioService.ACTION_JOIN, channel.number) }
                .setNegativeButton(R.string.no, null).show()
        }
        binding.items.addView(row)
    }

    private fun addPeerRow(peer: RadioPeer) {
        binding.items.addView(row(getString(R.string.radio_peer_row, peer.nickname, peer.signalPercent)))
    }

    private fun addEmptyRow(text: Int) {
        binding.items.addView(row(getString(text)).apply { alpha = 0.72f })
    }

    private fun row(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 18f
        setTextColor(ContextCompat.getColor(context, R.color.ocean_text_primary))
        setBackgroundResource(R.drawable.radio_card)
        setPadding(20.dp, 18.dp, 20.dp, 18.dp)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = 10.dp }
    }

    private fun requestExit() {
        if (snapshot.channel == null) leaveAndReturnToChats()
        else AlertDialog.Builder(requireContext()).setMessage(R.string.radio_leave_question)
            .setPositiveButton(R.string.yes) { _, _ -> leaveAndReturnToChats() }
            .setNegativeButton(R.string.no, null).show()
    }

    private fun leaveAndReturnToChats() {
        if (serviceStarted) command(RadioService.ACTION_LEAVE)
        serviceStarted = false
        (activity as? MainActivity)?.openChatsWithWindowMotion() ?: findNavController().popBackStack()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
