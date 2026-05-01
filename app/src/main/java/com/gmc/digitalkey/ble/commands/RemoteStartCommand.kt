package com.gmc.digitalkey.ble.commands

import com.gmc.digitalkey.ble.BleManager

class RemoteStartCommand(private val ble: BleManager) {
    fun executeStart() = ble.sendRemoteStart()
    fun executeStop() = ble.sendRemoteStop()
}
