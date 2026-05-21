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
    val name: String,
    val hasGmService: Boolean = false  // true = advertising GM Digital Key service FE2C
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
            onFound = { device, rssi, hasGmService ->
                val name = runCatching { device.name }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: if (hasGmService) "GM Digital Key Vehicle" else device.address
                val current = _scanResults.value.toMutableList()
                val idx = current.indexOfFirst { it.device.address == device.address }
                if (idx >= 0) {
                    // Upgrade hasGmService flag if newly confirmed; update RSSI
                    val prev = current[idx]
                    current[idx] = prev.copy(rssi = rssi,
                        hasGmService = prev.hasGmService || hasGmService,
                        name = if (hasGmService && prev.name == prev.device.address) name else prev.name)
                } else {
                    current.add(ScannedDevice(device, rssi, name, hasGmService))
                }
                _scanResults.value = current
            },
            onStopped = { _isScanning.value = false }
        )
    }

    fun stopScan() {
        bleManager.stopScan()
        _isScanning.value = false
    }

    fun pairDevice(
        device: BluetoothDevice,
        model: GmcEvModel,
        displayName: String,
        vin: String = "",
        imageUrl: String = ""
    ) {
        viewModelScope.launch {
            val vehicleId = UUID.randomUUID().toString()
            val publicKey = KeyCredentialStore.generateKeyPair(vehicleId)
            KeyCredentialStore.generateEcKeyPair(vehicleId)  // also generate ECDSA for BLE pairing
            val label = if (vin.length == 17) "${model.displayName} (${vin.takeLast(6)})" else displayName
            val entity = VehicleEntity(
                id = vehicleId,
                displayName = label,
                modelKey = model.name,
                year = 2024,
                bleAddress = device.address,
                vin = vin,
                imageUrl = imageUrl,
                publicKeyBytes = publicKey.encoded
            )
            db.vehicleDao().insert(entity)
            // Use pairing mode on first connect — sends PAIRING_REQUEST + EC public key
            bleManager.pair(device, vehicleId)
        }
    }

    fun dumpVehicleGatt(device: BluetoothDevice) {
        bleManager.dumpGatt(device)
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
