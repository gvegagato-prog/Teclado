package app.pera.client

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.util.Log
import app.pera.Protocol
import app.pera.shell.Shell
import app.pera.sync.Msg

/**
 * Añade redes Wi-Fi a este Android.
 *  - Con Shizuku: como redes guardadas normales (igual que en Ajustes).
 *  - Sin Shizuku: como «redes sugeridas» de Pera. Android se conecta solo cuando las ve; la primera vez
 *    pregunta si permites las sugerencias de Pera.
 */
object WifiApplier {
    private const val PREFS = "pera_suggestions"

    /** Devuelve true si la red queda disponible en este dispositivo. */
    fun apply(context: Context, w: Protocol.Wifi): Boolean {
        if (Shell.ready() && Shell.addNetwork(w)) return true
        return suggest(context, w) == null
    }

    /** null si ha ido bien; si no, el motivo. */
    @Synchronized
    fun suggest(context: Context, w: Protocol.Wifi): String? {
        val b = WifiNetworkSuggestion.Builder().setSsid(w.ssid).setIsAppInteractionRequired(false)
        when {
            w.sec == "open" -> Unit
            w.pass == null -> return "falta la contraseña de «${w.ssid}»"
            w.sec == "wpa3" -> b.setWpa3Passphrase(w.pass)
            else -> b.setWpa2Passphrase(w.pass)
        }
        val wm = context.getSystemService(WifiManager::class.java)
        val saved = load(context)
        val previous = saved.firstOrNull { it.ssid == w.ssid }
        if (previous == w) return null
        // Si cambió la contraseña, se quita la sugerencia anterior de esa red (solo esa).
        previous?.let { old -> build(old)?.let { runCatching { wm.removeNetworkSuggestions(listOf(it)) } } }
        val status = wm.addNetworkSuggestions(listOf(b.build()))
        val ok = status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
            status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
        if (!ok) {
            Log.w("PeraWifi", "addNetworkSuggestions(${w.ssid}) = $status")
            return "Android no aceptó la red ($status)"
        }
        save(context, saved.filter { it.ssid != w.ssid } + w)
        return null
    }

    private fun build(w: Protocol.Wifi): WifiNetworkSuggestion? = runCatching {
        val b = WifiNetworkSuggestion.Builder().setSsid(w.ssid).setIsAppInteractionRequired(false)
        when (w.sec) {
            "open" -> Unit
            "wpa3" -> b.setWpa3Passphrase(w.pass!!)
            else -> b.setWpa2Passphrase(w.pass!!)
        }
        b.build()
    }.getOrNull()

    private fun load(context: Context): List<Protocol.Wifi> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", null)
            ?.let { (Msg.decode(it.toByteArray()) as? Msg.Nets)?.nets }.orEmpty()

    private fun save(context: Context, list: List<Protocol.Wifi>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("list", String(Msg.Nets(list).encode())).apply()
}
