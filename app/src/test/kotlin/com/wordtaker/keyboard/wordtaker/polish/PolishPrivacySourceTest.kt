package com.wordtaker.keyboard.wordtaker.polish

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

class PolishPrivacySourceTest : FunSpec({

    fun moduleRoot(): File = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile

    test("production polish logs are fixed events with no transcript token code or server detail") {
        val root = moduleRoot()
        val realPolisher = File(
            root,
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/polish/RealPolisher.kt",
        ).readText()
        val onlinePolisher = File(
            root,
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/polish/OnlineOnlyPolisher.kt",
        ).readText()
        val diagnostics = File(
            root,
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/polish/PolishDiagnostics.kt",
        ).readText()
        val voiceViewModel = File(
            root,
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/voice/VoiceViewModel.kt",
        ).readText()

        realPolisher.contains("Log.") shouldBe false
        onlinePolisher.contains("Log.") shouldBe false
        voiceViewModel.contains("polish: failure (\${e.message})") shouldBe false

        val forbiddenDiagnosticReferences = listOf(
            "\${raw",
            "\${text",
            "\${code",
            "\${token",
            "\${failure",
            "\${error",
            ".message",
            ".reason",
        )
        forbiddenDiagnosticReferences.filter(diagnostics::contains).shouldBeEmpty()
    }

    test("production speech voice polish account and backend logs never interpolate private payloads") {
        val root = moduleRoot()
        val sourceRoots = listOf(
            "speech",
            "voice",
            "polish",
            "account",
            "backend",
        ).map { area ->
            File(root, "src/main/kotlin/com/wordtaker/keyboard/wordtaker/$area")
        }
        val productionFiles = sourceRoots
            .filter(File::isDirectory)
            .flatMap { directory -> directory.walkTopDown().filter { it.extension == "kt" }.toList() }
        val forbiddenInterpolation = Regex(
            """\$\{[^}]*(text|raw|transcript|token|code|prompt|message)[^}]*}""",
            RegexOption.IGNORE_CASE,
        )

        val unsafeLogLines = productionFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if ("Log." in line && forbiddenInterpolation.containsMatchIn(line)) {
                    "${file.relativeTo(root).path}:${index + 1}"
                } else {
                    null
                }
            }
        }

        unsafeLogLines.shouldBeEmpty()
    }

    test("production Android sources contain no app-owned relay credential or direct relay route") {
        val root = moduleRoot()
        val productionFiles = File(root, "src/main/kotlin")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .toList()
        val forbiddenMarkers = listOf(
            "X-App-Token",
            "APP_TOKEN",
            "RelayClient(",
        )

        val matches = productionFiles.flatMap { file ->
            forbiddenMarkers.mapNotNull { marker ->
                if (marker in file.readText()) "${file.relativeTo(root).path}:$marker" else null
            }
        }

        matches.shouldBeEmpty()
    }
})
