package com.gmc.digitalkey.ble.obd2

import java.util.UUID

object Elm327GattProfile {

    // Primary service variant (most common ELM327 BLE adapters)
    val SERVICE_FFF0: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    val CHAR_FFF1_RX: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB") // NOTIFY
    val CHAR_FFF2_TX: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB") // WRITE

    // Alternate variant (clone adapters with FFE0 service)
    val SERVICE_FFE0: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
    val CHAR_FFE1_RXTX: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB") // NOTIFY + WRITE

    // Nordic UART Service (NUS) — used by Basic OBD Coding Pro and similar adapters
    val SERVICE_NUS: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val CHAR_NUS_TX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // phone → device WRITE
    val CHAR_NUS_RX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // device → phone NOTIFY

    // Classic Bluetooth SPP (used by non-BLE ELM327 dongles)
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    val DESC_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // Standard BLE services to skip during auto-detection
    val STANDARD_SERVICE_PREFIXES = setOf(
        "00001800", "00001801", "0000180a", "0000180d", "0000180f"
    )

    val ELM_NAME_HINTS = listOf(
        "OBD", "ELM", "OBDII", "OBD2", "VLINK", "KONNWEI", "VEEPEAK",
        "BAFX", "IOBD", "FIXD", "CARISTA", "CARLY", "BLUEDRIVER",
        "BIMMER", "LEMUR", "SCAN", "DIAG", "LINK", "BT-", "BASIC", "CODING"
    )

    data class GattLayout(
        val txChar: UUID,
        val rxChar: UUID,
        val serviceUuid: UUID
    )
}
