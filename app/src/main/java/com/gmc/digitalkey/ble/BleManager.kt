package com.gmc.digitalkey.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gmc.digitalkey.crypto.ChallengeResponseEngine
import com.gmc.digitalkey.crypto.KeyCredentialStore
import com.gmc.digitalkey.model.ChargingState
import com.gmc.digitalkey.model.LockState
import com.gmc.digitalkey.model.PlugState
import com.gmc.digitalkey.model.VehicleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Full BLE advertisement record — used by raw discovery scan. */
data class RawAdvert(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<UUID>,
    val manufacturerData: Map<Int, ByteArray>,  // Bluetooth company ID → payload bytes
    val txPower: Int?,
) {
    fun serviceUuidsDisplay() =
        if (serviceUuids.isEmpty()) "—" else serviceUuids.joinToString("\n") { it.toString().uppercase() }

    fun manufacturerDisplay() = if (manufacturerData.isEmpty()) "—" else
        manufacturerData.entries.joinToString("\n") { (id, data) ->
            "Company 0x%04X: %s".format(id, data.joinToString(" ") { "%02X".format(it) }.take(40))
        }
}

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val TAG = "BleManager"

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _vehicleState = MutableStateFlow<VehicleState?>(null)
    val vehicleState: StateFlow<VehicleState?> = _vehicleState.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var currentVehicleId: String? = null
    private var isPairingMode = false      // true = send PAIRING_REQUEST + pubkey, not challenge-response
    private var isDumpMode = false         // true = only dump GATT services, don't auth
    private var reconnectAttempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var rawScanCallback: ScanCallback? = null

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

    fun scanAll(onFound: (BluetoothDevice, Int, Boolean) -> Unit, onStopped: () -> Unit = {}) {
        if (!isBluetoothOn) { _connectionState.value = BleConnectionState.BluetoothOff; return }
        if (!hasScanPermission()) { _connectionState.value = BleConnectionState.PermissionDenied; return }
        _connectionState.value = BleConnectionState.Scanning
        scanner = adapter.bluetoothLeScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Filtered scan: devices advertising the GM Digital Key service (FE2C)
        val gmFilter = listOf(
            ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(VehicleGattProfile.SERVICE_UUID))
                .build()
        )

        val found = mutableSetOf<String>()

        val gmCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.add(result.device.address)
                onFound(result.device, result.rssi, true)  // hasGmService = true
            }
        }

        val allCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Skip devices already found by GM filter
                if (result.device.address !in found) onFound(result.device, result.rssi, false)
            }
            override fun onScanFailed(errorCode: Int) {
                _connectionState.value = BleConnectionState.Error("BLE scan failed: $errorCode")
                onStopped()
            }
        }

        // Run both scans in parallel — GM-filtered finds vehicle even without a name
        scanner?.startScan(gmFilter, settings, gmCallback)
        scanner?.startScan(null, settings, allCallback)

        handler.postDelayed({ stopScan(); onStopped() }, 30_000)
    }

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

    // ─── Raw BLE discovery scan ───────────────────────────────────────────────
    // Unfiltered scan capturing the full advertisement record of every nearby device.
    // Used to discover the vehicle's real service UUID when it's in "Searching for Phone" mode.

    fun scanRaw(onFound: (RawAdvert) -> Unit, onStopped: () -> Unit = {}) {
        if (!isBluetoothOn) { _connectionState.value = BleConnectionState.BluetoothOff; return }
        if (!hasScanPermission()) { _connectionState.value = BleConnectionState.PermissionDenied; return }

        val leScanner = adapter.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rec = result.scanRecord
                val mfr: Map<Int, ByteArray> = buildMap {
                    rec?.manufacturerSpecificData?.let { sparse ->
                        for (i in 0 until sparse.size()) put(sparse.keyAt(i), sparse.valueAt(i))
                    }
                }
                onFound(
                    RawAdvert(
                        address = result.device.address,
                        name = rec?.deviceName,
                        rssi = result.rssi,
                        serviceUuids = rec?.serviceUuids?.map { it.uuid } ?: emptyList(),
                        manufacturerData = mfr,
                        txPower = rec?.txPowerLevel?.takeIf { it != Integer.MIN_VALUE },
                    )
                )
            }
            override fun onScanFailed(errorCode: Int) {
                _connectionState.value = BleConnectionState.Error("Raw scan failed: $errorCode")
                onStopped()
            }
        }
        rawScanCallback = cb
        leScanner.startScan(null, settings, cb)
        handler.postDelayed({ stopRawScan(); onStopped() }, 30_000)
    }

    fun stopRawScan() {
        val cb = rawScanCallback ?: return
        adapter.bluetoothLeScanner?.stopScan(cb)
        rawScanCallback = null
    }

    // ─── Connect (normal auth flow) ───────────────────────────────────────────

    fun connect(device: BluetoothDevice, vehicleId: String) {
        isPairingMode = false
        isDumpMode = false
        currentVehicleId = vehicleId
        _connectionState.value = BleConnectionState.Connecting(device)
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ─── Pair (sends PAIRING_REQUEST + ECDSA public key to vehicle) ───────────
    // Call when vehicle shows "Searching for Phone" — vehicle must be in pairing mode

    fun pair(device: BluetoothDevice, vehicleId: String) {
        isPairingMode = true
        isDumpMode = false
        currentVehicleId = vehicleId
        // Generate ECDSA P-256 keypair now (65-byte public key for BLE efficiency)
        runCatching { KeyCredentialStore.generateEcKeyPair(vehicleId) }
        _connectionState.value = BleConnectionState.Connecting(device)
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ─── GATT dump (enumerates all services/characteristics — for research) ───

    fun dumpGatt(device: BluetoothDevice) {
        isDumpMode = true
        isPairingMode = false
        currentVehicleId = null
        _connectionState.value = BleConnectionState.Connecting(device)
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    fun sendLock()   = sendCommand(VehicleGattProfile.Commands.LOCK, "LOCK")
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
        handler.postDelayed({
            if (_connectionState.value is BleConnectionState.CommandSent)
                _connectionState.value = state
        }, 1500)
    }

    fun disconnect() {
        reconnectAttempts = 0
        isPairingMode = false
        isDumpMode = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = BleConnectionState.Idle
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
                    if (isPairingMode || isDumpMode) {
                        // Don't auto-reconnect for one-shot operations
                        _connectionState.value = BleConnectionState.Idle
                        isPairingMode = false; isDumpMode = false
                        return
                    }
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

            if (isDumpMode) {
                performGattDump(gatt); return
            }

            if (isPairingMode) {
                sendPairingRequest(gatt); return
            }

            enableNotifications(gatt)
            readChallenge(gatt)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            if (char.uuid == VehicleGattProfile.CHAR_CHALLENGE && status == BluetoothGatt.GATT_SUCCESS) {
                if (isPairingMode) {
                    // During pairing, challenge = vehicle's response to our pairing request
                    val hex = char.value?.joinToString(" ") { "%02X".format(it) } ?: "empty"
                    Log.i(TAG, "Pairing response from vehicle: $hex")
                    val ok = char.value?.firstOrNull() == 0x00.toByte() ||
                             char.value?.firstOrNull() == 0x21.toByte()
                    if (ok) {
                        isPairingMode = false
                        _connectionState.value = BleConnectionState.PairingSuccess(gatt.device)
                    } else {
                        _connectionState.value = BleConnectionState.PairingFailed(gatt.device, hex)
                    }
                    gatt.disconnect()
                } else if (status == BluetoothGatt.GATT_SUCCESS) {
                    handleChallenge(gatt, char.value)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            when (char.uuid) {
                VehicleGattProfile.CHAR_CHALLENGE -> {
                    if (!isPairingMode) handleChallenge(gatt, char.value)
                }
                VehicleGattProfile.CHAR_STATUS   -> handleStatusUpdate(char.value)
                VehicleGattProfile.CHAR_CHARGING -> handleChargingUpdate(char.value)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            if (isPairingMode && char.uuid == VehicleGattProfile.CHAR_COMMAND) {
                Log.i(TAG, "PAIRING_REQUEST write status: $status")
                // After sending PAIRING_REQUEST, read the challenge/response characteristic
                handler.postDelayed({
                    val service = gatt.getService(VehicleGattProfile.SERVICE_UUID) ?: return@postDelayed
                    val challengeChar = service.getCharacteristic(VehicleGattProfile.CHAR_CHALLENGE) ?: return@postDelayed
                    gatt.readCharacteristic(challengeChar)
                }, 500)
            } else if (char.uuid == VehicleGattProfile.CHAR_RESPONSE && status == BluetoothGatt.GATT_SUCCESS) {
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

    // ─── Pairing helpers ──────────────────────────────────────────────────────

    private fun sendPairingRequest(gatt: BluetoothGatt) {
        val vehicleId = currentVehicleId ?: run {
            _connectionState.value = BleConnectionState.PairingFailed(gatt.device, "no vehicleId")
            return
        }
        val pubKeyBytes = KeyCredentialStore.getEcPublicKeyBytes(vehicleId)
            ?: run {
                _connectionState.value = BleConnectionState.PairingFailed(gatt.device, "no EC key")
                return
            }

        val service = gatt.getService(VehicleGattProfile.SERVICE_UUID) ?: run {
            // GM Digital Key service not found — dump available services for research
            performGattDump(gatt)
            return
        }
        val cmdChar = service.getCharacteristic(VehicleGattProfile.CHAR_COMMAND) ?: run {
            performGattDump(gatt); return
        }

        // Request MTU large enough for public key (65 bytes) + header
        gatt.requestMtu(185)

        // Payload: [PAIRING_REQUEST=0x20, key_len, ...EC_P256_pubkey_bytes...]
        val payload = byteArrayOf(VehicleGattProfile.Commands.PAIRING_REQUEST) +
                      byteArrayOf(pubKeyBytes.size.toByte()) + pubKeyBytes

        _connectionState.value = BleConnectionState.PairingInProgress(gatt.device)
        Log.i(TAG, "Sending PAIRING_REQUEST (${payload.size} bytes): ${payload.take(4).joinToString(" ") { "%02X".format(it) }}...")

        // Enable notifications so we get the vehicle's pairing response
        listOf(VehicleGattProfile.CHAR_CHALLENGE, VehicleGattProfile.CHAR_STATUS)
            .mapNotNull { service.getCharacteristic(it) }
            .forEach { char ->
                gatt.setCharacteristicNotification(char, true)
                char.getDescriptor(VehicleGattProfile.DESC_CCCD)?.let { desc ->
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(desc)
                }
            }

        handler.postDelayed({
            cmdChar.value = payload
            cmdChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(cmdChar)
        }, 300)
    }

    // ─── Auth helpers ─────────────────────────────────────────────────────────

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

    // ─── GATT dump ────────────────────────────────────────────────────────────

    private fun performGattDump(gatt: BluetoothGatt) {
        val services = gatt.services.map { svc ->
            val chars = svc.characteristics.map { char ->
                val props = buildList {
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)    add("READ")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)   add("WRITE")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)  add("NOTIFY")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
                }
                val value = char.value?.let { v -> v.joinToString(" ") { "%02X".format(it) } } ?: ""
                BleConnectionState.CharInfo(
                    uuid = char.uuid.toString().uppercase(),
                    properties = props.joinToString("|"),
                    value = value
                )
            }
            BleConnectionState.ServiceInfo(
                uuid = svc.uuid.toString().uppercase(),
                characteristics = chars
            )
        }
        Log.i(TAG, "GATT dump: ${services.size} services found")
        services.forEach { svc ->
            Log.i(TAG, "  Service: ${svc.uuid}")
            svc.characteristics.forEach { c -> Log.i(TAG, "    Char: ${c.uuid} [${c.properties}]") }
        }
        _connectionState.value = BleConnectionState.GattDump(gatt.device, services)
        handler.postDelayed({ gatt.disconnect() }, 2000)
        isDumpMode = false
    }

    // ─── Misc ─────────────────────────────────────────────────────────────────

    private fun handleStatusUpdate(bytes: ByteArray) {
        val vehicleId = currentVehicleId ?: return
        val lockByte = VehicleGattProfile.parseVehicleStatus(bytes)
        val lock = when (lockByte) {
            VehicleGattProfile.StatusBytes.LOCKED   -> LockState.LOCKED
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
