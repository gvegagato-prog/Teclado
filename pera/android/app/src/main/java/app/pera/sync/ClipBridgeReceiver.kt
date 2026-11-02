package app.pera.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Recibe de Teclado (el teclado de este mismo proyecto) cada texto que copias. Como Teclado es el
 * teclado activo, Android le deja leer el portapapeles sin avisos; Pera solo lo reenvía a la familia.
 *
 * Solo se acepta si Android confirma que lo envía Teclado (Android 14+: identidad del emisor).
 */
class ClipBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || sentFromPackage != SyncBus.TECLADO_PACKAGE) {
            Log.w("PeraBridge", "Copia rechazada: emisor no verificado")
            return
        }
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        app.pera.Watchdog.ensure(context) // si Android había parado los servicios, vuelven
        SyncBus.localCopy(context, text)
    }

    companion object {
        const val ACTION = "app.pera.action.CLIP"
        const val EXTRA_TEXT = "text"
    }
}
