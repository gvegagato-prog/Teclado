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
import android.os.ParcelUuid
import android.util.Log
import app.pera.Prefs
import app.pera.Protocol
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Cliente de Pera para la tablet (o cualquier Android): busca el móvil por Bluetooth LE, le pide que
 * encienda/apague el hotspot y, al encenderlo, entrega la red al sistema para que se conecte solo.
 *
 * Es bloqueante: llamar desde un hilo de fondo.
 */
@SuppressLint("MissingPermission") // permisos pedidos en MainActivity
class PeraClient(private val context: Context, private val progress: (String) -> Unit = {}) {

    data class Result(val ok: Boolean, val message: String, val hotspotOn: Boolean)

    private sealed class Ev {
        data class Connection(val status: Int, val state: Int) : Ev()
        data class Mtu(val status: Int) : Ev()
        data class Services(val status: Int) : Ev()
        data class DescWrite(val status: Int) : Ev()
        data class Write(val status: Int) : Ev()
        data class Read(val uuid: java.util.UUID, val status: Int, val value: ByteArray) : Ev()
        data class Notify(val value: ByteArray) : Ev()
    }

    private val events = LinkedBlockingQueue<Ev>()
    private var gatt: BluetoothGatt? = null

    fun run(turnOn: Boolean): Result = try {
        execute(turnOn)
    } catch (e: Failure) {
        Result(false, e.message ?: "Error", Prefs.clientLastOn(context))
    } catch (e: Exception) {
        Log.e(TAG, "run", e)
        Result(false, "Error inesperado: ${e.message}", Prefs.clientLastOn(context))
    } finally {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private class Failure(message: String) : Exception(message)

    private fun execute(turnOn: Boolean): Result {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager.adapter ?: throw Failure("Este dispositivo no tiene Bluetooth")
        if (!adapter.isEnabled) throw Failure("Activa el Bluetooth")

        progress("Buscando el móvil…")
        val device = scan(adapter.bluetoothLeScanner ?: throw Failure("Bluetooth LE no disponible"))
            ?: throw Failure("No encuentro el móvil. ¿Tiene Pera activado y el Bluetooth encendido?")

        progress("Conectando…")
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw Failure("No se pudo conectar")
        gatt = g
        val conn = await<Ev.Connection>(15) ?: throw Failure("El móvil no responde")
        if (conn.state != BluetoothProfile.STATE_CONNECTED) throw Failure("Conexión rechazada (${conn.status})")

        g.requestMtu(247) // el JSON del Wi-Fi cabe en una sola lectura
        await<Ev.Mtu>(5)
        if (!g.discoverServices()) throw Failure("No se pudieron leer los servicios")
        await<Ev.Services>(10)?.takeIf { it.status == BluetoothGatt.GATT_SUCCESS } ?: throw Failure("El móvil no responde")
        val service = g.getService(Protocol.SERVICE) ?: throw Failure("El móvil no tiene Pera activado")
        val cmd = service.getCharacteristic(Protocol.COMMAND)
        val state = service.getCharacteristic(Protocol.STATE)
        val wifi = service.getCharacteristic(Protocol.WIFI)
        if (cmd == null || state == null || wifi == null) throw Failure("Versión de Pera incompatible en el móvil")

        // Emparejar de forma explícita antes de la primera operación cifrada.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            progress("Acepta el emparejamiento en los dos dispositivos")
            if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
            waitBonded(device)
            if (device.bondState != BluetoothDevice.BOND_BONDED) throw Failure("No se aceptó el emparejamiento")
        }
        subscribe(g, state)
        // Emparejados y cifrados: a partir de ahora, este es nuestro móvil.
        foundHubId?.let { if (Prefs.pairedHubId(context) == null) Prefs.setPairedHubId(context, it) }

        progress(if (turnOn) "Encendiendo el hotspot…" else "Apagando el hotspot…")
        val payload = byteArrayOf(if (turnOn) Protocol.CMD_ON else Protocol.CMD_OFF)
        if (!writeChar(g, cmd, payload)) throw Failure("No se pudo enviar la orden")
        val w = await<Ev.Write>(30) ?: throw Failure("El móvil no confirmó la orden")
        if (w.status != BluetoothGatt.GATT_SUCCESS) throw Failure("El móvil rechazó la orden (${w.status})")

        val target = if (turnOn) Protocol.STATE_ON else Protocol.STATE_OFF
        val final = waitForState(g, state, target)
        when (final) {
            Protocol.STATE_NO_SHIZUKU -> throw Failure("En el móvil, Shizuku no está activo. Ábrelo y arráncalo.")
            Protocol.STATE_FAILED -> throw Failure("El móvil no pudo encender el hotspot")
            target -> Unit
            else -> throw Failure("El hotspot sigue ${Protocol.stateText(final)}")
        }
        Prefs.setClientLastOn(context, turnOn)
        if (!turnOn) return Result(true, "Hotspot apagado", false)

        progress("Configurando el Wi-Fi…")
        val info = read(g, wifi)?.let { Protocol.Wifi.decode(it) }
        val wifiMsg = info?.let { suggest(it) } ?: "no se pudo leer la red del móvil; conéctate a mano una vez"
        return Result(true, "Hotspot encendido · $wifiMsg", true)
    }

    private var foundHubId: ByteArray? = null

    private fun scan(scanner: android.bluetooth.le.BluetoothLeScanner): BluetoothDevice? {
        val found = LinkedBlockingQueue<ScanResult>()
        val mine = Prefs.pairedHubId(context)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val id = Protocol.hubId(result.scanRecord)
                // Con un móvil ya emparejado, se ignoran los de otras personas.
                if (mine == null || (id != null && id.contentEquals(mine))) found.offer(result)
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        return try {
            found.poll(12, TimeUnit.SECONDS)?.also {
                foundHubId = Protocol.hubId(it.scanRecord)
            }?.device
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    private fun subscribe(g: BluetoothGatt, state: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(state, true)
        val cccd = state.getDescriptor(Protocol.CCCD) ?: return
        repeat(2) { attempt ->
            if (!writeDesc(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                throw Failure("No se pudo activar el aviso de estado")
            }
            // Hay margen para que el usuario acepte el emparejamiento.
            val r = await<Ev.DescWrite>(40) ?: throw Failure("Emparejamiento no completado")
            if (r.status == BluetoothGatt.GATT_SUCCESS) return
            if (attempt == 0 && g.device.bondState != BluetoothDevice.BOND_BONDED) {
                g.device.createBond()
                waitBonded(g.device)
            }
        }
        throw Failure("No se pudo emparejar con el móvil")
    }

    private fun waitBonded(device: BluetoothDevice) {
        repeat(180) {
            if (device.bondState == BluetoothDevice.BOND_BONDED) return
            Thread.sleep(500)
        }
    }

    private fun waitForState(g: BluetoothGatt, state: BluetoothGattCharacteristic, target: Int): Int {
        val deadline = System.currentTimeMillis() + 25_000
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            // Notificaciones del móvil y, por si se pierde alguna, lectura cada 3 s.
            val ev = await<Ev.Notify>(3)
            last = ev?.value?.firstOrNull()?.toInt() ?: read(g, state)?.firstOrNull()?.toInt() ?: last
            if (last == target || last == Protocol.STATE_NO_SHIZUKU || last == Protocol.STATE_FAILED) return last
        }
        return last
    }

    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = v
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }

    private fun writeDesc(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }

    private fun read(g: BluetoothGatt, c: BluetoothGattCharacteristic): ByteArray? {
        if (!g.readCharacteristic(c)) return null
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val ev = events.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: return null
            if (ev is Ev.Read && ev.uuid == c.uuid) return ev.value.takeIf { ev.status == BluetoothGatt.GATT_SUCCESS }
            if (ev is Ev.Connection && ev.state != BluetoothProfile.STATE_CONNECTED) throw Failure("Se perdió la conexión")
        }
        return null
    }

    /** Entrega la red al sistema; Android se conecta solo cuando la ve (la primera vez pide permiso). */
    private fun suggest(w: Protocol.Wifi): String {
        if (app.pera.shell.Shell.ready() && app.pera.shell.Shell.connectTo(w)) {
            return "conectando a «${w.ssid}»"
        }
        val error = WifiApplier.suggest(context, w)
        return if (error == null) {
            "la tablet se conectará sola a «${w.ssid}» (la primera vez, acepta el aviso de redes sugeridas)"
        } else {
            "$error; conéctate a mano a «${w.ssid}» una vez"
        }
    }

    private inline fun <reified T : Ev> await(seconds: Long): T? {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val ev = events.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (ev is T) return ev
            if (ev is Ev.Connection && ev.state != BluetoothProfile.STATE_CONNECTED) {
                throw Failure("Se perdió la conexión con el móvil")
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            events.offer(Ev.Connection(status, newState))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            events.offer(Ev.Mtu(status))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            events.offer(Ev.Services(status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            events.offer(Ev.DescWrite(status))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            events.offer(Ev.Write(status))
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            events.offer(Ev.Read(characteristic.uuid, status, value))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            if (characteristic.uuid == Protocol.STATE) events.offer(Ev.Notify(value))
        }

        // Android 12 (API 31-32) usa las variantes antiguas sin el valor como parámetro.
        @Deprecated("API < 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            events.offer(Ev.Read(characteristic.uuid, status, characteristic.value ?: ByteArray(0)))
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            if (characteristic.uuid == Protocol.STATE) events.offer(Ev.Notify(characteristic.value ?: ByteArray(0)))
        }
    }

    companion object {
        private const val TAG = "PeraClient"
    }
}
