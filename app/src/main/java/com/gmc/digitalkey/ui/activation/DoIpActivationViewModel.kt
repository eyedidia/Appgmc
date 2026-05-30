package com.gmc.digitalkey.ui.activation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmc.digitalkey.ble.obd2.DoIpManager
import com.gmc.digitalkey.ble.obd2.GmVcimActivation
import com.gmc.digitalkey.db.AppDatabase
import com.gmc.digitalkey.vin.VinDecoder
import com.gmc.digitalkey.vin.VinInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DoIpActivationState {
    object Idle : DoIpActivationState()
    object Scanning : DoIpActivationState()
    data class VehicleFound(val ip: String, val vin: String?) : DoIpActivationState()
    object Connecting : DoIpActivationState()
    object DiscoveringEcus : DoIpActivationState()
    object ActivatingBle : DoIpActivationState()
    data class ActivationSuccess(val vcimAddress: Int, val vinInfo: VinInfo? = null) : DoIpActivationState()
    data class NeedsSecurityKey(val vcimAddress: Int, val seed: ByteArray) : DoIpActivationState()
    data class DiagnosticMode(val vcimAddress: Int, val didMap: Map<String, String>) : DoIpActivationState()
    data class ActivationError(val message: String, val recoverable: Boolean = true) : DoIpActivationState()
    data class NetworkScanResult(
        val openHosts: Map<String, List<Int>>,
        val subnetNote: String
    ) : DoIpActivationState()
}

class DoIpActivationViewModel(app: Application) : AndroidViewModel(app) {

    val doIpManager = DoIpManager(app)
    private val db = AppDatabase.get(app)

    private val _uiState = MutableStateFlow<DoIpActivationState>(DoIpActivationState.Idle)
    val uiState: StateFlow<DoIpActivationState> = _uiState.asStateFlow()

    private val _terminalLog = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val terminalLog: StateFlow<List<Pair<String, String>>> = _terminalLog.asStateFlow()

    private val _stepLog = MutableStateFlow<List<GmVcimActivation.StepResult>>(emptyList())
    val stepLog: StateFlow<List<GmVcimActivation.StepResult>> = _stepLog.asStateFlow()

    private val activeStepLog = mutableListOf<GmVcimActivation.StepResult>()
    private var discoveredVcimAddress = 0
    private var vehicleIp = ""
    private var vehicleId = ""

    fun loadVehicleId(id: String) { vehicleId = id }

    fun startDiscovery() {
        activeStepLog.clear()
        doIpManager.commandLog.clear()
        viewModelScope.launch {
            _uiState.value = DoIpActivationState.Scanning

            // Show network info before discovery starts
            val netInfo = doIpManager.getLocalNetworkInfo()
            val netNote = if (netInfo != null)
                "local=${netInfo.first}  gw=${netInfo.second}  /${netInfo.third}"
            else "No Wi-Fi connection detected"
            activeStepLog += GmVcimActivation.StepResult("Network", netInfo != null, netNote)
            emitLogs()

            val found = doIpManager.discoverVehicle()
            emitLogs()  // show full probe log after discovery

            when (found) {
                is DoIpManager.DiscoveryResult.Found -> {
                    vehicleIp = found.ip
                    activeStepLog += GmVcimActivation.StepResult("DoIP discovery", true, "Vehicle at ${found.ip}")
                    emitLogs()
                    _uiState.value = DoIpActivationState.VehicleFound(found.ip, found.vin)
                    connectAndActivate(found.ip, found.vin)
                }
                DoIpManager.DiscoveryResult.NotFound -> {
                    activeStepLog += GmVcimActivation.StepResult("DoIP discovery", false, "TCP:13400 closed on all scanned IPs")
                    emitLogs()
                    _uiState.value = DoIpActivationState.ActivationError(
                        "No vehicle found on Wi-Fi.\n\n$netNote\n\nCheck the log below for details. Make sure the phone is connected to the vehicle's Wi-Fi hotspot (not your home router), and the vehicle is in READY or ACC mode.",
                        recoverable = true
                    )
                }
            }
        }
    }

    private suspend fun connectAndActivate(ip: String, discoveredVin: String?) {
        _uiState.value = DoIpActivationState.Connecting

        // Decode VIN from NHTSA if we got it from the announcement
        val vinInfo = if (discoveredVin != null) {
            try { VinDecoder.decode(discoveredVin) } catch (_: Exception) { null }
        } else null

        if (!doIpManager.connect(ip)) {
            activeStepLog += GmVcimActivation.StepResult("DoIP routing activation", false, "TCP connect or activation failed")
            emitLogs()
            _uiState.value = DoIpActivationState.ActivationError("Routing activation failed at $ip:13400\n\nThe vehicle's DoIP gateway rejected the connection or is not accessible.")
            return
        }

        activeStepLog += GmVcimActivation.StepResult("DoIP routing activation", true, "Connected to $ip:13400")
        emitLogs()

        probeVcim(vinInfo)
    }

    private suspend fun probeVcim(vinInfo: VinInfo?) {
        _uiState.value = DoIpActivationState.DiscoveringEcus

        // Read VIN via DoIP if not already known
        if (vinInfo == null) {
            for (addr in listOf(0x0045, 0x0080, 0x0028)) {
                try {
                    val r = doIpManager.sendUds(addr, byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
                    if (r.firstOrNull() == 0x62.toByte() && r.size >= 20) {
                        val vin = String(r.drop(3).toByteArray(), Charsets.US_ASCII).filter { it.isLetterOrDigit() }.take(17)
                        if (vin.length == 17) {
                            activeStepLog += GmVcimActivation.StepResult("VIN via DoIP", true, vin)
                            emitLogs()
                            if (vehicleId.isNotEmpty()) {
                                db.vehicleDao().getById(vehicleId)?.let { entity ->
                                    if (entity.vin.isEmpty()) db.vehicleDao().update(entity.copy(vin = vin))
                                }
                            }
                            break
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Probe known VCIM logical addresses with DefaultSession (10 01)
        var vcimAddr = 0
        for (addr in DoIpManager.VCIM_PROBE_ADDRS) {
            try {
                val r = doIpManager.sendUds(addr, byteArrayOf(0x10, 0x01))
                if (r.firstOrNull() == 0x50.toByte()) {
                    vcimAddr = addr
                    activeStepLog += GmVcimActivation.StepResult(
                        "VCIM at 0x%04X".format(addr), true, "DefaultSession OK"
                    )
                    emitLogs()
                    // Try to identify the module
                    try {
                        val id = doIpManager.sendUds(addr, byteArrayOf(0x22, 0xF1.toByte(), 0x8A.toByte()))
                        if (id.firstOrNull() == 0x62.toByte() && id.size > 3) {
                            val name = String(id.drop(3).toByteArray(), Charsets.US_ASCII).trim()
                            activeStepLog += GmVcimActivation.StepResult("Module ID 0x%04X".format(addr), true, name)
                            emitLogs()
                        }
                    } catch (_: Exception) {}
                    break
                }
            } catch (_: Exception) {}
        }

        if (vcimAddr == 0) {
            activeStepLog += GmVcimActivation.StepResult(
                "VCIM probe", false,
                "No response on: ${DoIpManager.VCIM_PROBE_ADDRS.joinToString { "0x%04X".format(it) }}"
            )
            emitLogs()
            _uiState.value = DoIpActivationState.ActivationError(
                "K73 VCIM not found via DoIP.\n\nAll logical addresses returned no response. The VCIM may not be exposed through the Infotainment DoIP gateway on this vehicle model, or the vehicle needs to be in READY mode."
            )
            return
        }

        discoveredVcimAddress = vcimAddr
        activateBle(vcimAddr, vinInfo)
    }

    private suspend fun activateBle(vcimAddr: Int, vinInfo: VinInfo?) {
        _uiState.value = DoIpActivationState.ActivatingBle

        // Open extended diagnostic session first
        try {
            doIpManager.sendUds(vcimAddr, byteArrayOf(0x10, 0x03))
        } catch (_: Exception) {}

        for ((did, label) in DoIpManager.BLE_DID_CANDIDATES) {
            val didH = (did shr 8).toByte()
            val didL = (did and 0xFF).toByte()
            try {
                val r = doIpManager.sendUds(vcimAddr, byteArrayOf(0x2E, didH, didL, 0x01))
                val first = r.firstOrNull()?.toInt()?.and(0xFF) ?: continue
                when {
                    first == 0x6E -> {
                        activeStepLog += GmVcimActivation.StepResult("Write $label", true, "BLE enabled via DoIP!")
                        emitLogs()
                        if (vehicleId.isNotEmpty()) {
                            db.vehicleDao().getById(vehicleId)?.let {
                                db.vehicleDao().update(it.copy(activationMethod = "doip"))
                            }
                        }
                        _uiState.value = DoIpActivationState.ActivationSuccess(vcimAddr, vinInfo)
                        return
                    }
                    first == 0x7F -> {
                        val nrc = r.getOrNull(2)?.toInt()?.and(0xFF) ?: 0
                        activeStepLog += GmVcimActivation.StepResult("Write $label", false, "NRC 0x%02X".format(nrc))
                        emitLogs()
                        if (nrc == 0x33) {
                            // SecurityAccess needed — request seed
                            val seed = requestSeed(vcimAddr)
                            if (seed != null) {
                                activeStepLog += GmVcimActivation.StepResult(
                                    "SA seed (blocked on $label)", true,
                                    seed.joinToString(" ") { "%02X".format(it) }
                                )
                                emitLogs()
                                _uiState.value = DoIpActivationState.NeedsSecurityKey(vcimAddr, seed)
                                return
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // No DID worked — fall through to diagnostic dump
        runDiagnosticDump(vcimAddr)
    }

    private suspend fun requestSeed(vcimAddr: Int): ByteArray? {
        for (level in listOf(0x01.toByte(), 0x03.toByte(), 0x05.toByte())) {
            try {
                val r = doIpManager.sendUds(vcimAddr, byteArrayOf(0x27, level))
                if (r.firstOrNull() == 0x67.toByte() && r.size > 2) return r.drop(2).toByteArray()
            } catch (_: Exception) {}
        }
        return null
    }

    fun runDiagnosticDumpPublic() {
        viewModelScope.launch { runDiagnosticDump(discoveredVcimAddress) }
    }

    fun scanNetwork() {
        doIpManager.commandLog.clear()
        activeStepLog.clear()
        viewModelScope.launch {
            _uiState.value = DoIpActivationState.Scanning
            val netInfo = doIpManager.getLocalNetworkInfo()
            val subnetNote = if (netInfo != null)
                "${netInfo.first.substringBeforeLast(".")}.x/${netInfo.third}  gw=${netInfo.second}"
            else "No Wi-Fi connection detected"
            activeStepLog += GmVcimActivation.StepResult("Network", netInfo != null, subnetNote)
            emitLogs()

            val openHosts = doIpManager.scanSubnetForPorts()
            emitLogs()

            _uiState.value = DoIpActivationState.NetworkScanResult(openHosts, subnetNote)
        }
    }

    private suspend fun runDiagnosticDump(vcimAddr: Int) {
        val didMap = mutableMapOf<String, String>()
        val candidates = listOf(
            0xF190, 0xF1A0, 0xF1B0, 0xF180, 0xF181, 0xF182, 0xF183,
            0xF186, 0xF187, 0xF188, 0xF18A, 0xF18B, 0xF18C, 0xF18D,
            0xF191, 0xF192, 0xF193, 0xF194, 0xF195, 0xF1E0, 0xF1E1,
        )
        for (did in candidates) {
            try {
                val r = doIpManager.sendUds(vcimAddr, byteArrayOf(0x22, (did shr 8).toByte(), (did and 0xFF).toByte()))
                if (r.firstOrNull() == 0x62.toByte() && r.size > 3) {
                    didMap["F%03X".format(did and 0xFFF)] = r.drop(3).joinToString(" ") { "%02X".format(it) }
                }
            } catch (_: Exception) {}
        }
        emitLogs()
        _uiState.value = DoIpActivationState.DiagnosticMode(vcimAddr, didMap)
    }

    fun sendUdsCommand(addrHex: String, pduHex: String) {
        if (addrHex.isBlank() || pduHex.isBlank()) return
        viewModelScope.launch {
            try {
                val addr = addrHex.trim().replace("0x", "").toInt(16)
                val pdu  = pduHex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
                val resp = doIpManager.sendUds(addr, pdu)
                val respHex = resp.joinToString(" ") { "%02X".format(it) }
                _terminalLog.value = _terminalLog.value + ("0x%04X > $pduHex".format(addr) to respHex)
            } catch (e: Exception) {
                _terminalLog.value = _terminalLog.value + (pduHex to "ERROR: ${e.message}")
            }
            emitLogs()
        }
    }

    fun clearTerminalLog() {
        _terminalLog.value = emptyList()
        activeStepLog.clear()
        _stepLog.value = emptyList()
    }

    fun emitLogs() {
        _stepLog.value = activeStepLog.toList()
        _terminalLog.value = doIpManager.commandLog.toList()
    }

    fun buildExportText(state: DoIpActivationState): String {
        val sb = StringBuilder()
        sb.appendLine("=== YMGMC DoIP Diagnostic Export ===")
        sb.appendLine("Vehicle IP: $vehicleIp")
        sb.appendLine("VCIM logical address: 0x%04X".format(discoveredVcimAddress))
        if (activeStepLog.isNotEmpty()) {
            sb.appendLine("--- Activation Steps ---")
            activeStepLog.forEach { step ->
                sb.appendLine("${if (step.ok) "✓" else "✗"} ${step.name}: ${step.detail}")
            }
        }
        when (state) {
            is DoIpActivationState.DiagnosticMode -> {
                sb.appendLine("--- Readable DIDs on 0x%04X ---".format(state.vcimAddress))
                state.didMap.forEach { (k, v) -> sb.appendLine("$k = $v") }
            }
            is DoIpActivationState.NeedsSecurityKey -> {
                sb.appendLine("SA seed: ${state.seed.joinToString(" ") { "%02X".format(it) }}")
            }
            is DoIpActivationState.ActivationError -> sb.appendLine("Error: ${state.message}")
            is DoIpActivationState.NetworkScanResult -> {
                sb.appendLine("--- Network Scan: ${state.subnetNote} ---")
                if (state.openHosts.isEmpty()) {
                    sb.appendLine("No open ports found")
                } else {
                    state.openHosts.toSortedMap().forEach { (ip, ports) ->
                        sb.appendLine("$ip: ${ports.sorted().joinToString(", ")}")
                    }
                }
            }
            else -> {}
        }
        val log = doIpManager.commandLog
        if (log.isNotEmpty()) {
            sb.appendLine("--- UDS Command Log ---")
            log.forEach { (cmd, resp) -> sb.appendLine("> $cmd"); sb.appendLine(resp) }
        }
        return sb.toString()
    }

    override fun onCleared() {
        doIpManager.disconnect()
        super.onCleared()
    }
}
