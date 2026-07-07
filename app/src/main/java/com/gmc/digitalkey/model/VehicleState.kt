package com.gmc.digitalkey.model

data class VehicleState(
    val vehicleId: String,
    val lockState: LockState = LockState.UNKNOWN,
    val chargingState: ChargingState = ChargingState(),
    val bleRssi: Int = Int.MIN_VALUE,
    val lastUpdated: Long = 0L
) {
    val isConnected get() = bleRssi > Int.MIN_VALUE
    val isInRange get() = bleRssi > -90
}

enum class LockState { LOCKED, UNLOCKED, UNKNOWN }
