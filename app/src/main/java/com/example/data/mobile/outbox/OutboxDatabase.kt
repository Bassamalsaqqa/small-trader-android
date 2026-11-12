package com.example.data.mobile.outbox

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for persistent, bounded offline sales outbox.
 *
 * CRITICAL DURABILITY GUARANTEES:
 * 1. Schema version 1 is frozen and exported to schemas/ for reproducible verification.
 * 2. Destructive migration fallbacks (fallbackToDestructiveMigration) are FORBIDDEN.
 *    Any unhandled schema change must throw an error rather than silently deleting un-synced economic events.
 * 3. Database open/startup never deletes or resets the queue.
 */
@Database(
    entities = [
        OutboxEventEntity::class,
        OutboxDeliveryStateEntity::class,
        OutboxScopeLockEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class OutboxDatabase : RoomDatabase() {

    abstract fun outboxDao(): OutboxDao

    companion object {
        const val DATABASE_NAME = "small_trader_outbox.db"

        fun buildDatabase(
            context: Context,
            name: String = DATABASE_NAME,
            allowMainThreadQueries: Boolean = false
        ): OutboxDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                OutboxDatabase::class.java,
                name
            )
            if (allowMainThreadQueries) {
                builder.allowMainThreadQueries()
            }
            // Explicitly NO fallbackToDestructiveMigration
            return builder.build()
        }

        fun buildInMemory(
            context: Context,
            allowMainThreadQueries: Boolean = true
        ): OutboxDatabase {
            val builder = Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                OutboxDatabase::class.java
            )
            if (allowMainThreadQueries) {
                builder.allowMainThreadQueries()
            }
            return builder.build()
        }
    }
}
