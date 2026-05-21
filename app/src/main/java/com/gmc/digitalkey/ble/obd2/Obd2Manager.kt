package com.gmc.digitalkey.ble.obd2

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@SuppressLint("MissingPermission")
class Obd2Manager(private val context: Context) {

    private val _state = MutableStateFlow<Obd2State>(Obd2State.Idle)
    val state: StateFlow<Obd2State> = _state.asStateFlow()

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter

    private var gatt: BluetoothGatt? = null
    private var layout: Elm327GattProfile.GattLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private val commandMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var pendingResponse: CompletableDeferred<String>? = null
    private val rxBuffer = StringBuilder()

    // Classic Bluetooth (SPP) fields
    private var classicSocket: BluetoothSocket? = null
    private var isClassicMode = false

    // True when vehicle uses ISO 15765-4 CAN 29-bit (ATSP7) — e.g. Silverado EV 2025
    var use29BitCan = false
        private set

    // Auto command log
    private val _commandLog = mutableListOf<Pair<String, String>>()
    val commandLog: List<Pair<String, String>> get() = synchronized(_commandLog) { _commandLog.toList() }
    fun clearCommandLog() = synchronized(_commandLog) { _commandLog.clear() }

    // -- Scanning

    fun scanForAdapter(onFound: (BluetoothDevice) -> Unit, onStopped: () -> Unit = {}) {
        _state.value = Obd2State.Scanning
        val scanner = adapter.bluetoothLeScanner

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(Elm327GattProfile.SERVICE_FFF0)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(Elm327GattProfile.SERVICE_FFE0)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanner.stopScan(this)
                _state.value = Obd2State.AdapterFound(result.device)
                onFound(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                _state.value = Obd2State.Error("Scan failed: $errorCode")
                onStopped()
            }
        }

        scanner.startScan(filters, settings, cb)
        handler.postDelayed({
            scanner.stopScan(cb)
            if (_state.value is Obd2State.Scanning) {
                scanForAdapterByName(onFound, onStopped)
            }
        }, 15_000)
    }

    fun scanForAdapterByName(onFound: (BluetoothDevice) -> Unit, onStopped: () -> Unit = {}) {
        _state.value = Obd2State.Scanning
        val scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val found = mutableSetOf<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = runCatching { result.device.name }.getOrNull() ?: return
                if (Elm327GattProfile.ELM_NAME_HINTS.any { name.contains(it, ignoreCase = true) }) {
                    if (found.add(result.device.address)) {
                        _state.value = Obd2State.AdapterFound(result.device)
                        onFound(result.device)
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                _state.value = Obd2State.Error("Scan failed: $errorCode")
                onStopped()
            }
        }

        scanner.startScan(null, settings, cb)
        handler.postDelayed({
            scanner.stopScan(cb)
            if (_state.value is Obd2State.Scanning) {
                _state.value = Obd2State.Error("No OBD2 adapter found. Ensure adapter is plugged in and BLE is on.")
            }
            onStopped()
        }, 15_000)
    }

    fun scanForAllBleAdapters(onFound: (BluetoothDevice) -> Unit, onStopped: () -> Unit = {}) {
        _state.value = Obd2State.Scanning
        val scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val found = mutableSetOf<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (found.add(result.device.address)) onFound(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                _state.value = Obd2State.Error("Scan failed: $errorCode")
                onStopped()
            }
        }

        scanner.startScan(null, settings, cb)
        handler.postDelayed({
            scanner.stopScan(cb)
            if (_state.value is Obd2State.Scanning) _state.value = Obd2State.Idle
            onStopped()
        }, 10_000)
    }

    fun getClassicBtDevices(): List<BluetoothDevice> =
        runCatching { adapter.bondedDevices }.getOrElse { emptySet() }
            .filter {
                it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
                it.type == BluetoothDevice.DEVICE_TYPE_DUAL
            }
            .toList()

    // -- BLE Connection

    fun connect(device: BluetoothDevice) {
        _state.value = Obd2State.Connecting(device)
        isClassicMode = false
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // -- Classic Bluetooth (SPP) Connection

    fun connectClassic(device: BluetoothDevice) {
        _state.value = Obd2State.Connecting(device)
        isClassicMode = true
        gatt?.close(); gatt = null; layout = null
        val prevSocket = classicSocket
        classicSocket = null
        scope.launch {
            runCatching { prevSocket?.close() }
            try {
                adapter.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(Elm327GattProfile.SPP_UUID)
                classicSocket = socket
                withContext(Dispatchers.IO) {
                    try {
                        socket.connect()
                    } catch (secureEx: Exception) {
                        runCatching { socket.close() }
                        val insecureSocket = try {
                            device.createInsecureRfcommSocketToServiceRecord(Elm327GattProfile.SPP_UUID)
                        } catch (e: Exception) {
                            val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                            m.invoke(device, 1) as BluetoothSocket
                        }
                        classicSocket = insecureSocket
                        insecureSocket.connect()
                    }
                }
                val connectedSocket = classicSocket ?: error("Socket lost after connect")
                launch { classicReadLoop(connectedSocket) }
                _state.value = Obd2State.AdapterFound(device)
            } catch (e: Exception) {
                _state.value = Obd2State.Error("BT connect failed: ${e.message}\n\nEnsure vehicle ignition is ON (accessories mode) so the OBD2 adapter has power.")
            }
        }
    }

    private suspend fun classicReadLoop(socket: BluetoothSocket) {
        val buf = ByteArray(1024)
        try {
            while (true) {
                val n = withContext(Dispatchers.IO) { socket.inputStream.read(buf) }
                if (n <= 0) break
                val chunk = String(buf, 0, n, Charsets.US_ASCII)
                rxBuffer.append(chunk)
                if (rxBuffer.contains('>')) {
                    val response = rxBuffer.toString().substringBefore('>').trim()
                    rxBuffer.clear()
                    pendingResponse?.complete(response)
                    pendingResponse = null
                }
            }
        } catch (e: Exception) {
            _state.value = Obd2State.Disconnected
            pendingResponse?.completeExceptionally(e)
            pendingResponse = null
        }
    }

    fun disconnect() {
        scope.launch { runCatching { classicSocket?.close() } }
        classicSocket = null
        isClassicMode = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        layout = null
        pendingResponse?.cancel()
        pendingResponse = null
        rxBuffer.clear()
        use29BitCan = false
        _state.value = Obd2State.Disconnected
    }

    // -- AT / OBD command (coroutine-safe, serial via Mutex)

    suspend fun sendCommand(cmd: String, timeoutMs: Long = 4_000): String = commandMutex.withLock {
        try {
            val response: String
            if (isClassicMode) {
                val socket = classicSocket ?: error("Not connected to adapter")
                rxBuffer.clear()
                val deferred = CompletableDeferred<String>()
                pendingResponse = deferred
                withContext(Dispatchers.IO) {
                    socket.outputStream.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
                    socket.outputStream.flush()
                }
                response = withTimeout(timeoutMs) { deferred.await() }
            } else {
                val g = gatt ?: error("Not connected to adapter")
                val l = layout ?: error("GATT layout not discovered")
                val service = g.getService(l.serviceUuid) ?: error("OBD service missing")
                val txChar = service.getCharacteristic(l.txChar) ?: error("TX char missing")
                rxBuffer.clear()
                val deferred = CompletableDeferred<String>()
                pendingResponse = deferred
                txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                txChar.value = (cmd + "\r").toByteArray(Charsets.US_ASCII)
                g.writeCharacteristic(txChar)
                response = withTimeout(timeoutMs) { deferred.await() }
            }
            synchronized(_commandLog) { _commandLog.add(cmd to response) }
            response
        } catch (e: Exception) {
            synchronized(_commandLog) { _commandLog.add(cmd to "ERROR: ${e.message}") }
            throw e
        }
    }

    // -- High-level init sequence with automatic 29-bit CAN detection

    suspend fun initialize(): Boolean {
        _state.value = Obd2State.Initializing
        clearCommandLog()
        use29BitCan = false
        return try {
            sendCommand("ATZ", 5_000)
            delay(1_200)
            sendCommand("ATE0"); delay(100)
            sendCommand("ATL0"); delay(100)
            sendCommand("ATH1"); delay(100)  // headers on — needed for ECU ID discovery
            sendCommand("ATAT1"); delay(100)

            // Detect CAN protocol: try 29-bit first, fall back to auto
            use29BitCan = detect29BitCan()
            sendCommand("ATDP")  // log detected protocol

            _state.value = Obd2State.Ready
            true
        } catch (e: Exception) {
            _state.value = Obd2State.Error("Init failed: ${e.message}")
            false
        }
    }

    // Try ISO 15765-4 29-bit (ATSP7). Returns true if vehicle responds.
    private suspend fun detect29BitCan(): Boolean {
        return try {
            sendCommand("ATSP7"); delay(200)
            sendCommand("ATSH 18DB33F1"); delay(100)
            val r = sendCommand("10 01", 3_000)  // UDS default session — fast response
            val got29bit = r.isNotBlank()
                && !r.contains("NO DATA", ignoreCase = true)
                && !r.contains("SEARCHING", ignoreCase = true)
                && !r.contains("ERROR", ignoreCase = true)
            if (!got29bit) {
                // 29-bit didn't work — revert to auto
                sendCommand("ATSP0"); delay(200)
                sendCommand("ATSH 7DF"); delay(100)
            }
            got29bit
        } catch (e: Exception) {
            runCatching { sendCommand("ATSP0"); delay(200) }
            false
        }
    }

    // -- VIN reading (Mode 09 PID 02)

    suspend fun readVin(): String? = try {
        if (use29BitCan) {
            // In 29-bit mode, use ATH0 for VIN so header bytes don't pollute parsing
            sendCommand("ATH0")
            sendCommand("ATSH 18DB33F1")
        } else {
            sendCommand("ATSH 7DF")
        }
        val response = sendCommand("0902", 6_000)
        if (use29BitCan) sendCommand("ATH1")  // restore headers for ECU discovery
        parseVin(response)
    } catch (e: Exception) {
        if (use29BitCan) runCatching { sendCommand("ATH1") }
        null
    }

    private fun parseVin(raw: String): String? {
        val chars = raw.lines()
            .filter { it.contains("49 02", ignoreCase = true) || it.contains("4902", ignoreCase = true) }
            .flatMap { line ->
                val tokens = line.trim().split("\\s+".toRegex())
                val idx = tokens.indexOfFirst { it.uppercase() == "49" }
                if (idx < 0) return@flatMap emptyList()
                tokens.drop(idx + 3).take(5)
            }
            .mapNotNull { it.toIntOrNull(16)?.takeIf { b -> b != 0 }?.toChar() }
        val vin = chars.joinToString("")
        return if (vin.length == 17) vin else null
    }

    // -- Raw UDS send/receive (physical addressing)
    // addr = 11-bit CAN address (legacy) OR ECU ID byte (29-bit mode, e.g. 0x11)

    suspend fun sendUds(ecuAddress: Int, pdu: ByteArray): ByteArray {
        if (use29BitCan) {
            // 29-bit physical: tester → ECU: 18DA{ecu}F1, ECU → tester: 18DAF1{ecu}
            sendCommand("ATH0")  // no headers in response — avoids parsing ambiguity
            sendCommand("ATSH 18DA%02XF1".format(ecuAddress and 0xFF))
        } else {
            sendCommand("ATSH %03X".format(ecuAddress))
        }
        val hexCmd = pdu.joinToString(" ") { "%02X".format(it) }
        val response = sendCommand(hexCmd, 6_000)
        return parseHexResponse(response)
    }

    // Broadcast UDS to all ECUs (29-bit functional) and return list of responding ECU IDs
    suspend fun discoverEcuIds29Bit(): List<Int> {
        val ids = mutableListOf<Int>()
        try {
            sendCommand("ATH1")          // headers on — need them to see ECU addresses
            sendCommand("ATSH 18DB33F1") // functional broadcast (all ECUs)
            val resp = sendCommand("10 01", 5_000)

            // ELM327 may format 29-bit headers as "18 DA F1 11" (spaced) or "18DAF111" (compact)
            val spacedPattern  = Regex("(?i)18\\s+DA\\s+F1\\s+([0-9A-Fa-f]{2})")
            val compactPattern = Regex("(?i)18DAF1([0-9A-Fa-f]{2})")

            resp.lines().forEach { line ->
                spacedPattern.find(line)?.groupValues?.get(1)?.toIntOrNull(16)
                    ?.takeIf { it !in ids }?.let { ids.add(it) }
                compactPattern.find(line)?.groupValues?.get(1)?.toIntOrNull(16)
                    ?.takeIf { it !in ids }?.let { ids.add(it) }
            }

            sendCommand("ATH0")  // restore no-headers for subsequent sendUds calls
        } catch (e: Exception) {
            runCatching { sendCommand("ATH0") }
        }
        return ids
    }

    private fun parseHexResponse(raw: String): ByteArray =
        raw.lines()
            .filter { line ->
                line.trim().isNotEmpty() &&
                !line.startsWith(">") &&
                !line.uppercase().startsWith("NO DATA") &&
                !line.uppercase().startsWith("ERROR") &&
                !line.uppercase().startsWith("UNABLE") &&
                !line.uppercase().startsWith("BUS")
            }
            .flatMap { line ->
                line.trim().split("\\s+".toRegex())
                    .filter { it.matches(Regex("[0-9A-Fa-f]{2}")) }
            }
            .map { it.toInt(16).toByte() }
            .toByteArray()

    // -- GATT Callback

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = Obd2State.Disconnected
                    pendingResponse?.completeExceptionally(Exception("Disconnected"))
                    pendingResponse = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = Obd2State.Error("Service discovery failed (status $status)")
                return
            }
            val discoveredLayout = when {
                gatt.getService(Elm327GattProfile.SERVICE_FFF0) != null ->
                    Elm327GattProfile.GattLayout(
                        txChar = Elm327GattProfile.CHAR_FFF2_TX,
                        rxChar = Elm327GattProfile.CHAR_FFF1_RX,
                        serviceUuid = Elm327GattProfile.SERVICE_FFF0
                    )
                gatt.getService(Elm327GattProfile.SERVICE_FFE0) != null ->
                    Elm327GattProfile.GattLayout(
                        txChar = Elm327GattProfile.CHAR_FFE1_RXTX,
                        rxChar = Elm327GattProfile.CHAR_FFE1_RXTX,
                        serviceUuid = Elm327GattProfile.SERVICE_FFE0
                    )
                else -> {
                    _state.value = Obd2State.Error("Unknown adapter layout — not an ELM327?")
                    return
                }
            }
            layout = discoveredLayout

            val service = gatt.getService(discoveredLayout.serviceUuid) ?: return
            val rxChar = service.getCharacteristic(discoveredLayout.rxChar) ?: return
            gatt.setCharacteristicNotification(rxChar, true)
            val desc = rxChar.getDescriptor(Elm327GattProfile.DESC_CCCD)
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            } else {
                _state.value = Obd2State.AdapterFound(gatt.device)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            val chunk = char.value?.toString(Charsets.US_ASCII) ?: return
            rxBuffer.append(chunk)
            if (rxBuffer.contains('>')) {
                val response = rxBuffer.toString().substringBefore('>').trim()
                rxBuffer.clear()
                pendingResponse?.complete(response)
                pendingResponse = null
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            _state.value = Obd2State.AdapterFound(gatt.device)
        }
    }
}
