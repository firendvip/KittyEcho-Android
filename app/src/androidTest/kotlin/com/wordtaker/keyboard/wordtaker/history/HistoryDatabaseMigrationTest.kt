package com.wordtaker.keyboard.wordtaker.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "history-migration-${System.nanoTime()}.db"

    @Before
    fun createVersionOneDatabase() {
        context.getDatabasePath(databaseName).parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    raw TEXT NOT NULL,
                    polished TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO history (id, raw, polished, createdAt) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(17L, "旧原文", "旧润色文本", 1234L),
            )
            db.version = 1
        }
    }

    @After
    fun deleteDatabase() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromOneToTwoPreservesOldHistoryWithUnknownModelAndAcceptsNewRows() {
        val database = Room.databaseBuilder(context, HistoryDatabase::class.java, databaseName)
            .addMigrations(HistoryDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val old = runBlocking { database.historyDao().observeAll().first().single() }
            assertEquals(17L, old.id)
            assertEquals("旧原文", old.raw)
            assertEquals("旧润色文本", old.polished)
            assertEquals(1234L, old.createdAt)
            assertNull(old.polishModel)

            runBlocking {
                database.historyDao().insert(
                    HistoryEntity(
                        raw = "新原文",
                        polished = "新润色文本",
                        createdAt = 5678L,
                        polishModel = HistoryPolishModel.CLOUD.storedValue,
                    ),
                )
            }

            val rows = runBlocking { database.historyDao().observeAll().first() }
            assertEquals(2, rows.size)
            assertEquals(HistoryPolishModel.CLOUD.storedValue, rows.first().polishModel)
            assertNull(rows.last().polishModel)
        } finally {
            database.close()
        }
    }
}
