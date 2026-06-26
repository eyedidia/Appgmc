package com.gmc.digitalkey.ble

import java.util.UUID

object VehicleGattProfile {

    // ── Confirmed from GATT dump of 2026 GMC Sierra EV (VIN 1GT4EVEL2TU400190) ──
    //
    // Device MAC 71:6E:B3:C7:71:9F (resolvable private address — changes after pairing)
    // Advertising service UUID: 48B42B00-5640-4344-AD48-890CF2D43169
    //
    // The vehicle infotainment responded with "לא ניתן להתחבר" (Cannot connect) when our
    // app connected without credentials — confirming the BLE stack is live and protected.
    //
    // SERVICE 48B42B00-5640-4344-AD48-890CF2D43169 (GM Digital Key main service):
    //   CHAR 5E2A68A5 [READ|NOTIFY]   — vehicle → phone  (challenge, response, status events)
    //   CHAR 5E2A68A6 [WRITE|WRITE_NR] — phone → vehicle  (pairing request, signed response, commands)
    //   CHAR 2A05     [READ|NOTIFY]   — Service Changed (standard BLE)
    //
    // SERVICE 1801 (Generic Attribute):
    //   CHAR 2B3A [READ]       — Server Supported Features (BLE 5.x)
    //   CHAR 2B29 [READ|WRITE] — Client Supported Features (BLE 5.x robust caching)
    //   CHAR 2B2A [READ]       — Database Hash (BLE 5.x)

    /** GM Digital Key main service — advertised by Sierra EV (and likely all Ultium BLE DK vehicles). */
    val SERVICE_UUID: UUID = UUID.fromString("48B42B00-5640-4344-AD48-890CF2D43169")

    // Characteristics inside SERVICE_UUID:
    /** Vehicle → phone: challenge, pairing response, status events (READ + NOTIFY). */
    val CHAR_SERVER_WRITE: UUID = UUID.fromString("5E2A68A5-27BE-43F9-8D1E-4546976FABD7")

    /** Phone → vehicle: pairing request, signed challenge response, lock/unlock commands (WRITE + WRITE_NR). */
    val CHAR_CLIENT_WRITE: UUID = UUID.fromString("5E2A68A6-27BE-43F9-8D1E-4546976FABD7")

    // ── Sierra EV VCIM normal-mode channel (device name "TY", MAC 38:2C:E5:05:0F:9D) ──
    // Confirmed from GATT dump 2026 GMC Sierra EV. Service 0x1910 is always advertising.
    // Pairing (enrollment) uses 48B42B00 above; daily-use reconnect uses this service.
    // 0x2B11 = phone → VCIM (WRITE | WRITE_NO_RESPONSE)
    // 0x2B10 = VCIM → phone (NOTIFY)
    val SERVICE_VCIM_1910: UUID = UUID.fromString("00001910-0000-1000-8000-00805F9B34FB")
    val CHAR_VCIM_WRITE: UUID   = UUID.fromString("00002B11-0000-1000-8000-00805F9B34FB")
    val CHAR_VCIM_NOTIFY: UUID  = UUID.fromString("00002B10-0000-1000-8000-00805F9B34FB")

    // ── Secondary BLE device observed nearby (FC:B8:B1:B8:46:D1) ──────────────
    // Separate physical BLE module on the vehicle (possibly TPMS, OTA DFU, or RKE module).
    // Nordic DFU service FE59 also present on that device — do NOT interact with DFU.
    val SERVICE_DIRECT_DK: UUID  = UUID.fromString("4CDABAA0-2CEA-C0C1-B38D-A0481AE60A97")
    val CHAR_DIRECT_DK_TX: UUID = UUID.fromString("4CDABAA1-2CEA-C0C1-B38D-A0481AE60A97")  // WRITE_NR (phone → device)
    val CHAR_DIRECT_DK_RX: UUID = UUID.fromString("4CDABAA2-2CEA-C0C1-B38D-A0481AE60A97")  // NOTIFY  (device → phone)

    // ── Unconfirmed UUIDs from myGMC APK (classes4.dex) — kept for reference ──
    // These are present in the APK bytecode but haven't been observed on the real vehicle GATT yet.
    val V2_SERVICE_UUID: UUID = UUID.fromString("5EFD8B16-21D6-4FB1-B00A-A904720D1320")
    val VIPKIT_UUID_1: UUID = UUID.fromString("7AA4B91C-3888-4A3D-A448-3BF3402A6C0F")
    val VIPKIT_UUID_2: UUID = UUID.fromString("BAF7B76F-D419-40CE-8AEB-2B80C6510123")
    val VIPKIT_UUID_3: UUID = UUID.fromString("24289B40-AF40-4149-A5F4-878CCFF87566")

    // CCCD descriptor for enabling notifications (standard BLE)
    val DESC_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // Aliases used throughout BleManager and command classes
    val CHAR_CHALLENGE: UUID = CHAR_SERVER_WRITE   // car → phone: challenge bytes
    val CHAR_RESPONSE:  UUID = CHAR_CLIENT_WRITE   // phone → car: signed response
    val CHAR_COMMAND:   UUID = CHAR_CLIENT_WRITE   // phone → car: lock/unlock/etc commands
    val CHAR_STATUS:    UUID = CHAR_SERVER_WRITE   // car → phone: status events (same channel)
    val CHAR_CHARGING:  UUID = CHAR_SERVER_WRITE   // car → phone: charging events (same channel)

    // ── FD06-series BLE stack (GR-AC devices: Bolt EV / Equinox / older models) ─
    // Observed: "GR-AC_10001_09_9bf1_SC", MAC 94:24:B8:45:9B:F1
    // Base UUID: 173C-93D2-488E-FE144D2E12A2 (GM proprietary base)
    val SERVICE_FD06: UUID   = UUID.fromString("0000FD06-173C-93D2-488E-FE144D2E12A2")
    val CHAR_FD03_WRITE: UUID = UUID.fromString("0000FD03-0000-1000-8000-00805F9B34FB")  // phone → vehicle
    val CHAR_FD04_NOTIFY: UUID = UUID.fromString("0000FD04-0000-1000-8000-00805F9B34FB") // vehicle → phone
    val CHAR_FD05_INFO: UUID  = UUID.fromString("0000FD05-0000-1000-8000-00805F9B34FB")  // static (VIN? token?)
    val CHAR_FD06_INFO: UUID  = UUID.fromString("0000FD06-0000-1000-8000-00805F9B34FB")  // static
    val CHAR_FD07_INFO: UUID  = UUID.fromString("0000FD07-0000-1000-8000-00805F9B34FB")  // static

    /** Service UUIDs to flag in BLE scan — device lit green in raw scan when advertising any of these. */
    val SCAN_SERVICE_UUIDS = listOf(
        SERVICE_UUID,                               // 48B42B00 — confirmed pairing/enrollment service (Sierra EV)
        SERVICE_VCIM_1910,                          // 0x1910   — confirmed normal-mode VCIM service (Sierra EV "TY")
        SERVICE_FD06,                               // FD06     — confirmed GR-AC devices (older/different protocol)
        SERVICE_DIRECT_DK,                          // 4CDABAA0 — secondary BLE module
        V2_SERVICE_UUID,                            // 5EFD8B16 — unconfirmed V2 variant
        VIPKIT_UUID_1, VIPKIT_UUID_2, VIPKIT_UUID_3 // VipKit (unconfirmed)
    )

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
