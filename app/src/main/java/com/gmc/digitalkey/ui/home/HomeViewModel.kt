package com.gmc.digitalkey.ui.home

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmc.digitalkey.ble.BleConnectionState
import com.gmc.digitalkey.ble.BleManager
import com.gmc.digitalkey.db.AppDatabase
import com.gmc.digitalkey.model.PairedVehicle
import com.gmc.digitalkey.model.GmcEvModel
import com.gmc.digitalkey.model.VehicleState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    val bleManager = BleManager(app)
    private val db = AppDatabase.get(app)
    private val rssiHandler = Handler(Looper.getMainLooper())

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val vehicleState: StateFlow<VehicleState?> = bleManager.vehicleState

    private val _activeVehicle = MutableStateFlow<PairedVehicle?>(null)
    val activeVehicle: StateFlow<PairedVehicle?> = _activeVehicle.asStateFlow()

    init {
        viewModelScope.launch {
            db.vehicleDao().observeAll().collect { entities ->
                val entity = entities.firstOrNull()
                _activeVehicle.value = entity?.let {
                    PairedVehicle(
                        id = it.id,
                        displayName = it.displayName,
                        model = GmcEvModel.fromKey(it.modelKey),
                        year = it.year,
                        bleAddress = it.bleAddress,
                        vin = it.vin,
                        passiveUnlockEnabled = it.passiveUnlockEnabled
                    )
                }
            }
        }
        startRssiPolling()
    }

    fun connectToVehicle(vehicle: PairedVehicle) {
        val adapter = (getApplication<Application>()
            .getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        val device = runCatching { adapter.getRemoteDevice(vehicle.bleAddress) }.getOrNull() ?: return
        bleManager.connect(device, vehicle.id)
    }

    fun lock() = bleManager.sendLock()
    fun unlock() = bleManager.sendUnlock()
    fun remoteStart() = bleManager.sendRemoteStart()
    fun remoteStop() = bleManager.sendRemoteStop()
    fun hornLights() = bleManager.sendHornLights()

    private fun startRssiPolling() {
        rssiHandler.post(object : Runnable {
            override fun run() {
                if (connectionState.value.isReady) bleManager.pollRssi()
                rssiHandler.postDelayed(this, 5_000)
            }
        })
    }

    override fun onCleared() {
        rssiHandler.removeCallbacksAndMessages(null)
        bleManager.disconnect()
        super.onCleared()
    }
}
