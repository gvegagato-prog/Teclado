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

    @Volatile var onLocalMsg: ((Msg) -> Unit)? = null

    /**
     * Imagen copiada aquí (de Teclado o de «Compartir»): se guarda tal cual (calidad completa) y se
     * reparte; solo se reducirá si a algún dispositivo no le llega por la red local.
     */
    fun localImage(context: Context, source: ByteArray) {
        val store = SyncStore(context)
        if (!store.clipEnabled) return
        if (Images.hash(source) == SyncStore.lastAppliedImage) return // es la que acabamos de poner nosotros
        val img = Msg.Img(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), store.deviceName, Images.sniff(source), source)
        store.setImage(img)
        store.noteClip("imagen copiada aquí (${source.size / 1024} KB)")
        onLocalMsg?.invoke(img) // si no hay servicio aún, sale al conectar por ser lo más reciente
    }

    /** Fijados del teclado de este dispositivo, editados aquí. */
    fun localPins(context: Context, pins: Msg.Pins) {
        val store = SyncStore(context)
        if (!store.pinsEnabled || !SyncRules.acceptPins(store.pins, pins)) return
        store.pins = pins
        onLocalMsg?.invoke(pins)
    }

    /** Pone la imagen en el portapapeles: al pegar, la app lee el archivo a través de ClipProvider. */
    fun applyImage(context: Context, img: Msg.Img) {
        SyncStore.lastAppliedImage = Images.hash(img.data)
        runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "Pera", Images.uri(img.id)))
        }.onFailure { Log.w("PeraSync", "setPrimaryClip(imagen)", it) }
    }

    /** Entrega los fijados de la familia a Teclado (receptor protegido con el permiso de firma). */
    fun applyPins(context: Context, pins: Msg.Pins) {
        val intent = android.content.Intent("app.teclado.action.PINS").setPackage(TECLADO_PACKAGE)
            .putExtra("pins", String(pins.encode()))
            .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        runCatching { context.sendBroadcast(intent, "app.pera.permission.SEND_CLIP") }
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
