package com.barbarycoast.openclawvoice.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speaker_profiles")
data class SpeakerProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** MFCC embedding stored as comma-separated floats */
    val embedding: String
) {
    fun embeddingVector(): FloatArray =
        embedding.split(",").map { it.toFloat() }.toFloatArray()

    companion object {
        fun fromVector(name: String, vector: FloatArray): SpeakerProfile =
            SpeakerProfile(
                name = name,
                embedding = vector.joinToString(",")
            )
    }
}
