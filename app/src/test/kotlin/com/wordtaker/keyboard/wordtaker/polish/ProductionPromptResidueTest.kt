package com.wordtaker.keyboard.wordtaker.polish

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import java.io.File

private val FORBIDDEN_CLIENT_PROMPT_RESIDUE = listOf(
    "\"/prompt",
    "\"/aiapi/prompt",
    "getLocalPrompt",
    "systemPrompt",
    "userPrompt",
    "promptTemplate",
    "OfflineFunAsrNanoModelConfig",
    "OfflineQwen3AsrModelConfig",
    "class MockPolisher",
    "POLISHED_GAOEQ",
    "POLISHED_DEFAULT",
    "这个方案整体方向很不错，如果细节上我们能再一起打磨打磨，就更完善了。",
    "我想说的是，这个方案整体可行，细节方面还需要再讨论一下。",
)

class ProductionPromptResidueTest : FunSpec({

    test("residue guard covers executable prompt configuration and sherpa prompt model paths") {
        FORBIDDEN_CLIENT_PROMPT_RESIDUE.shouldContainAll(
            "\"/prompt",
            "\"/aiapi/prompt",
            "getLocalPrompt",
            "systemPrompt",
            "userPrompt",
            "promptTemplate",
            "OfflineFunAsrNanoModelConfig",
            "OfflineQwen3AsrModelConfig",
        )
    }

    test("production code resources and readable assets contain no client prompt residue") {
        val moduleRoot = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main").isDirectory }
            .canonicalFile
        val productionRoot = File(moduleRoot, "src/main")
        val scannedRoots = listOf(
            File(productionRoot, "kotlin"),
            File(productionRoot, "java"),
            File(productionRoot, "res"),
            File(productionRoot, "assets"),
        ).filter(File::isDirectory)
        val readableExtensions = setOf(
            "cfg", "html", "java", "js", "json", "kt", "md", "pro", "properties", "txt",
            "xml", "yaml", "yml",
        )
        val excludedReadableDataPrefixes = listOf(
            "assets/handwriting/",
            "assets/ime/dict/",
            "assets/ime/languagepack/",
            "assets/ime/media/",
            "assets/models/",
        )
        val matches = scannedRoots.asSequence()
            .flatMap(File::walkTopDown)
            .filter { file ->
                if (!file.isFile || file.extension.lowercase() !in readableExtensions) return@filter false
                val relative = file.relativeTo(productionRoot).invariantSeparatorsPath
                excludedReadableDataPrefixes.none(relative::startsWith)
            }
            .flatMap { file ->
                val text = file.readText()
                FORBIDDEN_CLIENT_PROMPT_RESIDUE.asSequence()
                    .filter(text::contains)
                    .map { token ->
                        "${file.relativeTo(productionRoot).invariantSeparatorsPath} contains $token"
                    }
            }
            .toList()

        matches.shouldBeEmpty()
    }
})
