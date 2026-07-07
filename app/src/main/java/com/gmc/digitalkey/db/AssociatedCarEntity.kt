package com.gmc.digitalkey.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores CDP association data for a paired vehicle.
 * Schema matches the `associated_cars` and `credentials` tables observed in the myGMC APK:
 *   associated_cars (id, encryptionKey, identificationKey, name, macAddress, isUserRenamed)
 *   credentials     (carId, token, handle)
 */
@Entity(tableName = "associated_cars")
data class AssociatedCarEntity(
    @PrimaryKey val id: String,        // vehicleId (same as VehicleEntity.id)
    val macAddress: String,            // BLE MAC at time of pairing
    val encryptionKey: ByteArray,      // D2D encode key (32 bytes)
    val identificationKey: ByteArray,  // D2D decode key (32 bytes)
    val name: String = "",             // vehicle display name (from vehicle or user-set)
    val isUserRenamed: Boolean = false,
    val escrowToken: ByteArray = ByteArray(0),  // token from TD_ESCROW_TOKEN
    val tokenHandle: ByteArray = ByteArray(0),  // handle for unlock credentials
    val pairedAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssociatedCarEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
