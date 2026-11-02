package app.pera.client

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.pera.MainActivity
import app.pera.Notifications
import app.pera.Prefs
import app.pera.Protocol
import app.pera.R
import app.pera.shell.Shell
import app.pera.sync.ClipWatcher
import app.pera.sync.Images
import app.pera.sync.LanShare
import app.pera.sync.Msg
import app.pera.sync.SyncBus
import app.pera.sync.SyncRules
import app.pera.sync.SyncStore
import java.util.concurrent.Executors

/**
 * Servicio permanente de la tablet: mantiene la conexión con el móvil y sincroniza el portapapeles y
 * las redes Wi-Fi. Se arranca desde la app y al encender el dispositivo si está activado.
 */
class SyncService : Service() {

    private lateinit var store: SyncStore
    private lateinit var link: HubLink
    private lateinit var clipWatcher: ClipWatcher
    private val worker = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        store = SyncStore(this)
        startForeground(
            Notifications.SYNC_ID, notification(getString(R.string.sync_searching)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        link = HubLink(
            this,
            onConnected = { worker.execute(::greet) },
            onMessage = { m -> worker.execute { handle(m) } },
            onHotspotState = { s -> hotspotState(s) },
            onStatus = { s ->
                status = s
                getSystemService(NotificationManager::class.java).notify(Notifications.SYNC_ID, notification(s))
            },
        )
        SyncBus.onLocalClip = { text -> worker.execute { localClip(text) } }
        SyncBus.onLocalMsg = { m ->
            if (m is Msg.Img) worker.execute { sendImage(m); noteSent("imagen", m.data.size) } else link.send(m)
        }
        clipWatcher = ClipWatcher(this).also { it.begin() }
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(shizukuReady)
        main.postDelayed(retry, 30_000)
        link.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (instance === this) instance = null
        fetcher.shutdownNow()
        main.removeCallbacks(retry)
        rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuReady)
        SyncBus.onLocalClip = null
        SyncBus.onLocalMsg = null
        clipWatcher.end()
        link.stop()
        worker.shutdownNow()
        status = null
        running = false
        super.onDestroy()
    }

    private fun greet() {
        link.send(Msg.Hello(store.deviceName, store.latestTs))
        // Lo copiado aquí mientras el móvil no estaba: el móvil se queda con lo más reciente.
        store.latest?.takeIf { (it as? Msg.Clip)?.from == store.deviceName || (it as? Msg.Img)?.from == store.deviceName }
            ?.let { if (it is Msg.Img) sendImage(it) else link.send(it) }
        if (store.pinsEnabled) store.pins?.let { link.send(it) }
        if (store.wifiEnabled && Shell.ready()) {
            val own = Shell.savedNetworks()
            if (own.isNotEmpty()) link.send(Msg.Nets(own))
        }
    }

    private fun handle(m: Msg, how: String = "por Bluetooth, reducida") {
        when (m) {
            is Msg.ImgRef -> if (store.clipEnabled && SyncRules.acceptCopy(store.latestTs, store.latestId, m.ts, m.id) && fetching.add(m.id)) {
                fetcher.execute {
                    val img = LanShare.fetch(m)
                    fetching.remove(m.id)
                    if (img != null) worker.execute { handle(img, "por la red, calidad completa") }
                    else link.send(Msg.ImgReq(m.id)) // sin red común con el móvil: que la mande reducida
                }
            }
            is Msg.ImgReq -> store.image?.takeIf { it.id == m.id }?.let { img ->
                Images.reduce(img.data)?.let { link.send(Msg.Img(img.id, img.ts, img.from, "image/jpeg", it)) }
            }
            is Msg.Img -> if (store.clipEnabled && SyncRules.acceptCopy(store.latestTs, store.latestId, m.ts, m.id)) {
                store.noteClip("imagen de ${m.from} $how (${m.data.size / 1024} KB)")
                store.setImage(m)
                SyncBus.applyImage(this, m)
            }
            is Msg.Pins -> if (store.pinsEnabled && SyncRules.acceptPins(store.pins, m)) {
                store.notePins("${m.items.size} recibidos del móvil")
                store.pins = m
                SyncBus.applyPins(this, m)
            }
            is Msg.Clip -> if (store.clipEnabled && SyncRules.acceptCopy(store.latestTs, store.latestId, m.ts, m.id)) {
                store.noteClip("recibida de ${m.from}")
                store.clip = m
                SyncBus.applyClip(this, m.text)
            }
            is Msg.Nets -> if (store.wifiEnabled) {
                val (all, changed) = SyncRules.merge(store.nets, m.nets)
                store.nets = all
                for (w in changed) {
                    if (!store.applied(w) && WifiApplier.apply(this, w)) store.markApplied(w)
                }
            }
            is Msg.Here -> if (store.wifiEnabled) here(m.net)
            is Msg.Hello -> Unit
        }
    }

    /**
     * El móvil está conectado a esta red: este dispositivo se conecta también, aunque no la tuviera.
     *  - Con Shizuku: se conecta YA (como al tocarla en Ajustes), también si estaba en otra Wi-Fi.
     *  - Sin Shizuku: Android no deja a una app conectar por su cuenta; queda como red sugerida y Android
     *    se conecta solo cuando no tiene otra Wi-Fi.
     */
    private fun here(net: Protocol.Wifi?) {
        store.here = net
        joinPhoneNetwork()
    }

    /**
     * Intenta estar en la misma Wi-Fi que el móvil. Se llama al recibir su red, cada 30 s (por si
     * no estaba al alcance o Shizuku aún no había arrancado) y cuando Shizuku queda listo.
     */
    private fun joinPhoneNetwork() {
        if (!store.wifiEnabled) return
        val net = store.here
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        when {
            net == null -> store.wifiDiag = "el móvil no está conectado a ninguna Wi-Fi (o no ha podido leerla)"
            !SyncRules.syncable(net) -> store.wifiDiag = "la red del móvil «${net.ssid}» no se puede compartir (tipo ${net.sec})"
            Shell.ready() -> {
                val visible = Shell.visibleSsids()
                if (Shell.currentSsid() == net.ssid) {
                    store.wifiDiag = "conectada a «${net.ssid}», la Wi-Fi del móvil ✓"
                } else if (!net.hidden && visible != null && net.ssid !in visible) {
                    // (Las redes ocultas no salen por su nombre en el escaneo: con esas se intenta igual.)
                    // No está al alcance: no se toca la Wi-Fi actual (se reintenta en 30 s).
                    store.wifiDiag = "$time «${net.ssid}» no está al alcance de la tablet; se reintenta"
                } else if (Shell.connectTo(net)) {
                    store.markApplied(net)
                    store.wifiDiag = "$time conectando a «${net.ssid}»…"
                } else {
                    store.wifiDiag = "$time no se pudo conectar a «${net.ssid}»: ${Shell.lastConnectError ?: "sin detalle"}"
                }
            }
            else -> {
                val err = WifiApplier.suggest(this, net)
                if (err == null) store.markApplied(net)
                store.wifiDiag = "sin Shizuku listo en esta tablet: «${net.ssid}» queda como red sugerida" +
                    (err?.let { " (error: $it)" } ?: "") + ". Para conectar sola, da permiso a Pera en Shizuku."
            }
        }
    }

    private val retry = object : Runnable {
        override fun run() {
            worker.execute { joinPhoneNetwork() }
            main.postDelayed(this, 30_000)
        }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val shizukuReady = rikka.shizuku.Shizuku.OnBinderReceivedListener { worker.execute { joinPhoneNetwork() } }

    /** El móvil avisa del estado real de su hotspot: el botón del panel lo refleja. */
    private fun hotspotState(s: Int) {
        hotspotStates.offer(s)
        if (ClientService.working) return // hay una orden en curso: manda su resultado
        val on = when (s) {
            Protocol.STATE_ON, Protocol.STATE_TURNING_ON -> true
            Protocol.STATE_OFF, Protocol.STATE_TURNING_OFF, Protocol.STATE_FAILED -> false
            else -> return
        }
        if (Prefs.clientLastOn(this) != on) {
            Prefs.setClientLastOn(this, on)
            HotspotTileService.refresh(this)
        }
    }

    private val fetcher = Executors.newCachedThreadPool()
    private val fetching = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Ofrece la imagen al móvil por la red local; si no hay red, va reducida por Bluetooth. */
    private fun sendImage(img: Msg.Img) {
        val ref = LanShare.offer(img)
        if (ref != null) link.send(ref)
        else Images.reduce(img.data)?.let { link.send(Msg.Img(img.id, img.ts, img.from, "image/jpeg", it)) }
    }

    private fun noteSent(what: String, bytes: Int) {
        store.noteClip(if (link.connected) "$what enviada al móvil (${bytes / 1024} KB; por Bluetooth tarda unos segundos)"
            else "$what copiada aquí; sin conexión con el móvil (se enviará al conectar)")
    }

    private fun localClip(text: String) {
        val clip = SyncStore.newClip(text, store.deviceName)
        store.clip = clip
        store.noteClip(if (link.connected) "enviada al móvil" else "copiada aquí; sin conexión con el móvil (se enviará al conectar)")
        link.send(clip)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, Notifications.CHANNEL_BG)
            .setSmallIcon(R.drawable.ic_pera)
            .setContentTitle(getString(R.string.sync_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        @Volatile var status: String? = null
            private set

        @Volatile private var instance: SyncService? = null

        /** Estados del hotspot que va avisando el móvil (para quien espera el resultado de una orden). */
        val hotspotStates = java.util.concurrent.LinkedBlockingQueue<Int>()

        /** La conexión permanente con el móvil, si ahora mismo está activa. */
        val connectedLink: HubLink?
            get() = instance?.link?.takeIf { it.connected }

        @Volatile var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
