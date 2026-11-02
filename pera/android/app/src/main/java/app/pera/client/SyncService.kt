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
        link.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
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
     * El móvil está conectado a esta red: este dispositivo se une también, aunque no la tuviera.
     * Con Shizuku queda guardada; sin él, como sugerida (Android se conecta solo si no tiene Wi-Fi) y,
     * si ahora no hay Wi-Fi, un aviso permite guardarla de verdad con un toque.
     */
    private fun here(net: Protocol.Wifi?) {
        store.here = net
        if (net == null || !SyncRules.syncable(net)) return
        val ok = WifiApplier.apply(this, net)
        if (ok) store.markApplied(net)
        if (!Shell.ready() && !store.saved(net) && !onWifi()) offerSave(net)
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun offerSave(net: Protocol.Wifi) {
        val open = PendingIntent.getActivity(
            this, net.ssid.hashCode(), SaveNetworksActivity.intent(this, listOf(net)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, Notifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_pera)
            .setContentTitle(getString(R.string.here_title, net.ssid))
            .setContentText(getString(R.string.here_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(Notifications.HERE_ID, n)
    }

    /** El móvil avisa del estado real de su hotspot: el botón del panel lo refleja. */
    private fun hotspotState(s: Int) {
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
