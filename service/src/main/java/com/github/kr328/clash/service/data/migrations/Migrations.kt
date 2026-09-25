package com.github.kr328.clash.service.data.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE imported ADD COLUMN ageSecretKey TEXT")
        database.execSQL("ALTER TABLE pending ADD COLUMN ageSecretKey TEXT")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `rule_override` (
                `id` TEXT NOT NULL,
                `profileUuid` TEXT NOT NULL,
                `position` TEXT NOT NULL,
                `ruleType` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `policy` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_rule_override_profileUuid` ON `rule_override` (`profileUuid`)"
        )
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `rule_provider` (
                `id` TEXT NOT NULL,
                `profileUuid` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `behavior` TEXT NOT NULL,
                `format` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `updateIntervalSeconds` INTEGER,
                `sortOrder` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_rule_provider_profileUuid` ON `rule_provider` (`profileUuid`)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_rule_provider_profileUuid_name` ON `rule_provider` (`profileUuid`, `name`)"
        )
    }
}

val MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
)

val LEGACY_MIGRATION = ::migrationFromLegacy
