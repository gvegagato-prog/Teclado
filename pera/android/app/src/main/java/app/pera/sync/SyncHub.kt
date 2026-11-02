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

    fun shutdown() {
        worker.shutdownNow()
        fetcher.shutdownNow()
    }

    fun onMessage(peer: String, msg: Msg) = worker.execute {
        when (msg) {
            is Msg.Hello -> {
                Log.i(TAG, "Hola de ${msg.device}")
                // Lo último copiado (texto o imagen), si el cliente no lo tiene.
                val latest = store.latest
                if (store.clipEnabled && latest != null && store.latestTs > msg.clipTs) {
                    if (latest is Msg.Img) sendImage(peer, latest) else transport.send(peer, latest)
                }
                if (store.pinsEnabled) store.pins?.let { transport.send(peer, it) }
                if (store.wifiEnabled) {
                    transport.send(peer, Msg.Nets(store.nets))
                    transport.send(peer, Msg.Here(store.here))
                }
            }
            is Msg.Img -> gotImage(msg, peer, "por Bluetooth, reducida")
            is Msg.ImgRef -> if (store.clipEnabled && SyncRules.acceptCopy(store.latestTs, store.latestId, msg.ts, msg.id) && fetching.add(msg.id)) {
                // Descarga por la red local en otro hilo (puede tardar); si no hay red común, se pide reducida.
                fetcher.execute {
                    val img = LanShare.fetch(msg)
                    fetching.remove(msg.id)
                    if (img != null) worker.execute { gotImage(img, peer, "por la red, calidad completa") }
                    else transport.send(peer, Msg.ImgReq(msg.id))
                }
            }
            is Msg.ImgReq -> store.image?.takeIf { it.id == msg.id }?.let { img ->
                reduced(img)?.let { transport.send(peer, it) }
            }
            is Msg.Pins -> if (store.pinsEnabled && SyncRules.acceptPins(store.pins, msg)) {
                store.notePins("${msg.items.size} recibidos de la familia")
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
            store.noteClip(if (n == 0) "imagen copiada; ningún dispositivo conectado" else "imagen ofrecida a $n dispositivo(s)")
            transport.peers().forEach { sendImage(it, msg) }
            return@execute
        }
        broadcast(msg, except = null)
    }

    private val fetcher = Executors.newCachedThreadPool()
    private val fetching = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Ofrece la imagen por la red local; sin red, va reducida por Bluetooth. */
    private fun sendImage(peer: String, img: Msg.Img) {
        val ref = LanShare.offer(img)
        if (ref != null) transport.send(peer, ref) else reduced(img)?.let { transport.send(peer, it) }
    }

    private fun reduced(img: Msg.Img): Msg.Img? =
        Images.reduce(img.data)?.let { Msg.Img(img.id, img.ts, img.from, "image/jpeg", it) }

    private fun gotImage(img: Msg.Img, peer: String, how: String) {
        if (!store.clipEnabled || !SyncRules.acceptCopy(store.latestTs, store.latestId, img.ts, img.id)) return
        store.noteClip("imagen de ${img.from} $how (${img.data.size / 1024} KB)")
        store.setImage(img)
        SyncBus.applyImage(context, img)
        transport.peers().filter { it != peer }.forEach { sendImage(it, img) }
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
