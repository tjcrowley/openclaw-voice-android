package com.barbarycoast.openclawvoice.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SpeakerDao {
    @Query("SELECT * FROM speaker_profiles")
    suspend fun getAll(): List<SpeakerProfile>

    @Query("SELECT COUNT(*) FROM speaker_profiles")
    suspend fun count(): Int

    @Insert
    suspend fun insert(profile: SpeakerProfile): Long

    @Delete
    suspend fun delete(profile: SpeakerProfile)

    @Query("DELETE FROM speaker_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}
