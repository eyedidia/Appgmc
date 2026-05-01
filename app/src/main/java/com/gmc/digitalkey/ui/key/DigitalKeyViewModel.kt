package com.gmc.digitalkey.ui.key

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
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

class DigitalKeyViewModel(app: Application) : AndroidViewModel(app) {

    val bleManager = BleManager(app)
    private val db = AppDatabase.get(app)

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState

    private val _scanResults = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scanResults: StateFlow<List<BluetoothDevice>> = _scanResults.asStateFlow()

    private val _pairedVehicles = db.vehicleDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pairedVehicles: StateFlow<List<VehicleEntity>> = _pairedVehicles

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    fun startScan() {
        _scanResults.value = emptyList()
        _isScanning.value = true
        bleManager.scanForVehicle(onFound = { device ->
            val current = _scanResults.value.toMutableList()
            if (current.none { it.address == device.address }) current.add(device)
            _scanResults.value = current
        })
        // Auto-stop scanning indicator after 30s
        viewModelScope.launch {
            kotlinx.coroutines.delay(30_000)
            _isScanning.value = false
        }
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
