package com.rillmaster.pipanel.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MetricEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun metricDao(): MetricDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Rétention des métriques : 7 jours. */
        const val RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pipanel.db"
                ).build().also { INSTANCE = it }
            }
    }
}
