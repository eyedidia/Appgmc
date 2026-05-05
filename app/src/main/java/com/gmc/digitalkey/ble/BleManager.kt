package com.gmc.digitalkey.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.gmc.digitalkey.crypto.ChallengeResponseEngine
import com.gmc.digitalkey.model.ChargingState
import com.gmc.digitalkey.model.LockState
import com.gmc.digitalkey.model.PlugState
import com.gmc.digitalkey.model.VehicleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _vehicleState = MutableStateFlow<VehicleState?>(null)
    val vehicleState: StateFlow<VehicleState?> = _vehicleState.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var currentVehicleId: String? = null
    private var reconnectAttempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null

    val isBluetoothOn get() = adapter?.isEnabled == true

    private fun hasScanPermission(): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            android.Manifest.permission.BLUETOOTH_SCAN
        else
            android.Manifest.permission.ACCESS_FINE_LOCATION
        return androidx.core.content.ContextCompat.checkSelfPermission(context, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // ─── Scanning ─────────────────────────────────────────────────────────────

    // Scan all nearby BLE devices (for pairing UI — no UUID filter)
    fun scanAll(onFound: (BluetoothDevice, Int) -> Unit, onStopped: () -> Unit = {}) {
        if (!isBluetoothOn) { _connectionState.value = BleConnectionState.BluetoothOff; return }
        if (!hasScanPermission()) { _connectionState.value = BleConnectionState.PermissionDenied; return }
        _connectionState.value = BleConnectionState.Scanning
        scanner = adapter.bluetoothLeScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(null, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onFound(result.device, result.rssi)
            }
            override fun onScanFailed(errorCode: Int) {
                _connectionState.value = BleConnectionState.Error("BLE scan failed: $errorCode")
                onStopped()
            }
        })

        handler.postDelayed({
            stopScan()
            onStopped()
        }, 30_000)
    }

    // Scan for a specific paired vehicle address (used by PassiveUnlockService)
    fun scanForVehicle(targetAddress: String? = null, onFound: (BluetoothDevice) -> Unit) {
        if (!isBluetoothOn) { _connectionState.value = BleConnectionState.BluetoothOff; return }
        if (!hasScanPermission()) { _connectionState.value = BleConnectionState.PermissionDenied; return }
        _connectionState.value = BleConnectionState.Scanning
        scanner = adapter.bluetoothLeScanner

        val filters = if (targetAddress != null)
            listOf(ScanFilter.Builder().setDeviceAddress(targetAddress).build())
        else null

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(filters, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                stopScan()
                onFound(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                _connectionState.value = BleConnectionState.Error("BLE scan failed: $errorCode")
            }
        })

        handler.postDelayed({ stopScan() }, 30_000)
    }

    fun stopScan() {
        scanner?.stopScan(object : ScanCallback() {})
        scanner = null
    }

    // ─── Connect ──────────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice, vehicleId: String) {
        currentVehicleId = vehicleId
        _connectionState.value = BleConnectionState.Connecting(device)
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        reconnectAttempts = 0
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = BleConnectionState.Idle
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    fun sendLock() = sendCommand(VehicleGattProfile.Commands.LOCK, "LOCK")
    fun sendUnlock() = sendCommand(VehicleGattProfile.Commands.UNLOCK, "UNLOCK")

    fun sendChargeLimit(limitPercent: Int) {
        val gatt = gatt ?: return
        val service = gatt.getService(VehicleGattProfile.SERVICE_UUID) ?: return
        val char = service.getCharacteristic(VehicleGattProfile.CHAR_COMMAND) ?: return
        char.value = VehicleGattProfile.buildChargeLimitCommand(limitPercent)
        gatt.writeCharacteristic(char)
    }

    private fun sendCommand(cmd: Byte, label: String) {
        val state = _connectionState.value
        if (state !is BleConnectionState.Ready) return
        val gatt = gatt ?: return
        val service = gatt.getService(VehicleGattProfile.SERVICE_UUID) ?: return
        val char = service.getCharacteristic(VehicleGattProfile.CHAR_COMMAND) ?: return
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        char.value = VehicleGattProfile.buildCommand(cmd)
        gatt.writeCharacteristic(char)
        _connectionState.value = BleConnectionState.CommandSent(label)
        // Revert to Ready after brief delay
        handler.postDelayed({ if (_connectionState.value is BleConnectionState.CommandSent) _connectionState.value = state }, 1500)
    }

    // ─── GATT Callback ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.Connected(gatt.device)
                    reconnectAttempts = 0
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (reconnectAttempts < 10) {
                        reconnectAttempts++
                        val backoff = (reconnectAttempts * 5_000L).coerceAtMost(30_000L)
                        handler.postDelayed({
                            val id = currentVehicleId ?: return@postDelayed
                            connect(gatt.device, id)
                        }, backoff)
                    } else {
                        _connectionState.value = BleConnectionState.Error("Vehicle out of range", recoverable = true)
                        reconnectAttempts = 0
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Error("Service discovery failed")
                return
            }
            enableNotifications(gatt)
            readChallenge(gatt)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            if (char.uuid == VehicleGattProfile.CHAR_CHALLENGE && status == BluetoothGatt.GATT_SUCCESS) {
                handleChallenge(gatt, char.value)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            when (char.uuid) {
                VehicleGattProfile.CHAR_CHALLENGE -> handleChallenge(gatt, char.value)
                VehicleGattProfile.CHAR_STATUS -> handleStatusUpdate(char.value)
                VehicleGattProfile.CHAR_CHARGING -> handleChargingUpdate(char.value)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            if (char.uuid == VehicleGattProfile.CHAR_RESPONSE && status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Ready(gatt.device)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            val state = _connectionState.value
            if (state is BleConnectionState.Ready) {
                _connectionState.value = state.copy(rssi = rssi)
                _vehicleState.value = _vehicleState.value?.copy(bleRssi = rssi)
            }
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private fun enableNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(VehicleGattProfile.SERVICE_UUID) ?: return
        listOf(VehicleGattProfile.CHAR_CHALLENGE, VehicleGattProfile.CHAR_STATUS, VehicleGattProfile.CHAR_CHARGING)
            .mapNotNull { service.getCharacteristic(it) }
            .forEach { char ->
                gatt.setCharacteristicNotification(char, true)
                val desc = char.getDescriptor(VehicleGattProfile.DESC_CCCD)
                desc?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
            }
    }

    private fun readChallenge(gatt: BluetoothGatt) {
        val service = gatt.getService(VehicleGattProfile.SERVICE_UUID) ?: return
        val char = service.getCharacteristic(VehicleGattProfile.CHAR_CHALLENGE) ?: return
        _connectionState.value = BleConnectionState.Authenticating(gatt.device)
        gatt.readCharacteristic(char)
    }

    private fun handleChallenge(gatt: BluetoothGatt, challenge: ByteArray) {
        val vehicleId = currentVehicleId ?: return
        val signature = runCatching { ChallengeResponseEngine.signChallenge(vehicleId, challenge) }
            .getOrNull() ?: return
        val service = gatt.getService(VehicleGattProfile.SERVICE_UUID) ?: return
        val char = service.getCharacteristic(VehicleGattProfile.CHAR_RESPONSE) ?: return
        char.value = signature
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(char)
    }

    private fun handleStatusUpdate(bytes: ByteArray) {
        val vehicleId = currentVehicleId ?: return
        val lockByte = VehicleGattProfile.parseVehicleStatus(bytes)
        val lock = when (lockByte) {
            VehicleGattProfile.StatusBytes.LOCKED -> LockState.LOCKED
            VehicleGattProfile.StatusBytes.UNLOCKED -> LockState.UNLOCKED
            else -> LockState.UNKNOWN
        }
        _vehicleState.value = (_vehicleState.value ?: VehicleState(vehicleId))
            .copy(lockState = lock, lastUpdated = System.currentTimeMillis())
    }

    private fun handleChargingUpdate(bytes: ByteArray) {
        val vehicleId = currentVehicleId ?: return
        val (soc, rangeKm, chargeKw) = VehicleGattProfile.parseChargingData(bytes)
        val plugState = if (chargeKw > 0f) PlugState.CHARGING else if (soc >= 0) PlugState.PLUGGED else PlugState.UNPLUGGED
        val charging = ChargingState(plugState, soc, rangeKm, chargeKw)
        _vehicleState.value = (_vehicleState.value ?: VehicleState(vehicleId))
            .copy(chargingState = charging, lastUpdated = System.currentTimeMillis())
    }

    fun pollRssi() { gatt?.readRemoteRssi() }
}
