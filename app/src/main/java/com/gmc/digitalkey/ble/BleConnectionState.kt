package com.gmc.digitalkey.ble

import android.bluetooth.BluetoothDevice

sealed class BleConnectionState {
    object BluetoothOff : BleConnectionState()
    object PermissionDenied : BleConnectionState()
    object Idle : BleConnectionState()
    object Scanning : BleConnectionState()
    data class Connecting(val device: BluetoothDevice) : BleConnectionState()
    data class Connected(val device: BluetoothDevice, val rssi: Int = 0) : BleConnectionState()
    data class Authenticating(val device: BluetoothDevice) : BleConnectionState()
    data class Ready(val device: BluetoothDevice, val rssi: Int = 0) : BleConnectionState()
    data class CommandSent(val command: String) : BleConnectionState()
    data class Error(val message: String, val recoverable: Boolean = true) : BleConnectionState()

    val isConnected get() = this is Connected || this is Authenticating || this is Ready
    val isReady get() = this is Ready
}
