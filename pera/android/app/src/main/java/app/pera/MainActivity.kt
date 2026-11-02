package app.pera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.pera.client.ClientService
import app.pera.client.HotspotTileService
import app.pera.client.SaveNetworksActivity
import app.pera.client.SyncService
import app.pera.host.HostService
import app.pera.host.HotspotController
import app.pera.host.HotspotController.ShizukuState
import app.pera.sync.SyncBus
import app.pera.sync.SyncStore
import app.pera.ui.Look
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Pantalla de Pera: una tarjeta por función. Verde suave si está activa, rojiza si no. Al tocarla se
 * abre su ficha: el interruptor de la función y cada cosa que necesita, con su botón para arreglarla.
 */
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {

    /** Una función de Pera tal como se muestra en su tarjeta. */
    private class Feature(
        val emoji: String,
        val title: String,
        val status: () -> String,
        val active: () -> Boolean,
        val detail: (LinearLayout) -> Unit,
    )

    private lateinit var look: Look
    private lateinit var hotspot: HotspotController
    private lateinit var store: SyncStore
    private lateinit var grid: GridLayout
    private lateinit var roleRow: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pendingAfterPermissions: (() -> Unit)? = null
    private var message: String? = null
    @Volatile private var hotspotState = -1

    private var openFeature: Feature? = null
    private var openBody: LinearLayout? = null
    private var openKey = ""

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post { refresh() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { main.post { refresh() } }
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        look = Look(this)
        hotspot = HotspotController(this)
        store = SyncStore(this)
        setContentView(buildUi())
        // Iconos de la barra de estado oscuros sobre el fondo claro.
        if (!look.dark) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        }
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

    // ---------- Estado ----------

    private val isHost get() = Prefs.hostEnabled(this)
    private val isClient get() = Prefs.syncEnabled(this)
    private val inFamily get() = isHost || isClient
    private val shizukuReady get() = hotspot.shizukuStatus() == ShizukuState.READY
    private val tecladoInstalled get() = runCatching { packageManager.getPackageInfo(SyncBus.TECLADO_PACKAGE, 0) }.isSuccess
    private val batteryFree get() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    private fun granted(perms: List<String>) = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private val linkConnected get() = SyncService.status == "Conectado al móvil"

    private val features: List<Feature> by lazy {
        listOf(
            Feature("📶", "Datos del móvil", ::hotspotStatus, ::hotspotActive, ::hotspotDetail),
            Feature("📋", "Copiar y pegar", ::clipStatus, ::clipActive, ::clipDetail),
            Feature("📌", "Fijados del teclado", ::pinsStatus, ::pinsActive, ::pinsDetail),
            Feature("📡", "Wi-Fi compartida", ::wifiStatus, ::wifiActive, ::wifiDetail),
            Feature("🍐", "Familia Pera", ::familyStatus, ::familyActive, ::familyDetail),
            Feature("🔑", "Shizuku", ::shizukuStatusText, { shizukuReady }, ::shizukuDetail),
        )
    }

    // ---------- Pantalla ----------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.dp(20), look.dp(28), look.dp(20), look.dp(28))
        }
        // Cabecera: la pera mordida y el nombre.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_launcher) },
                LinearLayout.LayoutParams(look.dp(52), look.dp(52)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(look.dp(14), 0, 0, 0)
                addView(look.label("Pera", 30f, bold = true))
                addView(look.label("Tu móvil, tu tablet y tu Mac, como una familia", 14f, look.soft))
            })
        })

        root.addView(look.label("Este dispositivo es…", 14f, look.soft).apply { setPadding(0, look.dp(24), 0, look.dp(8)) })
        roleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(roleRow)

        val columns = if (resources.configuration.smallestScreenWidthDp >= 600) 3 else 2
        grid = GridLayout(this).apply {
            columnCount = columns
            setPadding(0, look.dp(16), 0, 0)
        }
        root.addView(grid)

        root.addView(look.label("Toca una función para activarla o ver qué le falta.", 13f, look.soft).apply {
            gravity = Gravity.CENTER
            setPadding(0, look.dp(18), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(look.background)
            isFillViewport = true
            fitsSystemWindows = true // Android 15+ dibuja borde a borde: deja sitio a las barras del sistema
            addView(root)
        }
    }

    private fun refresh() {
        if (isFinishing) return
        io.execute { hotspotState = hotspot.state() }
        drawRoles()
        drawGrid()
        // La ficha abierta se repinta solo si ha cambiado algo (para no estorbar al pulsar).
        val f = openFeature ?: return
        val key = stateKey(f)
        if (key != openKey) {
            openKey = key
            openBody?.let { fillDetail(f, it) }
        }
    }

    private fun stateKey(f: Feature) = listOf(
        f.active(), f.status(), isHost, isClient, shizukuReady, batteryFree, hotspotState, message,
        store.clipEnabled, store.pinsEnabled, store.wifiEnabled, store.wifiDiag, store.clipDiag, store.pinsDiag, SyncService.status,
        ClientService.lastMessage,
    ).joinToString("|")

    private fun drawRoles() {
        roleRow.removeAllViews()
        fun role(label: String, selected: Boolean, onTap: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (selected) look.onInk else look.text)
            background = if (selected) look.tint(true).apply { cornerRadius = look.dp(50).toFloat() }
            else look.rounded(look.card, look.line, 50)
            setPadding(look.dp(12), look.dp(12), look.dp(12), look.dp(12))
            setOnClickListener { onTap() }
        }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = look.dp(8) } }
        roleRow.addView(role("📱  Mi móvil", isHost) { setHost(!isHost) }, lp())
        roleRow.addView(role("💻  Tablet u otro", isClient) { setClient(!isClient) }, lp())
    }

    private fun drawGrid() {
        grid.removeAllViews()
        val gap = look.dp(6)
        val width = (resources.displayMetrics.widthPixels - look.dp(40)) / grid.columnCount - gap * 2
        for (f in features) {
            val on = f.active()
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = look.tint(on)
                setPadding(look.dp(16), look.dp(16), look.dp(16), look.dp(16))

                isClickable = true
                setOnClickListener { openDetail(f) }
                addView(look.label(f.emoji, 28f))
                addView(look.label(f.title, 16f, bold = true).apply { setPadding(0, look.dp(10), 0, look.dp(2)) })
                addView(look.label(f.status(), 13f, if (on) look.onInk else look.offInk).apply {
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                this.width = width
                height = look.dp(168) // todas iguales, quepa o no una línea más
                setMargins(gap, gap, gap, gap)
            })
        }
    }

    // ---------- Ficha de una función ----------

    private fun openDetail(f: Feature) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.dp(22), look.dp(22), look.dp(22), look.dp(18))
        }
        openFeature = f
        openBody = body
        openKey = stateKey(f)
        fillDetail(f, body)
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(ScrollView(this@MainActivity).apply {
                background = look.rounded(look.background, look.line, 28)
                addView(body)
            })
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt().coerceAtMost(look.dp(560)),
                ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnDismissListener { if (openFeature === f) { openFeature = null; openBody = null } }
        }
        dialog.show()
    }

    private fun fillDetail(f: Feature, body: LinearLayout) {
        body.removeAllViews()
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(look.label(f.emoji, 30f))
            addView(look.label(f.title, 22f, bold = true).apply { setPadding(look.dp(12), 0, 0, 0) })
        })
        body.addView(look.label(f.status(), 14f, if (f.active()) look.onInk else look.offInk).apply {
            setPadding(0, look.dp(6), 0, look.dp(4))
        })
        body.addView(look.divider())
        f.detail(body)
        message?.let { body.addView(look.label(it, 13f, look.soft).apply { setPadding(0, look.dp(10), 0, 0) }) }
    }

    private fun LinearLayout.info(s: String) = addView(look.label(s, 14f, look.soft).apply { setPadding(0, look.dp(4), 0, look.dp(8)) })

    private fun LinearLayout.buttons(vararg b: Pair<String, () -> Unit>) = addView(LinearLayout(this@MainActivity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, look.dp(8), 0, look.dp(4))
        b.forEachIndexed { i, (label, action) ->
            addView(look.pill(label, strong = i == 0, onClick = action),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = look.dp(8) })
        }
    })

    private fun LinearLayout.familyRequirement() = addView(look.requirement(
        inFamily, if (inFamily) "Pera activa en este dispositivo" else "Elige arriba si este dispositivo es tu móvil o tu tablet",
    ))

    // --- Datos del móvil ---

    private fun hotspotActive() = if (isHost) shizukuReady else granted(clientPermissions())
    private fun hotspotStatus() = when {
        isHost && !shizukuReady -> "Falta Shizuku para encender el hotspot"
        isHost -> "Listo · hotspot ${Protocol.stateText(hotspotState).takeIf { hotspotState >= 0 } ?: "…"}"
        !granted(clientPermissions()) -> "Falta el permiso de Bluetooth"
        else -> if (Prefs.clientLastOn(this)) "Usando los datos del móvil" else "Listo para conectar"
    }

    private fun hotspotDetail(b: LinearLayout) {
        if (isHost) {
            b.addView(look.toggle("Compartir los datos de este móvil", isHost) { setHost(it) })
            b.info("La tablet o el Mac piden por Bluetooth que este móvil encienda su hotspot, y se conectan solos.")
            b.addView(look.requirement(shizukuReady, "Shizuku listo (para encender el hotspot)", "Arreglar" to { openDetail(features.last()) }))
            b.addView(look.requirement(granted(hostPermissions()), "Permiso de Bluetooth", "Dar" to { withPermissions(hostPermissions()) { refresh() } }))
            b.addView(look.requirement(batteryFree, "Puede funcionar en segundo plano", "Permitir" to ::askIgnoreBattery))
            b.buttons("Probar: encender" to { runLocal(true) }, "Apagar" to { runLocal(false) })
        } else {
            b.info("Un toque y la tablet le pide al móvil que comparta sus datos; otro toque y se apaga. También desde el botón «Datos del móvil» del panel de ajustes rápidos.")
            b.addView(look.requirement(granted(clientPermissions()), "Permiso de Bluetooth", "Dar" to { withPermissions(clientPermissions()) { refresh() } }))
            b.addView(look.requirement(Prefs.pairedHubId(this) != null, "Emparejada con tu móvil (se hace la primera vez que conectas)"))
            b.addView(look.requirement(shizukuReady, "Shizuku en esta tablet (para conectarse sola al hotspot)", "Ver" to { openDetail(features.last()) }))
            b.buttons("Conectar" to { runClient(true) }, "Desconectar" to { runClient(false) })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) b.buttons("Añadir el botón al panel" to ::addTile)
            ClientService.lastMessage?.let { b.info(it) }
        }
    }

    // --- Copiar y pegar ---

    private fun clipSource() = when {
        SyncBus.tecladoIsKeyboard(this) -> "Teclado"
        shizukuReady -> "Shizuku"
        else -> null
    }

    private fun clipActive() = store.clipEnabled && inFamily
    private fun clipStatus() = when {
        !store.clipEnabled -> "Desactivado"
        !inFamily -> "Falta elegir móvil o tablet"
        clipSource() == null -> "Solo recibe (falta Teclado o Shizuku)"
        else -> "Texto y fotos · con ${clipSource()}"
    }

    private fun clipDetail(b: LinearLayout) {
        b.addView(look.toggle("Copiar y pegar entre dispositivos", store.clipEnabled) { store.clipEnabled = it; refresh() })
        b.info("Lo último que copias (texto o una foto) aparece en tu tablet y tu Mac, listo para pegar. Las fotos van a calidad completa por tu Wi-Fi; solo si no hay red en común se reducen y van por Bluetooth.")
        b.info("Para pegar una foto: el botón de pegar de Teclado la inserta en las apps que admiten imágenes, o mantén pulsado y «Pegar» en las que lo permitan.")
        b.familyRequirement()
        b.addView(look.requirement(clipSource() != null,
            clipSource()?.let { "Detecta lo que copias con $it" } ?: "Para enviar lo que copias: usa Teclado como teclado (o Shizuku)",
            "Elegir teclado" to { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }))
        if (clipSource() == "Shizuku") b.info("Con Shizuku, Android puede avisar «Shell ha pegado…»: se quita desactivando «Mostrar acceso al portapapeles» en los ajustes de privacidad.")
        b.addView(look.divider())
        b.addView(look.label("Últimos movimientos", 14f, bold = true))
        b.info(store.clipDiag.joinToString("\n").ifEmpty { "Aún nada" })
    }

    // --- Fijados ---

    private fun pinsActive() = store.pinsEnabled && inFamily && tecladoInstalled
    private fun pinsStatus() = when {
        !store.pinsEnabled -> "Desactivado"
        !tecladoInstalled -> "Falta Teclado"
        !inFamily -> "Falta elegir móvil o tablet"
        else -> store.pins?.let { "${it.items.size} fijados en la familia" } ?: "Sincronizando"
    }

    private fun pinsDetail(b: LinearLayout) {
        b.addView(look.toggle("Mismos fijados en todos los teclados", store.pinsEnabled) { store.pinsEnabled = it; refresh() })
        b.info("Los textos que fijas en Teclado (con su título) aparecen en el Teclado de tus otros dispositivos. Gana el último cambio. La primera vez se juntan con los que ya tuvieras.")
        b.familyRequirement()
        b.addView(look.requirement(tecladoInstalled, "Teclado instalado (firmado con tu misma clave)"))
        b.addView(look.requirement(SyncBus.tecladoIsKeyboard(this), "Teclado es tu teclado activo",
            "Elegir teclado" to { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }))
        b.addView(look.divider())
        b.addView(look.label("Últimos movimientos", 14f, bold = true))
        b.info(store.pinsDiag.joinToString("\n").ifEmpty { "Aún nada (abre el teclado una vez para que envíe sus fijados)" })
    }

    // --- Wi-Fi ---

    private fun wifiActive() = store.wifiEnabled && inFamily && shizukuReady
    private fun wifiStatus() = when {
        !store.wifiEnabled -> "Desactivado"
        !inFamily -> "Falta elegir móvil o tablet"
        !shizukuReady -> if (isHost) "Falta Shizuku para leer las redes" else "Sin Shizuku: solo como red sugerida"
        isHost -> store.here?.let { "Compartiendo «${it.ssid}»" } ?: "Sin Wi-Fi ahora"
        else -> store.here?.let { "Red del móvil: «${it.ssid}»" } ?: "Esperando la red del móvil"
    }

    private fun wifiDetail(b: LinearLayout) {
        b.addView(look.toggle("Compartir redes Wi-Fi", store.wifiEnabled) { store.wifiEnabled = it; refresh() })
        b.info(if (isHost) "Este móvil pasa a la familia la Wi-Fi a la que está conectado (y las que tiene guardadas)."
        else "Esta tablet se conecta sola a la Wi-Fi de tu móvil, aunque no la conociera.")
        b.familyRequirement()
        b.addView(look.requirement(shizukuReady,
            if (isHost) "Shizuku en el móvil (para leer las contraseñas)" else "Shizuku en esta tablet (para conectarse sola)",
            "Ver" to { openDetail(features.last()) }))
        b.addView(look.divider())
        b.addView(look.label("Diagnóstico", 14f, bold = true))
        b.info(store.wifiDiag ?: "Aún sin datos")
        if (!isHost && !shizukuReady && store.nets.isNotEmpty()) {
            b.buttons("Guardar las redes aquí" to {
                val pending = (store.nets + listOfNotNull(store.here)).distinctBy { it.ssid }.filter { !store.saved(it) }
                startActivity(SaveNetworksActivity.intent(this, pending))
            })
        }
    }

    // --- Familia ---

    private fun familyActive() = isHost || (isClient && linkConnected)
    private fun familyStatus() = when {
        isHost -> "Este móvil es el centro"
        isClient -> SyncService.status ?: "Buscando el móvil…"
        else -> "Elige móvil o tablet"
    }

    private fun familyDetail(b: LinearLayout) {
        b.info("La tablet y el Mac se mantienen conectados al móvil por Bluetooth (cifrado y solo entre tus dispositivos emparejados). Funciona escondida: no hace falta abrir Pera.")
        b.addView(look.toggle("Este es mi móvil (el centro)", isHost) { setHost(it) })
        b.addView(look.toggle("Esta es mi tablet (se conecta al móvil)", isClient) { setClient(it) })
        b.addView(look.requirement(batteryFree, "Puede funcionar en segundo plano", "Permitir" to ::askIgnoreBattery))
        if (isClient) {
            b.addView(look.requirement(linkConnected, SyncService.status ?: "Buscando el móvil…"))
            b.buttons("Olvidar el móvil emparejado" to {
                Prefs.forgetPairedHub(this)
                message = "Hecho: la próxima búsqueda aceptará cualquier móvil con Pera."
                refresh()
            })
        }
    }

    // --- Shizuku ---

    private fun shizukuStatusText() = when {
        !hotspot.shizukuInstalled() && hotspot.shizukuStatus() == ShizukuState.NOT_RUNNING -> "No instalado"
        hotspot.shizukuStatus() == ShizukuState.NOT_RUNNING -> "Instalado, sin arrancar"
        hotspot.shizukuStatus() == ShizukuState.NO_PERMISSION -> "Falta dar permiso a Pera"
        else -> "Listo"
    }

    private fun shizukuDetail(b: LinearLayout) {
        b.info("Shizuku deja a Pera hacer lo que Android reserva al sistema: encender el hotspot, leer y guardar redes Wi-Fi y conectarse a ellas. Hace falta en el móvil y, para conectarse sola, en la tablet.")
        val installed = hotspot.shizukuInstalled() || hotspot.shizukuStatus() != ShizukuState.NOT_RUNNING
        val running = hotspot.shizukuStatus() != ShizukuState.NOT_RUNNING
        b.addView(look.requirement(installed, "Instalado (fork de thedjchi, arranca solo al reiniciar)", "Descargar" to {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)))
        }))
        b.addView(look.requirement(running, "En marcha (activa «Iniciar al arrancar» en Shizuku)", "Abrir" to {
            packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let { startActivity(it) }
        }))
        b.addView(look.requirement(shizukuReady, "Permiso para Pera", "Dar permiso" to {
            if (running) Shizuku.requestPermission(1)
        }))
    }

    // ---------- Acciones ----------

    private fun setHost(on: Boolean) {
        if (!on) {
            Prefs.setHostEnabled(this, false)
            HostService.stop(this)
            refresh()
            return
        }
        withPermissions(hostPermissions()) {
            Prefs.setHostEnabled(this, true)
            if (isClient) {
                Prefs.setSyncEnabled(this, false)
                SyncService.stop(this)
            }
            HostService.start(this)
            refresh()
        }
    }

    private fun setClient(on: Boolean) {
        if (!on) {
            Prefs.setSyncEnabled(this, false)
            SyncService.stop(this)
            refresh()
            return
        }
        withPermissions(clientPermissions()) {
            if (isHost) {
                Prefs.setHostEnabled(this, false)
                HostService.stop(this)
            }
            Prefs.setSyncEnabled(this, true)
            SyncService.start(this)
            refresh()
        }
    }

    private fun runLocal(on: Boolean) {
        io.execute {
            val error = if (on) hotspot.start() else hotspot.stop()
            main.post { message = error ?: if (on) "Encendiendo el hotspot…" else "Apagando el hotspot…"; refresh() }
        }
    }

    private fun runClient(on: Boolean) {
        withPermissions(clientPermissions()) {
            message = getString(R.string.client_working)
            startForegroundService(ClientService.intent(this, on))
            refresh()
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
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
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
        if (bluetoothOk) action?.invoke() else message = getString(R.string.need_bluetooth)
        refresh()
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        /** Fork de Shizuku con arranque automático tras reiniciar (sin root, Android 11+). */
        private const val SHIZUKU_URL = "https://github.com/thedjchi/Shizuku/releases"
    }
}
