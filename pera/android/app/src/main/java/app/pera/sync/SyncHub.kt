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
                // Lo último copiado (texto o imagen), si el cliente no lo tiene.
                val latest = store.latest
                if (store.clipEnabled && latest != null && store.latestTs > msg.clipTs) transport.send(peer, latest)
                if (store.pinsEnabled) store.pins?.let { transport.send(peer, it) }
                if (store.wifiEnabled) {
                    transport.send(peer, Msg.Nets(store.nets))
                    transport.send(peer, Msg.Here(store.here))
                }
            }
            is Msg.Img -> if (store.clipEnabled && SyncRules.acceptCopy(store.latestTs, store.latestId, msg.ts, msg.id)) {
                store.noteClip("imagen recibida de ${msg.from} (${msg.data.size / 1024} KB)")
                store.setImage(msg)
                SyncBus.applyImage(context, msg)
                broadcast(msg, except = peer)
            }
            is Msg.Pins -> if (store.pinsEnabled && SyncRules.acceptPins(store.pins, msg)) {
                store.pins = msg
                SyncBus.applyPins(context, msg)
                broadcast(msg, except = peer)
            }
            is Msg.Clip -> if (store.clipEnabled && SyncRules.acceptCopy(store.latestTs, store.latestId, msg.ts, msg.id)) {
                store.noteClip("recibida de ${msg.from}")
                store.clip = msg
                SyncBus.applyClip(context, msg.text)
                broadcast(msg, except = peer)
            }
            is Msg.Here -> Unit // solo lo envía el móvil
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

    /** Imagen o fijados del propio móvil (ya guardados por SyncBus): se reparten. */
    fun onLocalMsg(msg: Msg) = worker.execute {
        if (msg is Msg.Img) {
            val n = transport.peers().size
            store.noteClip(if (n == 0) "imagen copiada; ningún dispositivo conectado" else "imagen enviada a $n dispositivo(s)")
        }
        broadcast(msg, except = null)
    }

    /** Copia hecha en el propio móvil. */
    fun onLocalClip(text: String) = worker.execute {
        val clip = SyncStore.newClip(text, store.deviceName)
        store.clip = clip
        val n = transport.peers().size
        store.noteClip(if (n == 0) "copiada aquí; ningún dispositivo conectado" else "enviada a $n dispositivo(s)")
        broadcast(clip, except = null)
    }

    /**
     * Lee las redes guardadas en el móvil y la red a la que está conectado (con Shizuku), y lo
     * comparte. Se llama al arrancar, al tener Shizuku listo y cada vez que cambia la Wi-Fi.
     */
    fun refreshOwnNetworks() = worker.execute {
        if (!store.wifiEnabled) return@execute
        if (!Shell.ready()) {
            store.wifiDiag = "sin Shizuku: el móvil no puede leer sus redes"
            return@execute
        }
        val hotspot = ownHotspotSsid()
        val own = Shell.savedNetworks().filter { it.ssid != hotspot }
        own.forEach(store::markApplied) // ya están en este móvil
        val (all, changed) = SyncRules.merge(store.nets, own)
        if (changed.isNotEmpty()) {
            store.nets = all
            broadcast(Msg.Nets(all), except = null)
        }
        val here = Shell.currentNetwork()?.takeIf { SyncRules.syncable(it) && it.ssid != hotspot }
        if (here != store.here) {
            store.here = here
            broadcast(Msg.Here(here), except = null)
        }
        store.wifiDiag = Shell.lastWifiError?.let { "error al leer: $it" }
            ?: "${own.size} redes guardadas leídas · conectado a: ${here?.ssid ?: "ninguna"} (${Shell.currentHow})"
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
