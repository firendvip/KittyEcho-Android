package com.wordtaker.keyboard.wordtaker.history

import com.wordtaker.keyboard.wordtaker.polish.PolishOutcomeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class HistoryModelAttributionTest : FunSpec({

    test("a successful Android cloud polish is stored as the actual cloud model") {
        val dao = CapturingHistoryDao()

        runTest {
            HistoryRepository(dao).add(
                raw = "原始长文本",
                polished = "润色后的长文本",
                outcome = PolishOutcomeKind.Polished,
            )
        }

        dao.inserted.single().polishModel shouldBe HistoryPolishModel.CLOUD.storedValue
    }

    test("direct and fallback outcomes are stored as not polished rather than cloud") {
        val directOrFallback = PolishOutcomeKind.entries - PolishOutcomeKind.Polished
        val dao = CapturingHistoryDao()
        val repository = HistoryRepository(dao)

        runTest {
            directOrFallback.forEach { outcome ->
                repository.add(
                    raw = "原文-$outcome",
                    polished = "原文-$outcome",
                    outcome = outcome,
                )
            }
        }

        dao.inserted.map(HistoryEntity::polishModel).shouldContainExactly(
            directOrFallback.map { HistoryPolishModel.NONE.storedValue },
        )
    }

    test("history labels match PC actual-engine semantics without inventing legacy data") {
        historyProcessingLabel(entry(polishModel = HistoryPolishModel.LOCAL.storedValue)) shouldBe
            "AI优化·本地模型"
        historyProcessingLabel(entry(polishModel = HistoryPolishModel.CLOUD.storedValue)) shouldBe
            "AI优化·云端AI"
        historyProcessingLabel(
            entry(
                polished = "原始长文本",
                polishModel = HistoryPolishModel.NONE.storedValue,
            ),
        ) shouldBe "未润色"
        historyProcessingLabel(entry(polishModel = null)) shouldBe "AI优化"
        historyProcessingLabel(
            entry(
                polished = "原始长文本",
                polishModel = null,
            ),
        ) shouldBe null
    }

    test("unknown future storage values degrade to legacy-compatible labels") {
        historyProcessingLabel(entry(polishModel = "future-engine")) shouldBe "AI优化"
        historyProcessingLabel(
            entry(
                polished = "原始长文本",
                polishModel = "future-engine",
            ),
        ) shouldBe null
    }

    test("sample rows remain explicitly unattributed instead of pretending to use cloud") {
        val dao = CapturingHistoryDao(existingCount = 0)

        runTest {
            HistoryRepository(dao).seedIfNeeded()
        }

        dao.inserted.size shouldBe 2
        dao.inserted.all { it.polishModel == null } shouldBe true
    }
})

private fun entry(
    polished: String = "润色后的长文本",
    polishModel: String?,
): HistoryEntity = HistoryEntity(
    id = 7,
    raw = "原始长文本",
    polished = polished,
    createdAt = 123,
    polishModel = polishModel,
)

private class CapturingHistoryDao(
    private val existingCount: Int = 1,
) : HistoryDao {
    val inserted = mutableListOf<HistoryEntity>()
    private val rows = MutableStateFlow<List<HistoryEntity>>(emptyList())

    override suspend fun insert(entity: HistoryEntity): Long {
        inserted += entity
        rows.value = inserted.toList()
        return inserted.size.toLong()
    }

    override fun observeAll(): Flow<List<HistoryEntity>> = rows

    override fun search(q: String): Flow<List<HistoryEntity>> = rows

    override suspend fun delete(id: Long) = Unit

    override suspend fun clearAll() = Unit

    // Simulate an already-initialized history so repository sample seeding does not obscure
    // per-call attribution assertions.
    override suspend fun count(): Int = existingCount + inserted.size
}
