package app.teclado

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Pasa a Pera (pera/ en este repo) cada texto copiado, para sincronizarlo con la tablet y el Mac.
 * Teclado, como teclado activo, puede leer el portapapeles sin avisos; Pera no. Solo se envía a la app
 * Pera instalada y Android le dice a Pera quién lo envía (Android 14+), así no acepta copias falsas.
 */
object PeraBridge {
    private const val PERA_PACKAGE = "app.pera"
    private const val ACTION = "app.pera.action.CLIP"

    fun send(context: Context, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return // Pera no podría verificarlo
        if (runCatching { context.packageManager.getPackageInfo(PERA_PACKAGE, 0) }.isFailure) return
        val intent = Intent(ACTION).setPackage(PERA_PACKAGE).putExtra("text", text)
            // Aunque Android haya detenido Pera, la despierta (y Pera vuelve a arrancar sus servicios).
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
        runCatching { context.sendBroadcast(intent, null, options.toBundle()) }
    }
}
