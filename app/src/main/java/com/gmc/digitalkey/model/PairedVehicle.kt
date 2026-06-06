package com.gmc.digitalkey.model

data class PairedVehicle(
    val id: String,
    val displayName: String,
    val model: GmcEvModel,
    val year: Int,
    val bleAddress: String,
    val vin: String,
    val imageUrl: String = "",
    val passiveUnlockEnabled: Boolean = false
)

/**
 * Hint to OBD2 activation: which CAN bus protocol to try first.
 * AUTO = let Obd2Manager detect (ATSP0 / ATSP7 probe).
 * PREFER_29BIT = try ISO 15765-4 29-bit (ATSP7) first — Sierra EV, Silverado EV.
 * PREFER_11BIT = try ISO 15765-4 11-bit (ATSP6) first — older VCIM variants.
 */
enum class CanHint { AUTO, PREFER_29BIT, PREFER_11BIT }

enum class GmcEvModel(
    val displayName: String,
    val brand: String = "GMC",
    val supportsRemoteStart: Boolean = true,
    /**
     * CAN protocol preference for OBD2 activation.
     * Only used as a starting point — the manager always falls back to AUTO on failure.
     */
    val canHint: CanHint = CanHint.AUTO,
    /**
     * Known VCIM/BECM ECU address for this model (decimal byte used with ATSH DAxxF1 in 29-bit mode).
     * null = run full ECU discovery scan (slower but model-agnostic).
     * Confirmed: 0x80 on Sierra EV Denali (1GT4EVEL*, 2026) via 29-bit broadcast 18DB33F1.
     */
    val knownVcimAddr: Int? = null,
    /**
     * DID (Data Identifier) written to enable BLE Digital Key.
     * Stored as a 2-byte hex pair — "F1A0" confirmed on Sierra EV ECU 0x80.
     * All other models default to "F1A0" until confirmed otherwise.
     */
    val activationDid: String = "F1A0"
) {
    // ── GMC ──────────────────────────────────────────────────────────────────
    HUMMER_EV_PICKUP (
        "Hummer EV Pickup",
        canHint = CanHint.AUTO,
        knownVcimAddr = null    // TODO: confirm ECU address on Hummer EV
    ),
    HUMMER_EV_SUV (
        "Hummer EV SUV",
        canHint = CanHint.AUTO,
        knownVcimAddr = null
    ),
    SIERRA_EV_DENALI (
        "Sierra EV Denali",
        canHint = CanHint.PREFER_29BIT,
        knownVcimAddr = 0x80    // confirmed: VIN 1GT4EVEL2TU400190, 2026
    ),
    TERRAIN_EV (
        "Terrain EV",
        canHint = CanHint.AUTO,
        knownVcimAddr = null
    ),

    // ── Chevrolet ─────────────────────────────────────────────────────────
    SILVERADO_EV (
        "Silverado EV",
        brand = "Chevrolet",
        canHint = CanHint.PREFER_29BIT,
        knownVcimAddr = null    // TODO: confirm — likely same ECU range as Sierra EV
    ),
    BLAZER_EV (
        "Blazer EV",
        brand = "Chevrolet",
        canHint = CanHint.AUTO,
        knownVcimAddr = null
    ),
    EQUINOX_EV (
        "Equinox EV",
        brand = "Chevrolet",
        canHint = CanHint.AUTO,
        knownVcimAddr = null
    ),

    // ── Cadillac ──────────────────────────────────────────────────────────
    LYRIQ (
        "LYRIQ",
        brand = "Cadillac",
        canHint = CanHint.AUTO,
        knownVcimAddr = null
    ),
    OPTIQ (
        "OPTIQ",
        brand = "Cadillac",
        canHint = CanHint.AUTO,
        knownVcimAddr = null
    ),
    VISTIQ (
        "VISTIQ",
        brand = "Cadillac",
        canHint = CanHint.AUTO,
        knownVcimAddr = null
    ),
    ESCALADE_IQ (
        "ESCALADE IQ",
        brand = "Cadillac",
        canHint = CanHint.AUTO,
        knownVcimAddr = null
    );

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.name == key } ?: HUMMER_EV_PICKUP
    }
}
