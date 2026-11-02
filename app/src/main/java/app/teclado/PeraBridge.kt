package app.teclado

import android.content.Context
import android.content.Intent

/**
 * Pasa a Pera (pera/ en este repo) cada texto copiado, para sincronizarlo con la tablet y el Mac.
 * Teclado, como teclado activo, puede leer el portapapeles sin avisos; Pera no. El receptor de Pera
 * exige un permiso de firma: solo una app firmada con la misma clave (esta) puede enviarle copias.
 */
object PeraBridge {
    private const val PERA_PACKAGE = "app.pera"
    private const val ACTION = "app.pera.action.CLIP"

    fun send(context: Context, text: String) {
        if (runCatching { context.packageManager.getPackageInfo(PERA_PACKAGE, 0) }.isFailure) return
        val intent = Intent(ACTION).setPackage(PERA_PACKAGE).putExtra("text", text)
            // Aunque Android haya detenido Pera, la despierta (y Pera vuelve a arrancar sus servicios).
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        runCatching { context.sendBroadcast(intent) }
    }
}
