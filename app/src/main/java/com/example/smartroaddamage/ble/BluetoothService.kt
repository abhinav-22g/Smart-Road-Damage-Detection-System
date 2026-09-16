package com.example.smartroaddamage.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.smartroaddamage.models.ScannedDevice
import com.example.smartroaddamage.models.CPacket
private const val TAG = "BluetoothService"

/**
 * BluetoothService — Foreground-capable LifecycleService that owns the Classic Bluetooth connection.
 *
 * Replaces BleService by using standard discovery and RFCOMM via [SmartRoadClassicManager].
 */
@SuppressLint("MissingPermission")
class BluetoothService : LifecycleService() {

    private lateinit var classicManager: SmartRoadClassicManager
    private var bluetoothAdapter: BluetoothAdapter? = null

    // 1. Create the Local Binder
    inner class LocalBinder : android.os.Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    override fun onBind(intent: Intent): android.os.IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    // ── Scanning state ─────────────────────────────────────────────────────────
    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    // ── Forwarded flows from ClassicManager ───────────────────────────────────
    val potholeEvents: SharedFlow<PotholeEvent> get() = classicManager.potholeEvents
    val cPackets: SharedFlow<CPacket> get() = classicManager.cPackets
    val iPackets: SharedFlow<com.example.smartroaddamage.detector.ImuSample> get() = classicManager.iPackets
    val rawPackets: SharedFlow<String> get() = classicManager.rawPackets
    val connectionState: StateFlow<ConnectionState> get() = classicManager.connectionState

    override fun onCreate() {
        super.onCreate()
        classicManager = SmartRoadClassicManager(lifecycleScope)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Register for classic discovery broadcasts
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(deviceFoundReceiver, filter)

        Log.d(TAG, "BluetoothService created.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        try { unregisterReceiver(deviceFoundReceiver) } catch (_: Exception) {}
        classicManager.stopAllOperations()
        Log.d(TAG, "BluetoothService destroyed — RFCOMM socket closed.")
    }

    // ── Classic Bluetooth Discovery ───────────────────────────────────────────

    private val deviceFoundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    
                    if (device != null) {
                        val name = device.name?.takeIf { it.isNotBlank() } ?: device.address
                        val current = _scannedDevices.value.toMutableList()
                        val existing = current.indexOfFirst { it.address == device.address }
                        
                        if (existing >= 0) {
                            current[existing] = current[existing].copy(rssi = rssi, name = name)
                        } else {
                            current.add(ScannedDevice(name = name, address = device.address, rssi = rssi))
                        }
                        
                        _scannedDevices.value = current.sortedByDescending { it.rssi }
                        Log.d(TAG, "Device found: $name @ ${device.address} RSSI=$rssi")
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    Log.d(TAG, "Discovery Started broadcast received.")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Discovery Finished broadcast received.")
                }
            }
        }
    }

    fun startScan() {
        // Immediately emit bonded (paired) devices for better UX
        val paired = bluetoothAdapter?.bondedDevices?.map {
            ScannedDevice(it.name ?: it.address, it.address, 0 /* Paired devices have no instant RSSI */)
        } ?: emptyList()
        
        _scannedDevices.value = paired

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "BluetoothAdapter unavailable or off.")
            return
        }

        if (bluetoothAdapter!!.isDiscovering) {
            bluetoothAdapter!!.cancelDiscovery()
        }
        
        bluetoothAdapter!!.startDiscovery()
        Log.d(TAG, "Classic BT scan started. Pre-populated ${paired.size} bonded devices.")
    }

    fun stopScan() {
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        Log.d(TAG, "Classic BT scan stopped.")
    }

    // ── Connection ─────────────────────────────────────────────────────────────

    fun connectToDevice(deviceAddress: String) {
        // Stop discovery before connecting, as it dramatically slows down connection speeds
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        
        val device: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device == null) {
            Log.e(TAG, "Device not found for address: $deviceAddress")
            return
        }
        
        classicManager.connect(device)
    }

    fun disconnect() {
        classicManager.stopAllOperations()
    }
    
    fun stopAllOperations() {
        classicManager.stopAllOperations()
    }

    fun sendCommand(cmd: String) {
        classicManager.sendCommand(cmd)
    }

    // ── Calibration ──────────────────────────────────────────────────────────

    /** Lock the current resting IMU values as mounting-angle offsets. */
    fun lockMountCalibration() = classicManager.lockMountCalibration()

    /** Lock pre-computed noise-averaged offsets from a 100-sample calibration window. */
    fun lockMountCalibrationFromAverages(
        ax: Float, ay: Float, az: Float,
        gx: Float, gy: Float, gz: Float
    ) = classicManager.lockMountCalibrationFromAverages(ax, ay, az, gx, gy, gz)

    /** True once the user has locked a gyro calibration snapshot. */
    val isMountCalibrated: Boolean get() = classicManager.isCalibrated
}
