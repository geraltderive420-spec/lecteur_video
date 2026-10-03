package com.lecteur.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration1To2KeepsWatchStatesAndAddsDisplayMode() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO library_folders (id, uri, displayPath, category, enabled) VALUES (1, 'u', '/d', 'MOVIES', 1)")
            execSQL(
                "INSERT INTO media_files (id, folderId, uri, displayPath, fileName, size, fingerprint, lastModified, videoCodec, hdrType, addedAt, isAvailable) " +
                    "VALUES (1, 1, 'uri://f', '/f', 'f.mkv', 10, 'fp', 1, 'UNKNOWN', 'NONE', 1, 1)"
            )
            execSQL(
                "INSERT INTO watch_states (id, mediaFileId, positionMs, durationMs, isCompleted, playCount, lastWatchedAt, audioDelayMs, subtitleDelayMs) " +
                    "VALUES (1, 1, 500, 1000, 0, 0, 1, 0, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-test", 2, true, Migrations.MIGRATION_1_2)

        db.query("SELECT positionMs, displayMode FROM watch_states WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(500)
            assertThat(c.isNull(1)).isTrue()
        }
    }

    @Test
    fun migration2To3KeepsRowsAndAddsAliasesAndAttemptColumn() {
        helper.createDatabase("migration-test-3", 2).apply {
            execSQL("INSERT INTO movies (id, title, sortTitle, addedAt, matchState, matchLocked) VALUES (1, 'Dune', 'Dune', 1, 'IDENTIFIED', 1)")
            execSQL("INSERT INTO series (id, title, sortTitle, addedAt, matchState, matchLocked) VALUES (1, 'Show', 'Show', 1, 'UNIDENTIFIED', 0)")
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-test-3", 3, true, Migrations.MIGRATION_2_3)

        db.query("SELECT title, matchLocked, matchAttemptedAt FROM movies WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Dune")
            assertThat(c.getInt(1)).isEqualTo(1)
            assertThat(c.isNull(2)).isTrue()
        }
        // The cascade itself is exercised on a real Room database in LibraryDaosTest (foreign keys are off on this raw helper)
        db.execSQL("INSERT INTO series_aliases (alias, seriesId) VALUES ('show', 1)")
        db.query("SELECT seriesId FROM series_aliases WHERE alias = 'show'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getLong(0)).isEqualTo(1)
        }
    }

    @Test
    fun migration3To4AllowsTheSameFolderUnderSeveralCategories() {
        helper.createDatabase("migration-test-4", 3).apply {
            execSQL("INSERT INTO library_folders (id, uri, displayPath, category, enabled) VALUES (1, 'tree://media', '/media', 'MOVIES', 1)")
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-test-4", 4, true, Migrations.MIGRATION_3_4)

        db.execSQL("INSERT INTO library_folders (id, uri, displayPath, category, enabled) VALUES (2, 'tree://media', '/media', 'SERIES', 1)")
        db.query("SELECT COUNT(*) FROM library_folders WHERE uri = 'tree://media'").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(2)
        }
        // ... but not twice under the same category
        val duplicate = runCatching {
            db.execSQL("INSERT INTO library_folders (id, uri, displayPath, category, enabled) VALUES (3, 'tree://media', '/media', 'SERIES', 1)")
        }
        assertThat(duplicate.isFailure).isTrue()
    }
}
