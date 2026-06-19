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
import java.net.InetSocketAddress
import java.net.Socket

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

    // Wi-Fi TCP fields (e.g. Basic OBD Coding Pro, ELM327 Wi-Fi dongles)
    private var wifiSocket: Socket? = null
    private var isWifiMode = false

    companion object {
        // Default for most Wi-Fi ELM327 adapters (Basic OBD Coding Pro, BAFX, Veepeak Wi-Fi)
        const val WIFI_DEFAULT_HOST = "192.168.0.10"
        const val WIFI_DEFAULT_PORT = 35000
        // Alternative common addresses
        val WIFI_FALLBACK_HOSTS = listOf(
            "192.168.0.10" to 35000,
            "192.168.4.1"  to 35000,
            "192.168.0.1"  to 35000,
            "10.0.0.1"     to 35000,
        )
    }

    // True when vehicle uses ISO 15765-4 CAN 29-bit (ATSP7) — e.g. Silverado EV 2025
    var use29BitCan = false
        private set

    // Adapter self-identification string from ATI response (e.g. "ELM327 v1.5", "OBDII v2.1")
    var adapterIdentity: String = ""
        private set

    // True when switched to 11-bit CAN mode for Ultium K73 VCIM at 0x252/0x652
    var isUltiumMode = false
        private set

    // Switch to 11-bit CAN (ATSP6) to reach Ultium K73 VCIM at 0x252/0x652
    suspend fun enableUltiumMode() {
        sendCommand("ATSP6"); delay(300)
        sendCommand("ATCRA 652"); delay(100)
        isUltiumMode = true
    }

    // Restore 29-bit CAN (ATSP7) after Ultium probing.
    // ATCRA 652 set during Ultium mode persists across protocol switch on ELM327 v1.5 clones —
    // it would silently block all 29-bit responses whose CAN ID doesn't match 0x652.
    // Setting CAN mask to all-zeros disables the filter (frame_id & 0 == filter & 0 for any ID).
    suspend fun disableUltiumMode() {
        sendCommand("ATSP7"); delay(200)
        runCatching { sendCommand("ATCM 00 00 00 00"); delay(100) }  // clear receive filter
        isUltiumMode = false
    }

    // Auto command log
    private val _commandLog = mutableListOf<Pair<String, String>>()
    val commandLog: List<Pair<String, String>> get() = synchronized(_commandLog) { _commandLog.toList() }
    fun clearCommandLog() = synchronized(_commandLog) { _commandLog.clear() }

    // GATT profile summary — persists across clearCommandLog so it's always in exports
    private var gattProfileInfo: String = ""

    // 6E400003 READ-polling fallback: Basic OBD Coding Pro adapter exposes this char with READ
    // (not NOTIFY) inside the FFE0 service. After writing to FFE1 we poll it for responses.
    private var pollReadChar: BluetoothGattCharacteristic? = null

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

    /** Filtered scan: only devices advertising FFF0/FFE0/NUS service UUIDs or matching OBD2 name hints.
     *  This is the default scan shown to users — avoids the "monster list" of all nearby BLE devices. */
    fun scanForObd2Adapters(onFound: (BluetoothDevice) -> Unit, onStopped: () -> Unit = {}) {
        _state.value = Obd2State.Scanning
        val scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Filter by service UUID — catches devices that advertise their OBD2 service
        val serviceFilters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(Elm327GattProfile.SERVICE_FFF0)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(Elm327GattProfile.SERVICE_FFE0)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(Elm327GattProfile.SERVICE_NUS)).build(),
        )
        val found = mutableSetOf<String>()

        val filteredCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (found.add(result.device.address)) onFound(result.device)
            }
            override fun onScanFailed(errorCode: Int) { /* will be caught by unfiltered below */ }
        }

        // Also run an unfiltered scan in parallel to catch adapters that don't include service UUID
        // in their advertisement — filter by name hint instead.
        val nameCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = runCatching { result.device.name }.getOrNull() ?: return
                if (Elm327GattProfile.ELM_NAME_HINTS.any { name.contains(it, ignoreCase = true) }) {
                    if (found.add(result.device.address)) onFound(result.device)
                }
            }
            override fun onScanFailed(errorCode: Int) {}
        }

        scanner.startScan(serviceFilters, settings, filteredCb)
        scanner.startScan(null, settings, nameCb)

        handler.postDelayed({
            scanner.stopScan(filteredCb)
            scanner.stopScan(nameCb)
            if (_state.value is Obd2State.Scanning) _state.value = Obd2State.Idle
            onStopped()
        }, 12_000)
    }

    /** Unfiltered scan — shows ALL nearby BLE devices. Only used when user taps "Show All". */
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

    // -- Wi-Fi TCP Connection (Basic OBD Coding Pro and similar LAN adapters)

    fun connectWifi(host: String = WIFI_DEFAULT_HOST, port: Int = WIFI_DEFAULT_PORT) {
        isWifiMode = true
        isClassicMode = false
        gatt?.close(); gatt = null; layout = null
        runCatching { classicSocket?.close() }; classicSocket = null
        _state.value = Obd2State.Connecting(null)
        scope.launch {
            runCatching { wifiSocket?.close() }
            try {
                val socket = Socket()
                withContext(Dispatchers.IO) {
                    socket.connect(InetSocketAddress(host, port), 8_000)
                    socket.soTimeout = 10_000
                }
                wifiSocket = socket
                launch { wifiReadLoop(socket) }
                _state.value = Obd2State.AdapterFound(null)
            } catch (e: Exception) {
                isWifiMode = false
                _state.value = Obd2State.Error(
                    "Wi-Fi connect failed: ${e.message}\n\n" +
                    "1. Connect phone to adapter's Wi-Fi (not home router)\n" +
                    "2. Default address: $host:$port\n" +
                    "3. Vehicle must have ignition ON"
                )
            }
        }
    }

    /** Try all known Wi-Fi OBD2 addresses; connects to the first that responds. */
    fun autoConnectWifi() {
        isWifiMode = true
        isClassicMode = false
        gatt?.close(); gatt = null; layout = null
        runCatching { classicSocket?.close() }; classicSocket = null
        _state.value = Obd2State.Scanning
        scope.launch {
            runCatching { wifiSocket?.close() }
            for ((host, port) in WIFI_FALLBACK_HOSTS) {
                try {
                    val socket = Socket()
                    withContext(Dispatchers.IO) {
                        socket.connect(InetSocketAddress(host, port), 2_000)
                        socket.soTimeout = 10_000
                    }
                    wifiSocket = socket
                    launch { wifiReadLoop(socket) }
                    synchronized(_commandLog) {
                        _commandLog.add("Wi-Fi" to "Connected to $host:$port")
                    }
                    _state.value = Obd2State.AdapterFound(null)
                    return@launch
                } catch (_: Exception) {
                    synchronized(_commandLog) { _commandLog.add("Wi-Fi try" to "$host:$port — no response") }
                }
            }
            isWifiMode = false
            _state.value = Obd2State.Error(
                "No Wi-Fi OBD2 adapter found.\n\n" +
                "Tried: ${WIFI_FALLBACK_HOSTS.joinToString(", ") { "${it.first}:${it.second}" }}\n\n" +
                "Connect phone to the adapter's Wi-Fi network first."
            )
        }
    }

    private suspend fun wifiReadLoop(socket: Socket) {
        val buf = ByteArray(1024)
        try {
            while (true) {
                val n = withContext(Dispatchers.IO) { socket.getInputStream().read(buf) }
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
        scope.launch { runCatching { wifiSocket?.close() } }
        classicSocket = null
        wifiSocket = null
        isClassicMode = false
        isWifiMode = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        layout = null
        pollReadChar = null
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
            if (isWifiMode) {
                val socket = wifiSocket ?: error("Not connected to Wi-Fi adapter")
                rxBuffer.clear()
                val deferred = CompletableDeferred<String>()
                pendingResponse = deferred
                withContext(Dispatchers.IO) {
                    socket.getOutputStream().write((cmd + "\r").toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
                response = withTimeout(timeoutMs) { deferred.await() }
            } else if (isClassicMode) {
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
                // Prefer WRITE_TYPE_DEFAULT (acknowledged) when the characteristic supports it —
                // coding/professional adapters may ignore WRITE_NO_RESPONSE silently.
                // Fall back to WRITE_NO_RESPONSE only when WRITE is not available.
                txChar.writeType = if (txChar.properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                txChar.value = (cmd + "\r").toByteArray(Charsets.US_ASCII)
                val writeOk = g.writeCharacteristic(txChar)
                synchronized(_commandLog) {
                    _commandLog.add("BLE tx" to "writeCharacteristic returned=${writeOk} type=${txChar.writeType} bytes=${txChar.value?.size}")
                }
                if (!writeOk) {
                    pendingResponse = null
                    error("BLE write failed (writeCharacteristic returned false) — GATT busy or disconnected")
                }
                response = awaitResponseWithOptionalPoll(g, deferred, timeoutMs)
            }
            synchronized(_commandLog) { _commandLog.add(cmd to response) }
            response
        } catch (e: Exception) {
            synchronized(_commandLog) { _commandLog.add(cmd to "ERROR: ${e.message}") }
            throw e
        }
    }

    // Poll on 6E400003 concurrently while waiting for an FFE1 notification.
    // If the adapter delivers responses via READ instead of NOTIFY, the poll coroutine
    // feeds `pendingResponse` via onCharacteristicRead before the notification fires.
    private suspend fun awaitResponseWithOptionalPoll(
        g: BluetoothGatt,
        deferred: CompletableDeferred<String>,
        timeoutMs: Long
    ): String {
        val pollChar = pollReadChar ?: return withTimeout(timeoutMs) { deferred.await() }
        val pollJob = scope.launch {
            delay(150)  // give FFE1 NOTIFY a chance first
            while (!deferred.isCompleted) {
                g.readCharacteristic(pollChar)
                delay(120)
            }
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pollJob.cancel()
        }
    }

    // -- High-level init sequence with automatic 29-bit CAN detection

    suspend fun initialize(): Boolean {
        _state.value = Obd2State.Initializing
        clearCommandLog()
        // Re-inject GATT profile so it survives clearCommandLog and appears in every export
        if (gattProfileInfo.isNotEmpty()) {
            synchronized(_commandLog) { _commandLog.add("GATT profile" to gattProfileInfo) }
        }
        use29BitCan = false
        adapterIdentity = ""
        return try {
            // Let BLE NOTIFY settle after CCCD write — professional coding adapters need this.
            delay(1_500)

            // Probe the adapter. ELM327 ignores a bare CR and returns '>'.
            // OBD Coding Pro proprietary protocol responds with "{CODE},{STATUS},{DATA};\r\n".
            val probeResp = try { sendCommand("", 2_000) } catch (_: Exception) { "" }

            // Pattern: decimal number, comma, hex-or-decimal digits, comma, optional data
            // Examples: "24,00," (ping-ok) or "25,15,Error no such command"
            val isProprietaryProtocol = probeResp.matches(Regex("\\d{2,},\\w+,.*"))
            if (isProprietaryProtocol) {
                synchronized(_commandLog) {
                    _commandLog.add("Protocol" to
                        "OBD Coding Pro proprietary detected (not ELM327). Probe: \"$probeResp\"")
                    _commandLog.add("Info" to
                        "Use AT terminal to explore commands. Known format: {CODE},{LEN_HEX},{DATA};")
                }
                adapterIdentity = "OBD Coding Pro (proprietary)"
                _state.value = Obd2State.Ready   // allow AT terminal to function
                return true
            }

            // ELM327 path — send standard initialization sequence
            delay(300)
            try { sendCommand("\n", 500) } catch (_: Exception) {}
            delay(200)

            sendCommand("ATZ", 5_000)
            delay(1_200)

            // Read adapter identity before muting echo — helps identify Basic OBD Coding Pro variants
            adapterIdentity = try { sendCommand("ATI", 2_000).trim() } catch (_: Exception) { "?" }

            sendCommand("ATE0"); delay(100)
            sendCommand("ATL0"); delay(100)
            sendCommand("ATH1"); delay(100)  // headers on — needed for ECU ID discovery
            sendCommand("ATAT1"); delay(100)
            sendCommand("ATCAF1"); delay(100) // CAN auto-format ON — required for ISO-TP multi-frame TX

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
    // In ATSP7 mode the ELM327's default send header is already 18DB33F1 (functional broadcast).
    // Never call ATSH/ATCP for functional broadcast — many ELM327 clones hang when ATSH is
    // called immediately after ATSP7, because they try to validate the header on the CAN bus.
    private suspend fun detect29BitCan(): Boolean {
        return try {
            sendCommand("ATSP7"); delay(300)
            val r = sendCommand("10 01", 5_000)
            val got29bit = r.isNotBlank()
                && !r.contains("NO DATA", ignoreCase = true)
                && !r.contains("SEARCHING", ignoreCase = true)
                && !r.contains("ERROR", ignoreCase = true)
            if (!got29bit) {
                sendCommand("ATSP0"); delay(200)
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
            sendCommand("ATH0")
            // ATSP7 default send header is already 18DB33F1 — no ATSH needed
        } else {
            sendCommand("ATSH 7DF")
        }
        val response = sendCommand("0902", 6_000)
        if (use29BitCan) sendCommand("ATH1")  // restore headers for ECU discovery
        parseVin(response) ?: if (use29BitCan) readVinViaDid() else null
    } catch (e: Exception) {
        if (use29BitCan) runCatching { sendCommand("ATH1") }
        null
    }

    private suspend fun readVinViaDid(): String? {
        for (addr in listOf(0x45, 0x80, 0x28)) {
            try {
                val r = sendUds(addr, byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
                if (r.firstOrNull() == 0x62.toByte() && r.size >= 20) {
                    val vin = String(r.drop(3).toByteArray(), Charsets.US_ASCII)
                        .filter { it.isLetterOrDigit() }.take(17)
                    if (vin.length == 17) return vin
                }
            } catch (_: Exception) { }
        }
        return null
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
        if (isUltiumMode) {
            // 11-bit Ultium mode — ATSP6 + ATCRA 652 already set; just update ATSH per call
            sendCommand("ATH0")
            sendCommand("ATSH %03X".format(ecuAddress))
        } else if (use29BitCan) {
            // ATH0 and ATSH can intermittently time out on ELM327 clones when the CAN bus still
            // has residual frames from the previous UDS response. Retry once with a settling delay.
            for (cmd in listOf(
                "ATH0",
                "ATSH DA%02XF1".format(ecuAddress and 0xFF)
            )) {
                try {
                    sendCommand(cmd)
                } catch (e: Exception) {
                    delay(400)
                    sendCommand(cmd)
                }
            }
        } else {
            sendCommand("ATSH %03X".format(ecuAddress))
        }
        val hexCmd = pdu.joinToString(" ") { "%02X".format(it) }
        val response = sendCommand(hexCmd, 6_000)
        delay(200)  // let bus settle — prevents next ATH0/ATSH from timing out
        return parseHexResponse(response).skipPending()
    }

    // Strip any number of NRC 0x78 (responseCorrectlyReceivedResponsePending) prefixes.
    // ECU 0x80 on Sierra EV 2026 sends "7F 27 78" before the actual seed response in the same
    // ELM327 multi-frame reply, causing the first byte to be 0x7F instead of 0x67.
    private fun ByteArray.skipPending(): ByteArray {
        var i = 0
        while (i + 2 < size && this[i] == 0x7F.toByte() && this[i + 2] == 0x78.toByte()) {
            i += 3
        }
        return if (i == 0) this else copyOfRange(i, size)
    }

    // Broadcast UDS to all ECUs (29-bit functional) and return list of responding ECU IDs
    suspend fun discoverEcuIds29Bit(): List<Int> {
        val ids = mutableListOf<Int>()
        try {
            sendCommand("ATH1")
            // ATSP7 default send header is already 18DB33F1 — no ATSH needed
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

    // Unified response extractor — handles both ELM327 ('>' prompt) and proprietary (';' terminated).
    // Called from onCharacteristicChanged and onCharacteristicRead.
    private fun extractResponse() {
        val buf = rxBuffer.toString()
        val (response, consumed) = when {
            // ELM327: response ends with '>' prompt
            buf.contains('>') ->
                buf.substringBefore('>').trim() to true
            // OBD Coding Pro proprietary: response ends with ';\r\n' or just ';' at end of buffer
            buf.contains(";\r\n") ->
                buf.substringBefore(";\r\n").trim() to true
            buf.trimEnd().endsWith(';') ->
                buf.trimEnd().trimEnd(';').trim() to true
            else -> null to false
        }
        if (consumed && response != null) {
            rxBuffer.clear()
            pendingResponse?.complete(response)
            pendingResponse = null
        }
    }

    // Scan all non-standard GATT services for a characteristic with WRITE + separate NOTIFY,
    // or a single characteristic that has both WRITE and NOTIFY (like FFE1).
    private fun autoDetectGattLayout(gatt: BluetoothGatt): Elm327GattProfile.GattLayout? {
        for (service in gatt.services) {
            if (Elm327GattProfile.STANDARD_SERVICE_PREFIXES.any {
                service.uuid.toString().lowercase().startsWith(it) }) continue
            var writeChar: android.bluetooth.BluetoothGattCharacteristic? = null
            var notifyChar: android.bluetooth.BluetoothGattCharacteristic? = null
            for (ch in service.characteristics) {
                val p = ch.properties
                val canWrite = p and (android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE or
                    android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                val canNotify = p and android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                if (canWrite && canNotify)
                    return Elm327GattProfile.GattLayout(ch.uuid, ch.uuid, service.uuid)
                if (canWrite) writeChar = ch
                if (canNotify) notifyChar = ch
            }
            if (writeChar != null && notifyChar != null)
                return Elm327GattProfile.GattLayout(writeChar.uuid, notifyChar.uuid, service.uuid)
        }
        return null
    }

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
                gatt.getService(Elm327GattProfile.SERVICE_NUS) != null ->
                    Elm327GattProfile.GattLayout(
                        txChar = Elm327GattProfile.CHAR_NUS_TX,
                        rxChar = Elm327GattProfile.CHAR_NUS_RX,
                        serviceUuid = Elm327GattProfile.SERVICE_NUS
                    )
                else -> {
                    // Fallback: scan all non-standard services for a WRITE+NOTIFY pair
                    autoDetectGattLayout(gatt) ?: run {
                        val serviceList = gatt.services.joinToString(", ") {
                            it.uuid.toString().uppercase().take(8)
                        }
                        _state.value = Obd2State.Error(
                            "Adapter GATT profile not recognised.\n\n" +
                            "Services: $serviceList\n\n" +
                            "Supported: FFF0 (ELM327), FFE0 (clone), NUS (Nordic).\n" +
                            "Try pairing the adapter in Android Bluetooth settings first."
                        )
                        return
                    }
                }
            }
            layout = discoveredLayout
            // Log which GATT profile was selected so export shows service/char UUIDs
            val svcShort = discoveredLayout.serviceUuid.toString().uppercase().take(8)
            val txShort  = discoveredLayout.txChar.toString().uppercase().take(8)
            val rxShort  = discoveredLayout.rxChar.toString().uppercase().take(8)
            val txProps  = gatt.getService(discoveredLayout.serviceUuid)
                ?.getCharacteristic(discoveredLayout.txChar)?.properties ?: 0
            val propsStr = buildString {
                if (txProps and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W ")
                if (txProps and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("WNR ")
                if (txProps and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("N ")
                if (txProps and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("I ")
            }.trim()
            gattProfileInfo = "svc=$svcShort  tx=$txShort  rx=$rxShort  props=[$propsStr]"
            synchronized(_commandLog) {
                _commandLog.add("GATT profile" to gattProfileInfo)
            }

            val service = gatt.getService(discoveredLayout.serviceUuid) ?: return
            val rxChar = service.getCharacteristic(discoveredLayout.rxChar) ?: return

            // Basic OBD Coding Pro: 6E400003 [READ] sits inside the FFE0 service.
            // If FFE1 NOTIFY is silent, we poll this char for responses.
            if (discoveredLayout.serviceUuid == Elm327GattProfile.SERVICE_FFE0) {
                val mayPoll = service.getCharacteristic(Elm327GattProfile.CHAR_NUS_RX)
                if (mayPoll != null &&
                    mayPoll.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    pollReadChar = mayPoll
                    synchronized(_commandLog) {
                        _commandLog.add("Poll char" to "6E400003 [READ] found in FFE0 — polling enabled as NOTIFY fallback")
                    }
                }
            }
            val notifyOk = gatt.setCharacteristicNotification(rxChar, true)
            synchronized(_commandLog) {
                _commandLog.add("BLE setup" to "setCharacteristicNotification=${notifyOk} on ${rxChar.uuid.toString().uppercase().take(8)}")
            }
            val desc = rxChar.getDescriptor(Elm327GattProfile.DESC_CCCD)
            if (desc != null) {
                // Use only the bits the characteristic actually supports.
                // Sending INDICATE bit (0x02) when the adapter only supports NOTIFY causes
                // some stacks to reject the write, silently disabling all notifications.
                val indicateSupported = rxChar.properties and
                    BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                val notifySupported = rxChar.properties and
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val cccdByte: Byte = when {
                    notifySupported && indicateSupported -> 0x03
                    indicateSupported                   -> 0x02
                    else                                -> 0x01  // NOTIFY only (most ELM327 clones)
                }
                desc.value = byteArrayOf(cccdByte, 0x00.toByte())
                val writeOk = gatt.writeDescriptor(desc)
                synchronized(_commandLog) {
                    _commandLog.add("BLE setup" to "CCCD write queued=${writeOk} value=0x${"%02X".format(cccdByte)}")
                }
            } else {
                synchronized(_commandLog) {
                    _commandLog.add("BLE setup" to "No CCCD descriptor — proceeding without NOTIFY enable")
                }
                _state.value = Obd2State.AdapterFound(gatt.device)
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (char.uuid != Elm327GattProfile.CHAR_NUS_RX) return
            val raw = char.value?.takeIf { it.isNotEmpty() } ?: return
            val chunk = String(raw, Charsets.US_ASCII)
            if (chunk.isBlank()) return
            rxBuffer.append(chunk)
            extractResponse()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            synchronized(_commandLog) {
                _commandLog.add("BLE write ack" to
                    "${char.uuid.toString().uppercase().take(8)} status=${if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAIL($status)"}")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            val raw = char.value ?: return
            val chunk = raw.toString(Charsets.US_ASCII)
            synchronized(_commandLog) {
                _commandLog.add("BLE notify" to
                    "${char.uuid.toString().uppercase().take(8)} bytes=${raw.size} data=${chunk.take(40).replace("\r","\\r").replace("\n","\\n")}")
            }
            rxBuffer.append(chunk)
            extractResponse()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            synchronized(_commandLog) {
                _commandLog.add("BLE setup" to
                    "CCCD write result=${if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAIL($status)"}")
            }
            _state.value = Obd2State.AdapterFound(gatt.device)
        }
    }
}
