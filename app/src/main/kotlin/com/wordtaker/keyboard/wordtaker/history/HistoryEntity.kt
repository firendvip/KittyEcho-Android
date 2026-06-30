package com.wordtaker.keyboard.wordtaker.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single voice-to-text history record.
 *
 * @property raw      the recognized (unpolished) speech text
 * @property polished the AI-polished rewrite
 * @property createdAt creation time in epoch milliseconds
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val raw: String,
    val polished: String,
    val createdAt: Long,
)
