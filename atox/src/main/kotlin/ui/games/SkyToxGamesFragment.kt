// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package ltd.evilcorp.atox.ui.games

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import com.hqgame.networknes.BaseActivity
import com.hqgame.networknes.MultinessGameActivity
import com.hqgame.networknes.NesDiagnostics
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.nayuki.qrcodegen.QrCode
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import ltd.evilcorp.atox.R
import ltd.evilcorp.atox.databinding.FragmentGamesBinding
import ltd.evilcorp.atox.ui.BaseFragment
import ltd.evilcorp.domain.feature.SkyToxPublicFolders

class SkyToxGamesFragment : BaseFragment<FragmentGamesBinding>(FragmentGamesBinding::inflate) {
    private var games: List<File> = emptyList()
    private var baseToolbarHeight = 0
    private var serverAddress: String? = null
    private var serverDialog: AlertDialog? = null
    private var signalServer: ServerSocket? = null
    private val signalServerRunning = AtomicBoolean(false)

    private val scanQrLauncher = registerForActivityResult(ScanContract()) {
        val payload = it.contents ?: return@registerForActivityResult
        joinServer(parseServerAddress(payload) ?: payload)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?): Unit = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val insets = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            if (baseToolbarHeight == 0) {
                baseToolbarHeight = toolbar.layoutParams.height
            }
            toolbar.layoutParams = toolbar.layoutParams.apply {
                height = baseToolbarHeight + insets.top
            }
            toolbar.updatePadding(top = insets.top)
            gamesList.updatePadding(bottom = insets.bottom + 24)
            emulatorThanks.updatePadding(bottom = insets.bottom + 12)
            compat
        }

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        toolbar.setOnMenuItemClickListener(::onGamesMenuItem)
        emulatorThanks.movementMethod = LinkMovementMethod.getInstance()

        refreshGames()
        refreshMenu()

        gamesList.setOnItemClickListener { _, _, position, _ ->
            startGame(games[position])
        }
    }

    override fun onResume() {
        super.onResume()
        if (binding.gamesList.adapter != null) {
            refreshGames()
        }
    }

    override fun onDestroyView() {
        stopSignalServer()
        serverDialog?.dismiss()
        serverDialog = null
        super.onDestroyView()
    }

    private fun refreshGames() = binding.run {
        SkyToxPublicFolders.ensureDirectories()
        games = SkyToxPublicFolders.gamesDir
            .listFiles { file -> file.isFile && file.extension.equals("nes", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }

        toolbar.title = getString(
            if (games.isEmpty()) R.string.skytox_games_not_found else R.string.skytox_games,
        )
        toolbar.subtitle = serverAddress?.let {
            getString(R.string.skytox_games_server_running, "$it:${MultinessGameActivity.DEFAULT_PORT}")
        }
        gamesList.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            games.map { it.name },
        )
    }

    private fun startGame(rom: File) {
        runCatching {
            val mode = if (serverAddress == null) {
                MultinessGameActivity.MODE_SINGLE
            } else {
                MultinessGameActivity.MODE_HOST
            }
            startActivity(
                Intent(requireContext(), MultinessGameActivity::class.java)
                    .putExtra(MultinessGameActivity.EXTRA_ROM_PATH, rom.absolutePath)
                    .putExtra(MultinessGameActivity.EXTRA_MODE, mode),
            )
        }.onFailure {
            Toast.makeText(requireContext(), R.string.skytox_games_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshMenu() = binding.toolbar.menu.run {
        clear()
        add(
            Menu.NONE,
            MENU_SERVER,
            Menu.NONE,
            if (serverAddress == null) R.string.skytox_games_start_server else R.string.skytox_games_stop_server,
        ).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        add(Menu.NONE, MENU_JOIN, Menu.NONE, R.string.skytox_games_join).apply {
            isEnabled = serverAddress == null
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }

    private fun onGamesMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SERVER -> {
            if (serverAddress == null) {
                startServerMode()
            } else {
                stopServerMode()
            }
            true
        }
        MENU_JOIN -> {
            scanOrEnterServerAddress()
            true
        }
        else -> false
    }

    private fun startServerMode() {
        runCatching {
            BaseActivity.init(requireContext().applicationContext)
            BaseActivity.bindProcessToWifiNetwork(requireContext().applicationContext)
            serverAddress = BaseActivity.currentHostIPAddress()
            refreshGames()
            refreshMenu()
            showServerQrDialog(requireNotNull(serverAddress))
        }.onFailure {
            Toast.makeText(requireContext(), R.string.skytox_games_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServerMode() {
        serverAddress = null
        stopSignalServer()
        BaseActivity.clearProcessNetworkBinding(requireContext().applicationContext)
        refreshGames()
        refreshMenu()
    }

    private fun scanOrEnterServerAddress() {
        runCatching {
            scanQrLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
                    .setPrompt(""),
            )
        }.onFailure {
            Toast.makeText(requireContext(), R.string.skytox_games_scanner_missing, Toast.LENGTH_LONG).show()
            showJoinManualDialog()
        }
    }

    private fun joinServer(host: String) {
        val endpoint = parseServerEndpoint(host) ?: ServerEndpoint(host.trim(), MultinessGameActivity.DEFAULT_PORT)
        runCatching {
            val appContext = requireContext().applicationContext
            BaseActivity.bindProcessToWifiNetwork(appContext)
            notifyServerScanned(endpoint.host)
            binding.root.postDelayed(
                {
                    runCatching {
                        val intent = Intent(appContext, MultinessGameActivity::class.java)
                            .putExtra(MultinessGameActivity.EXTRA_MODE, MultinessGameActivity.MODE_CLIENT)
                            .putExtra(MultinessGameActivity.EXTRA_HOST, endpoint.host)
                            .putExtra(MultinessGameActivity.EXTRA_PORT, endpoint.port)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        appContext.startActivity(intent)
                    }.onFailure {
                        NesDiagnostics.error("SkyToxGamesFragment.joinServer startActivity failed", it)
                        Toast.makeText(appContext, R.string.skytox_games_join_failed, Toast.LENGTH_LONG).show()
                    }
                },
                700L,
            )
        }.onFailure {
            NesDiagnostics.error("SkyToxGamesFragment.joinServer failed", it)
            Toast.makeText(requireContext(), R.string.skytox_games_join_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showJoinManualDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.skytox_games_join_manual_hint)
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.skytox_games_join_manual_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotBlank() }?.let(::joinServer)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showServerQrDialog(host: String) {
        startSignalServer()
        val payload = "skytox-game://$host:${MultinessGameActivity.DEFAULT_PORT}"
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32)
            addView(ImageView(requireContext()).apply {
                setImageBitmap(asQr(payload))
                adjustViewBounds = true
            })
            addView(TextView(requireContext()).apply {
                text = getString(R.string.skytox_games_waiting_client, "$host:${MultinessGameActivity.DEFAULT_PORT}")
                setPadding(0, 18, 0, 0)
            })
        }
        serverDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.skytox_games_start_server)
            .setView(content)
            .setNegativeButton(android.R.string.cancel) { _, _ -> stopSignalServer() }
            .show()
    }

    private fun asQr(payload: String): Bitmap {
        val qrData = QrCode.encodeText(payload, QrCode.Ecc.LOW)
        val scale = (min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.65f).toInt()
            .coerceAtLeast(320)
        val pixelSize = (scale / qrData.size).coerceAtLeast(1)
        val padding = pixelSize * 4
        val size = qrData.size * pixelSize + padding * 2
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            eraseColor(Color.WHITE)
            for (x in 0 until qrData.size) {
                for (y in 0 until qrData.size) {
                    val color = if (qrData.getModule(x, y)) Color.BLACK else Color.WHITE
                    for (dx in 0 until pixelSize) {
                        for (dy in 0 until pixelSize) {
                            setPixel(padding + x * pixelSize + dx, padding + y * pixelSize + dy, color)
                        }
                    }
                }
            }
        }
    }

    private fun parseServerAddress(payload: String): String? = runCatching {
        parseServerEndpoint(payload)?.host ?: run {
            val uri = Uri.parse(payload)
            when {
                uri.scheme == "skytox-game" && !uri.host.isNullOrBlank() -> uri.host
                payload.contains(":") -> payload.substringBefore(":").takeIf { it.isNotBlank() }
                else -> payload.takeIf { it.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) }
            }
        }
    }.getOrNull()

    private fun parseServerEndpoint(payload: String): ServerEndpoint? = runCatching {
        val trimmed = payload.trim()
        val uri = Uri.parse(payload)
        when {
            uri.scheme == "skytox-game" && !uri.host.isNullOrBlank() -> {
                ServerEndpoint(uri.host!!, if (uri.port > 0) uri.port else MultinessGameActivity.DEFAULT_PORT)
            }
            trimmed.contains(":") -> {
                val host = trimmed.substringBefore(":").trim()
                val port = trimmed.substringAfter(":", "").trim().toIntOrNull() ?: MultinessGameActivity.DEFAULT_PORT
                host.takeIf { it.isNotBlank() }?.let { ServerEndpoint(it, port) }
            }
            trimmed.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) -> {
                ServerEndpoint(trimmed, MultinessGameActivity.DEFAULT_PORT)
            }
            else -> null
        }
    }.getOrNull()

    private fun startSignalServer() {
        stopSignalServer()
        signalServerRunning.set(true)
        Thread {
            runCatching {
                val signalPort = MultinessGameActivity.DEFAULT_PORT + 1
                NesDiagnostics.event("SkyToxGamesFragment.signalServer starting port=$signalPort")
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(signalPort))
                    signalServer = server
                    server.soTimeout = 1000
                    NesDiagnostics.event("SkyToxGamesFragment.signalServer listening port=$signalPort")
                    while (signalServerRunning.get()) {
                        try {
                            server.accept().use { socket ->
                                NesDiagnostics.event("SkyToxGamesFragment.signalServer accepted from=${socket.inetAddress?.hostAddress}")
                                socket.getOutputStream().write("OK\n".toByteArray())
                                socket.getOutputStream().flush()
                            }
                            signalServerRunning.set(false)
                            view?.post {
                                NesDiagnostics.event("SkyToxGamesFragment.signalServer dismiss qr dialog")
                                serverDialog?.dismiss()
                                serverDialog = null
                            }
                        } catch (_: SocketTimeoutException) {
                            // keep waiting until canceled or a client pings us
                        }
                    }
                }
            }.onFailure {
                NesDiagnostics.error("SkyToxGamesFragment.signalServer failed", it)
            }
            NesDiagnostics.event("SkyToxGamesFragment.signalServer stopped")
            signalServer = null
        }.apply {
            name = "skytox-nes-signal-server"
            isDaemon = true
            start()
        }
    }

    private fun stopSignalServer() {
        NesDiagnostics.event("SkyToxGamesFragment.signalServer stop requested")
        signalServerRunning.set(false)
        runCatching { signalServer?.close() }
        signalServer = null
    }

    private fun notifyServerScanned(host: String) {
        val appContext = requireContext().applicationContext
        Thread {
            runCatching {
                val signalPort = MultinessGameActivity.DEFAULT_PORT + 1
                NesDiagnostics.event("SkyToxGamesFragment.signalClient connect host=$host port=$signalPort")
                BaseActivity.createWifiSocket(appContext, host, signalPort, 4000).use { socket ->
                    socket.soTimeout = 1000
                    socket.getInputStream().read(ByteArray(16))
                }
                NesDiagnostics.event("SkyToxGamesFragment.signalClient success host=$host")
            }.onFailure {
                NesDiagnostics.error("SkyToxGamesFragment.signalClient failed host=$host", it)
            }
        }.apply {
            name = "skytox-nes-signal-client"
            isDaemon = true
            start()
        }
    }

    private data class ServerEndpoint(val host: String, val port: Int)

    private companion object {
        const val MENU_SERVER = 1
        const val MENU_JOIN = 2
    }
}
