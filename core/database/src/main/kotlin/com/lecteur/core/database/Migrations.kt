package com.lecteur.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {

    /** v1 -> v2: per-media display mode (fit / fill / stretch...) remembered by the player. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE watch_states ADD COLUMN displayMode TEXT")
        }
    }

    /** v2 -> v3: series aliases (episodes find their series again) and the last online match attempt. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE movies ADD COLUMN matchAttemptedAt INTEGER")
            db.execSQL("ALTER TABLE series ADD COLUMN matchAttemptedAt INTEGER")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS series_aliases (" +
                    "alias TEXT NOT NULL, seriesId INTEGER NOT NULL, PRIMARY KEY(alias), " +
                    "FOREIGN KEY(seriesId) REFERENCES series(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_series_aliases_seriesId ON series_aliases (seriesId)")
        }
    }

    /** v3 -> v4: a folder can be added once per category, so uniqueness moves from (uri) to (uri, category). */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS index_library_folders_uri")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_library_folders_uri_category ON library_folders (uri, category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_library_folders_uri ON library_folders (uri)")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
