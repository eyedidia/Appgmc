package com.gmc.digitalkey.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AssociatedCarDao {
    @Upsert
    suspend fun upsert(car: AssociatedCarEntity)

    @Query("SELECT * FROM associated_cars WHERE id = :vehicleId LIMIT 1")
    suspend fun findById(vehicleId: String): AssociatedCarEntity?

    @Query("SELECT * FROM associated_cars WHERE macAddress = :mac LIMIT 1")
    suspend fun findByMac(mac: String): AssociatedCarEntity?

    @Query("SELECT * FROM associated_cars")
    fun observeAll(): Flow<List<AssociatedCarEntity>>

    @Delete
    suspend fun delete(car: AssociatedCarEntity)

    @Query("DELETE FROM associated_cars WHERE id = :vehicleId")
    suspend fun deleteById(vehicleId: String)
}
