package app.pera.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import app.pera.Protocol
import app.pera.R
import app.pera.sync.Msg
import app.pera.sync.SyncStore

/**
 * Guarda redes de la familia como redes normales de este Android (como si las añadieras en Ajustes),
 * sin Shizuku: usa la pantalla del sistema ACTION_WIFI_ADD_NETWORKS (Android 11+), que pide confirmar
 * con un toque y admite hasta 5 redes cada vez. Si hay más, se repite por tandas.
 */
class SaveNetworksActivity : Activity() {

    private var pending = ArrayDeque<List<Protocol.Wifi>>()
    private var current: List<Protocol.Wifi> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = intent.getStringExtra(EXTRA_NETS)
        val nets = json?.let { (Msg.decode(it.toByteArray()) as? Msg.Nets)?.nets }.orEmpty()
        if (nets.isEmpty()) {
            Toast.makeText(this, R.string.save_nets_none, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        pending = ArrayDeque(nets.chunked(5))
        next()
    }

    private fun next() {
        val batch = pending.removeFirstOrNull() ?: return finish()
        current = batch
        val suggestions = ArrayList(batch.mapNotNull(::toSuggestion))
        if (suggestions.isEmpty()) return next()
        val i = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
            .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, suggestions)
        runCatching { startActivityForResult(i, 1) }.onFailure {
            Toast.makeText(this, R.string.save_nets_unsupported, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            // Guardadas: no se vuelven a ofrecer.
            val store = SyncStore(this)
            current.forEach(store::markSaved)
        }
        next()
    }

    private fun toSuggestion(w: Protocol.Wifi): WifiNetworkSuggestion? = runCatching {
        val b = WifiNetworkSuggestion.Builder().setSsid(w.ssid)
        when (w.sec) {
            "open" -> Unit
            "wpa3" -> b.setWpa3Passphrase(w.pass!!)
            else -> b.setWpa2Passphrase(w.pass!!)
        }
        b.build()
    }.getOrNull()

    companion object {
        private const val EXTRA_NETS = "nets"

        fun intent(context: Context, nets: List<Protocol.Wifi>) =
            Intent(context, SaveNetworksActivity::class.java)
                .putExtra(EXTRA_NETS, String(Msg.Nets(nets).encode()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
