package com.gmc.digitalkey.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {

    @Query("SELECT * FROM vehicles ORDER BY pairedAt DESC")
    fun observeAll(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles ORDER BY pairedAt DESC")
    suspend fun getAll(): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun getById(id: String): VehicleEntity?

    @Query("SELECT * FROM vehicles WHERE bleAddress = :address LIMIT 1")
    suspend fun getByBleAddress(address: String): VehicleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vehicle: VehicleEntity)

    @Update
    suspend fun update(vehicle: VehicleEntity)

    @Query("UPDATE vehicles SET lastKnownSoc = :soc, lastKnownRangeKm = :rangeKm, lastSeenAt = :ts WHERE id = :id")
    suspend fun updateEvStatus(id: String, soc: Int, rangeKm: Int, ts: Long)

    @Query("UPDATE vehicles SET passiveUnlockEnabled = :enabled WHERE id = :id")
    suspend fun setPassiveUnlock(id: String, enabled: Boolean)

    @Delete
    suspend fun delete(vehicle: VehicleEntity)

    @Query("DELETE FROM vehicles WHERE id = :id")
    suspend fun deleteById(id: String)
}
