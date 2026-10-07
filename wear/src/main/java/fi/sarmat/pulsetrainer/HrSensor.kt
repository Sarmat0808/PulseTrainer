package fi.sarmat.pulsetrainer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * Bluetooth LE heart-rate strap (Polar H10 or any standard HR sensor, service 0x180D).
 *
 * - Remembers the strap; connects directly by address (no scan) → fast start.
 * - High connection priority → every beat notification arrives without delay.
 * - Auto-reconnects if the link drops.
 * - Also delivers RR intervals (for the morning HRV test).
 */
@SuppressLint("MissingPermission")
object HrSensor {
    enum class Status { OFF, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

    data class Found(val name: String, val address: String, val rssi: Int)

    val status = MutableStateFlow(Status.OFF)
    val bpm = MutableStateFlow<Int?>(null)
    val battery = MutableStateFlow<Int?>(null)
    val found = MutableStateFlow<List<Found>>(emptyList())
    val rr = MutableSharedFlow<List<Int>>(extraBufferCapacity = 128)
    val contactLost = MutableStateFlow(false)

    @Volatile var lastBeatAt = 0L   // elapsedRealtime of the last HR notification
        private set

    private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEAS: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val BATT_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val BATT_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    // Polar Measurement Data (PMD) service — raw ECG from the H10 (documented in Polar's open BLE SDK).
    private val PMD_SERVICE: UUID = UUID.fromString("fb005c80-02e7-f387-1cad-8acd2d8df0c8")
    private val PMD_CP: UUID = UUID.fromString("fb005c81-02e7-f387-1cad-8acd2d8df0c8")
    private val PMD_DATA: UUID = UUID.fromString("fb005c82-02e7-f387-1cad-8acd2d8df0c8")

    /** Raw ECG samples (µV, 130 Hz) while an ECG recording runs. */
    val ecg = MutableSharedFlow<IntArray>(extraBufferCapacity = 256)
    val ecgRunning = MutableStateFlow(false)
    val supportsEcg = MutableStateFlow(false)

    // ---------- GATT operations must run one at a time ----------
    private val ops = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var opBusy = false

    private fun enqueue(op: (BluetoothGatt) -> Boolean) {
        main.post { ops.addLast(op); if (!opBusy) nextOp() }
    }

    private fun nextOp() {
        val g = gatt
        if (g == null) { ops.clear(); opBusy = false; return }
        val op = ops.removeFirstOrNull() ?: run { opBusy = false; return }
        opBusy = true
        val started = try { op(g) } catch (_: Exception) { false }
        if (!started) { opBusy = false; nextOp() }
        else main.postDelayed(opTimeout, 3000)
    }

    private val opTimeout = Runnable { opBusy = false; nextOp() }

    private fun opDone() { main.removeCallbacks(opTimeout); main.post { opBusy = false; nextOp() } }

    private fun writeDesc(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == 0
        else { @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) } }

    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        else { @Suppress("DEPRECATION") run { c.value = v; c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(c) } }

    private fun subscribe(service: UUID, ch: UUID, indicate: Boolean) = enqueue { g ->
        val c = g.getService(service)?.getCharacteristic(ch) ?: return@enqueue false
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return@enqueue false
        writeDesc(g, d, if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    private fun readBattery() = enqueue { g -> g.getService(BATT_SERVICE)?.getCharacteristic(BATT_LEVEL)?.let { g.readCharacteristic(it) } ?: false }

    // ---------- ECG (H10) ----------

    /** Start a raw ECG stream: 130 Hz, 14-bit. Needs the H10 (other straps do not have PMD). */
    fun startEcg(): Boolean {
        if (status.value != Status.CONNECTED || !supportsEcg.value) return false
        ecgRunning.value = true
        subscribe(PMD_SERVICE, PMD_CP, indicate = true)
        subscribe(PMD_SERVICE, PMD_DATA, indicate = false)
        enqueue { g ->
            val c = g.getService(PMD_SERVICE)?.getCharacteristic(PMD_CP) ?: return@enqueue false
            writeChar(g, c, byteArrayOf(0x02, 0x00, 0x00, 0x01, 0x82.toByte(), 0x00, 0x01, 0x01, 0x0E, 0x00))
        }
        return true
    }

    fun stopEcg() {
        if (!ecgRunning.value) return
        ecgRunning.value = false
        enqueue { g ->
            val c = g.getService(PMD_SERVICE)?.getCharacteristic(PMD_CP) ?: return@enqueue false
            writeChar(g, c, byteArrayOf(0x03, 0x00))
        }
    }

    private fun parseEcg(v: ByteArray) {
        if (v.size < 10 || v[0].toInt() != 0x00 || v[9].toInt() != 0x00) return
        val n = (v.size - 10) / 3
        val out = IntArray(n)
        for (k in 0 until n) {
            val i = 10 + k * 3
            var x = (v[i].toInt() and 0xff) or ((v[i + 1].toInt() and 0xff) shl 8) or ((v[i + 2].toInt() and 0xff) shl 16)
            if (x and 0x800000 != 0) x = x or 0xFF000000.toInt()
            out[k] = x
        }
        ecg.tryEmit(out)
    }

    // ---------- Link health ----------

    /** The link can stay "connected" while beats stop (strap off, out of range): then reconnect. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (wanted && status.value == Status.CONNECTED && lastBeatAt > 0 && SystemClock.elapsedRealtime() - lastBeatAt > 20_000) {
                try { gatt?.disconnect() } catch (_: Exception) {}
            }
            if (wanted && status.value == Status.CONNECTED && SystemClock.elapsedRealtime() - lastBatt > 10 * 60_000L) {
                lastBatt = SystemClock.elapsedRealtime(); readBattery()
            }
            main.postDelayed(this, 5000)
        }
    }
    private var lastBatt = 0L

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var wanted = false
    private var retries = 0

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /** Fresh = a beat arrived in the last 5 seconds. */
    fun freshBpm(): Int? {
        val v = bpm.value ?: return null
        return if (SystemClock.elapsedRealtime() - lastBeatAt < 6000 && !contactLost.value) v else null
    }

    fun isConnected() = status.value == Status.CONNECTED

    private fun adapter() = app.getSystemService(BluetoothManager::class.java)?.adapter

    // ---------- Scan ----------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = result.scanRecord?.deviceName ?: dev.name ?: ""
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val isHr = HR_SERVICE in uuids || name.contains("Polar", true) || name.contains("H10", true)
            if (!isHr) return
            val list = found.value.filter { it.address != dev.address } +
                Found(name.ifBlank { "Датчик пульса" }, dev.address, result.rssi)
            found.value = list.sortedByDescending { it.rssi }
        }
    }

    fun startScan() {
        val scanner = adapter()?.bluetoothLeScanner ?: return
        found.value = emptyList()
        if (status.value == Status.OFF) status.value = Status.SCANNING
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        } catch (e: Exception) {
            status.value = Status.OFF
        }
        main.postDelayed({ stopScan() }, 20_000)
    }

    fun stopScan() {
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        if (status.value == Status.SCANNING) status.value = Status.OFF
    }

    // ---------- Connect ----------

    /** Connect to a strap and remember it. */
    fun connect(address: String, name: String) {
        Storage.saveSensor(address, name)
        stopScan()
        disconnect(forget = false)
        wanted = true
        retries = 0
        open(address)
    }

    /** Connect to the remembered strap, if any. Safe to call many times. */
    fun connectSaved() {
        val addr = Storage.sensorAddress() ?: return
        if (gatt != null && status.value != Status.OFF) return
        wanted = true
        retries = 0
        open(addr)
    }

    fun disconnect(forget: Boolean) {
        wanted = false
        main.removeCallbacksAndMessages(null)
        ops.clear(); opBusy = false; ecgRunning.value = false
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        status.value = Status.OFF
        bpm.value = null
        if (forget) Storage.saveSensor(null, null)
    }

    private fun open(address: String) {
        val ad = adapter() ?: return
        if (!ad.isEnabled) { status.value = Status.OFF; return }
        val dev: BluetoothDevice = try { ad.getRemoteDevice(address) } catch (e: Exception) { return }
        status.value = if (retries == 0) Status.CONNECTING else Status.RECONNECTING
        try { gatt?.close() } catch (_: Exception) {}
        gatt = dev.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        // If nothing happens in 12 s, try again.
        main.postDelayed(timeoutCheck, 12_000)
    }

    private val timeoutCheck = Runnable {
        if (wanted && status.value != Status.CONNECTED) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        val addr = Storage.sensorAddress() ?: return
        retries++
        status.value = Status.RECONNECTING
        main.removeCallbacks(timeoutCheck)
        val delay = (1000L * retries).coerceAtMost(5000L)
        main.postDelayed({ if (wanted) open(addr) }, delay)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // Larger packets for ECG; then discover services.
                main.post { if (!g.requestMtu(232)) g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close() } catch (_: Exception) {}
                if (g == gatt) gatt = null
                bpm.value = null
                ecgRunning.value = false
                main.post { ops.clear(); opBusy = false }
                main.post {
                    if (wanted) scheduleReconnect() else status.value = Status.OFF
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) {
            main.post { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            if (g.getService(HR_SERVICE)?.getCharacteristic(HR_MEAS) == null) { g.disconnect(); return }
            supportsEcg.value = g.getService(PMD_SERVICE) != null
            main.post { ops.clear(); opBusy = false }
            subscribe(HR_SERVICE, HR_MEAS, indicate = false)
            readBattery()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            if (d.characteristic.uuid == HR_MEAS) {
                retries = 0
                main.removeCallbacks(timeoutCheck)
                status.value = Status.CONNECTED
                lastBatt = SystemClock.elapsedRealtime()
                main.removeCallbacks(watchdog); main.postDelayed(watchdog, 5000)
            }
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { opDone() }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33 && c.uuid == BATT_LEVEL) battery.value = c.value?.firstOrNull()?.toInt()?.and(0xff)
            if (Build.VERSION.SDK_INT < 33) opDone()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) {
            if (c.uuid == BATT_LEVEL) battery.value = value.firstOrNull()?.toInt()?.and(0xff)
            opDone()
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33 && c.uuid == HR_MEAS) parse(c.value ?: return)
            if (Build.VERSION.SDK_INT < 33 && c.uuid == PMD_DATA) parseEcg(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == HR_MEAS) parse(value)
            else if (c.uuid == PMD_DATA) parseEcg(value)
        }
    }

    /** Bluetooth SIG Heart Rate Measurement format. */
    private fun parse(v: ByteArray) {
        if (v.isEmpty()) return
        fun u(i: Int) = v[i].toInt() and 0xff
        val flags = u(0)
        var i = 1
        val hr = if (flags and 0x01 != 0) {
            if (v.size < 3) return
            (u(1) or (u(2) shl 8)).also { i = 3 }
        } else {
            if (v.size < 2) return
            u(1).also { i = 2 }
        }
        val contactSupported = flags and 0x04 != 0
        val contact = flags and 0x02 != 0
        contactLost.value = contactSupported && !contact
        if (flags and 0x08 != 0) i += 2 // energy expended
        if (flags and 0x10 != 0) {
            val list = ArrayList<Int>(4)
            while (i + 1 < v.size) {
                val raw = u(i) or (u(i + 1) shl 8)
                list.add(raw * 1000 / 1024)
                i += 2
            }
            if (list.isNotEmpty()) rr.tryEmit(list)
        }
        if (hr in 25..240) {
            bpm.value = hr
            lastBeatAt = SystemClock.elapsedRealtime()
        }
    }
}
