package app.pera.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import app.pera.shell.Shell
import rikka.shizuku.Shizuku

/**
 * Punto de encuentro dentro de la app: las copias locales (de Teclado o de Shizuku) llegan aquí y se
 * entregan al servicio que esté activo (hub en el móvil o enlace en la tablet).
 */
object SyncBus {
    @Volatile var onLocalClip: ((String) -> Unit)? = null

    fun localCopy(context: Context, text: String) {
        if (text.isBlank() || text.length > SyncStore.MAX_CLIP_CHARS) return
        if (text == SyncStore.lastApplied) return // lo acabamos de poner nosotros
        val store = SyncStore(context)
        if (!store.clipEnabled) return
        val handler = onLocalClip
        if (handler != null) return handler(text)
        // El servicio aún no ha arrancado (p. ej. Teclado acaba de despertar a Pera): se guarda como
        // la copia más reciente y sale al conectar (el saludo la reparte por ser la más nueva).
        store.clip = SyncStore.newClip(text, store.deviceName)
        store.noteClip("guardada; se enviará en cuanto Pera conecte")
    }

    /** Escribe en el portapapeles de este dispositivo (Android lo permite sin estar en primer plano). */
    fun applyClip(context: Context, text: String) {
        SyncStore.lastApplied = text
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Pera", text))
        }.onFailure { Log.w("PeraSync", "setPrimaryClip", it) }
    }

    fun tecladoIsKeyboard(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$TECLADO_PACKAGE/") == true

    const val TECLADO_PACKAGE = "app.teclado"
}

/**
 * Vigila el portapapeles con Shizuku cuando Teclado no es el teclado activo (si lo es, Teclado ya avisa
 * de cada copia sin que Android muestre el aviso de «ha pegado desde el portapapeles»).
 */
class ClipWatcher(private val context: Context) {
    private var stop: (() -> Unit)? = null
    private val onBinder = Shizuku.OnBinderReceivedListener { start() }

    fun begin() {
        Shizuku.addBinderReceivedListenerSticky(onBinder)
    }

    fun end() {
        Shizuku.removeBinderReceivedListener(onBinder)
        stop?.invoke()
        stop = null
    }

    @Synchronized
    private fun start() {
        if (stop != null || SyncBus.tecladoIsKeyboard(context) || !Shell.ready()) return
        stop = Shell.listenClipboard {
            if (SyncBus.tecladoIsKeyboard(context)) return@listenClipboard
            Shell.clipboardText()?.let { SyncBus.localCopy(context, it) }
        }
    }
}
