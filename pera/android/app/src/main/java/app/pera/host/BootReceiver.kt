package app.pera.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Al encender el dispositivo vuelve a arrancar el servicio activado (tipo connectedDevice: permitido). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (HostService.enabled(context)) {
            runCatching { HostService.start(context) }
                .onFailure { Log.e("PeraBoot", "No se pudo arrancar el servicio del móvil", it) }
        } else if (app.pera.Prefs.syncEnabled(context)) {
            runCatching { app.pera.client.SyncService.start(context) }
                .onFailure { Log.e("PeraBoot", "No se pudo arrancar la sincronización", it) }
        }
    }
}
