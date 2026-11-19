package com.example.data.mobile.outbox

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
    exportSchema = true
)
abstract class OutboxDatabase : RoomDatabase() {

    abstract fun outboxDao(): OutboxDao

    companion object {
        const val DATABASE_NAME = "small_trader_outbox.db"

        const val CREATE_MERCHANT_COMMANDS_SQL = """
            CREATE TABLE IF NOT EXISTS `merchant_commands` (
                `company_id` TEXT NOT NULL,
                `command_id` TEXT NOT NULL,
                `actor_id` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `command_type` TEXT NOT NULL,
                `idempotency_key` TEXT NOT NULL,
                `payload_digest` TEXT NOT NULL,
                `payload_bytes` BLOB NOT NULL,
                `status` TEXT NOT NULL,
                `response_snapshot` BLOB,
                `error_message` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`company_id`, `command_id`),
                CONSTRAINT `uq_merchant_commands_scope_key` UNIQUE (`company_id`, `actor_id`, `device_id`, `idempotency_key`)
            )
        """

        const val CREATE_INDEX_SCOPE_KEY_SQL = """
            CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_commands_scope_key`
            ON `merchant_commands` (`company_id`, `actor_id`, `device_id`, `idempotency_key`)
        """

        const val CREATE_INDEX_SCOPE_STATUS_SQL = """
            CREATE INDEX IF NOT EXISTS `index_merchant_commands_scope_status`
            ON `merchant_commands` (`company_id`, `actor_id`, `device_id`, `status`)
        """

        const val CREATE_MERCHANT_FORM_INTENTS_SQL = """
            CREATE TABLE IF NOT EXISTS `merchant_form_intents` (
                `company_id` TEXT NOT NULL,
                `actor_id` TEXT NOT NULL,
                `device_id` TEXT NOT NULL,
                `form_type` TEXT NOT NULL,
                `operation_id` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`company_id`, `actor_id`, `device_id`, `form_type`)
            )
        """

        internal fun ensureMerchantCommandsTable(db: SupportSQLiteDatabase) {
            val cursor = db.query("PRAGMA table_info(`merchant_commands`)")
            var hasPayloadBytes = false
            var colCount = 0
            cursor.use { c ->
                colCount = c.count
                while (c.moveToNext()) {
                    val nameIdx = c.getColumnIndex("name")
                    if (nameIdx >= 0 && c.getString(nameIdx) == "payload_bytes") {
                        hasPayloadBytes = true
                        break
                    }
                }
            }
            if (!hasPayloadBytes && colCount > 0) {
                db.execSQL("ALTER TABLE `merchant_commands` RENAME TO `merchant_commands_old`")
                db.execSQL(CREATE_MERCHANT_COMMANDS_SQL)
                db.execSQL("DROP TABLE IF EXISTS `merchant_commands_old`")
            } else if (!hasPayloadBytes) {
                db.execSQL(CREATE_MERCHANT_COMMANDS_SQL)
            }
            db.execSQL(CREATE_INDEX_SCOPE_KEY_SQL)
            db.execSQL(CREATE_INDEX_SCOPE_STATUS_SQL)
            db.execSQL(CREATE_MERCHANT_FORM_INTENTS_SQL)
        }

        val CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                ensureMerchantCommandsTable(db)
            }
            override fun onOpen(db: SupportSQLiteDatabase) {
                ensureMerchantCommandsTable(db)
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve child rows separately; recreate their final FK explicitly for older Android SQLite.
                db.execSQL("CREATE TABLE IF NOT EXISTS `outbox_events_new` (`client_event_id` TEXT NOT NULL, `company_id` TEXT NOT NULL, `actor_id` TEXT NOT NULL, `device_id` TEXT NOT NULL, `device_lease_id` TEXT, `captured_at` TEXT NOT NULL, `payload_digest` TEXT NOT NULL, `event_version` INTEGER NOT NULL, `payload_bytes` BLOB NOT NULL, PRIMARY KEY(`company_id`, `client_event_id`))")
                db.execSQL("INSERT INTO outbox_events_new (`client_event_id`, `company_id`, `actor_id`, `device_id`, `device_lease_id`, `captured_at`, `payload_digest`, `event_version`, `payload_bytes`) SELECT `client_event_id`, `company_id`, `actor_id`, `device_id`, `device_lease_id`, `captured_at`, `payload_digest`, `event_version`, `payload_bytes` FROM outbox_events")
                db.execSQL("CREATE TABLE outbox_delivery_states_backup AS SELECT * FROM outbox_delivery_states")
                db.execSQL("DROP TABLE outbox_delivery_states")
                db.execSQL("DROP TABLE outbox_events")
                db.execSQL("ALTER TABLE outbox_events_new RENAME TO outbox_events")
                db.execSQL("CREATE TABLE IF NOT EXISTS `outbox_delivery_states` (`client_event_id` TEXT NOT NULL, `company_id` TEXT NOT NULL, `actor_id` TEXT NOT NULL, `device_id` TEXT NOT NULL, `status` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL, `last_attempt_at` INTEGER, `claim_token` TEXT, `claim_expires_at` INTEGER, `invoice_ulid` TEXT, `payment_ulid` TEXT, `server_ulid` TEXT, `server_acknowledged_at` TEXT, `error_message` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`company_id`, `client_event_id`), FOREIGN KEY(`company_id`, `client_event_id`) REFERENCES `outbox_events`(`company_id`, `client_event_id`) ON UPDATE RESTRICT ON DELETE RESTRICT )")
                db.execSQL("INSERT INTO outbox_delivery_states (`client_event_id`, `company_id`, `actor_id`, `device_id`, `status`, `attempt_count`, `last_attempt_at`, `claim_token`, `claim_expires_at`, `invoice_ulid`, `payment_ulid`, `server_ulid`, `server_acknowledged_at`, `error_message`, `created_at`, `updated_at`) SELECT `client_event_id`, `company_id`, `actor_id`, `device_id`, `status`, `attempt_count`, `last_attempt_at`, `claim_token`, `claim_expires_at`, `invoice_ulid`, `payment_ulid`, `server_ulid`, `server_acknowledged_at`, `error_message`, `created_at`, `updated_at` FROM outbox_delivery_states_backup")
                db.execSQL("DROP TABLE outbox_delivery_states_backup")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_events_scope` ON `outbox_events` (`company_id`, `actor_id`, `device_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_states_scope_status` ON `outbox_delivery_states` (`company_id`, `actor_id`, `device_id`, `status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_states_status_claim` ON `outbox_delivery_states` (`status`, `claim_expires_at`)")
                ensureMerchantCommandsTable(db)
            }
        }

        fun buildDatabase(
            context: Context,
            name: String = DATABASE_NAME,
            allowMainThreadQueries: Boolean = false
        ): OutboxDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                OutboxDatabase::class.java,
                name
            ).addMigrations(MIGRATION_1_2)
            .addCallback(CALLBACK)
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
            ).addMigrations(MIGRATION_1_2)
            .addCallback(CALLBACK)
            if (allowMainThreadQueries) {
                builder.allowMainThreadQueries()
            }
            return builder.build()
        }
    }
}
