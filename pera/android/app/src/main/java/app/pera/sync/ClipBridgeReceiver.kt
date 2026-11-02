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
        when (intent.action) {
            ACTION_IMAGE -> return image(context, intent)
            ACTION_PINS -> {
                val pins = intent.getStringExtra("pins")?.let { Msg.decode(it.toByteArray()) as? Msg.Pins } ?: return
                app.pera.Watchdog.ensure(context)
                return SyncBus.localPins(context, pins)
            }
            ACTION -> Unit
            else -> return
        }
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        if (text == SyncStore.lastApplied) return // es lo que Pera acaba de poner (llegó de otro dispositivo)
        SyncStore(context).noteClip("de Teclado: ${text.take(30)}")
        app.pera.Watchdog.ensure(context) // si Android había parado los servicios, vuelven
        SyncBus.localCopy(context, text)
    }

    /** Teclado deja la imagen copiada en su proveedor (protegido con el mismo permiso de firma). */
    private fun image(context: Context, intent: Intent) {
        val uri = intent.getStringExtra("uri")?.let(android.net.Uri::parse) ?: return
        app.pera.Watchdog.ensure(context)
        val pending = goAsync() // leer y reducir la imagen lleva un momento
        Thread {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { s ->
                    s.readBytes().takeIf { it.size <= Images.MAX_SOURCE_BYTES }
                }
                if (bytes != null) SyncBus.localImage(context, bytes)
            } catch (e: Exception) {
                SyncStore(context).noteClip("no se pudo leer la imagen de Teclado: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_IMAGE = "app.pera.action.IMAGE"
        const val ACTION_PINS = "app.pera.action.PINS"
        const val ACTION = "app.pera.action.CLIP"
        const val EXTRA_TEXT = "text"
    }
}
