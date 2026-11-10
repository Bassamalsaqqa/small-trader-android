package com.example.data.mobile.outbox

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration definitions for the OutboxDatabase.
 *
 * Upgrade Strategy:
 * Schema migrations must strictly preserve all existing queued records in
 * outbox_events and outbox_delivery_states.
 * Destructive fallback (fallbackToDestructiveMigration) is strictly prohibited.
 */
object OutboxMigrations {

    /**
     * Migration from Version 1 to Version 2:
     * Adds an optional routing_tag column to outbox_delivery_states,
     * demonstrating upgrade compatibility while preserving all immutable event payloads.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE outbox_delivery_states ADD COLUMN routing_tag TEXT DEFAULT NULL")
        }
    }
}
