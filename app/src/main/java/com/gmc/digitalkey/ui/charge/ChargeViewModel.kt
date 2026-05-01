package com.gmc.digitalkey.ui.charge

import android.app.Application
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gmc.digitalkey.ble.BleConnectionState
import com.gmc.digitalkey.ble.BleManager
import com.gmc.digitalkey.db.AppDatabase
import com.gmc.digitalkey.model.ChargingState
import com.gmc.digitalkey.model.VehicleState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

class ChargeViewModel(app: Application) : AndroidViewModel(app) {

    val bleManager = BleManager(app)
    private val db = AppDatabase.get(app)

    val vehicleState: StateFlow<VehicleState?> = bleManager.vehicleState
    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState

    private val _scheduleHour = MutableStateFlow(22)
    val scheduleHour: StateFlow<Int> = _scheduleHour.asStateFlow()

    private val _scheduleMinute = MutableStateFlow(0)
    val scheduleMinute: StateFlow<Int> = _scheduleMinute.asStateFlow()

    private val _scheduleEnabled = MutableStateFlow(false)
    val scheduleEnabled: StateFlow<Boolean> = _scheduleEnabled.asStateFlow()

    private val _chargeLimit = MutableStateFlow(90)
    val chargeLimit: StateFlow<Int> = _chargeLimit.asStateFlow()

    val chargingState: StateFlow<ChargingState> = vehicleState
        .map { it?.chargingState ?: ChargingState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChargingState())

    // Last-known SOC from DB for stale display
    val lastKnownSoc: Flow<Pair<Int, Int>> = db.vehicleDao().observeAll()
        .map { entities ->
            val e = entities.firstOrNull()
            (e?.lastKnownSoc ?: -1) to (e?.lastKnownRangeKm ?: -1)
        }

    init {
        viewModelScope.launch {
            vehicleState.collect { state ->
                val soc = state?.chargingState?.socPercent ?: return@collect
                if (soc >= 0) {
                    val vehicles = db.vehicleDao().getAll()
                    vehicles.firstOrNull()?.let {
                        db.vehicleDao().updateEvStatus(
                            it.id, soc,
                            state.chargingState.estimatedRangeKm,
                            System.currentTimeMillis()
                        )
                    }
                }
            }
        }
    }

    fun setChargeLimit(percent: Int) {
        _chargeLimit.value = percent
        bleManager.sendChargeLimit(percent)
    }

    fun setSchedule(hour: Int, minute: Int, enabled: Boolean) {
        _scheduleHour.value = hour
        _scheduleMinute.value = minute
        _scheduleEnabled.value = enabled

        val alarmMgr = getApplication<Application>()
            .getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = PendingIntent.getBroadcast(
            getApplication(), 0,
            Intent("com.gmc.digitalkey.START_CHARGE"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (enabled) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
            }
            alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, intent)
        } else {
            alarmMgr.cancel(intent)
        }
    }
}
