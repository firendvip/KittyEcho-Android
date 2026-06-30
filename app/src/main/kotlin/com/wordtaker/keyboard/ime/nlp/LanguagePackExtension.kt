/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wordtaker.keyboard.ime.nlp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.wordtaker.keyboard.lib.FlorisLocale
import com.wordtaker.keyboard.lib.devtools.flogError
import com.wordtaker.keyboard.lib.ext.Extension
import com.wordtaker.keyboard.lib.ext.ExtensionComponent
import com.wordtaker.keyboard.lib.ext.ExtensionEditor
import com.wordtaker.keyboard.lib.ext.ExtensionMeta
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import com.wordtaker.lib.kotlin.io.FsDir
import com.wordtaker.lib.kotlin.io.subFile

@Serializable
class LanguagePackComponent(
    override val id: String,
    override val label: String,
    override val authors: List<String>,
    val locale: FlorisLocale = FlorisLocale.fromTag(id),
    val hanShapeBasedKeyCode: String = "abcdefghijklmnopqrstuvwxyz",
) : ExtensionComponent {
    @Transient var parent: LanguagePackExtension? = null

    @SerialName("hanShapeBasedTable")
    private val _hanShapeBasedTable: String? = null  // Allows overriding the sqlite3 table to query in the json
    val hanShapeBasedTable
        get() = _hanShapeBasedTable ?: locale.variant
}

@SerialName(LanguagePackExtension.SERIAL_TYPE)
@Serializable
class LanguagePackExtension( // FIXME: how to make this support multiple types of language packs, and selectively load?
    override val meta: ExtensionMeta,
    override val dependencies: List<String>? = null,
    val items: List<LanguagePackComponent> = listOf(),
    val hanShapeBasedSQLite: String = "han.sqlite3",
) : Extension() {

    override fun components(): List<ExtensionComponent> = items

    override fun edit(): ExtensionEditor {
        TODO("LOL LMAO")
    }

    companion object {
        const val SERIAL_TYPE = "ime.extension.languagepack"
    }

    override fun serialType() = SERIAL_TYPE

    @Transient var hanShapeBasedSQLiteDatabase: SQLiteDatabase = SQLiteDatabase.create(null)

    override fun onAfterLoad(context: Context, cacheDir: FsDir) {
        // FIXME: this is loading language packs of all subtypes when they load.
        super.onAfterLoad(context, cacheDir)

        // Use the DB from the loader's working dir only if it is present AND actually contains the
        // shape-based tables. The asset→workingDir copy can silently produce a missing or empty
        // file, in which case every shape-based query (五笔/笔画) fails with "no such table" and no
        // candidates appear. When the working-dir DB is unusable, fall back to copying the bundled
        // han.sqlite3 straight out of the APK assets into the app files dir and open that.
        val workingDbPath = workingDir?.subFile(hanShapeBasedSQLite)?.path
            ?.takeIf { java.io.File(it).exists() }
        val databasePath = workingDbPath?.takeIf { isUsableHanDb(it) }
            ?: extractBundledDbFromAssets(context)?.takeIf { isUsableHanDb(it) }
        if (databasePath == null) {
            flogError { "Han shape-based language pack not found or loaded" }
        } else try {
            // TODO: use lock on database?
            hanShapeBasedSQLiteDatabase.takeIf { it.isOpen }?.close()
            hanShapeBasedSQLiteDatabase =
                SQLiteDatabase.openDatabase(databasePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            flogError { "SQLiteException in openDatabase: path=$databasePath, error='${e}'" }
        }
    }

    /** True if [path] opens as a SQLite DB that has at least one user table. */
    private fun isUsableHanDb(path: String): Boolean = try {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' LIMIT 1", null).use { c ->
                c.moveToFirst()
            }
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Copies the bundled `han.sqlite3` from APK assets into the app files dir (once) and returns
     * its absolute path, or null if the asset is absent. The asset lives under the extension's
     * source dir, e.g. `ime/languagepack/<extId>/han.sqlite3`.
     */
    private fun extractBundledDbFromAssets(context: Context): String? {
        return try {
            val assetRel = "ime/languagepack/${meta.id}/$hanShapeBasedSQLite"
            val outFile = java.io.File(context.filesDir, "hanshapebased/${meta.id}/$hanShapeBasedSQLite")
            if (!outFile.exists() || outFile.length() == 0L) {
                outFile.parentFile?.mkdirs()
                context.assets.open(assetRel).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            outFile.path
        } catch (e: Exception) {
            flogError { "Failed to extract bundled han DB from assets: $e" }
            null
        }
    }

    override fun onBeforeUnload(context: Context, cacheDir: FsDir) {
        super.onBeforeUnload(context, cacheDir)
        hanShapeBasedSQLiteDatabase.takeIf { it.isOpen }?.close()
    }
}
