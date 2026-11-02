package app.pera.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Al encender el dispositivo vuelve a arrancar el servicio activado (tipo connectedDevice: permitido). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        app.pera.Watchdog.ensure(context)
    }
}
