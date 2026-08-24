package com.rillmaster.pipanel.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Relevé de métriques système du Raspberry Pi, collecté par MonitoringWorker.
 */
@Entity(tableName = "metrics")
data class MetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val tempCelsius: Double,
    val cpuPercent: Int,
    val ramUsedMb: Int,
    val ramTotalMb: Int
)
