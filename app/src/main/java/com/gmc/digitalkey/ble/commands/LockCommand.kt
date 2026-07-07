package com.gmc.digitalkey.ble.commands

import com.gmc.digitalkey.ble.BleManager

class LockCommand(private val ble: BleManager) {
    fun execute() = ble.sendLock()
}
