package com.gmc.digitalkey.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gmc.digitalkey.ble.companion.CdpSession
import com.gmc.digitalkey.crypto.ChallengeResponseEngine
import com.gmc.digitalkey.crypto.KeyCredentialStore
import com.gmc.digitalkey.db.AppDatabase
import com.gmc.digitalkey.db.AssociatedCarEntity
import com.gmc.digitalkey.model.ChargingState
import com.gmc.digitalkey.model.LockState
import com.gmc.digitalkey.model.PlugState
import com.gmc.digitalkey.model.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Full BLE advertisement record — used by raw discovery scan. */
data class RawAdvert(
    val device: BluetoothDevice,
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: List<UUID>,
    val manufacturerData: Map<Int, ByteArray>,  // Bluetooth company ID → payload bytes
    val txPower: Int?,
) {
    // GM's registered Bluetooth SIG company ID
    val isGm: Boolean get() = 0x005D in manufacturerData

    fun serviceUuidsDisplay() =
        if (serviceUuids.isEmpty()) "—" else serviceUuids.joinToString("\n") { it.toString().uppercase() }

    fun manufacturerDisplay() = if (manufacturerData.isEmpty()) "—" else
        manufacturerData.entries.joinToString("\n") { (id, data) ->
            val label = when (id) {
                0x005D -> "General Motors"
                0x004C -> "Apple"
                0x0006 -> "Microsoft"
                0x0075 -> "Samsung"
                0x00E0 -> "Google"
                else -> "0x%04X".format(id)
            }
            "$label: ${data.joinToString(" ") { "%02X".format(it) }.take(60)}"
        }
}

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val TAG = "BleManager"

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter
    private val db by lazy { AppDatabase.get(context) }
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _vehicleState = MutableStateFlow<VehicleState?>(null)
    val vehicleState: StateFlow<VehicleState?> = _vehicleState.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var currentVehicleId: String? = null
    private var isPairingMode = false      // true = send PAIRING_REQUEST + pubkey, not challenge-response
    private var isDumpMode = false         // true = only dump GATT services, don't auth
    private var isProbeMode = false        // true = subscribe to NOTIFY and log raw bytes
    private var isCdpMode = false          // true = use CDP/UKEY2 protocol (myGMC-compatible)
    private var pairingPubKeyBytes: ByteArray? = null  // stored between sendPairingRequest and onMtuChanged
    private val probeLogs = mutableListOf<String>()
    private var reconnectAttempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanAllCallback: ScanCallback? = null
    private var rawScanCallback: ScanCallback? = null
    // Queue for reading characteristics one-by-one during GATT dump
    private val dumpReadQueue = ArrayDeque<BluetoothGattCharacteristic>()

    // CDP session (non-null while a CDP connection is active)
    private var cdpSession: CdpSession? = null

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

        // Stop any previous scan before starting a new one
        stopScan()

        _connectionState.value = BleConnectionState.Scanning
        val leScanner = adapter.bluetoothLeScanner ?: return
        scanner = leScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // GM service UUIDs: 48B42B00 = pairing, 0x1910 = VCIM normal-mode (Sierra EV "TY"),
        // 5EFD8B16 = V2, 4CDABAA0 = secondary module, FD06 = GR-AC older stack
        val gmParcelUuids = VehicleGattProfile.SCAN_SERVICE_UUIDS
            .map { android.os.ParcelUuid(it) }.toSet()

        // Single unfiltered scan — check GM service UUIDs inline from the scan record.
        // Using two concurrent scans previously caused SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
        // (error 2) because Android limits simultaneous scans per app; and the old stopScan()
        // passed a new anonymous callback to stopScan() which never stopped the running scan.
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advertisedUuids = result.scanRecord?.serviceUuids?.toSet() ?: emptySet()
                val hasGmService = advertisedUuids.any { it in gmParcelUuids }
                // Also flag GM by manufacturer company ID 0x005D (covers FD06-series "GR-AC" vehicles
                // that advertise the service UUID only in the GATT table, not in the scan record)
                val hasGmMfr = result.scanRecord?.manufacturerSpecificData?.get(0x005D) != null
                onFound(result.device, result.rssi, hasGmService || hasGmMfr)
            }
            override fun onScanFailed(errorCode: Int) {
                _connectionState.value = BleConnectionState.Error("BLE scan failed: $errorCode")
                onStopped()
            }
        }
        scanAllCallback = cb
        leScanner.startScan(null, settings, cb)
        handler.postDelayed({ stopScan(); onStopped() }, 30_000)
    }

    fun scanForVehicle(targetAddress: String? = null, onFound: (BluetoothDevice) -> Unit) {
        if (!isBluetoothOn) { _connectionState.value = BleConnectionState.BluetoothOff; return }
        if (!hasScanPermission()) { _connectionState.value = BleConnectionState.PermissionDenied; return }

        stopScan()
        _connectionState.value = BleConnectionState.Scanning
        val leScanner = adapter.bluetoothLeScanner ?: return
        scanner = leScanner

        val filters = if (targetAddress != null)
            listOf(ScanFilter.Builder().setDeviceAddress(targetAddress).build())
        else null

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                stopScan()
                onFound(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                _connectionState.value = BleConnectionState.Error("BLE scan failed: $errorCode")
            }
        }
        scanAllCallback = cb
        leScanner.startScan(filters, settings, cb)
        handler.postDelayed({ stopScan() }, 30_000)
    }

    fun stopScan() {
        val cb = scanAllCallback ?: return
        adapter.bluetoothLeScanner?.stopScan(cb)
        scanAllCallback = null
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
                        device = result.device,
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

    // ─── CDP connect (UKEY2 + AES-GCM, myGMC-compatible protocol) ───────────
    // Use this for normal unlock/lock on vehicles that use the Google Companion Device Platform.

    fun connectCdp(device: BluetoothDevice, vehicleId: String) {
        isCdpMode = true
        isPairingMode = false
        isDumpMode = false
        isProbeMode = false
        currentVehicleId = vehicleId
        _connectionState.value = BleConnectionState.Connecting(device)
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ─── GATT dump (enumerates all services/characteristics — for research) ───

    fun dumpGatt(device: BluetoothDevice) {
        isDumpMode = true
        isPairingMode = false
        isProbeMode = false
        currentVehicleId = null
        _connectionState.value = BleConnectionState.Connecting(device)
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ─── Direct BLE probe (4CDABAA0) — subscribe to NOTIFY and log raw bytes ──
    // Use when you've identified the vehicle's direct digital-key BLE service and want
    // to capture what the vehicle sends spontaneously after connection.

    fun probeVehicleDirect(device: BluetoothDevice) {
        isProbeMode = true
        isDumpMode = false
        isPairingMode = false
        currentVehicleId = null
        synchronized(probeLogs) { probeLogs.clear() }
        _connectionState.value = BleConnectionState.Connecting(device)
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun sendDirectBleBytes(hexString: String) {
        if (!isProbeMode) return
        val g = gatt ?: return
        val (service, txCharUuid) = when {
            g.getService(VehicleGattProfile.SERVICE_VCIM_1910) != null ->
                g.getService(VehicleGattProfile.SERVICE_VCIM_1910)!! to VehicleGattProfile.CHAR_VCIM_WRITE
            g.getService(VehicleGattProfile.SERVICE_DIRECT_DK) != null ->
                g.getService(VehicleGattProfile.SERVICE_DIRECT_DK)!! to VehicleGattProfile.CHAR_DIRECT_DK_TX
            g.getService(VehicleGattProfile.SERVICE_FD06) != null ->
                g.getService(VehicleGattProfile.SERVICE_FD06)!! to VehicleGattProfile.CHAR_FD03_WRITE
            else -> {
                Log.w(TAG, "sendDirectBleBytes: no known TX service found"); return
            }
        }
        val txChar = service.getCharacteristic(txCharUuid) ?: return
        val bytes = hexString.replace(" ", "").chunked(2)
            .mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        txChar.value = bytes
        // Use the write type the characteristic actually supports
        txChar.writeType = if (txChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        g.writeCharacteristic(txChar)
        val entry = "TX → ${bytes.joinToString(" ") { "%02X".format(it) }}"
        synchronized(probeLogs) { probeLogs.add(entry) }
        _connectionState.value = BleConnectionState.VehicleBleLog(g.device, probeLogs.toList())
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    fun sendLock()   = if (isCdpMode) sendCdpLock() else sendCommand(VehicleGattProfile.Commands.LOCK, "LOCK")
    fun sendUnlock() = if (isCdpMode) sendCdpUnlock() else sendCommand(VehicleGattProfile.Commands.UNLOCK, "UNLOCK")

    private fun sendCdpLock() {
        cdpSession?.sendLock()
    }

    private fun sendCdpUnlock() {
        val session = cdpSession ?: return
        val vehicleId = currentVehicleId ?: return
        ioScope.launch {
            val car = db.associatedCarDao().findById(vehicleId) ?: run {
                Log.w(TAG, "CDP unlock: no association for $vehicleId"); return@launch
            }
            session.sendUnlock(car.tokenHandle, car.escrowToken)
        }
    }

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
        isProbeMode = false
        isCdpMode = false
        pairingPubKeyBytes = null
        cdpSession?.reset()
        cdpSession = null
        synchronized(probeLogs) { probeLogs.clear() }
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
                    if (isPairingMode || isDumpMode || isProbeMode) {
                        // Don't auto-reconnect for one-shot operations
                        _connectionState.value = BleConnectionState.Idle
                        isPairingMode = false; isDumpMode = false; isProbeMode = false
                        return
                    }
                    if (isCdpMode) {
                        cdpSession?.reset(); cdpSession = null
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

            if (isProbeMode) {
                performVehicleProbe(gatt); return
            }

            if (isCdpMode) {
                startCdpSession(gatt); return
            }

            if (isPairingMode) {
                sendPairingRequest(gatt); return
            }

            enableNotifications(gatt)
            // Authenticating state + challenge arrive via NOTIFY on 5E2A68A5 (set in onDescriptorWrite)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            // GATT dump: drain read queue then emit
            if (isDumpMode) {
                val next = dumpReadQueue.removeFirstOrNull()
                if (next != null) gatt.readCharacteristic(next)
                else emitGattDump(gatt)
                return
            }
            val challengeUuids = setOf(
                VehicleGattProfile.CHAR_CHALLENGE,  // 5E2A68A5
                VehicleGattProfile.CHAR_VCIM_NOTIFY // 0x2B10 (used as READ on some firmware)
            )
            if (char.uuid in challengeUuids && status == BluetoothGatt.GATT_SUCCESS) {
                if (isPairingMode) {
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
                } else {
                    handleChallenge(gatt, char.value)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            if (isProbeMode) {
                val bytes = char.value ?: return
                val hex = bytes.joinToString(" ") { "%02X".format(it) }
                val ascii = String(bytes).map { if (it.code in 32..126) it else '.' }.joinToString("")
                val ts = System.currentTimeMillis() % 1_000_000
                val entry = "$ts  RX ← $hex  |  $ascii"
                synchronized(probeLogs) { probeLogs.add(entry) }
                _connectionState.value = BleConnectionState.VehicleBleLog(gatt.device, probeLogs.toList())
                return
            }
            // Route to CDP session if active
            if (isCdpMode) {
                val bytes = char.value ?: return
                Log.d(TAG, "← CDP NOTIFY ${char.uuid.toString().uppercase().take(8)} (${bytes.size}B)")
                cdpSession?.onBytesReceived(bytes)
                return
            }
            when (char.uuid) {
                VehicleGattProfile.CHAR_SERVER_WRITE -> {
                    val bytes = char.value ?: return
                    val hex = bytes.joinToString(" ") { "%02X".format(it) }
                    Log.i(TAG, "← 5E2A68A5 NOTIFY (${bytes.size}B): $hex")
                    if (isPairingMode) {
                        val first = bytes.firstOrNull()
                        val ok = first == 0x00.toByte() || first == 0x21.toByte()
                        isPairingMode = false
                        pairingPubKeyBytes = null
                        if (ok) {
                            _connectionState.value = BleConnectionState.PairingSuccess(gatt.device)
                        } else {
                            _connectionState.value = BleConnectionState.PairingFailed(gatt.device, hex)
                        }
                        gatt.disconnect()
                    } else {
                        handleChallenge(gatt, bytes)
                    }
                }
                VehicleGattProfile.CHAR_VCIM_NOTIFY -> {
                    val bytes = char.value ?: return
                    val hex = bytes.joinToString(" ") { "%02X".format(it) }
                    Log.i(TAG, "← 0x2B10 NOTIFY (${bytes.size}B): $hex")
                    handleChallenge(gatt, bytes)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            if (isPairingMode && char.uuid == VehicleGattProfile.CHAR_COMMAND) {
                Log.i(TAG, "→ PAIRING_REQUEST written (status=$status), waiting for vehicle NOTIFY…")
            } else if ((char.uuid == VehicleGattProfile.CHAR_RESPONSE ||
                        char.uuid == VehicleGattProfile.CHAR_VCIM_WRITE) &&
                       status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.Ready(gatt.device)
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write failed on ${char.uuid}: status=$status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD write failed: $status")
                return
            }
            if (isCdpMode) {
                // NOTIFY subscription confirmed — negotiate larger MTU before sending CLIENT_INIT
                Log.i(TAG, "CDP CCCD written OK → requesting MTU 512…")
                gatt.requestMtu(512)
                return
            }
            if (isPairingMode) {
                // NOTIFY subscription confirmed — now negotiate larger MTU before sending the public key
                Log.i(TAG, "Pairing CCCD written OK → requesting MTU 185…")
                gatt.requestMtu(185)
            } else {
                // Auth flow: vehicle will now send the challenge via NOTIFY on 5E2A68A5
                Log.i(TAG, "Auth CCCD written OK → Authenticating, waiting for challenge NOTIFY…")
                _connectionState.value = BleConnectionState.Authenticating(gatt.device)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (isCdpMode) {
                Log.i(TAG, "CDP MTU=$mtu → starting UKEY2 handshake…")
                cdpSession?.start()
                return
            }
            if (!isPairingMode) return
            val pubKeyBytes = pairingPubKeyBytes ?: return
            val service = gatt.getService(VehicleGattProfile.SERVICE_UUID) ?: run {
                performGattDump(gatt); return
            }
            val cmdChar = service.getCharacteristic(VehicleGattProfile.CHAR_COMMAND) ?: run {
                performGattDump(gatt); return
            }
            val payload = byteArrayOf(VehicleGattProfile.Commands.PAIRING_REQUEST) +
                          byteArrayOf(pubKeyBytes.size.toByte()) + pubKeyBytes
            Log.i(TAG, "MTU=$mtu → sending PAIRING_REQUEST (${payload.size}B): " +
                       payload.take(6).joinToString(" ") { "%02X".format(it) } + "…")
            cmdChar.value = payload
            cmdChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(cmdChar)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            val state = _connectionState.value
            if (state is BleConnectionState.Ready) {
                _connectionState.value = state.copy(rssi = rssi)
                _vehicleState.value = _vehicleState.value?.copy(bleRssi = rssi)
            }
        }
    }

    // ─── CDP session setup ────────────────────────────────────────────────────

    private fun startCdpSession(gatt: BluetoothGatt) {
        val vehicleId = currentVehicleId ?: run {
            _connectionState.value = BleConnectionState.Error("CDP: no vehicleId"); return
        }

        // Determine which service/characteristic to use for TX (phone → vehicle)
        val mainSvc  = gatt.getService(VehicleGattProfile.SERVICE_UUID)    // 48B42B00
        val vcim1910 = gatt.getService(VehicleGattProfile.SERVICE_VCIM_1910) // 0x1910

        val (txService, txCharUuid, rxCharUuid) = when {
            mainSvc  != null -> Triple(mainSvc,  VehicleGattProfile.CHAR_CLIENT_WRITE, VehicleGattProfile.CHAR_SERVER_WRITE)
            vcim1910 != null -> Triple(vcim1910, VehicleGattProfile.CHAR_VCIM_WRITE,   VehicleGattProfile.CHAR_VCIM_NOTIFY)
            else -> {
                _connectionState.value = BleConnectionState.Error(
                    "CDP: no compatible service (needs 48B42B00 or 0x1910)")
                gatt.disconnect(); return
            }
        }

        // Subscribe to NOTIFY (RX channel)
        val rxChar = txService.getCharacteristic(rxCharUuid) ?: run {
            _connectionState.value = BleConnectionState.Error("CDP: RX characteristic not found")
            gatt.disconnect(); return
        }
        gatt.setCharacteristicNotification(rxChar, true)
        rxChar.getDescriptor(VehicleGattProfile.DESC_CCCD)?.let { desc ->
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(desc)
        }

        // Build session with transport: write CDP bytes to the TX characteristic
        val txChar = txService.getCharacteristic(txCharUuid)
        val session = CdpSession(
            vehicleId = vehicleId,
            send = { bytes ->
                if (txChar != null) {
                    txChar.value = bytes
                    txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(txChar)
                }
            },
            listener = cdpListener
        )
        cdpSession = session
        _connectionState.value = BleConnectionState.CdpHandshaking(gatt.device)
        // CCCD write completes in onDescriptorWrite → then we request MTU → then start session
        Log.i(TAG, "[$vehicleId] CDP session created, waiting for CCCD write…")
    }

    private val cdpListener = object : CdpSession.Listener {
        override fun onSecureChannelEstablished(session: CdpSession, needsVisualConfirm: Boolean) {
            val g = gatt ?: return
            val vehicleId = currentVehicleId ?: return
            if (needsVisualConfirm) {
                Log.i(TAG, "[$vehicleId] CDP secure channel — visual confirmation required")
                _connectionState.value = BleConnectionState.CdpAwaitingConfirm(g.device, "")
            } else {
                Log.i(TAG, "[$vehicleId] CDP secure channel ready")
                _connectionState.value = BleConnectionState.CdpReady(g.device)
            }
        }

        override fun onEscrowToken(vehicleId: String, token: ByteArray, handle: ByteArray) {
            val g = gatt ?: return
            ioScope.launch {
                // Load existing association or create new one
                val existing = db.associatedCarDao().findById(vehicleId)
                val updated = (existing ?: AssociatedCarEntity(
                    id = vehicleId,
                    macAddress = g.device.address,
                    encryptionKey = ByteArray(0),
                    identificationKey = ByteArray(0),
                )).copy(escrowToken = token, tokenHandle = handle)
                db.associatedCarDao().upsert(updated)
                Log.i(TAG, "[$vehicleId] Escrow token stored (${token.size}B)")
            }
            _connectionState.value = BleConnectionState.CdpAssociated(g.device, vehicleId)
        }

        override fun onCommandAck(vehicleId: String, success: Boolean) {
            val g = gatt ?: return
            Log.i(TAG, "[$vehicleId] Command ack success=$success")
            _connectionState.value = BleConnectionState.CdpReady(g.device)
        }

        override fun onError(vehicleId: String, reason: String) {
            Log.e(TAG, "[$vehicleId] CDP error: $reason")
            _connectionState.value = BleConnectionState.Error(reason, recoverable = true)
        }

        override fun onAssociationSuccess(vehicleId: String) {
            val g = gatt ?: return
            Log.i(TAG, "[$vehicleId] CDP association complete")
            _connectionState.value = BleConnectionState.CdpReady(g.device)
        }
    }

    // ─── Pairing helpers ──────────────────────────────────────────────────────

    private fun sendPairingRequest(gatt: BluetoothGatt) {
        val vehicleId = currentVehicleId ?: run {
            _connectionState.value = BleConnectionState.PairingFailed(gatt.device, "no vehicleId")
            return
        }
        val pubKeyBytes = KeyCredentialStore.getEcPublicKeyBytes(vehicleId) ?: run {
            _connectionState.value = BleConnectionState.PairingFailed(gatt.device, "no EC key")
            return
        }
        val service = gatt.getService(VehicleGattProfile.SERVICE_UUID) ?: run {
            // Vehicle is in normal mode (0x1910), not pairing mode (48B42B00).
            // Switch to auth flow instead of aborting.
            Log.w(TAG, "Pairing mode: 48B42B00 not found — vehicle not in pairing mode. Trying auth flow.")
            isPairingMode = false
            pairingPubKeyBytes = null
            enableNotifications(gatt)
            return
        }

        // Step 1: subscribe to NOTIFY on 5E2A68A5 so we receive the vehicle's pairing response.
        // The actual payload write happens in onMtuChanged, after onDescriptorWrite → requestMtu(185).
        val serverChar = service.getCharacteristic(VehicleGattProfile.CHAR_SERVER_WRITE)
        if (serverChar != null) {
            gatt.setCharacteristicNotification(serverChar, true)
            serverChar.getDescriptor(VehicleGattProfile.DESC_CCCD)?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }

        // Store key bytes — used by onMtuChanged to build and send the pairing payload
        pairingPubKeyBytes = pubKeyBytes
        _connectionState.value = BleConnectionState.PairingInProgress(gatt.device)
        Log.i(TAG, "Pairing: enabling NOTIFY on 5E2A68A5, then MTU, then PAIRING_REQUEST…")
    }

    // ─── Auth helpers ─────────────────────────────────────────────────────────

    private fun enableNotifications(gatt: BluetoothGatt) {
        // Support both 48B42B00 (pairing-mode service) and 0x1910 (normal-mode VCIM service)
        val mainSvc  = gatt.getService(VehicleGattProfile.SERVICE_UUID)
        val vcim1910 = gatt.getService(VehicleGattProfile.SERVICE_VCIM_1910)

        val notifyChar: BluetoothGattCharacteristic? = when {
            mainSvc != null  -> mainSvc.getCharacteristic(VehicleGattProfile.CHAR_SERVER_WRITE)
            vcim1910 != null -> vcim1910.getCharacteristic(VehicleGattProfile.CHAR_VCIM_NOTIFY)
            else             -> null
        }

        if (notifyChar == null) {
            Log.w(TAG, "enableNotifications: neither 48B42B00 nor 0x1910 service found → GATT dump")
            _connectionState.value = BleConnectionState.Error(
                "Vehicle service not found. Put the vehicle in pairing mode or try again.",
                recoverable = true
            )
            gatt.disconnect()
            return
        }

        gatt.setCharacteristicNotification(notifyChar, true)
        notifyChar.getDescriptor(VehicleGattProfile.DESC_CCCD)?.let { desc ->
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(desc)
        }
        Log.i(TAG, "enableNotifications: subscribed to NOTIFY on ${notifyChar.uuid}")
    }

    private fun readChallenge(gatt: BluetoothGatt) {
        val service = gatt.getService(VehicleGattProfile.SERVICE_UUID) ?: return
        val char = service.getCharacteristic(VehicleGattProfile.CHAR_CHALLENGE) ?: return
        _connectionState.value = BleConnectionState.Authenticating(gatt.device)
        gatt.readCharacteristic(char)
    }

    private fun handleChallenge(gatt: BluetoothGatt, challenge: ByteArray) {
        val vehicleId = currentVehicleId ?: return
        Log.i(TAG, "Challenge (${challenge.size}B): ${challenge.joinToString(" ") { "%02X".format(it) }}")
        val signature = runCatching { ChallengeResponseEngine.signChallenge(vehicleId, challenge) }
            .getOrElse { e -> Log.e(TAG, "Sign failed: $e"); return }
        Log.i(TAG, "→ Response (${signature.size}B): ${signature.take(8).joinToString(" ") { "%02X".format(it) }}…")

        // 48B42B00 service → write to CHAR_RESPONSE; 0x1910 service → write to CHAR_VCIM_WRITE
        val mainSvc = gatt.getService(VehicleGattProfile.SERVICE_UUID)
        val vcim1910 = gatt.getService(VehicleGattProfile.SERVICE_VCIM_1910)
        val (service, charUuid) = when {
            mainSvc  != null -> mainSvc  to VehicleGattProfile.CHAR_RESPONSE
            vcim1910 != null -> vcim1910 to VehicleGattProfile.CHAR_VCIM_WRITE
            else             -> return
        }
        val char = service.getCharacteristic(charUuid) ?: return
        char.value = signature
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(char)
    }

    // ─── Direct BLE probe ─────────────────────────────────────────────────────

    private fun performVehicleProbe(gatt: BluetoothGatt) {
        // Support 0x1910 (Sierra EV VCIM), 4CDABAA0 (Ultium secondary module), FD06 (GR-AC series)
        val vcim1910Svc = gatt.getService(VehicleGattProfile.SERVICE_VCIM_1910)
        val directSvc   = gatt.getService(VehicleGattProfile.SERVICE_DIRECT_DK)
        val fd06Svc     = gatt.getService(VehicleGattProfile.SERVICE_FD06)

        val rxChar: BluetoothGattCharacteristic
        val svcLabel: String
        when {
            vcim1910Svc != null -> {
                rxChar   = vcim1910Svc.getCharacteristic(VehicleGattProfile.CHAR_VCIM_NOTIFY)
                              ?: run { performGattDump(gatt); return }
                svcLabel = "0x1910 (VCIM)"
            }
            directSvc != null -> {
                rxChar   = directSvc.getCharacteristic(VehicleGattProfile.CHAR_DIRECT_DK_RX)
                              ?: run { performGattDump(gatt); return }
                svcLabel = "4CDABAA0"
            }
            fd06Svc != null -> {
                rxChar   = fd06Svc.getCharacteristic(VehicleGattProfile.CHAR_FD04_NOTIFY)
                              ?: run { performGattDump(gatt); return }
                svcLabel = "FD06"
            }
            else -> { Log.i(TAG, "No probe service — GATT dump"); performGattDump(gatt); return }
        }

        gatt.setCharacteristicNotification(rxChar, true)
        rxChar.getDescriptor(VehicleGattProfile.DESC_CCCD)?.let { desc ->
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(desc)
        }
        val header = "Connected to ${gatt.device.address}  service=$svcLabel\nListening for NOTIFY…"
        synchronized(probeLogs) { probeLogs.add(header) }
        _connectionState.value = BleConnectionState.VehicleBleLog(gatt.device, probeLogs.toList())
        Log.i(TAG, "Vehicle BLE probe: subscribed to NOTIFY  service=$svcLabel")
        handler.postDelayed({
            if (isProbeMode) { gatt.disconnect(); isProbeMode = false }
        }, 120_000)
    }

    // ─── GATT dump ────────────────────────────────────────────────────────────

    private fun performGattDump(gatt: BluetoothGatt) {
        // Queue all readable characteristics — values read sequentially via onCharacteristicRead
        dumpReadQueue.clear()
        for (svc in gatt.services) {
            for (char in svc.characteristics) {
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    dumpReadQueue.addLast(char)
                }
            }
        }
        if (dumpReadQueue.isEmpty()) { emitGattDump(gatt); return }
        gatt.readCharacteristic(dumpReadQueue.removeFirst())
    }

    private fun emitGattDump(gatt: BluetoothGatt) {
        val services = gatt.services.map { svc ->
            val chars = svc.characteristics.map { char ->
                val props = buildList {
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)    add("READ")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)   add("WRITE")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)  add("NOTIFY")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
                }
                val raw = char.value
                val value = when {
                    raw == null || raw.isEmpty() -> ""
                    raw.all { it in 32..126 }    -> "\"${String(raw)}\"  [${raw.joinToString(" ") { "%02X".format(it) }}]"
                    else                          -> raw.joinToString(" ") { "%02X".format(it) }
                }
                BleConnectionState.CharInfo(
                    uuid = char.uuid.toString().uppercase(),
                    properties = props.joinToString("|"),
                    value = value
                )
            }
            BleConnectionState.ServiceInfo(uuid = svc.uuid.toString().uppercase(), characteristics = chars)
        }
        Log.i(TAG, "GATT dump: ${services.size} services")
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
