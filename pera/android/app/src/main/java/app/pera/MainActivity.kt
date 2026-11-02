package app.pera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.pera.client.ClientService
import app.pera.client.HotspotTileService
import app.pera.client.SyncService
import app.pera.host.HostService
import app.pera.host.HotspotController
import app.pera.host.HotspotController.ShizukuState
import app.pera.sync.SyncBus
import app.pera.sync.SyncStore
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** Pantalla de Pera: activar el móvil que comparte datos y/o usar este dispositivo como cliente. */
@SuppressLint("UseSwitchCompatOrMaterialCode", "SetTextI18n")
class MainActivity : Activity() {

    private lateinit var hotspot: HotspotController
    private lateinit var hostSwitch: Switch
    private lateinit var shizukuText: TextView
    private lateinit var shizukuButton: Button
    private lateinit var hotspotText: TextView
    private lateinit var batteryButton: Button
    private lateinit var clientText: TextView
    private lateinit var syncSwitch: Switch
    private lateinit var syncSource: TextView
    private lateinit var syncStatus: TextView
    private lateinit var wifiDiag: TextView
    private lateinit var clipDiag: TextView
    private lateinit var saveNets: Button
    private lateinit var store: SyncStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pendingAfterPermissions: (() -> Unit)? = null

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val binderListener = Shizuku.OnBinderReceivedListener { main.post { refresh() } }
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hotspot = HotspotController(this)
        store = SyncStore(this)
        setContentView(buildUi())
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        Watchdog.ensure(this)
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------- Interfaz ----------

    private fun buildUi(): View {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(title("Pera", 28f))
        root.addView(text(getString(R.string.intro)))
        // Shizuku hace falta en el móvil (hotspot, leer redes) y en la tablet (conectarse sola a la Wi-Fi).
        shizukuText = text("")
        root.addView(shizukuText)
        shizukuButton = button("") {}
        root.addView(shizukuButton)

        // --- Móvil ---
        root.addView(title(getString(R.string.host_title), 20f))
        root.addView(text(getString(R.string.host_explain)))
        hostSwitch = Switch(this).apply {
            text = getString(R.string.host_switch)
            isChecked = Prefs.hostEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on -> onHostSwitch(on) }
        }
        root.addView(hostSwitch)
        hotspotText = text("")
        root.addView(hotspotText)
        root.addView(row(
            button(getString(R.string.test_on)) { runLocal(true) },
            button(getString(R.string.test_off)) { runLocal(false) },
        ))
        batteryButton = button(getString(R.string.battery)) { askIgnoreBattery() }
        root.addView(batteryButton)

        // --- Cliente ---
        root.addView(title(getString(R.string.client_title), 20f))
        root.addView(text(getString(R.string.client_explain)))
        root.addView(row(
            button(getString(R.string.client_on)) { runClient(true) },
            button(getString(R.string.client_off)) { runClient(false) },
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            root.addView(button(getString(R.string.add_tile)) { addTile() })
        }
        clientText = text("")
        root.addView(clientText)

        // --- Sincronización ---
        root.addView(title(getString(R.string.sync_section), 20f))
        root.addView(text(getString(R.string.sync_explain)))
        root.addView(Switch(this).apply {
            text = getString(R.string.sync_clip)
            isChecked = store.clipEnabled
            setOnCheckedChangeListener { _, on -> store.clipEnabled = on }
        })
        root.addView(Switch(this).apply {
            text = getString(R.string.sync_wifi)
            isChecked = store.wifiEnabled
            setOnCheckedChangeListener { _, on -> store.wifiEnabled = on }
        })
        syncSwitch = Switch(this).apply {
            text = getString(R.string.sync_switch_client)
            isChecked = Prefs.syncEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on -> onSyncSwitch(on) }
        }
        root.addView(syncSwitch)
        syncSource = text("")
        root.addView(syncSource)
        syncStatus = text("")
        root.addView(syncStatus)
        wifiDiag = text("")
        root.addView(wifiDiag)
        clipDiag = text("")
        root.addView(clipDiag)
        saveNets = button(getString(R.string.save_nets)) {
            val pending = (store.nets + listOfNotNull(store.here)).distinctBy { it.ssid }.filter { !store.saved(it) }
            startActivity(app.pera.client.SaveNetworksActivity.intent(this, pending))
        }
        root.addView(saveNets)
        root.addView(button(getString(R.string.forget_hub)) {
            Prefs.forgetPairedHub(this) // se aplica en la próxima búsqueda del móvil
            clientText.text = getString(R.string.forget_hub_done)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        if (isFinishing) return
        when {
            !hotspot.shizukuInstalled() && hotspot.shizukuStatus() == ShizukuState.NOT_RUNNING -> {
                shizukuText.text = getString(R.string.shizuku_missing)
                shizukuButton.text = getString(R.string.shizuku_download)
                shizukuButton.visibility = View.VISIBLE
                shizukuButton.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)))
                }
            }
            hotspot.shizukuStatus() == ShizukuState.NOT_RUNNING -> {
                shizukuText.text = getString(R.string.shizuku_not_running)
                shizukuButton.text = getString(R.string.shizuku_open)
                shizukuButton.visibility = View.VISIBLE
                shizukuButton.setOnClickListener {
                    packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let { startActivity(it) }
                }
            }
            hotspot.shizukuStatus() == ShizukuState.NO_PERMISSION -> {
                shizukuText.text = getString(R.string.shizuku_no_permission)
                shizukuButton.text = getString(R.string.shizuku_grant)
                shizukuButton.visibility = View.VISIBLE
                shizukuButton.setOnClickListener { Shizuku.requestPermission(1) }
            }
            else -> {
                shizukuText.text = getString(R.string.shizuku_ready)
                shizukuButton.visibility = View.GONE
            }
        }
        io.execute {
            val s = Protocol.stateText(hotspot.state())
            main.post { if (!isFinishing) hotspotText.text = getString(R.string.hotspot_state, s) }
        }
        val pm = getSystemService(PowerManager::class.java)
        batteryButton.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        ClientService.lastMessage?.let { clientText.text = it }

        val host = Prefs.hostEnabled(this)
        wifiDiag.text = getString(
            R.string.wifi_diag,
            if (host) {
                store.wifiDiag ?: "aún sin leer"
            } else {
                (store.wifiDiag ?: "esperando la red del móvil") + " · ${store.nets.size} redes recibidas"
            },
        )
        clipDiag.text = getString(R.string.clip_diag, store.clipDiag.joinToString("\n").ifEmpty { "sin movimientos todavía" })
        // Sin Shizuku, la tablet solo puede guardarlas con la pantalla del sistema (un toque).
        saveNets.visibility = if (!host && store.nets.isNotEmpty() && hotspot.shizukuStatus() != ShizukuState.READY) View.VISIBLE else View.GONE
        syncSwitch.isEnabled = !host // el móvil ya es el centro: no se conecta a sí mismo
        syncSource.text = getString(
            when {
                SyncBus.tecladoIsKeyboard(this) -> R.string.sync_source_teclado
                hotspot.shizukuStatus() == ShizukuState.READY -> R.string.sync_source_shizuku
                else -> R.string.sync_source_none
            },
        )
        syncStatus.text = getString(
            R.string.sync_status,
            when {
                host -> getString(R.string.sync_hub)
                Prefs.syncEnabled(this) -> SyncService.status ?: getString(R.string.sync_searching)
                else -> getString(R.string.sync_off)
            },
        )
    }

    // ---------- Acciones ----------

    private fun onHostSwitch(on: Boolean) {
        if (!on) {
            Prefs.setHostEnabled(this, false)
            HostService.stop(this)
            return
        }
        withPermissions(hostPermissions()) {
            Prefs.setHostEnabled(this, true)
            if (Prefs.syncEnabled(this)) {
                Prefs.setSyncEnabled(this, false)
                syncSwitch.isChecked = false
                SyncService.stop(this)
            }
            HostService.start(this)
        }
    }

    private fun onSyncSwitch(on: Boolean) {
        if (!on) {
            Prefs.setSyncEnabled(this, false)
            SyncService.stop(this)
            return
        }
        withPermissions(clientPermissions()) {
            Prefs.setSyncEnabled(this, true)
            SyncService.start(this)
        }
    }

    private fun runLocal(on: Boolean) {
        io.execute {
            val error = if (on) hotspot.start() else hotspot.stop()
            main.post { hotspotText.text = error ?: getString(R.string.sent) }
        }
    }

    private fun runClient(on: Boolean) {
        withPermissions(clientPermissions()) {
            clientText.text = getString(R.string.client_working)
            startForegroundService(ClientService.intent(this, on))
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, HotspotTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_pera),
            mainExecutor,
        ) {}
    }

    @SuppressLint("BatteryLife") // app instalada a mano: el servicio debe sobrevivir al ahorro de batería
    private fun askIgnoreBattery() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
        )
    }

    // ---------- Permisos ----------

    private fun hostPermissions() = listOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    ) + notificationPermission()

    private fun clientPermissions() = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    ) + notificationPermission()

    private fun notificationPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    private fun withPermissions(perms: List<String>, action: () -> Unit) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingAfterPermissions = action
        requestPermissions(missing.toTypedArray(), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pendingAfterPermissions
        pendingAfterPermissions = null
        // Las notificaciones son opcionales; Bluetooth no.
        val bluetoothOk = permissions.indices.all { i ->
            permissions[i] == Manifest.permission.POST_NOTIFICATIONS || grantResults[i] == PackageManager.PERMISSION_GRANTED
        }
        if (bluetoothOk) {
            action?.invoke()
        } else {
            hostSwitch.isChecked = Prefs.hostEnabled(this)
            syncSwitch.isChecked = Prefs.syncEnabled(this)
            clientText.text = getString(R.string.need_bluetooth)
        }
    }

    // ---------- Utilidades de vista ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(s: String, size: Float) = TextView(this).apply {
        text = s
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun text(s: String) = TextView(this).apply {
        text = s
        textSize = 15f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        /** Fork de Shizuku con arranque automático tras reiniciar (sin root, Android 11+). */
        private const val SHIZUKU_URL = "https://github.com/thedjchi/Shizuku/releases"
    }
}
