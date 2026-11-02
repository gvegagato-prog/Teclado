package app.pera.client

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.service.quicksettings.TileService
import app.pera.Notifications
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
        Thread {
            val nm = getSystemService(NotificationManager::class.java)
            val result = PeraClient(this) { nm.notify(Notifications.CLIENT_ID, notification(it)) }.run(turnOn)
            lastMessage = result.message
            TileService.requestListeningState(this, ComponentName(this, HotspotTileService::class.java))
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Aviso final breve con el resultado.
            nm.notify(Notifications.CLIENT_ID, resultNotification(result))
            busy.set(false)
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, Notifications.CHANNEL)
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

        fun intent(context: Context, turnOn: Boolean) =
            Intent(context, ClientService::class.java).putExtra(EXTRA_ON, turnOn)
    }
}
