package com.gmc.digitalkey.ble.obd2

sealed class DoIpState {
    object Idle : DoIpState()
    object Scanning : DoIpState()
    data class VehicleFound(val ip: String, val vin: String?) : DoIpState()
    data class Connected(val ip: String) : DoIpState()
    data class Error(val message: String) : DoIpState()
}
