package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import java.io.File

class ParaformerBundledDistributionContractTest : FunSpec({

    test("build generates bundled Paraformer assets only from frozen external inputs") {
        val root = bundledProjectRoot()
        val appBuild = File(root, "app/build.gradle.kts").readText()
        val generator = File(
            root,
            "tools/asr-benchmark/scripts/prepare_paraformer_app_assets.py",
        )

        appBuild shouldContain "generateBundledParaformerAssets"
        appBuild shouldContain "kittyechoParaformerModel"
        appBuild shouldContain "kittyechoParaformerTokens"
        appBuild shouldContain "kittyechoParaformerModelReceipt"
        appBuild shouldContain "kittyechoParaformerTokensReceipt"
        appBuild shouldContain "generated/assets/paraformer"
        appBuild shouldContain "prepare_paraformer_app_assets.py"
        appBuild shouldContain "noCompress += listOf(\"onnx\", \"txt\""
        appBuild.contains("/Users/").shouldBeFalse()
        File(root, "app/src/main/assets/models/paraformer").exists().shouldBeFalse()
        generator.isFile.shouldBeTrue()

        val generatorSource = generator.readText()
        generatorSource shouldContain "load_download_freeze_receipt"
        generatorSource shouldContain "model.int8.onnx"
        generatorSource shouldContain "tokens.txt"
        generatorSource shouldContain "O_NOFOLLOW"
        generatorSource shouldContain "os.fsync"
    }

    test("production has no Paraformer network download WorkManager or foreground service chain") {
        val root = bundledProjectRoot()
        val appBuild = File(root, "app/build.gradle.kts").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val settings = bundledProductionSource("wordtaker/speech/ParaformerModelSettingsSection.kt")

        listOf(
            "ParaformerArtifactDownloader.kt",
            "ParaformerDownloadPolicy.kt",
            "ParaformerHttpTransfer.kt",
            "ParaformerModelDownloadWorker.kt",
            "ParaformerWorkManagerScheduler.kt",
        ).forEach { filename ->
            File(
                root,
                "app/src/main/kotlin/com/wordtaker/keyboard/wordtaker/speech/$filename",
            ).exists().shouldBeFalse()
        }
        appBuild.contains("libs.androidx.work.runtime").shouldBeFalse()
        manifest.contains("android.permission.FOREGROUND_SERVICE").shouldBeFalse()
        manifest.contains("androidx.work.impl.foreground.SystemForegroundService").shouldBeFalse()
        settings.contains("下载").shouldBeFalse()
        settings.contains("Wi-Fi").shouldBeFalse()
        settings.contains("移动数据").shouldBeFalse()
    }

    test("runtime bundled installer retains fail closed atomic private installation") {
        val installer = bundledProductionSource(
            "wordtaker/speech/ParaformerBundledAssetInstaller.kt",
        )
        val engine = bundledProductionSource("wordtaker/speech/RealSpeechEngine.kt")
        val voice = bundledProductionSource("wordtaker/voice/VoiceViewModel.kt")

        installer shouldContain "models/paraformer"
        installer shouldContain "ParaformerSecureInstaller"
        installer shouldContain "ensureStagingDirectory"
        installer shouldContain "O_NOFOLLOW"
        installer shouldContain "output.fd.sync()"
        installer shouldContain "deleteObsoleteDownloadState"
        engine shouldContain "ensureInstalled"
        voice shouldContain "语音模型正在准备，请稍候"
    }
})

private fun bundledProductionSource(relativePath: String): String =
    File(
        bundledProjectRoot(),
        "app/src/main/kotlin/com/wordtaker/keyboard/$relativePath",
    ).readText()

private fun bundledProjectRoot(): File {
    var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    repeat(8) {
        if (File(current, "app/src/main").isDirectory) return current
        current = current.parentFile ?: error("Could not locate project root")
    }
    error("Could not locate project root")
}
