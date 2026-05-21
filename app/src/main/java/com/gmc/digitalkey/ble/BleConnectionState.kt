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
    data class PairingInProgress(val device: BluetoothDevice) : BleConnectionState()
    data class PairingSuccess(val device: BluetoothDevice) : BleConnectionState()
    data class PairingFailed(val device: BluetoothDevice, val responseHex: String) : BleConnectionState()
    data class Ready(val device: BluetoothDevice, val rssi: Int = 0) : BleConnectionState()
    data class CommandSent(val command: String) : BleConnectionState()
    data class GattDump(val device: BluetoothDevice, val services: List<ServiceInfo>) : BleConnectionState()
    data class Error(val message: String, val recoverable: Boolean = true) : BleConnectionState()

    val isConnected get() = this is Connected || this is Authenticating || this is Ready || this is PairingInProgress
    val isReady get() = this is Ready

    data class ServiceInfo(val uuid: String, val characteristics: List<CharInfo>)
    data class CharInfo(val uuid: String, val properties: String, val value: String)
}
