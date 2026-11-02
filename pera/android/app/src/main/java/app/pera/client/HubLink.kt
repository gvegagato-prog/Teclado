package app.pera.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.util.Log
import app.pera.Prefs
import app.pera.Protocol
import app.pera.sync.Framing
import app.pera.sync.Msg
import java.util.ArrayDeque

/**
 * Conexión permanente de un cliente Android (tablet) con el móvil, para sincronizar.
 *
 * Mientras el móvil no está cerca, escanea en modo de bajo consumo filtrando por el servicio de Pera
 * (el filtrado lo hace el chip Bluetooth). Al encontrarlo conecta, se suscribe a los mensajes y
 * saluda. Si se pierde la conexión, vuelve a escanear.
 *
 * Android solo admite una operación GATT a la vez: todas pasan por una cola en un hilo propio.
 */
@SuppressLint("MissingPermission")
class HubLink(
    private val context: Context,
    private val onConnected: () -> Unit,
    private val onMessage: (Msg) -> Unit,
    private val onStatus: (String) -> Unit,
    /** Estado real del hotspot del móvil (Protocol.STATE_*), en cuanto cambia. */
    private val onHotspotState: (Int) -> Unit = {},
) {
    private val thread = HandlerThread("pera-link").apply { start() }
    private val handler = Handler(thread.looper)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)

    private var gatt: BluetoothGatt? = null
    private var msgIn: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val assembler = Framing.Assembler()
    private var nextMsgId = 0
    private var scanning = false
    private var running = false
    @Volatile var connected = false
        private set

    // Cola de operaciones GATT: cada una devuelve false si no se pudo lanzar.
    private val ops = ArrayDeque<() -> Boolean>()
    private var opBusy = false
    private val opTimeout = Runnable { Log.w(TAG, "Operación GATT sin respuesta"); opDone() }

    fun start() = handler.post {
        running = true
        scan()
    }

    fun stop() = handler.post {
        running = false
        stopScan()
        close()
        thread.quitSafely()
    }

    /** Envía un mensaje al móvil (se descarta si no hay conexión). */
    fun send(msg: Msg) = handler.post {
        val c = msgIn ?: return@post
        if (!connected) return@post
        Framing.split(nextMsgId++ and 0xFFFF, msg.encode(), mtu - 3).forEach { frame ->
            enqueue { write(c, frame) }
        }
    }

    // ---------- Escaneo ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { onFound(result) }
        }
    }

    private fun scan() {
        if (!running || scanning || gatt != null) return
        val scanner = bluetooth.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            onStatus("Bluetooth apagado")
            handler.postDelayed({ scan() }, 30_000)
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE)).build()
        // Equilibrado: el de bajo consumo pierde anuncios cuando hay otros dispositivos Bluetooth activos.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onSuccess { scanning = true; onStatus("Buscando el móvil…") }
            .onFailure { handler.postDelayed({ scan() }, 30_000) }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { bluetooth.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun onFound(result: ScanResult) {
        if (!running || gatt != null) return
        val id = Protocol.hubId(result.scanRecord)
        val mine = Prefs.pairedHubId(context)
        // Con identificador guardado, solo nuestro móvil; si aún no hay, el primero que se encuentre.
        if (mine != null && (id == null || !id.contentEquals(mine))) return
        stopScan()
        pendingHubId = id
        onStatus("Conectando…")
        gatt = result.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, 0, handler)
    }

    private var pendingHubId: ByteArray? = null

    private fun close() {
        connected = false
        msgIn = null
        ops.clear()
        opBusy = false
        handler.removeCallbacks(opTimeout)
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun reconnectLater() {
        close()
        if (running) handler.postDelayed({ scan() }, 3_000)
    }

    // ---------- Cola de operaciones ----------

    private fun enqueue(op: () -> Boolean) {
        ops.addLast(op)
        if (!opBusy) next()
    }

    private fun next() {
        while (true) {
            val op = ops.pollFirst() ?: return
            if (op()) {
                opBusy = true
                handler.postDelayed(opTimeout, 15_000)
                return
            }
        }
    }

    private fun opDone() {
        handler.removeCallbacks(opTimeout)
        opBusy = false
        next()
    }

    private fun write(c: BluetoothGattCharacteristic, v: ByteArray): Boolean {
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = v
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    private fun writeDescriptor(d: BluetoothGattDescriptor, v: ByteArray): Boolean {
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    // ---------- Callbacks (llegan en el hilo del handler) ----------

    private var subscribeAttempts = 0

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                enqueue { g.requestMtu(247) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connected) onStatus("Móvil fuera de alcance")
                reconnectLater()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            opDone()
            enqueue { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            opDone()
            val service = g.getService(Protocol.SERVICE)
            val inC = service?.getCharacteristic(Protocol.MSG_IN)
            val outC = service?.getCharacteristic(Protocol.MSG_OUT)
            if (inC == null || outC == null) {
                onStatus("El móvil tiene una versión de Pera sin sincronización")
                g.disconnect()
                return
            }
            msgIn = inC
            subscribeAttempts = 0
            bondThen(g) { subscribe(g, outC) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            opDone()
            if (d.characteristic.uuid == Protocol.STATE) {
                // Avisos del estado del hotspot activados: se lee el actual.
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val c = d.characteristic
                    enqueue { g.readCharacteristic(c) }
                }
                return
            }
            if (d.characteristic.uuid != Protocol.MSG_OUT) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                // Ya cifrado (emparejados): este es nuestro móvil.
                pendingHubId?.let { if (Prefs.pairedHubId(context) == null) Prefs.setPairedHubId(context, it) }
                onStatus("Conectado al móvil")
                onConnected()
                g.getService(Protocol.SERVICE)?.getCharacteristic(Protocol.STATE)?.let { subscribe(g, it) }
            } else if (subscribeAttempts++ < 2) {
                // Falta emparejar: Android muestra el aviso; se reintenta al terminar.
                if (g.device.bondState != BluetoothDevice.BOND_BONDED) g.device.createBond()
                handler.postDelayed({ subscribe(g, d.characteristic) }, 8_000)
            } else {
                onStatus("No se pudo emparejar con el móvil")
                g.disconnect()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            opDone()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            opDone()
            if (c.uuid == Protocol.STATE && status == BluetoothGatt.GATT_SUCCESS) value.firstOrNull()?.let { onHotspotState(it.toInt()) }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            onCharacteristicRead(g, c, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == Protocol.STATE) {
                value.firstOrNull()?.let { onHotspotState(it.toInt()) }
                return
            }
            if (c.uuid != Protocol.MSG_OUT) return
            assembler.add(value)?.let { Msg.decode(it) }?.let(onMessage)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            onCharacteristicChanged(g, c, c.value ?: return)
        }
    }

    /**
     * Empareja de forma explícita antes de la primera operación cifrada (más fiable que esperar a que
     * Android lo haga solo): aparece el aviso de emparejar en los dos dispositivos.
     */
    private fun bondThen(g: BluetoothGatt, next: () -> Unit) {
        val d = g.device
        if (d.bondState == BluetoothDevice.BOND_BONDED) return next()
        onStatus("Acepta el emparejamiento en los dos dispositivos")
        if (d.bondState == BluetoothDevice.BOND_NONE) d.createBond()
        var waited = 0
        val check = object : Runnable {
            override fun run() {
                if (gatt !== g) return // se perdió la conexión mientras tanto
                when {
                    d.bondState == BluetoothDevice.BOND_BONDED -> next()
                    waited >= 90 -> { onStatus("No se aceptó el emparejamiento"); g.disconnect() }
                    else -> {
                        if (d.bondState == BluetoothDevice.BOND_NONE && waited % 30 == 29) d.createBond()
                        waited++
                        handler.postDelayed(this, 1_000)
                    }
                }
            }
        }
        handler.postDelayed(check, 1_000)
    }

    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(Protocol.CCCD) ?: return
        enqueue { writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
    }

    companion object {
        private const val TAG = "PeraLink"
    }
}
