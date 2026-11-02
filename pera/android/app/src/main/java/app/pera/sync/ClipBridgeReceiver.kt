package app.pera.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Recibe de Teclado (el teclado de este mismo proyecto) cada texto que copias. Como Teclado es el
 * teclado activo, Android le deja leer el portapapeles sin avisos; Pera solo lo reenvía a la familia.
 *
 * El receptor exige el permiso de firma app.pera.permission.SEND_CLIP (manifiesto): Android solo deja
 * enviar a apps firmadas con la misma clave, así que nadie más puede colar copias.
 */
class ClipBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        SyncStore(context).noteClip("de Teclado: ${text.take(30)}")
        app.pera.Watchdog.ensure(context) // si Android había parado los servicios, vuelven
        SyncBus.localCopy(context, text)
    }

    companion object {
        const val ACTION = "app.pera.action.CLIP"
        const val EXTRA_TEXT = "text"
    }
}
