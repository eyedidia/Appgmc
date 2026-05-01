package com.gmc.digitalkey.ble

import java.util.UUID

object VehicleGattProfile {

    // GM Digital Key primary service
    val SERVICE_UUID: UUID = UUID.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

    // Challenge from vehicle (READ + NOTIFY)
    val CHAR_CHALLENGE: UUID = UUID.fromString("0000FE2D-0000-1000-8000-00805F9B34FB")

    // Signed response from phone (WRITE)
    val CHAR_RESPONSE: UUID = UUID.fromString("0000FE2E-0000-1000-8000-00805F9B34FB")

    // Command bytes (WRITE WITHOUT RESPONSE)
    val CHAR_COMMAND: UUID = UUID.fromString("0000FE2F-0000-1000-8000-00805F9B34FB")

    // Vehicle status updates (READ + NOTIFY)
    val CHAR_STATUS: UUID = UUID.fromString("0000FE30-0000-1000-8000-00805F9B34FB")

    // EV charging data (READ + NOTIFY)
    val CHAR_CHARGING: UUID = UUID.fromString("0000FE31-0000-1000-8000-00805F9B34FB")

    // CCCD descriptor for enabling notifications
    val DESC_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    object Commands {
        const val LOCK: Byte = 0x01
        const val UNLOCK: Byte = 0x02
        const val REMOTE_START: Byte = 0x03
        const val REMOTE_STOP: Byte = 0x04
        const val CHARGING_STATUS_REQUEST: Byte = 0x10
        const val SET_CHARGE_LIMIT: Byte = 0x11
        const val HORN_LIGHTS: Byte = 0x05
        const val PAIRING_REQUEST: Byte = 0x20
    }

    object StatusBytes {
        const val LOCKED: Byte = 0x01
        const val UNLOCKED: Byte = 0x02
        const val ENGINE_OFF: Byte = 0x10
        const val ENGINE_STARTING: Byte = 0x11
        const val ENGINE_RUNNING: Byte = 0x12
    }

    fun buildCommand(cmd: Byte, vararg params: Byte): ByteArray =
        byteArrayOf(cmd, *params)

    fun buildChargeLimitCommand(limitPercent: Int): ByteArray =
        byteArrayOf(Commands.SET_CHARGE_LIMIT, limitPercent.coerceIn(20, 100).toByte())

    fun parseVehicleStatus(bytes: ByteArray): Pair<Byte, Byte> {
        val lockByte = if (bytes.isNotEmpty()) bytes[0] else 0x00
        val engineByte = if (bytes.size > 1) bytes[1] else 0x00
        return lockByte to engineByte
    }

    fun parseChargingData(bytes: ByteArray): Triple<Int, Int, Float> {
        if (bytes.size < 6) return Triple(-1, -1, 0f)
        val soc = bytes[0].toInt() and 0xFF
        val rangeKm = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
        val chargeKw = ((bytes[3].toInt() and 0xFF) shl 8 or (bytes[4].toInt() and 0xFF)) / 10f
        return Triple(soc, rangeKm, chargeKw)
    }
}
