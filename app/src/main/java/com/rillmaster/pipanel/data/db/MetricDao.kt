package com.rillmaster.pipanel.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MetricDao {

    @Insert
    suspend fun insert(metric: MetricEntity)

    /** Métriques depuis [since] (epoch millis), triées chronologiquement. */
    @Query("SELECT * FROM metrics WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun getSince(since: Long): Flow<List<MetricEntity>>

    /** Supprime les relevés plus vieux que [before] (rétention). */
    @Query("DELETE FROM metrics WHERE timestamp < :before")
    suspend fun pruneOlderThan(before: Long)
}
