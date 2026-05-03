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

    private var pendingResponse: CompletableDeferred<String>? = null
    private val rxBuffer = StringBuilder()

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
                // Fallback: unfiltered scan by device name
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

    // -- Connection

    fun connect(device: BluetoothDevice) {
        _state.value = Obd2State.Connecting(device)
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        layout = null
        pendingResponse?.cancel()
        pendingResponse = null
        rxBuffer.clear()
        _state.value = Obd2State.Disconnected
    }

    // -- AT / OBD command (coroutine-safe, serial via Mutex)

    suspend fun sendCommand(cmd: String, timeoutMs: Long = 4_000): String = commandMutex.withLock {
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

        withTimeout(timeoutMs) { deferred.await() }
    }

    // -- High-level init sequence

    suspend fun initialize(): Boolean {
        _state.value = Obd2State.Initializing
        return try {
            sendCommand("ATZ", 3_000)
            delay(400)
            sendCommand("ATE0")   // echo off
            sendCommand("ATL0")   // linefeed off
            sendCommand("ATH1")   // headers on
            sendCommand("ATSP0")  // auto protocol
            sendCommand("ATAT1")  // adaptive timing
            _state.value = Obd2State.Ready
            true
        } catch (e: Exception) {
            _state.value = Obd2State.Error("Init failed: ${e.message}")
            false
        }
    }

    // -- VIN reading (Mode 09 PID 02) — call BEFORE any sendUds() to avoid header pollution

    suspend fun readVin(): String? = try {
        sendCommand("ATSH 7DF") // broadcast header for standard OBD
        val response = sendCommand("0902", 6_000)
        parseVin(response)
    } catch (e: Exception) {
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

    // -- Raw UDS send/receive

    suspend fun sendUds(ecuAddress: Int, pdu: ByteArray): ByteArray {
        sendCommand("ATSH %03X".format(ecuAddress))
        val hexCmd = pdu.joinToString(" ") { "%02X".format(it) }
        val response = sendCommand(hexCmd, 6_000)
        return parseHexResponse(response)
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
                // Some clones skip CCCD — proceed directly
                _state.value = Obd2State.AdapterFound(gatt.device)
            }
        }

        // Accumulate RX chunks until the ELM327 '>' prompt appears
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
            // CCCD write complete — adapter is subscribed and ready for initialize()
            _state.value = Obd2State.AdapterFound(gatt.device)
        }
    }
}
