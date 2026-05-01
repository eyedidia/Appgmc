package com.gmc.digitalkey.model

data class PairedVehicle(
    val id: String,
    val displayName: String,
    val model: GmcEvModel,
    val year: Int,
    val bleAddress: String,
    val vinPrefix: String,
    val passiveUnlockEnabled: Boolean = false
)

enum class GmcEvModel(
    val displayName: String,
    val bleProfile: String,
    val supportsRemoteStart: Boolean = true
) {
    HUMMER_EV_PICKUP("Hummer EV Pickup", "GM-DKEV-01"),
    HUMMER_EV_SUV("Hummer EV SUV", "GM-DKEV-01"),
    SIERRA_EV_DENALI("Sierra EV Denali", "GM-DKEV-02"),
    TERRAIN_EV("Terrain EV", "GM-DKEV-02");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.name == key } ?: HUMMER_EV_PICKUP
    }
}
