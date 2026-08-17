package com.wordtaker.keyboard.ime.smartbar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.File

class CandidateTouchSnapshotTest : FunSpec({

    test("candidate list and touched object stay frozen until the owning gesture ends") {
        val touchedCandidate = Any()
        val localSnapshot = listOf(touchedCandidate, Any())
        val cloudReordered = listOf(Any(), touchedCandidate)
        val snapshot = CandidateTouchSnapshot<List<Any>>()

        val token = snapshot.begin(localSnapshot)
        val candidateCapturedAtDown = snapshot.currentOr(cloudReordered).first()

        snapshot.currentOr(cloudReordered) shouldBeSameInstanceAs localSnapshot
        candidateCapturedAtDown shouldBeSameInstanceAs touchedCandidate

        snapshot.end(token)

        snapshot.currentOr(cloudReordered) shouldBeSameInstanceAs cloudReordered
    }

    test("a stale cancel cannot release a newer gesture snapshot") {
        val snapshot = CandidateTouchSnapshot<String>()
        val staleToken = snapshot.begin("first")
        val currentToken = snapshot.begin("second")

        snapshot.end(staleToken)
        snapshot.currentOr("live") shouldBe "second"

        snapshot.end(currentToken)
        snapshot.currentOr("live") shouldBe "live"
    }

    test("candidate row commits the object captured at pointer down without index re-resolution") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val source = File(
            moduleRoot,
            "src/main/kotlin/com/wordtaker/keyboard/ime/smartbar/CandidatesRow.kt",
        ).readText()

        source shouldContain "val touchedCandidate = latestCandidate"
        source shouldContain "latestOnClick(touchedCandidate)"
        source shouldContain ".pointerInput(Unit)"
        source shouldNotContain "candidates.getOrNull(n)"
    }
})
