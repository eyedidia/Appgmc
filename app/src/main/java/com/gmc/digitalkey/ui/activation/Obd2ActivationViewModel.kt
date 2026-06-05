package com.gmc.digitalkey.ui.activation

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmc.digitalkey.ble.obd2.GmVcimActivation
import com.gmc.digitalkey.ble.obd2.Obd2Manager
import com.gmc.digitalkey.ble.obd2.Obd2State
import com.gmc.digitalkey.db.AppDatabase
import com.gmc.digitalkey.vin.VinDecoder
import com.gmc.digitalkey.vin.VinInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class Obd2ActivationState {
    object Idle : Obd2ActivationState()
    object ScanningForAdapter : Obd2ActivationState()
    data class AdapterList(val devices: List<BluetoothDevice>) : Obd2ActivationState()
    data class Connecting(val device: BluetoothDevice) : Obd2ActivationState()
    object InitializingAdapter : Obd2ActivationState()
    object ReadingVin : Obd2ActivationState()
    object DiscoveringEcus : Obd2ActivationState()
    data class VinMismatch(val fromObd: String, val storedVin: String) : Obd2ActivationState()
    object ActivatingBle : Obd2ActivationState()
    data class ActivationSuccess(val vcimAddress: Int, val vinInfo: VinInfo? = null) : Obd2ActivationState()
    data class ActivationError(val message: String, val recoverable: Boolean = true) : Obd2ActivationState()
    data class NeedsSecurityKey(val vcimAddress: Int, val seed: ByteArray) : Obd2ActivationState()
    object UnsupportedModel : Obd2ActivationState()
    data class DiagnosticMode(val vcimAddress: Int, val didMap: Map<String, String>) : Obd2ActivationState()
    data class AtTerminalResult(val cmd: String, val response: String) : Obd2ActivationState()
}

class Obd2ActivationViewModel(app: Application) : AndroidViewModel(app) {

    val obd2Manager = Obd2Manager(app)
    private val db = AppDatabase.get(app)

    private val _uiState = MutableStateFlow<Obd2ActivationState>(Obd2ActivationState.Idle)
    val uiState: StateFlow<Obd2ActivationState> = _uiState.asStateFlow()

    private val _foundAdapters = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val foundAdapters: StateFlow<List<BluetoothDevice>> = _foundAdapters.asStateFlow()

    // AT terminal history (manual input + synced auto log)
    private val _terminalLog = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val terminalLog: StateFlow<List<Pair<String, String>>> = _terminalLog.asStateFlow()

    // Structured step log — shows each activation step with ✓/✗
    private val _stepLog = MutableStateFlow<List<GmVcimActivation.StepResult>>(emptyList())
    val stepLog: StateFlow<List<GmVcimActivation.StepResult>> = _stepLog.asStateFlow()

    private val activeStepLog = mutableListOf<GmVcimActivation.StepResult>()

    // Sync both logs to UI
    private fun emitStepLog() {
        _stepLog.value = activeStepLog.toList()
        _terminalLog.value = obd2Manager.commandLog   // real-time AT command sync
    }

    private var storedVin: String = ""
    private var vehicleId: String = ""
    private var discoveredVcimAddress: Int = 0x7E3
    private var pendingVinInfo: VinInfo? = null

    fun loadVehicleVin(id: String) {
        vehicleId = id
        viewModelScope.launch {
            storedVin = db.vehicleDao().getById(id)?.vin ?: ""
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdapterScan() {
        _foundAdapters.value = emptyList()
        _uiState.value = Obd2ActivationState.ScanningForAdapter

        obd2Manager.getClassicBtDevices().forEach { addAdapter(it) }

        obd2Manager.scanForAdapter(
            onFound = { device -> addAdapter(device) },
            onStopped = {
                if (_foundAdapters.value.isEmpty()) {
                    _uiState.value = Obd2ActivationState.ActivationError(
                        "No OBD2 adapter found nearby.\nMake sure the ELM327 adapter is plugged into the OBD2 port (under the dashboard) and phone Bluetooth is on.",
                        recoverable = true
                    )
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun showAllBleDevices() {
        _uiState.value = Obd2ActivationState.ScanningForAdapter
        obd2Manager.scanForAllBleAdapters(
            onFound = { device -> addAdapter(device) },
            onStopped = {
                if (_foundAdapters.value.isEmpty()) {
                    _uiState.value = Obd2ActivationState.ActivationError(
                        "No Bluetooth devices found nearby. Make sure Bluetooth is on.",
                        recoverable = true
                    )
                } else {
                    _uiState.value = Obd2ActivationState.AdapterList(_foundAdapters.value)
                }
            }
        )
    }

    private fun addAdapter(device: BluetoothDevice) {
        val current = _foundAdapters.value.toMutableList()
        if (current.none { it.address == device.address }) current.add(device)
        _foundAdapters.value = current
        _uiState.value = Obd2ActivationState.AdapterList(current)
    }

    /** Connect to a Wi-Fi OBD2 adapter (Basic OBD Coding Pro, ELM327 Wi-Fi dongles).
     *  Call after connecting phone to the adapter's Wi-Fi hotspot. */
    fun connectWifiAdapter(host: String = Obd2Manager.WIFI_DEFAULT_HOST,
                           port: Int = Obd2Manager.WIFI_DEFAULT_PORT) {
        activeStepLog.clear()
        _stepLog.value = emptyList()

        // Try specified host first; if blank, auto-try all known addresses
        if (host.isBlank()) {
            obd2Manager.autoConnectWifi()
        } else {
            obd2Manager.connectWifi(host, port)
        }

        viewModelScope.launch {
            val readyState = obd2Manager.state
                .filter { it !is Obd2State.Scanning && it !is Obd2State.Connecting }
                .first()
            if (readyState is Obd2State.Error) {
                _uiState.value = Obd2ActivationState.ActivationError(readyState.message)
                return@launch
            }
            // Connected — run the same activation sequence as BLE/Classic
            runActivationSequence()
        }
    }

    @SuppressLint("MissingPermission")
    fun connectAndActivate(device: BluetoothDevice) {
        activeStepLog.clear()
        _stepLog.value = emptyList()
        _uiState.value = Obd2ActivationState.Connecting(device)
        if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC ||
            device.type == BluetoothDevice.DEVICE_TYPE_DUAL) {
            obd2Manager.connectClassic(device)
        } else {
            obd2Manager.connect(device)
        }
        viewModelScope.launch {
            val readyState = obd2Manager.state.filter { it !is Obd2State.Connecting }.first()
            if (readyState is Obd2State.Error) {
                _uiState.value = Obd2ActivationState.ActivationError(readyState.message)
                return@launch
            }
            if (readyState is Obd2State.Disconnected) {
                _uiState.value = Obd2ActivationState.ActivationError(
                    "Adapter disconnected unexpectedly.\nEnsure vehicle ignition is ON so the adapter has power."
                )
                return@launch
            }
            runActivationSequence()
        }
    }

    private suspend fun runActivationSequence() {
        _uiState.value = Obd2ActivationState.InitializingAdapter
        if (!obd2Manager.initialize()) {
            emitStepLog()
            _uiState.value = Obd2ActivationState.ActivationError(
                "Adapter initialization failed. Try unplugging and re-plugging the adapter."
            )
            return
        }
        // Log which CAN protocol was detected
        activeStepLog += GmVcimActivation.StepResult(
            "Protocol detection",
            true,
            if (obd2Manager.use29BitCan) "ISO 15765-4 CAN 29-bit / 500kbaud (ATSP7)"
            else "ISO 15765-4 CAN 11-bit / auto (ATSP0)"
        )
        emitStepLog()

        _uiState.value = Obd2ActivationState.ReadingVin
        val obdVin = obd2Manager.readVin()
        pendingVinInfo = if (obdVin != null) {
            try { VinDecoder.decode(obdVin) } catch (_: Exception) { null }
        } else null
        if (obdVin != null && storedVin.length == 17 && !obdVin.equals(storedVin, ignoreCase = true)) {
            _uiState.value = Obd2ActivationState.VinMismatch(obdVin, storedVin)
            return
        }
        if (obdVin != null && vehicleId.isNotEmpty()) {
            db.vehicleDao().getById(vehicleId)?.let { entity ->
                if (entity.vin.isEmpty()) db.vehicleDao().update(entity.copy(vin = obdVin))
            }
        }

        _uiState.value = Obd2ActivationState.DiscoveringEcus
        val ecus = GmVcimActivation.discoverEcus(obd2Manager, activeStepLog)
        emitStepLog()
        discoveredVcimAddress = GmVcimActivation.findVcimAddress(obd2Manager, ecus, activeStepLog)
        emitStepLog()

        activateBle()
    }

    fun proceedAfterVinMismatch() {
        viewModelScope.launch {
            _uiState.value = Obd2ActivationState.DiscoveringEcus
            val ecus = GmVcimActivation.discoverEcus(obd2Manager, activeStepLog)
            emitStepLog()
            discoveredVcimAddress = GmVcimActivation.findVcimAddress(obd2Manager, ecus, activeStepLog)
            emitStepLog()
            activateBle()
        }
    }

    private suspend fun activateBle() {
        _uiState.value = Obd2ActivationState.ActivatingBle
        when (val result = GmVcimActivation.activateDigitalKeyBle(obd2Manager, discoveredVcimAddress, activeStepLog)) {
            is GmVcimActivation.ActivationResult.Success -> {
                emitStepLog()
                if (vehicleId.isNotEmpty()) {
                    db.vehicleDao().getById(vehicleId)?.let {
                        db.vehicleDao().update(it.copy(activationMethod = "obd2"))
                    }
                }
                _uiState.value = Obd2ActivationState.ActivationSuccess(result.vcimAddress, pendingVinInfo)
            }
            is GmVcimActivation.ActivationResult.NeedsSecurityKey -> {
                emitStepLog()
                _uiState.value = Obd2ActivationState.NeedsSecurityKey(result.vcimAddress, result.seed)
            }
            is GmVcimActivation.ActivationResult.UnsupportedModel -> {
                emitStepLog()
                _uiState.value = Obd2ActivationState.UnsupportedModel
            }
            is GmVcimActivation.ActivationResult.DiagnosticData -> {
                emitStepLog()
                val readable = result.readDids.mapValues { (_, v) ->
                    v.joinToString(" ") { "%02X".format(it) }
                }
                _uiState.value = Obd2ActivationState.DiagnosticMode(result.vcimAddress, readable)
            }
            is GmVcimActivation.ActivationResult.CommunicationError -> {
                emitStepLog()
                _uiState.value = Obd2ActivationState.ActivationError(result.detail)
            }
        }
    }

    fun runDiagnosticDump() {
        viewModelScope.launch {
            _uiState.value = Obd2ActivationState.ActivatingBle
            val dids = GmVcimActivation.discoverVcimDids(obd2Manager, discoveredVcimAddress, activeStepLog)
            emitStepLog()
            val readable = dids.mapValues { (_, v) -> v.joinToString(" ") { "%02X".format(it) } }
            _uiState.value = Obd2ActivationState.DiagnosticMode(discoveredVcimAddress, readable)
        }
    }

    fun sendAtCommand(cmd: String) {
        if (cmd.isBlank()) return
        viewModelScope.launch {
            val response = try {
                obd2Manager.sendCommand(cmd.trim().uppercase())
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
            _terminalLog.value = _terminalLog.value + (cmd.trim().uppercase() to response)
        }
    }

    fun clearTerminalLog() {
        _terminalLog.value = emptyList()
        activeStepLog.clear()
        _stepLog.value = emptyList()
    }

    fun buildExportText(state: Obd2ActivationState): String {
        val sb = StringBuilder()
        sb.appendLine("=== YMGMC OBD2 Diagnostic Export ===")
        sb.appendLine("VCIM address: 0x${discoveredVcimAddress.toString(16).uppercase()}")
        if (storedVin.isNotEmpty()) sb.appendLine("VIN: $storedVin")

        if (activeStepLog.isNotEmpty()) {
            sb.appendLine("--- Activation Steps ---")
            activeStepLog.forEach { step ->
                val icon = if (step.ok) "✓" else "✗"
                sb.appendLine("$icon ${step.name}: ${step.detail}")
            }
        }

        when (state) {
            is Obd2ActivationState.DiagnosticMode -> {
                sb.appendLine("VCIM: 0x${state.vcimAddress.toString(16).uppercase()}")
                sb.appendLine("--- Readable DIDs ---")
                state.didMap.forEach { (k, v) -> sb.appendLine("$k = $v") }
            }
            is Obd2ActivationState.NeedsSecurityKey -> {
                sb.appendLine("VCIM: 0x${state.vcimAddress.toString(16).uppercase()}")
                sb.appendLine("SecurityAccess seed: ${state.seed.joinToString(" ") { "%02X".format(it) }}")
            }
            is Obd2ActivationState.ActivationError -> {
                sb.appendLine("Error: ${state.message}")
            }
            else -> {}
        }

        val autoLog = obd2Manager.commandLog
        if (autoLog.isNotEmpty()) {
            sb.appendLine("--- AT Command Log ---")
            autoLog.forEach { (cmd, resp) ->
                sb.appendLine("> $cmd")
                sb.appendLine(resp.ifEmpty { "(no response)" })
            }
        }
        return sb.toString()
    }

    override fun onCleared() {
        obd2Manager.disconnect()
        super.onCleared()
    }
}
