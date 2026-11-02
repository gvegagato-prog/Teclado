package app.pera.client

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.pera.Notifications
import app.pera.Prefs
import app.pera.Protocol
import app.pera.R
import app.pera.shell.Shell
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ejecuta una orden (encender/apagar) como servicio en primer plano para que Android no la corte a
 * mitad aunque se cierre el panel de ajustes rápidos.
 */
class ClientService : Service() {

    private val busy = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val turnOn = intent?.getBooleanExtra(EXTRA_ON, true) ?: true
        startForeground(
            Notifications.CLIENT_ID, notification(getString(R.string.client_working)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (!busy.compareAndSet(false, true)) return START_NOT_STICKY
        markWorking()
        Thread {
            val nm = getSystemService(NotificationManager::class.java)
            val result = if (Prefs.hostEnabled(this)) {
                // El botón está en el propio móvil: se enciende/apaga aquí mismo.
                val error = app.pera.host.HotspotController(this).let { if (turnOn) it.start() else it.stop() }
                PeraClient.Result(error == null, error ?: if (turnOn) "Hotspot encendido" else "Hotspot apagado", turnOn && error == null)
            } else {
                // Si la tablet ya está conectada al móvil (sincronización), la orden va por esa
                // conexión: buscarlo de nuevo fallaría porque, conectado, puede no anunciarse.
                val link = SyncService.connectedLink
                if (link != null) viaLink(link, turnOn) { nm.notify(Notifications.CLIENT_ID, notification(it)) }
                else PeraClient(this) { nm.notify(Notifications.CLIENT_ID, notification(it)) }.run(turnOn)
            }
            lastMessage = result.message
            // Encender: el botón queda encendido solo si funcionó. Apagar: queda apagado siempre (si el
            // móvil no responde es que no está cerca, y entonces tampoco estás usando sus datos).
            Prefs.setClientLastOn(this, turnOn && result.ok)
            workingSince = 0
            HotspotTileService.refresh(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Función discreta: solo se avisa si algo ha ido mal.
            if (!result.ok) nm.notify(Notifications.CLIENT_ID, resultNotification(result))
            busy.set(false)
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    private fun viaLink(link: HubLink, turnOn: Boolean, progress: (String) -> Unit): PeraClient.Result {
        progress(if (turnOn) "Encendiendo el hotspot…" else "Apagando el hotspot…")
        SyncService.hotspotStates.clear()
        link.hotspot(turnOn)
        val target = if (turnOn) Protocol.STATE_ON else Protocol.STATE_OFF
        val deadline = System.currentTimeMillis() + 25_000
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            last = SyncService.hotspotStates.poll(1, TimeUnit.SECONDS) ?: continue
            if (last == target || last == Protocol.STATE_NO_SHIZUKU || last == Protocol.STATE_FAILED) break
        }
        when (last) {
            Protocol.STATE_NO_SHIZUKU -> return PeraClient.Result(false, "En el móvil, Shizuku no está activo. Ábrelo y arráncalo.", false)
            Protocol.STATE_FAILED -> return PeraClient.Result(false, "El móvil no pudo encender el hotspot", false)
            target -> Unit
            else -> return PeraClient.Result(false, "El móvil no respondió a tiempo", false)
        }
        if (!turnOn) return PeraClient.Result(true, "Hotspot apagado", false)
        progress("Conectando al hotspot…")
        val got = java.util.concurrent.LinkedBlockingQueue<Optional<ByteArray>>()
        link.readHotspotWifi { got.offer(Optional.ofNullable(it)) }
        val wifi = got.poll(10, TimeUnit.SECONDS)?.orElse(null)?.let { Protocol.Wifi.decode(it) }
            ?: return PeraClient.Result(true, "Hotspot encendido (no se pudo leer su red)", true)
        val msg = when {
            Shell.ready() && Shell.connectTo(wifi) -> "conectando a «${wifi.ssid}»"
            WifiApplier.suggest(this, wifi) == null -> "la tablet se conectará sola a «${wifi.ssid}»"
            else -> "conéctate a mano a «${wifi.ssid}» una vez"
        }
        return PeraClient.Result(true, "Hotspot encendido · $msg", true)
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, Notifications.CHANNEL_BG)
            .setSmallIcon(R.drawable.ic_pera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun resultNotification(r: PeraClient.Result): Notification =
        Notification.Builder(this, Notifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_pera)
            .setContentTitle(getString(if (r.ok) R.string.client_ok else R.string.client_error))
            .setContentText(r.message)
            .setStyle(Notification.BigTextStyle().bigText(r.message))
            .setAutoCancel(true)
            .setTimeoutAfter(if (r.ok) 8_000 else 60_000)
            .build()

    companion object {
        const val EXTRA_ON = "on"

        @Volatile var lastMessage: String? = null

        @Volatile private var workingSince = 0L

        /** Hay una orden en curso (el botón del panel no acepta otra hasta que termine). Caduca a los
         *  90 s por si el servicio no llegó a arrancar. */
        val working: Boolean
            get() = workingSince != 0L && System.currentTimeMillis() - workingSince < 90_000

        fun markWorking() {
            workingSince = System.currentTimeMillis()
        }

        fun intent(context: Context, turnOn: Boolean) =
            Intent(context, ClientService::class.java).putExtra(EXTRA_ON, turnOn)
    }
}
