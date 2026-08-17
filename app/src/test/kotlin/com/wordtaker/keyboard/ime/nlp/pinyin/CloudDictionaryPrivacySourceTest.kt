package com.wordtaker.keyboard.ime.nlp.pinyin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

class CloudDictionaryPrivacySourceTest : FunSpec({

    fun moduleRoot(): File = sequenceOf(File("."), File("app"))
        .first { File(it, "src/main").isDirectory }
        .canonicalFile

    test("production pinyin and cloud logs contain no raw composing or candidate text") {
        val moduleRoot = moduleRoot()
        val sourceFiles = listOf(
            "src/main/kotlin/com/wordtaker/keyboard/ime/nlp/pinyin/CloudDictionaryAugmenter.kt",
            "src/main/kotlin/com/wordtaker/keyboard/ime/nlp/pinyin/PinyinLanguageProvider.kt",
            "src/main/kotlin/com/wordtaker/keyboard/ime/nlp/pinyin/ShuangpinLanguageProvider.kt",
            "src/main/kotlin/com/wordtaker/keyboard/ime/nlp/pinyin/T9LanguageProvider.kt",
            "src/main/kotlin/com/wordtaker/keyboard/wordtaker/backend/BackendClient.kt",
        ).map { File(moduleRoot, it) }
        val forbiddenContentReferences = listOf(
            "${'$'}pinyin",
            "${'$'}composing",
            "${'$'}{candidate.text}",
            "${'$'}{req.pinyin}",
            "${'$'}{request.pinyin}",
        )
        val logBody = Regex(
            pattern = """flog(?:Debug|Info|Warning|Error)\s*\{(.*?)\}""",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )

        val leaks = sourceFiles.flatMap { file ->
            logBody.findAll(file.readText()).flatMap { match ->
                forbiddenContentReferences.asSequence()
                    .filter(match.value::contains)
                    .map { reference -> "${file.name}: $reference" }
            }.toList()
        }

        leaks.shouldBeEmpty()
    }

    test("cloud candidate cache has no persistence API") {
        val source = File(
            moduleRoot(),
            "src/main/kotlin/com/wordtaker/keyboard/ime/nlp/pinyin/CloudDictionaryAugmenter.kt",
        ).readText()
        val forbiddenPersistenceApis = listOf(
            "SharedPreferences",
            "DataStore",
            "RoomDatabase",
            "java.io.File",
            "FileOutputStream",
        )

        forbiddenPersistenceApis.filter(source::contains).shouldBeEmpty()
    }
})
