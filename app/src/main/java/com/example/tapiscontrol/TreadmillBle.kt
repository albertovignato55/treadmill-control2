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
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import kotlin.math.abs
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
    private class QueuedOp(val key: String?, val op: () -> Boolean)
    private val queue = ArrayDeque<QueuedOp>()
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
        synchronized(lock) { queue.clear(); busy = false; cpWaiting = false; cpEarlyResponse = false }
        handler.removeCallbacks(responseTimeout)
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
            if (st != BluetoothGatt.GATT_SUCCESS) {
                addLog("Scrittura fallita (status $st)")
                done()
            } else if (c.uuid == Ftms.CONTROL_POINT) {
                holdForResponse()   // il comando successivo parte solo dopo la risposta del tapis (o dopo 1,5 s)
            } else {
                done()
            }
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
                    onCpResponse()
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

    // ---------------------------------------------------------------- attesa risposta ai comandi
    // Il tapis roulant tende a ignorare un comando se ne arriva un altro subito dopo: ogni comando di controllo
    // aspetta la risposta del tapis (max 1,5 s) e poi una breve pausa prima del successivo.
    private var cpWaiting = false
    private var cpEarlyResponse = false
    private val responseTimeout = Runnable {
        synchronized(lock) { if (!cpWaiting) return@Runnable; cpWaiting = false }
        done()
    }

    private fun holdForResponse() {
        synchronized(lock) {
            if (cpEarlyResponse) {
                cpEarlyResponse = false
                handler.postDelayed({ done() }, 250)
                return
            }
            cpWaiting = true
        }
        handler.postDelayed(responseTimeout, 1500)
    }

    private fun onCpResponse() {
        synchronized(lock) {
            if (!cpWaiting) { cpEarlyResponse = true; return }
            cpWaiting = false
        }
        handler.removeCallbacks(responseTimeout)
        handler.postDelayed({ done() }, 250)
    }

    // ---------------------------------------------------------------- coda GATT
    private fun enqueue(key: String? = null, op: () -> Boolean) {
        synchronized(lock) {
            // un comando di velocita'/pendenza non ancora inviato viene sostituito da quello piu' recente
            if (key != null) queue.removeAll { it.key == key }
            queue.addLast(QueuedOp(key, op))
            if (!busy) next()
        }
    }

    private fun next() {
        synchronized(lock) {
            val q = queue.removeFirstOrNull()
            if (q == null) { busy = false; return }
            busy = true
            val ok = try { q.op() } catch (e: Exception) { false }
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
        synchronized(lock) { cpEarlyResponse = false }
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

    private fun sendKeyed(key: String, vararg bytes: Int) {
        val data = ByteArray(bytes.size) { bytes[it].toByte() }
        enqueue(key) { writeCp(data) }
    }

    fun requestControl() = sendCommand(Ftms.OP_REQUEST_CONTROL)
    fun start() { clearWanted(); sendCommand(Ftms.OP_START) }
    fun stop() { clearWanted(); sendCommand(Ftms.OP_STOP, 0x01) }
    fun pause() { clearWanted(); sendCommand(Ftms.OP_STOP, 0x02) }

    /** Dopo Start aspetta che il nastro si muova davvero (il tapis fa un conto alla rovescia), max 15 s. */
    suspend fun awaitBeltMoving(minMs: Long = 3000, maxMs: Long = 15000) {
        delay(minMs)
        var waited = minMs
        while (waited < maxMs && connected.value && (live.value.speedKmh ?: 0.0) < 0.3) {
            delay(500); waited += 500
        }
        delay(500)
    }

    // ---------------------------------------------------------------- limiti di sicurezza
    /** Limiti applicati a OGNI comando, qualunque sia la schermata che lo invia. */
    @Volatile var speedLimit = 15.0
    @Volatile var inclineLimit = 15.0

    // ---------------------------------------------------------------- verifica e ripetizione comandi
    // Il tapis roulant a volte ignora un comando (es. subito dopo Start): se dopo qualche secondo il valore
    // letto non corrisponde a quello richiesto, il comando viene ripetuto (max 5 volte).
    private var wantSpeed: Double? = null
    private var wantIncline: Double? = null
    private var speedSentAt = 0L
    private var inclineSentAt = 0L
    private var speedTries = 0
    private var inclineTries = 0
    private var speedReached = false     // la velocita' richiesta e' gia' stata raggiunta almeno una volta
    private var inclineReached = false
    private var enforcerPosted = false

    fun clearWanted() { wantSpeed = null; wantIncline = null }

    private fun ensureEnforcer() {
        if (enforcerPosted) return
        enforcerPosted = true
        handler.postDelayed(enforceTick, 2000)
    }

    private val enforceTick = object : Runnable {
        override fun run() {
            enforcerPosted = false
            if (!connected.value) return
            val now = SystemClock.elapsedRealtime()
            val l = live.value
            val ws = wantSpeed
            val cs = l.speedKmh
            // si ripete solo se il nastro e' in movimento: mai a nastro fermo
            if (ws != null && cs != null && cs > 0.3) {
                val diff = abs(cs - ws)
                if (diff <= 0.25) {
                    speedTries = 0
                    speedReached = true
                } else if (speedReached) {
                    // raggiunta e poi cambiata (di solito dalla console del tapis): non la riporto indietro
                    if (diff > 0.4) {
                        wantSpeed = null
                        addLog("Velocita' cambiata dal tapis ($cs km/h): non la forzo piu'.")
                    }
                } else if (now - speedSentAt >= 3000) {
                    if (speedTries < 5) {
                        speedTries++; speedSentAt = now; writeSpeed(ws, true)
                    } else if (speedTries == 5) {
                        speedTries++
                        addLog("Il tapis roulant non applica la velocita' richiesta ($ws km/h): usa la console.")
                    }
                }
            }
            val wi = wantIncline
            val ci = l.inclinePct
            if (wi != null && ci != null && cs != null && cs > 0.3) {
                val diffI = abs(ci - wi)
                if (diffI <= 0.4) {
                    inclineTries = 0
                    inclineReached = true
                } else if (inclineReached) {
                    if (diffI > 0.6) {
                        wantIncline = null
                        addLog("Pendenza cambiata dal tapis ($ci %): non la forzo piu'.")
                    }
                } else if (now - inclineSentAt >= 3000) {
                    if (inclineTries < 5) {
                        inclineTries++; inclineSentAt = now; writeIncline(wi, true)
                    } else if (inclineTries == 5) {
                        inclineTries++
                        addLog("Pendenza letta $ci % diversa da quella richiesta ($wi %) dopo 5 tentativi.")
                    }
                }
            }
            if (wantSpeed != null || wantIncline != null) ensureEnforcer()
        }
    }

    fun setSpeed(kmh: Double) {
        var s = kmh.coerceAtMost(speedLimit)
        speedRange.value?.let { s = s.coerceIn(it) }
        wantSpeed = s
        speedSentAt = SystemClock.elapsedRealtime()
        speedTries = 0
        speedReached = false
        writeSpeed(s, false)
        ensureEnforcer()
    }

    private fun writeSpeed(s: Double, repeat: Boolean) {
        val v = (s * 100).roundToInt().coerceIn(0, 0xFFFF)
        sendKeyed("speed", Ftms.OP_SET_SPEED, v and 0xFF, (v shr 8) and 0xFF)
        addLog(if (repeat) "-> velocita' $s km/h (ripeto)" else "-> velocita' $s km/h")
    }

    fun setIncline(pct: Double) {
        var p = roundIncline(pct.coerceIn(0.0, inclineLimit))
        if (p > inclineLimit) p -= INCLINE_STEP
        p = p.coerceAtLeast(0.0)
        inclineRange.value?.let { p = p.coerceIn(it) }
        wantIncline = p
        inclineSentAt = SystemClock.elapsedRealtime()
        inclineTries = 0
        inclineReached = false
        writeIncline(p, false)
        ensureEnforcer()
    }

    private fun writeIncline(p: Double, repeat: Boolean) {
        val v = (p * 10).roundToInt()
        sendKeyed("incline", Ftms.OP_SET_INCLINE, v and 0xFF, (v shr 8) and 0xFF)
        addLog(if (repeat) "-> inclinazione $p % (ripeto)" else "-> inclinazione $p %")
    }
}
