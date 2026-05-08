package com.gmc.digitalkey.ble.obd2

import java.util.UUID

object Elm327GattProfile {

    // Primary service variant (most common ELM327 BLE adapters)
    val SERVICE_FFF0: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    val CHAR_FFF1_RX: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB") // NOTIFY
    val CHAR_FFF2_TX: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB") // WRITE

    // Alternate variant (clone adapters)
    val SERVICE_FFE0: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
    val CHAR_FFE1_RXTX: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB") // NOTIFY + WRITE

    // Classic Bluetooth SPP (used by non-BLE ELM327 dongles)
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    val DESC_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    val ELM_NAME_HINTS = listOf(
        "OBD", "ELM", "OBDII", "OBD2", "VLINK", "KONNWEI", "VEEPEAK",
        "BAFX", "IOBD", "FIXD", "CARISTA", "CARLY", "BLUEDRIVER",
        "BIMMER", "LEMUR", "SCAN", "DIAG", "LINK", "BT-"
    )

    data class GattLayout(
        val txChar: UUID,
        val rxChar: UUID,
        val serviceUuid: UUID
    )
}
