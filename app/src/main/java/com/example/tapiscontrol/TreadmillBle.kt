package com.example.tapiscontrol

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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import kotlin.math.roundToInt

/** Gestione BLE del tapis roulant tramite FTMS. Permessi gia' verificati dall'Activity. */
@SuppressLint("MissingPermission")
class TreadmillBle(private val context: Context) {

    data class Found(val name: String, val address: String, val rssi: Int)

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val handler = Handler(Looper.getMainLooper())

    val devices = MutableStateFlow<List<Found>>(emptyList())
    val scanning = MutableStateFlow(false)
    val status = MutableStateFlow("Non connesso")
    val connected = MutableStateFlow(false)
    val controlGranted = MutableStateFlow(false)
    val live = MutableStateFlow(LiveData())
    val speedRange = MutableStateFlow<ClosedFloatingPointRange<Double>?>(null)
    val inclineRange = MutableStateFlow<ClosedFloatingPointRange<Double>?>(null)
    val log = MutableStateFlow<List<String>>(emptyList())

    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null

    // Coda delle operazioni GATT (Android ne accetta una alla volta)
    private val lock = Any()
    private val queue = ArrayDeque<() -> Boolean>()
    private var busy = false

    fun addLog(msg: String) {
        log.update { (it + msg).takeLast(150) }
    }

    // ---------------------------------------------------------------- scansione
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            devices.update { list ->
                if (list.any { it.address == result.device.address }) list
                else list + Found(name, result.device.address, result.rssi)
            }
        }
    }

    fun startScan() {
        val scanner = manager.adapter?.bluetoothLeScanner
        if (scanner == null) {
            status.value = "Bluetooth spento o non disponibile"
            return
        }
        devices.value = emptyList()
        scanning.value = true
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        handler.postDelayed({ stopScan() }, 12_000)
    }

    fun stopScan() {
        if (!scanning.value) return
        scanning.value = false
        manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    // ---------------------------------------------------------------- connessione
    fun connect(address: String) {
        stopScan()
        val device = manager.adapter?.getRemoteDevice(address) ?: return
        status.value = "Connessione in corso..."
        addLog("Connessione a $address")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        val g = gatt
        if (g != null) g.disconnect() else cleanup()
    }

    private fun cleanup() {
        synchronized(lock) { queue.clear(); busy = false }
        gatt?.close()
        gatt = null
        controlPoint = null
        connected.value = false
        controlGranted.value = false
        status.value = "Non connesso"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                addLog("Connesso (status $st), cerco i servizi...")
                status.value = "Connesso, scopro i servizi"
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                addLog("Disconnesso (status $st)")
                cleanup()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val svc = g.getService(Ftms.SERVICE)
            if (svc == null) {
                addLog("Servizio FTMS 0x1826 NON trovato")
                status.value = "FTMS non disponibile"
                return
            }
            svc.characteristics.forEach {
                val short = it.uuid.toString().substring(4, 8)
                addLog("  caratteristica 0x$short props=0x${it.properties.toString(16)}")
            }
            controlPoint = svc.getCharacteristic(Ftms.CONTROL_POINT)
            if (controlPoint == null) addLog("ATTENZIONE: manca il Control Point (0x2AD9)")

            svc.getCharacteristic(Ftms.SPEED_RANGE)?.let { c -> enqueue { g.readCharacteristic(c) } }
            svc.getCharacteristic(Ftms.INCLINE_RANGE)?.let { c -> enqueue { g.readCharacteristic(c) } }
            controlPoint?.let { c -> enqueue { enableCccd(c, indicate = true) } }
            svc.getCharacteristic(Ftms.TREADMILL_DATA)?.let { c -> enqueue { enableCccd(c, indicate = false) } }
            enqueue { writeCp(byteArrayOf(Ftms.OP_REQUEST_CONTROL.toByte())) }

            connected.value = true
            status.value = "Pronto"
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int,
        ) {
            handleRead(c.uuid, value, st); done()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            handleRead(c.uuid, c.value ?: ByteArray(0), st); done()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            if (st != BluetoothGatt.GATT_SUCCESS) addLog("Scrittura fallita (status $st)")
            done()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            if (st != BluetoothGatt.GATT_SUCCESS) addLog("Abilitazione notifiche fallita (status $st)")
            done()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            handleChanged(c.uuid, value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleChanged(c.uuid, c.value ?: ByteArray(0))
        }
    }

    // ---------------------------------------------------------------- gestione dati
    private fun handleRead(uuid: UUID, v: ByteArray, st: Int) {
        if (st != BluetoothGatt.GATT_SUCCESS || v.size < 4) return
        fun u16(i: Int) = (v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8)
        fun s16(i: Int) = u16(i).toShort().toInt()
        when (uuid) {
            Ftms.SPEED_RANGE -> {
                speedRange.value = (u16(0) / 100.0)..(u16(2) / 100.0)
                addLog("Velocita' supportata: ${u16(0) / 100.0}..${u16(2) / 100.0} km/h")
            }
            Ftms.INCLINE_RANGE -> {
                inclineRange.value = (s16(0) / 10.0)..(s16(2) / 10.0)
                addLog("Inclinazione supportata: ${s16(0) / 10.0}..${s16(2) / 10.0} %")
            }
        }
    }

    private fun handleChanged(uuid: UUID, v: ByteArray) {
        when (uuid) {
            Ftms.TREADMILL_DATA -> live.value = parseTreadmillData(v)
            Ftms.CONTROL_POINT -> {
                if (v.size >= 3 && (v[0].toInt() and 0xFF) == Ftms.OP_RESPONSE) {
                    val op = v[1].toInt() and 0xFF
                    val res = v[2].toInt() and 0xFF
                    addLog("Risposta a op 0x${op.toString(16)}: ${resultName(res)}")
                    if (op == Ftms.OP_REQUEST_CONTROL && res == 1) controlGranted.value = true
                }
            }
        }
    }

    private fun resultName(code: Int) = when (code) {
        1 -> "OK"
        2 -> "non supportato"
        3 -> "parametro non valido"
        4 -> "operazione fallita"
        5 -> "controllo non consentito"
        else -> "codice $code"
    }

    // ---------------------------------------------------------------- coda GATT
    private fun enqueue(op: () -> Boolean) {
        synchronized(lock) {
            queue.addLast(op)
            if (!busy) next()
        }
    }

    private fun next() {
        synchronized(lock) {
            val op = queue.removeFirstOrNull()
            if (op == null) { busy = false; return }
            busy = true
            val ok = try { op() } catch (e: Exception) { false }
            if (!ok) { addLog("Operazione GATT rifiutata"); next() }
        }
    }

    private fun done() {
        synchronized(lock) { busy = false; next() }
    }

    @Suppress("DEPRECATION")
    private fun enableCccd(c: BluetoothGattCharacteristic, indicate: Boolean): Boolean {
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(Ftms.CCCD) ?: return false
        val v = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = v
            g.writeDescriptor(d)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCp(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = controlPoint ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = data
            g.writeCharacteristic(c)
        }
    }

    // ---------------------------------------------------------------- comandi FTMS
    private fun sendCommand(vararg bytes: Int) {
        val data = ByteArray(bytes.size) { bytes[it].toByte() }
        enqueue { writeCp(data) }
    }

    fun requestControl() = sendCommand(Ftms.OP_REQUEST_CONTROL)
    fun start() = sendCommand(Ftms.OP_START)
    fun stop() = sendCommand(Ftms.OP_STOP, 0x01)
    fun pause() = sendCommand(Ftms.OP_STOP, 0x02)

    fun setSpeed(kmh: Double) {
        val r = speedRange.value
        val s = if (r != null) kmh.coerceIn(r) else kmh
        val v = (s * 100).roundToInt().coerceIn(0, 0xFFFF)
        sendCommand(Ftms.OP_SET_SPEED, v and 0xFF, (v shr 8) and 0xFF)
        addLog("-> velocita' $s km/h")
    }

    fun setIncline(pct: Double) {
        val r = inclineRange.value
        val p = if (r != null) pct.coerceIn(r) else pct
        val v = (p * 10).roundToInt()
        sendCommand(Ftms.OP_SET_INCLINE, v and 0xFF, (v shr 8) and 0xFF)
        addLog("-> inclinazione $p %")
    }
}
