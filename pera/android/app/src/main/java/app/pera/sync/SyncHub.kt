package app.pera.sync

import android.content.Context
import android.util.Log
import app.pera.Protocol
import app.pera.shell.Shell
import java.util.concurrent.Executors

/**
 * Lógica de sincronización del móvil (el «hub» de la familia). Recibe mensajes de la tablet y el Mac,
 * guarda el último texto copiado y las redes conocidas, y reenvía a los demás.
 * No sabe nada de Bluetooth: [Transport] lo implementa HostService.
 */
class SyncHub(private val context: Context, private val transport: Transport) {

    interface Transport {
        /** Clientes conectados y suscritos a los mensajes. */
        fun peers(): Collection<String>
        fun send(peer: String, msg: Msg)
    }

    private val store = SyncStore(context)
    private val worker = Executors.newSingleThreadExecutor()

    fun shutdown() = worker.shutdownNow()

    fun onMessage(peer: String, msg: Msg) = worker.execute {
        when (msg) {
            is Msg.Hello -> {
                Log.i(TAG, "Hola de ${msg.device}")
                val clip = store.clip
                if (store.clipEnabled && clip != null && clip.ts > msg.clipTs) transport.send(peer, clip)
                if (store.wifiEnabled) transport.send(peer, Msg.Nets(store.nets))
            }
            is Msg.Clip -> if (store.clipEnabled && SyncRules.acceptClip(store.clip, msg)) {
                store.clip = msg
                SyncBus.applyClip(context, msg.text)
                broadcast(msg, except = peer)
            }
            is Msg.Nets -> if (store.wifiEnabled) {
                val (all, changed) = SyncRules.merge(store.nets, msg.nets)
                if (changed.isNotEmpty()) {
                    store.nets = all
                    applyNetworks(changed)
                    broadcast(Msg.Nets(all), except = peer)
                }
            }
        }
    }

    /** Copia hecha en el propio móvil. */
    fun onLocalClip(text: String) = worker.execute {
        val clip = SyncStore.newClip(text, store.deviceName)
        store.clip = clip
        broadcast(clip, except = null)
    }

    /** Lee las redes guardadas en el móvil (con Shizuku) y comparte las nuevas. */
    fun refreshOwnNetworks() = worker.execute {
        if (!store.wifiEnabled || !Shell.ready()) return@execute
        val own = Shell.savedNetworks().filter { it.ssid != ownHotspotSsid() }
        own.forEach(store::markApplied) // ya están en este móvil
        val (all, changed) = SyncRules.merge(store.nets, own)
        if (changed.isNotEmpty()) {
            store.nets = all
            broadcast(Msg.Nets(all), except = null)
        }
    }

    private fun applyNetworks(nets: List<Protocol.Wifi>) {
        if (!Shell.ready()) return // sin Shizuku el móvil solo las guarda y las reenvía
        for (w in nets) {
            if (store.applied(w)) continue
            if (Shell.addNetwork(w)) store.markApplied(w)
        }
    }

    private fun ownHotspotSsid(): String? = runCatching { app.pera.host.HotspotController(context).wifi()?.ssid }.getOrNull()

    private fun broadcast(msg: Msg, except: String?) {
        transport.peers().filter { it != except }.forEach { transport.send(it, msg) }
    }

    companion object {
        private const val TAG = "PeraHub"
    }
}
