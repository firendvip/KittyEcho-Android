package com.wordtaker.keyboard.wordtaker.history

import kotlinx.coroutines.flow.Flow

/**
 * Wraps [HistoryDao]. On first use (empty table) seeds two sample records so the
 * history screen is never blank on a fresh install.
 */
class HistoryRepository(private val dao: HistoryDao) {

    @Volatile
    private var seeded = false

    private suspend fun ensureSeeded() {
        if (seeded) return
        if (dao.count() == 0) {
            val now = System.currentTimeMillis()
            // Newest first.
            dao.insert(
                HistoryEntity(
                    raw = "嗯就是明天的会议我看一下时间安排再跟你说",
                    polished = "明天的会议时间安排，我确认后再同步给你。",
                    createdAt = now - HOUR_MS * 5,
                )
            )
            dao.insert(
                HistoryEntity(
                    raw = "那个文档我已经改好了你有空帮我看看",
                    polished = "文档我已经修改完成，方便时麻烦你帮忙过目一下。",
                    createdAt = now - HOUR_MS * 26,
                )
            )
        }
        seeded = true
    }

    fun observeAll(): Flow<List<HistoryEntity>> = dao.observeAll()

    fun search(q: String): Flow<List<HistoryEntity>> = dao.search(q)

    suspend fun add(raw: String, polished: String) {
        ensureSeeded()
        dao.insert(
            HistoryEntity(
                raw = raw,
                polished = polished,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun seedIfNeeded() = ensureSeeded()

    suspend fun remove(id: Long) = dao.delete(id)

    suspend fun clear() = dao.clearAll()

    private companion object {
        const val HOUR_MS = 60L * 60L * 1000L
    }
}
