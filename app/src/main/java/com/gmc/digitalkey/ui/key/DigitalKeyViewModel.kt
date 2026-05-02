package com.gmc.digitalkey.ui.key

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmc.digitalkey.ble.BleConnectionState
import com.gmc.digitalkey.ble.BleManager
import com.gmc.digitalkey.crypto.KeyCredentialStore
import com.gmc.digitalkey.db.AppDatabase
import com.gmc.digitalkey.db.VehicleEntity
import com.gmc.digitalkey.model.GmcEvModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

data class ScannedDevice(
    val device: BluetoothDevice,
    val rssi: Int,
    val name: String
)

class DigitalKeyViewModel(app: Application) : AndroidViewModel(app) {

    val bleManager = BleManager(app)
    private val db = AppDatabase.get(app)

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    private val _pairedVehicles = db.vehicleDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pairedVehicles: StateFlow<List<VehicleEntity>> = _pairedVehicles

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startScan() {
        _scanResults.value = emptyList()
        _isScanning.value = true
        bleManager.scanAll(
            onFound = { device, rssi ->
                val name = runCatching { device.name }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: device.address
                val current = _scanResults.value.toMutableList()
                val idx = current.indexOfFirst { it.device.address == device.address }
                if (idx >= 0) {
                    current[idx] = ScannedDevice(device, rssi, name)
                } else {
                    current.add(ScannedDevice(device, rssi, name))
                }
                _scanResults.value = current.sortedByDescending { it.rssi }
            },
            onStopped = { _isScanning.value = false }
        )
    }

    fun stopScan() {
        bleManager.stopScan()
        _isScanning.value = false
    }

    fun pairDevice(device: BluetoothDevice, model: GmcEvModel, displayName: String) {
        viewModelScope.launch {
            val vehicleId = UUID.randomUUID().toString()
            val publicKey = KeyCredentialStore.generateKeyPair(vehicleId)
            val entity = VehicleEntity(
                id = vehicleId,
                displayName = displayName,
                modelKey = model.name,
                year = 2024,
                bleAddress = device.address,
                vinPrefix = "1GKSX",
                publicKeyBytes = publicKey.encoded
            )
            db.vehicleDao().insert(entity)
            bleManager.connect(device, vehicleId)
        }
    }

    fun unpairVehicle(vehicleId: String) {
        viewModelScope.launch {
            db.vehicleDao().deleteById(vehicleId)
            KeyCredentialStore.deleteKey(vehicleId)
        }
    }

    fun setPassiveUnlock(vehicleId: String, enabled: Boolean) {
        viewModelScope.launch { db.vehicleDao().setPassiveUnlock(vehicleId, enabled) }
    }
}
