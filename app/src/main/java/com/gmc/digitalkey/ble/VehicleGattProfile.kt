package com.gmc.digitalkey.ble

import java.util.UUID

object VehicleGattProfile {

    // GM uses Google Android Auto Companion (automotive_trustagent) for BLE digital key.
    // These UUIDs were confirmed by decompiling the official myGMC APK (classes4.dex).
    //
    // Association service — advertised during pairing window ("Searching for Phone" QR screen)
    val SERVICE_UUID: UUID = UUID.fromString("5E2A68A6-27BE-43F9-8D1E-4546976FABD7")

    // Reconnection service — advertised when vehicle recognises a previously paired phone
    val RECONNECTION_SERVICE_UUID: UUID = UUID.fromString("5E2A68A5-27BE-43F9-8D1E-4546976FABD7")

    // V2 service (newer protocol variant also present in classes4.dex)
    val V2_SERVICE_UUID: UUID = UUID.fromString("5EFD8B16-21D6-4FB1-B00A-A904720D1320")

    // Characteristics — phone writes to car (WRITE / WRITE_NO_RESPONSE)
    // Actual UUIDs to be confirmed via GATT dump during pairing window
    val CHAR_CLIENT_WRITE: UUID = UUID.fromString("74BCDADC-2FDC-4BB3-8459-76D06952A0E9")

    // Characteristics — car writes to phone (NOTIFY / INDICATE)
    val CHAR_SERVER_WRITE: UUID = UUID.fromString("85DFF28B-3036-4662-BB22-BAA7F898DC47")

    // Additional characteristic candidates from classes4.dex (role TBD via GATT dump)
    val CHAR_EXTRA_1: UUID = UUID.fromString("87749DF4-7CCF-48F8-AA87-704BAD0E0E16")
    val CHAR_EXTRA_2: UUID = UUID.fromString("892AC5D9-E9A5-48DC-874A-C01E3CB00D5D")
    val CHAR_EXTRA_3: UUID = UUID.fromString("9188040D-6C67-4C5B-B112-36A304B66DAD")
    val CHAR_EXTRA_4: UUID = UUID.fromString("9EB6528D-BB65-4239-B196-6789196CF2A9")

    // CCCD descriptor for enabling notifications (standard BLE)
    val DESC_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // Keep legacy aliases so callers compile without change (will be wired to real chars after GATT dump)
    val CHAR_CHALLENGE: UUID = CHAR_SERVER_WRITE   // car → phone challenge
    val CHAR_RESPONSE:  UUID = CHAR_CLIENT_WRITE   // phone → car signed response
    val CHAR_COMMAND:   UUID = CHAR_CLIENT_WRITE   // phone → car lock/unlock command
    val CHAR_STATUS:    UUID = CHAR_EXTRA_1
    val CHAR_CHARGING:  UUID = CHAR_EXTRA_2

    /** All service UUIDs to include in the BLE scan filter. */
    val SCAN_SERVICE_UUIDS = listOf(SERVICE_UUID, RECONNECTION_SERVICE_UUID, V2_SERVICE_UUID)

    object Commands {
        const val LOCK: Byte = 0x01
        const val UNLOCK: Byte = 0x02
        const val CHARGING_STATUS_REQUEST: Byte = 0x10
        const val SET_CHARGE_LIMIT: Byte = 0x11
        const val PAIRING_REQUEST: Byte = 0x20
    }

    object StatusBytes {
        const val LOCKED: Byte = 0x01
        const val UNLOCKED: Byte = 0x02
    }

    fun buildCommand(cmd: Byte, vararg params: Byte): ByteArray =
        byteArrayOf(cmd, *params)

    fun buildChargeLimitCommand(limitPercent: Int): ByteArray =
        byteArrayOf(Commands.SET_CHARGE_LIMIT, limitPercent.coerceIn(20, 100).toByte())

    fun parseVehicleStatus(bytes: ByteArray): Byte =
        if (bytes.isNotEmpty()) bytes[0] else 0x00

    fun parseChargingData(bytes: ByteArray): Triple<Int, Int, Float> {
        if (bytes.size < 6) return Triple(-1, -1, 0f)
        val soc = bytes[0].toInt() and 0xFF
        val rangeKm = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val chargeKw = ((bytes[3].toInt() and 0xFF) shl 8 or (bytes[4].toInt() and 0xFF)) / 10f
        return Triple(soc, rangeKm, chargeKw)
    }
}
