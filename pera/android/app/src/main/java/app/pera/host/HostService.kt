package app.pera.host

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import app.pera.MainActivity
import app.pera.Notifications
import app.pera.Prefs
import app.pera.Protocol
import app.pera.R
import app.pera.sync.ClipWatcher
import app.pera.sync.Framing
import app.pera.sync.Msg
import app.pera.sync.SyncBus
import app.pera.sync.SyncHub
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Servicio permanente del móvil: anuncia Pera por Bluetooth LE, enciende el hotspot cuando se lo piden
 * y hace de centro de la familia (portapapeles y redes Wi-Fi) para la tablet y el Mac.
 * Se arranca desde la app y, si está activado, al encender el móvil (BootReceiver).
 */
@SuppressLint("MissingPermission") // los permisos se piden en MainActivity antes de arrancar el servicio
class HostService : Service() {

    /** Un cliente conectado (tablet o Mac). */
    private class Peer(val device: BluetoothDevice) {
        @Volatile var mtu = 23
        @Volatile var stateNotify = false
        @Volatile var msgNotify = false
        val assembler = Framing.Assembler()
        val outbox = ArrayDeque<ByteArray>()
        var sending = false
        var nextMsgId = 0
    }

    private lateinit var hotspot: HotspotController
    private lateinit var bluetooth: BluetoothManager
    private lateinit var hub: SyncHub
    private lateinit var clipWatcher: ClipWatcher
    private var server: BluetoothGattServer? = null
    private var stateChar: BluetoothGattCharacteristic? = null
    private var msgOutChar: BluetoothGattCharacteristic? = null
    private val peers = ConcurrentHashMap<String, Peer>()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var lastState = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        hotspot = HotspotController(this)
        bluetooth = getSystemService(BluetoothManager::class.java)
        startForeground(
            Notifications.HOST_ID, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        hub = SyncHub(this, transport)
        SyncBus.onLocalClip = { hub.onLocalClip(it) }
        clipWatcher = ClipWatcher(this).also { it.begin() }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(HotspotController.ACTION_AP_STATE_CHANGED)
        }
        registerReceiver(receiver, filter, RECEIVER_EXPORTED) // difusiones del sistema
        // Al conectarse a una Wi-Fi (quizá nueva) se vuelven a leer las redes guardadas.
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            wifiCallback,
        )
        hub.refreshOwnNetworks()
        startBle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (SyncBus.onLocalClip != null) SyncBus.onLocalClip = null
        clipWatcher.end()
        runCatching { unregisterReceiver(receiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
        stopBle()
        hub.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> startBle()
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> stopBle()
                }
                HotspotController.ACTION_AP_STATE_CHANGED -> worker.execute { notifyState() }
            }
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            hub.refreshOwnNetworks()
        }
    }

    // ---------- Bluetooth LE ----------

    @Synchronized
    private fun startBle() {
        val adapter = bluetooth.adapter ?: return
        if (!adapter.isEnabled || server != null) return
        val gatt = bluetooth.openGattServer(this, callback) ?: return
        server = gatt

        val service = BluetoothGattService(Protocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                Protocol.COMMAND,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        val state = notifyCharacteristic(Protocol.STATE, readable = true)
        service.addCharacteristic(state)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                Protocol.WIFI,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                Protocol.MSG_IN,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        val msgOut = notifyCharacteristic(Protocol.MSG_OUT, readable = false)
        service.addCharacteristic(msgOut)
        stateChar = state
        msgOutChar = msgOut
        gatt.addService(service)
        // El anuncio se lanza en onServiceAdded, cuando el servicio ya está registrado.
    }

    private fun notifyCharacteristic(uuid: java.util.UUID, readable: Boolean): BluetoothGattCharacteristic {
        val c = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or (if (readable) BluetoothGattCharacteristic.PROPERTY_READ else 0),
            if (readable) BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED else 0,
        )
        c.addDescriptor(
            BluetoothGattDescriptor(
                Protocol.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        return c
    }

    private fun startAdvertising() {
        val advertiser = bluetooth.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER) // ~1 s: poca batería
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Protocol.SERVICE))
            .setIncludeDeviceName(false)
            .build()
        // El identificador del móvil va en la respuesta al escaneo (en el anuncio no cabe).
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(Protocol.SERVICE), Prefs.hubId(this))
            .setIncludeDeviceName(false)
            .build()
        runCatching { advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback) }
            .onFailure { Log.e(TAG, "startAdvertising", it) }
    }

    @Synchronized
    private fun stopBle() {
        runCatching { bluetooth.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        server = null
        stateChar = null
        msgOutChar = null
        peers.clear()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Anunciando Pera por Bluetooth LE")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "No se pudo anunciar por Bluetooth LE: $errorCode")
        }
    }

    private fun peer(device: BluetoothDevice) = peers.getOrPut(device.address) { Peer(device) }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) peers.remove(device.address)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            peer(device).mtu = mtu
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value = when (characteristic.uuid) {
                Protocol.STATE -> byteArrayOf(hotspot.state().toByte())
                Protocol.WIFI -> hotspot.wifi()?.encode()
                else -> null
            }
            if (value == null) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }
            // Lecturas largas (JSON del Wi-Fi) llegan troceadas con desplazamiento.
            val slice = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ok = when (characteristic.uuid) {
                Protocol.COMMAND -> value != null && value.size == 1 &&
                    (value[0] == Protocol.CMD_ON || value[0] == Protocol.CMD_OFF)
                Protocol.MSG_IN -> value != null && !preparedWrite && offset == 0
                else -> false
            }
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    0, null,
                )
            }
            if (!ok) return
            when (characteristic.uuid) {
                Protocol.COMMAND -> {
                    val on = value!![0] == Protocol.CMD_ON
                    worker.execute { runCommand(on) }
                }
                Protocol.MSG_IN -> {
                    val whole = peer(device).assembler.add(value!!) ?: return
                    Msg.decode(whole)?.let { hub.onMessage(device.address, it) }
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val p = peers[device.address]
            val on = when (descriptor.characteristic.uuid) {
                Protocol.STATE -> p?.stateNotify == true
                Protocol.MSG_OUT -> p?.msgNotify == true
                else -> false
            }
            val v = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid == Protocol.CCCD) {
                val on = value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val p = peer(device)
                when (descriptor.characteristic.uuid) {
                    Protocol.STATE -> p.stateNotify = on
                    Protocol.MSG_OUT -> p.msgNotify = on
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val p = peers[device.address] ?: return
            synchronized(p) {
                p.sending = false
                pump(p)
            }
        }
    }

    // ---------- Mensajes al cliente ----------

    private val transport = object : SyncHub.Transport {
        override fun peers(): Collection<String> = peers.values.filter { it.msgNotify }.map { it.device.address }

        override fun send(peer: String, msg: Msg) {
            val p = peers[peer] ?: return
            synchronized(p) {
                val frames = Framing.split(p.nextMsgId++ and 0xFFFF, msg.encode(), p.mtu - 3)
                p.outbox.addAll(frames)
                pump(p)
            }
        }
    }

    /** Android exige esperar a onNotificationSent antes de la siguiente notificación al mismo cliente. */
    private fun pump(p: Peer) {
        if (p.sending) return
        val frame = p.outbox.pollFirst() ?: return
        val ok = notify(p.device, msgOutChar ?: return, frame)
        if (ok) p.sending = true else p.outbox.clear() // cliente desconectado: se descarta
    }

    // ---------- Hotspot ----------

    private fun runCommand(on: Boolean) {
        val error = if (on) hotspot.start() else hotspot.stop()
        if (error != null) {
            Log.w(TAG, error)
            val s = if (hotspot.shizukuStatus() != HotspotController.ShizukuState.READY) {
                Protocol.STATE_NO_SHIZUKU
            } else {
                Protocol.STATE_FAILED
            }
            notifyState(s)
            return
        }
        // El sistema tarda unos segundos: se avisa a los clientes en cada cambio.
        val target = if (on) Protocol.STATE_ON else Protocol.STATE_OFF
        repeat(40) {
            notifyState()
            if (lastState == target) return
            Thread.sleep(500)
        }
        notifyState()
    }

    private fun notifyState(state: Int = hotspot.state()) {
        if (state == lastState) return
        lastState = state
        val char = stateChar ?: return
        val value = byteArrayOf(state.toByte())
        // El estado es un único byte y no compite con los mensajes: no hace falta cola.
        peers.values.filter { it.stateNotify }.forEach { notify(it.device, char, value) }
    }

    private fun notify(device: BluetoothDevice, char: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val gatt = server ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.notifyCharacteristicChanged(device, char, false, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = value
                @Suppress("DEPRECATION")
                gatt.notifyCharacteristicChanged(device, char, false)
            }
        }.getOrDefault(false)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, Notifications.CHANNEL)
            .setSmallIcon(R.drawable.ic_pera)
            .setContentTitle(getString(R.string.host_notification_title))
            .setContentText(getString(R.string.host_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PeraHost"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HostService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HostService::class.java))
        }

        fun enabled(context: Context) = Prefs.hostEnabled(context)
    }
}
