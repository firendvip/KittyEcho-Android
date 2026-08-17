package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

private const val ARTIFACT_REVISION = "fe3e2bbfa0a0d3789b653c4b6cf3f87a5dbf2b94"
private const val CONVERSION_REVISION = "bbf29cf22ede51f541c052af8f8e77fc54c76e21"
private const val OFFICIAL_BASE =
    "https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28/resolve/$ARTIFACT_REVISION"
private const val MIRROR_BASE =
    "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28/resolve/$ARTIFACT_REVISION"

class ParaformerDistributionContractTest : FunSpec({

    test("production recognition attribution uses the deployed artifact revision") {
        ParaformerModelContract.MODEL_REVISION shouldBe ARTIFACT_REVISION
    }

    test("production contract freezes official first mirror second and all attribution metadata") {
        val source = productionSource("wordtaker/speech/ParaformerModelContract.kt")

        source shouldContain OFFICIAL_BASE
        source shouldContain MIRROR_BASE
        (source.indexOf(OFFICIAL_BASE) < source.indexOf(MIRROR_BASE)) shouldBe true
        source shouldContain "iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch"
        source shouldContain "v2.0.4"
        source shouldContain CONVERSION_REVISION
        source shouldContain ARTIFACT_REVISION
        source shouldContain "sherpa-onnx-1.13.3"
        source shouldContain "223_385_835L"
        source shouldContain "75_756L"
        source shouldContain "9ada9127ca5b82320385ac12340eb8b05dee64fd45cf8cf593ec693826ec2fd7"
        source shouldContain "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"
    }

    test("APK source tree contains no legacy Zipformer model or dead install chain") {
        val root = projectRoot()
        File(root, "app/src/main/assets/models/zipformer-zh").exists() shouldBe false
        listOf(
            "ModelDownloader.kt",
            "ModelAssetInstaller.kt",
            "ZipformerController.kt",
        ).map { File(root, "app/src/main/kotlin/com/wordtaker/keyboard/wordtaker/speech/$it") }
            .filter(File::exists)
            .map(File::getName)
            .shouldBe(emptyList())
    }

    test("offline model license page and full MIT Apache texts ship with the APK") {
        val root = projectRoot()
        val page = File(
            root,
            "app/src/main/kotlin/com/wordtaker/keyboard/app/settings/about/VoiceModelLicensesScreen.kt",
        )
        page.isFile shouldBe true
        val pageText = page.readText()
        pageText shouldContain "Paraformer"
        pageText shouldContain "MIT"
        pageText shouldContain "Apache-2.0"
        pageText shouldContain CONVERSION_REVISION
        pageText shouldContain ARTIFACT_REVISION

        val mit = File(root, "app/src/main/assets/license/paraformer_mit.txt")
        val apache = File(root, "app/src/main/assets/license/apache-2.0.txt")
        mit.isFile shouldBe true
        apache.isFile shouldBe true
        mit.readText() shouldContain "Permission is hereby granted, free of charge"
        apache.readText() shouldContain "Apache License"
        apache.readText() shouldContain "Version 2.0, January 2004"
    }

    test("offline attribution removes the unsupported owner and ships embedded runtime notices") {
        val root = projectRoot()
        val page = File(
            root,
            "app/src/main/kotlin/com/wordtaker/keyboard/app/settings/about/VoiceModelLicensesScreen.kt",
        ).readText()
        val paraformerMit = File(root, "app/src/main/assets/license/paraformer_mit.txt").readText()
        val onnxRuntimeMit = File(root, "app/src/main/assets/license/onnxruntime-1.24.3-license.txt")
        val onnxRuntimeNotices =
            File(root, "app/src/main/assets/license/onnxruntime-1.24.3-third-party-notices.txt")

        paraformerMit shouldNotContain "Xiaomi Corporation"
        paraformerMit shouldContain
            "csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28@$ARTIFACT_REVISION"
        paraformerMit shouldContain
            "csukuangfj/paraformer-onnxruntime-python-example@$CONVERSION_REVISION"
        paraformerMit shouldContain "The pinned sources do not publish a standalone copyright notice."

        onnxRuntimeMit.isFile shouldBe true
        onnxRuntimeMit.readText() shouldContain "Copyright (c) Microsoft Corporation"
        onnxRuntimeMit.readText() shouldContain "Permission is hereby granted, free of charge"
        onnxRuntimeNotices.isFile shouldBe true
        (onnxRuntimeNotices.length() > 100_000L) shouldBe true
        onnxRuntimeNotices.readText() shouldContain "THIRD PARTY SOFTWARE NOTICES AND INFORMATION"
        onnxRuntimeNotices.readText() shouldContain "protocolbuffers/protobuf"

        page shouldContain "license/onnxruntime-1.24.3-license.txt"
        page shouldContain "license/onnxruntime-1.24.3-third-party-notices.txt"
        page shouldContain "ONNX Runtime 1.24.3"
        page shouldContain "https://github.com/microsoft/onnxruntime/tree/v1.24.3"
        page shouldContain "https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.3"
        page shouldContain "https://modelscope.cn/models/iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch"
        page shouldContain "备用下载源（非权利来源）"
    }

    test("benchmark registry and README use the frozen artifact and explicit product decision") {
        val root = projectRoot()
        val registry = File(
            root,
            "tools/asr-benchmark/model-registry/candidates-v1.json",
        ).readText()
        val paraformer = registry
            .substringAfter("\"model_id\": \"paraformer_int8\"")
            .substringBefore("\"model_id\": \"fireredasr2_aed_int8\"")
        val readme = File(root, "tools/asr-benchmark/README.md").readText()
        val paraformerRow = readme.lineSequence().single { it.startsWith("| Paraformer int8 |") }

        paraformer shouldContain
            "csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28/tree/$ARTIFACT_REVISION"
        paraformer shouldContain
            "csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28/blob/$ARTIFACT_REVISION/model.int8.onnx"
        paraformer shouldContain
            "csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28/blob/$ARTIFACT_REVISION/tokens.txt"
        paraformer shouldContain "9ada9127ca5b82320385ac12340eb8b05dee64fd45cf8cf593ec693826ec2fd7"
        paraformer shouldContain "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"
        paraformer shouldContain "\"download_status\": \"already_present_hash_verified\""
        paraformer shouldContain "\"android_runtime_compatibility\": \"locally_verified\""
        paraformer shouldContain "\"clearance\": \"cleared_by_product_decision\""
        paraformer shouldContain "ONNXRuntime-ThirdPartyNotices-1.24.3"
        paraformer shouldNotContain "paraformer-onnxruntime-python-example/blob/"
        paraformer shouldNotContain "\"sha256\": null"

        paraformerRow shouldContain "sherpa-onnx-paraformer-zh-2023-03-28"
        paraformerRow shouldContain "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"
        paraformerRow shouldContain "用户确认的产品决策"
        paraformerRow shouldNotContain "NO-GO"
        readme shouldNotContain "官方保证商用"
        readme shouldNotContain "完全合规"
        readme shouldNotContain "永久免费"
        readme shouldNotContain "达到竞品准确率"
    }

    test("main app IME settings and missing-model route expose one shared model manager") {
        val main = productionSource("app/settings/MinimalSettingsScreen.kt")
        val ime = productionSource("wordtaker/settings/ImeSettingsLayout.kt")
        val fallback = productionSource("wordtaker/ui/WordTakerSettingsActivity.kt")
        val graph = productionSource("wordtaker/di/AppGraph.kt")
        val toolbar = productionSource("wordtaker/voice/CatKeyboardLayout.kt")

        listOf(main, ime, fallback, toolbar).forEach { source ->
            source shouldContain "paraformerModelManager"
        }
        graph shouldContain "val paraformerModelManager"
        toolbar shouldContain "VoiceToolbarPresentation.Candidates"
        toolbar shouldContain "ParaformerCompactStatus"
    }
})

private fun productionSource(relativePath: String): String =
    File(projectRoot(), "app/src/main/kotlin/com/wordtaker/keyboard/$relativePath").readText()

private fun projectRoot(): File {
    var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    repeat(8) {
        if (File(current, "app/src/main").isDirectory) return current
        current = current.parentFile ?: error("Could not locate project root")
    }
    error("Could not locate project root")
}
