package com.gmc.digitalkey.model

data class ChargingState(
    val plugState: PlugState = PlugState.UNKNOWN,
    val socPercent: Int = -1,
    val estimatedRangeKm: Int = -1,
    val chargeRateKw: Float = 0f,
    val isScheduled: Boolean = false,
    val scheduleHour: Int = 22,
    val scheduleMinute: Int = 0,
    val chargeLimitPercent: Int = 90
) {
    val isCharging get() = plugState == PlugState.CHARGING
    val isStale get() = socPercent == -1
    val isLowBattery get() = socPercent in 0..19
    val isFull get() = socPercent == 100
}

enum class PlugState { UNPLUGGED, PLUGGED, CHARGING, UNKNOWN }
