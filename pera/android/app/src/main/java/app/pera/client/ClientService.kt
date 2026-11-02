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
import app.pera.R
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
                PeraClient(this) { nm.notify(Notifications.CLIENT_ID, notification(it)) }.run(turnOn)
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
