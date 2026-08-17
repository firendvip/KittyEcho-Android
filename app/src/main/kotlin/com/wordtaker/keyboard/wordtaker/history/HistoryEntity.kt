package com.wordtaker.keyboard.wordtaker.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single voice-to-text history record.
 *
 * @property raw      the recognized (unpolished) speech text
 * @property polished the final committed text, which may be polished or an unchanged direct result
 * @property createdAt creation time in epoch milliseconds
 * @property polishModel actual model that produced [polished], `none` for direct/fallback output,
 * and `null` only when a legacy row predates model attribution
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raw: String,
    val polished: String,
    val createdAt: Long,
    @ColumnInfo(name = "polish_model") val polishModel: String? = null,
)
