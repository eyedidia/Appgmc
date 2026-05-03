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
    data class VinMismatch(val fromObd: String, val storedVin: String) : Obd2ActivationState()
    object ActivatingBle : Obd2ActivationState()
    object ActivationSuccess : Obd2ActivationState()
    data class ActivationError(val message: String, val recoverable: Boolean = true) : Obd2ActivationState()
    object NeedsSecurityKey : Obd2ActivationState()
    object UnsupportedModel : Obd2ActivationState()
    data class DiagnosticMode(val didMap: Map<String, String>) : Obd2ActivationState()
}

class Obd2ActivationViewModel(app: Application) : AndroidViewModel(app) {

    val obd2Manager = Obd2Manager(app)
    private val db = AppDatabase.get(app)

    private val _uiState = MutableStateFlow<Obd2ActivationState>(Obd2ActivationState.Idle)
    val uiState: StateFlow<Obd2ActivationState> = _uiState.asStateFlow()

    private val _foundAdapters = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val foundAdapters: StateFlow<List<BluetoothDevice>> = _foundAdapters.asStateFlow()

    private var storedVin: String = ""
    private var vehicleId: String = ""

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
        obd2Manager.scanForAdapter(
            onFound = { device -> addAdapter(device) },
            onStopped = {
                if (_foundAdapters.value.isEmpty()) {
                    _uiState.value = Obd2ActivationState.ActivationError(
                        "No OBD2 adapter found nearby.\nMake sure the ELM327 adapter is plugged into the OBD2 port and phone Bluetooth is on.",
                        recoverable = true
                    )
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

    fun connectAndActivate(device: BluetoothDevice) {
        _uiState.value = Obd2ActivationState.Connecting(device)
        obd2Manager.connect(device)

        viewModelScope.launch {
            // Wait until GATT connects and subscribes to notifications
            val readyState = obd2Manager.state
                .filter { it !is Obd2State.Connecting }
                .first()

            if (readyState is Obd2State.Error || readyState is Obd2State.Disconnected) {
                _uiState.value = Obd2ActivationState.ActivationError(
                    "Failed to connect to OBD2 adapter."
                )
                return@launch
            }
            runActivationSequence()
        }
    }

    private suspend fun runActivationSequence() {
        _uiState.value = Obd2ActivationState.InitializingAdapter
        if (!obd2Manager.initialize()) {
            _uiState.value = Obd2ActivationState.ActivationError(
                "Adapter initialization failed. Try unplugging and re-plugging the adapter."
            )
            return
        }

        _uiState.value = Obd2ActivationState.ReadingVin
        val obdVin = obd2Manager.readVin()

        if (obdVin != null && storedVin.length == 17 && !obdVin.equals(storedVin, ignoreCase = true)) {
            _uiState.value = Obd2ActivationState.VinMismatch(obdVin, storedVin)
            return
        }

        // If no VIN stored yet, save it from OBD
        if (obdVin != null && vehicleId.isNotEmpty()) {
            val entity = db.vehicleDao().getById(vehicleId)
            if (entity != null && entity.vin.isEmpty()) {
                db.vehicleDao().update(entity.copy(vin = obdVin))
            }
        }

        activateBle()
    }

    fun proceedAfterVinMismatch() {
        viewModelScope.launch { activateBle() }
    }

    private suspend fun activateBle() {
        _uiState.value = Obd2ActivationState.ActivatingBle
        when (val result = GmVcimActivation.activateDigitalKeyBle(obd2Manager)) {
            is GmVcimActivation.ActivationResult.Success -> {
                if (vehicleId.isNotEmpty()) {
                    db.vehicleDao().getById(vehicleId)?.let {
                        db.vehicleDao().update(it.copy(activationMethod = "obd2"))
                    }
                }
                _uiState.value = Obd2ActivationState.ActivationSuccess
            }
            is GmVcimActivation.ActivationResult.NeedsSecurityKey ->
                _uiState.value = Obd2ActivationState.NeedsSecurityKey
            is GmVcimActivation.ActivationResult.UnsupportedModel ->
                _uiState.value = Obd2ActivationState.UnsupportedModel
            is GmVcimActivation.ActivationResult.DiagnosticData -> {
                val readable = result.readDids.mapValues { (_, v) ->
                    v.joinToString(" ") { "%02X".format(it) }
                }
                _uiState.value = Obd2ActivationState.DiagnosticMode(readable)
            }
            is GmVcimActivation.ActivationResult.CommunicationError ->
                _uiState.value = Obd2ActivationState.ActivationError(result.detail)
        }
    }

    fun runDiagnosticDump() {
        viewModelScope.launch {
            _uiState.value = Obd2ActivationState.ActivatingBle
            val results = GmVcimActivation.discoverVcimDids(obd2Manager)
            val readable = results.mapValues { (_, v) ->
                v.joinToString(" ") { "%02X".format(it) }
            }
            _uiState.value = Obd2ActivationState.DiagnosticMode(readable)
        }
    }

    override fun onCleared() {
        obd2Manager.disconnect()
        super.onCleared()
    }
}
