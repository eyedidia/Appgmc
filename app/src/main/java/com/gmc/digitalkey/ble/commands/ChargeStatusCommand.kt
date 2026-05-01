package com.gmc.digitalkey.ble.commands

import com.gmc.digitalkey.ble.BleManager

class ChargeStatusCommand(private val ble: BleManager) {
    fun setLimit(percent: Int) = ble.sendChargeLimit(percent)
}
