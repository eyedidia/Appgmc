package com.gmc.digitalkey.ble.commands

import com.gmc.digitalkey.ble.BleManager

class UnlockCommand(private val ble: BleManager) {
    fun execute() = ble.sendUnlock()
}
