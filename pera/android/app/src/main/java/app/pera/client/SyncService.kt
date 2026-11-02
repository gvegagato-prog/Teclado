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
        clipWatcher = ClipWatcher(this).also { it.begin() }
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(shizukuReady)
        main.postDelayed(retry, 30_000)
        link.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (instance === this) instance = null
        main.removeCallbacks(retry)
        rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuReady)
        SyncBus.onLocalClip = null
        clipWatcher.end()
        link.stop()
        worker.shutdownNow()
        status = null
        running = false
        super.onDestroy()
    }

    private fun greet() {
        link.send(Msg.Hello(store.deviceName, store.clip?.ts ?: 0))
        // Lo copiado aquí mientras el móvil no estaba: el móvil se queda con lo más reciente.
        store.clip?.takeIf { it.from == store.deviceName }?.let { link.send(it) }
        if (store.wifiEnabled && Shell.ready()) {
            val own = Shell.savedNetworks()
            if (own.isNotEmpty()) link.send(Msg.Nets(own))
        }
    }

    private fun handle(m: Msg) {
        when (m) {
            is Msg.Clip -> if (store.clipEnabled && SyncRules.acceptClip(store.clip, m)) {
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
                } else if (visible != null && net.ssid !in visible) {
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
