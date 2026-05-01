package com.gmc.digitalkey.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val modelKey: String,       // e.g. "hummer_ev_pickup", "sierra_ev"
    val year: Int,
    val bleAddress: String,
    val vinPrefix: String,
    val publicKeyBytes: ByteArray,
    val pairedAt: Long = System.currentTimeMillis(),
    val passiveUnlockEnabled: Boolean = false,
    val lastKnownSoc: Int = -1,
    val lastKnownRangeKm: Int = -1,
    val lastSeenAt: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VehicleEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
