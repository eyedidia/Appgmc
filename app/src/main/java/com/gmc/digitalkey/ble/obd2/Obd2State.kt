package com.gmc.digitalkey.ble.obd2

import android.bluetooth.BluetoothDevice

sealed class Obd2State {
    object Idle : Obd2State()
    object Scanning : Obd2State()
    data class AdapterFound(val device: BluetoothDevice) : Obd2State()
    data class Connecting(val device: BluetoothDevice) : Obd2State()
    object Initializing : Obd2State()
    object Ready : Obd2State()
    data class Error(val message: String) : Obd2State()
    object Disconnected : Obd2State()
}
