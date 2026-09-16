package com.example.smartroaddamage.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smartroaddamage.ble.BluetoothService
import com.example.smartroaddamage.models.ScannedDevice
import com.example.smartroaddamage.ui.components.ConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "HardwareBridgeVM"

/**
 * HardwareBridgeViewModel
 *
 * Owns all BLE connection logic for [HardwareBridgeScreen].
 * Binds to [BluetoothService] and forwards its scanned-device stream to the UI.
 * Replacing all simulation blocks with real BLE calls.
 */
class HardwareBridgeViewModel(private val appContext: Context) : ViewModel() {

    // ── UI-facing state ────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _foundDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val foundDevices: StateFlow<List<ScannedDevice>> = _foundDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<ScannedDevice?>(null)
    val selectedDevice: StateFlow<ScannedDevice?> = _selectedDevice.asStateFlow()

    private val _hardwareError = MutableStateFlow<String?>(null)
    val hardwareError: StateFlow<String?> = _hardwareError.asStateFlow()

    fun dismissHardwareError() {
        _hardwareError.value = null
    }

    // ── BleService binding ─────────────────────────────────────────────────────

    private var bluetoothService: BluetoothService? = null
    private var scanCollectJob: Job? = null
    private var serviceStateJob: Job? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder is BluetoothService.LocalBinder) {
                isServiceBound = true
                bluetoothService = binder.getService()

                serviceStateJob?.cancel()
                serviceStateJob = viewModelScope.launch {
                    bluetoothService?.connectionState?.collect { svcState ->
                        when (svcState) {
                            com.example.smartroaddamage.ble.ConnectionState.CONNECTED -> {
                                if (_connectionState.value != ConnectionState.CONNECTED) {
                                    _connectionState.value = ConnectionState.CONNECTED
                                    triggerPairingHaptic()
                                }
                            }
                            com.example.smartroaddamage.ble.ConnectionState.DISCONNECTED -> {
                                if (_connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.CONNECTING) {
                                    _connectionState.value = ConnectionState.IDLE
                                    _selectedDevice.value = null
                                }
                            }
                            com.example.smartroaddamage.ble.ConnectionState.CONNECTING -> {
                                _connectionState.value = ConnectionState.CONNECTING
                            }
                            com.example.smartroaddamage.ble.ConnectionState.DISCONNECTING -> {
                                _connectionState.value = ConnectionState.IDLE
                            }
                            com.example.smartroaddamage.ble.ConnectionState.ERROR -> {
                                _connectionState.value = ConnectionState.IDLE
                                _hardwareError.value = "Bluetooth connection error. Try reconnecting."
                            }
                        }
                    }
                }
            }
            
            Log.d(TAG, "BluetoothService connected.")
            if (_connectionState.value == ConnectionState.SEARCHING) {
                startBleScanner()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            serviceStateJob?.cancel()
            serviceStateJob = null
            bluetoothService = null
            Log.w(TAG, "BluetoothService disconnected unexpectedly.")
        }
    }

    // ── Hardware Monitoring ────────────────────────────────────────────────────

    private val hardwareReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (_connectionState.value == ConnectionState.SEARCHING) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                            terminateScanWithError("Radar terminated: Bluetooth hardware disabled.")
                        }
                    }
                    LocationManager.PROVIDERS_CHANGED_ACTION -> {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                                !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                                terminateScanWithError("Radar terminated: Location hardware disabled.")
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        // Bind to BleService so it stays alive across configuration changes
        bindBluetoothServiceIfNeeded()

        // Monitor manual hardware toggles down in Quick Settings
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            }
        }
        appContext.registerReceiver(hardwareReceiver, filter)
    }

    private fun terminateScanWithError(message: String) {
        cancelScan()
        _hardwareError.value = message
    }

    // ── Public API called by HardwareBridgeScreen ──────────────────────────────

    fun onConnectClicked() {
        when (_connectionState.value) {
            ConnectionState.IDLE       -> startScanning()
            ConnectionState.SEARCHING  -> cancelScan()
            ConnectionState.CONNECTING -> cancelConnection()
            ConnectionState.CONNECTED  -> disconnect()
        }
    }

    fun onDeviceSelected(device: ScannedDevice) {
        _selectedDevice.value = device
        _connectionState.value = ConnectionState.CONNECTING
        bluetoothService?.stopScan()
        scanCollectJob?.cancel()
        bluetoothService?.connectToDevice(device.address)
    }

    // ── Haptic helpers ─────────────────────────────────────────────────────────

    /** Sharp TICK per device discovered — confirms hardware is responding. */
    @SuppressLint("MissingPermission")
    private fun triggerDiscoveryHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }

    /** Magnetic-lock waveform [0, 60, 30, 150] — simulates bridge snapping shut. */
    @SuppressLint("MissingPermission")
    private fun triggerPairingHaptic() {
        try {
            val timings    = longArrayOf(0, 60, 30, 150)
            val amplitudes = intArrayOf(0, 80, 0, 255)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            }
        } catch (_: Exception) {}
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun startScanning() {
        _connectionState.value = ConnectionState.SEARCHING
        _foundDevices.value = emptyList()

        bindBluetoothServiceIfNeeded()
        startBleScanner()
    }

    private fun bindBluetoothServiceIfNeeded() {
        if (isServiceBound) return

        val intent = Intent(appContext, BluetoothService::class.java)
        try {
            appContext.startService(intent)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Not allowed to start service in background, relying on BIND_AUTO_CREATE", e)
        }
        isServiceBound = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!isServiceBound) {
            Log.e(TAG, "Failed to bind BluetoothService.")
        }
    }

    private fun startBleScanner() {
        bluetoothService?.startScan() ?: run {
            Log.d(TAG, "BluetoothService not yet bound, will start scan on connect.")
            return
        }

        scanCollectJob?.cancel()
        scanCollectJob = viewModelScope.launch {
            var previousCount = 0
            bluetoothService?.scannedDevices?.collect { devices ->
                _foundDevices.value = devices
                
                // Fire haptic for every newly discovered device
                if (devices.size > previousCount) {
                    repeat(devices.size - previousCount) { triggerDiscoveryHaptic() }
                }
                previousCount = devices.size
            }
        }
    }

    private fun cancelScan() {
        bluetoothService?.stopScan()
        scanCollectJob?.cancel()
        _connectionState.value = ConnectionState.IDLE
        _foundDevices.value = emptyList()
    }

    private fun cancelConnection() {
        bluetoothService?.disconnect()
        _connectionState.value = ConnectionState.IDLE
        _selectedDevice.value = null
    }

    private fun disconnect() {
        bluetoothService?.disconnect()
        bluetoothService?.stopScan()
        scanCollectJob?.cancel()
        _connectionState.value = ConnectionState.IDLE
        _selectedDevice.value = null
        _foundDevices.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        try { appContext.unregisterReceiver(hardwareReceiver) } catch (_: Exception) {}
        bluetoothService?.stopScan()
        serviceStateJob?.cancel()
        serviceStateJob = null
        if (isServiceBound) {
            try { appContext.unbindService(serviceConnection) } catch (_: Exception) {}
            isServiceBound = false
        }
    }

    // ── Factory ────────────────────────────────────────────────────────────────

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HardwareBridgeViewModel(context.applicationContext) as T
    }
}
