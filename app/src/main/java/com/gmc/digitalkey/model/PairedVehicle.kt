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

enum class GmcEvModel(
    val displayName: String,
    val bleProfile: String,
    val brand: String = "GMC",
    val supportsRemoteStart: Boolean = true
) {
    // GMC
    HUMMER_EV_PICKUP("Hummer EV Pickup", "GM-DKEV-01"),
    HUMMER_EV_SUV("Hummer EV SUV", "GM-DKEV-01"),
    SIERRA_EV_DENALI("Sierra EV Denali", "GM-DKEV-02"),
    TERRAIN_EV("Terrain EV", "GM-DKEV-02"),
    // Chevrolet
    SILVERADO_EV("Silverado EV", "GM-DKEV-02", "Chevrolet"),
    BLAZER_EV("Blazer EV", "GM-DKEV-03", "Chevrolet"),
    EQUINOX_EV("Equinox EV", "GM-DKEV-03", "Chevrolet"),
    // Cadillac
    LYRIQ("LYRIQ", "GM-DKEV-03", "Cadillac"),
    OPTIQ("OPTIQ", "GM-DKEV-03", "Cadillac"),
    VISTIQ("VISTIQ", "GM-DKEV-03", "Cadillac"),
    ESCALADE_IQ("ESCALADE IQ", "GM-DKEV-01", "Cadillac");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.name == key } ?: HUMMER_EV_PICKUP
    }
}
