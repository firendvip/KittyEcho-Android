package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val SHERPA_AAR_SHA256 =
    "243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6"

private val UNUSED_THIRD_PARTY_PROMPT_MODEL_CLASSES = setOf(
    "com/k2fsa/sherpa/onnx/OfflineFunAsrNanoModelConfig.class",
    "com/k2fsa/sherpa/onnx/OfflineQwen3AsrModelConfig.class",
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SherpaOnnxBinarySourceContractTest : FunSpec({

    test("bundled sherpa-onnx binary matches the reviewed upstream artifact") {
        val aar = projectFile("app/libs/sherpa-onnx-1.13.3.aar")

        aar.inputStream().use { input ->
            MessageDigest.getInstance("SHA-256")
                .digest(input.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) } shouldBe SHERPA_AAR_SHA256
        }
    }

    test("generic prompt model APIs are third-party binary classes unused by app source") {
        val aar = projectFile("app/libs/sherpa-onnx-1.13.3.aar")
        val aarClasses = ZipFile(aar).use { zip ->
            val classesJar = zip.getInputStream(zip.getEntry("classes.jar"))
            JarInputStream(classesJar).use { jar ->
                generateSequence { jar.nextJarEntry }
                    .map { it.name }
                    .toSet()
            }
        }
        aarClasses.shouldContainAll(UNUSED_THIRD_PARTY_PROMPT_MODEL_CLASSES)

        val forbiddenSimpleNames = UNUSED_THIRD_PARTY_PROMPT_MODEL_CLASSES
            .map { it.substringAfterLast('/').removeSuffix(".class") }
        val productionRoot = projectFile("app/src/main")
        val productionReferences = listOf(
            File(productionRoot, "kotlin"),
            File(productionRoot, "java"),
        ).filter(File::isDirectory)
            .asSequence()
            .flatMap(File::walkTopDown)
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .flatMap { file ->
                val text = file.readText()
                forbiddenSimpleNames.asSequence()
                    .filter(text::contains)
                    .map { className -> "${file.invariantSeparatorsPath}:$className" }
            }
            .toList()

        productionReferences.shouldBeEmpty()
    }

    test("frozen private Paraformer contract accepts only exact filename bytes sha and file type") {
        val valid = listOf(
            ParaformerArtifactFacts(
                ParaformerModelContract.MODEL_FILENAME,
                ParaformerModelContract.MODEL_BYTES,
                ParaformerModelContract.MODEL_SHA256,
                regularFile = true,
                symbolicLink = false,
            ),
            ParaformerArtifactFacts(
                ParaformerModelContract.TOKENS_FILENAME,
                ParaformerModelContract.TOKENS_BYTES,
                ParaformerModelContract.TOKENS_SHA256,
                regularFile = true,
                symbolicLink = false,
            ),
        )
        ParaformerModelContract.validate(valid)
        shouldThrow<AsrModelMissingException> {
            ParaformerModelContract.validate(valid.dropLast(1))
        }
        shouldThrow<AsrModelCorruptException> {
            ParaformerModelContract.validate(
                valid.mapIndexed { index, fact ->
                    if (index == 0) fact.copy(sizeBytes = fact.sizeBytes - 1) else fact
                },
            )
        }
        shouldThrow<AsrModelCorruptException> {
            ParaformerModelContract.validate(
                valid.mapIndexed { index, fact ->
                    if (index == 1) fact.copy(symbolicLink = true) else fact
                },
            )
        }
        shouldThrow<AsrModelCorruptException> {
            ParaformerModelContract.validate(
                valid.mapIndexed { index, fact ->
                    if (index == 0) fact.copy(linkCount = 2) else fact
                },
            )
        }
    }

    test("private Paraformer paths are fixed direct children without traversal") {
        listOf(
            ParaformerModelContract.PRIVATE_DIRECTORY,
            ParaformerModelContract.MODEL_FILENAME,
            ParaformerModelContract.TOKENS_FILENAME,
        ).forEach { fixedName ->
            File(fixedName).name shouldBe fixedName
            fixedName.contains("..") shouldBe false
            fixedName.contains('/') shouldBe false
            fixedName.contains('\\') shouldBe false
        }
    }

    test("single actor keeps complete utterances and completes strict FIFO with typed errors") {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val firstDecodeGate = CompletableDeferred<Unit>()
            val seen = mutableListOf<FloatArray>()
            val actor = ParaformerRecognitionActor(
                decoder = WholeUtteranceDecoder { samples ->
                    seen += samples
                    if (seen.size == 1) firstDecodeGate.await()
                    AsrResult(
                        if (seen.size == 1) "第一段" else "第二段",
                        ParaformerModelContract.MODEL_ID,
                        ParaformerModelContract.MODEL_REVISION,
                    )
                },
                scope = CoroutineScope(SupervisorJob() + dispatcher),
            )
            val fullFirst = FloatArray(16_000) { it / 16_000f }
            val fullSecond = floatArrayOf(-1f, 0f, 1f)
            val first = actor.submit(fullFirst)
            val second = actor.submit(fullSecond)
            val secondAwait = async { second.await() }
            runCurrent()
            seen.size shouldBe 1
            seen.single().toList() shouldContainExactly fullFirst.toList()
            secondAwait.isCompleted shouldBe false
            firstDecodeGate.complete(Unit)
            runCurrent()
            first.await().text shouldBe "第一段"
            secondAwait.await().text shouldBe "第二段"
            seen[1].toList() shouldContainExactly fullSecond.toList()
            actor.close()

            val oomActor = ParaformerRecognitionActor(
                WholeUtteranceDecoder { throw OutOfMemoryError("private") },
                CoroutineScope(SupervisorJob() + dispatcher),
            )
            val oom = async {
                shouldThrow<AsrOutOfMemoryException> {
                    oomActor.submit(floatArrayOf(1f)).await()
                }
            }
            runCurrent()
            oom.await()
            oomActor.close()
        }
    }
})

private fun projectFile(relativePath: String): File {
    var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    repeat(8) {
        val candidate = File(current, relativePath)
        if (candidate.exists()) return candidate
        current = current.parentFile ?: error("Could not find project file: $relativePath")
    }
    error("Could not find project file: $relativePath")
}
