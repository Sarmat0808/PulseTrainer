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
                main.post { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close() } catch (_: Exception) {}
                if (g == gatt) gatt = null
                bpm.value = null
                main.post {
                    if (wanted) scheduleReconnect() else status.value = Status.OFF
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEAS)
            if (ch == null) { g.disconnect(); return }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            retries = 0
            main.removeCallbacks(timeoutCheck)
            status.value = Status.CONNECTED
            // Battery level (nice to have).
            g.getService(BATT_SERVICE)?.getCharacteristic(BATT_LEVEL)?.let { g.readCharacteristic(it) }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33 && c.uuid == BATT_LEVEL) battery.value = c.value?.firstOrNull()?.toInt()?.and(0xff)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) {
            if (c.uuid == BATT_LEVEL) battery.value = value.firstOrNull()?.toInt()?.and(0xff)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33 && c.uuid == HR_MEAS) parse(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == HR_MEAS) parse(value)
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
