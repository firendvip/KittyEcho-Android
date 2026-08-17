package com.wordtaker.keyboard.app.settings.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordtaker.keyboard.lib.compose.FlorisScreen

private const val CONVERSION_REVISION = "bbf29cf22ede51f541c052af8f8e77fc54c76e21"
private const val ARTIFACT_REVISION = "fe3e2bbfa0a0d3789b653c4b6cf3f87a5dbf2b94"
private const val OFFICIAL_SOURCE =
    "https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28"
private const val MIRROR_SOURCE =
    "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-03-28"
private const val CONVERSION_SOURCE =
    "https://huggingface.co/csukuangfj/paraformer-onnxruntime-python-example"
private const val UPSTREAM_SOURCE =
    "https://modelscope.cn/models/iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch"
private const val SHERPA_SOURCE =
    "https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.3"
private const val ONNX_RUNTIME_SOURCE =
    "https://github.com/microsoft/onnxruntime/tree/v1.24.3"
private const val ONNX_RUNTIME_NOTICES_SOURCE =
    "https://github.com/microsoft/onnxruntime/blob/v1.24.3/ThirdPartyNotices.txt"

/** Offline attribution and exact license texts for every shipped voice-model component. */
@Composable
fun VoiceModelLicensesScreen() = FlorisScreen {
    title = "语音模型与许可证"
    scrollable = false
    val context = LocalContext.current
    val paraformerMit = remember {
        context.assets.open("license/paraformer_mit.txt").bufferedReader().use { it.readText() }
    }
    val apache = remember { context.assets.open("license/apache-2.0.txt").bufferedReader().use { it.readText() } }
    val onnxRuntimeMit = remember {
        context.assets.open("license/onnxruntime-1.24.3-license.txt").bufferedReader().use { it.readText() }
    }
    val onnxRuntimeNotices = remember {
        context.assets.open("license/onnxruntime-1.24.3-third-party-notices.txt")
            .bufferedReader()
            .use { it.readText() }
    }

    content {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                LicenseHeading("Paraformer 制品 · MIT")
                LicenseBody(
                    "modelId: paraformer_int8\n" +
                        "制品来源: $OFFICIAL_SOURCE\n" +
                        "备用下载源（非权利来源）: $MIRROR_SOURCE\n" +
                        "artifactRevision: $ARTIFACT_REVISION\n" +
                        "转换来源: $CONVERSION_SOURCE\n" +
                        "conversionRevision: $CONVERSION_REVISION",
                )
                Spacer(Modifier.height(12.dp))
                LicenseHeading("上游模型 · Apache-2.0")
                LicenseBody(
                    "iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch\n" +
                        "upstreamVersion: v2.0.4\n" +
                        "来源: $UPSTREAM_SOURCE",
                )
                Spacer(Modifier.height(12.dp))
                LicenseHeading("sherpa-onnx 运行时 · Apache-2.0")
                LicenseBody("runtime: sherpa-onnx-1.13.3\n来源: $SHERPA_SOURCE")
                Spacer(Modifier.height(12.dp))
                LicenseHeading("ONNX Runtime 1.24.3 · MIT")
                LicenseBody("来源: $ONNX_RUNTIME_SOURCE")
                Spacer(Modifier.height(20.dp))
                LicenseHeading("Paraformer 制品 MIT 声明与许可文本")
                LicenseText(paraformerMit)
                Spacer(Modifier.height(20.dp))
                LicenseHeading("ONNX Runtime 1.24.3 MIT License 全文")
                LicenseText(onnxRuntimeMit)
                Spacer(Modifier.height(20.dp))
                LicenseHeading("Apache License 2.0 全文")
                LicenseText(apache)
                Spacer(Modifier.height(20.dp))
                LicenseHeading("ONNX Runtime 1.24.3 ThirdPartyNotices")
                LicenseBody("来源: $ONNX_RUNTIME_NOTICES_SOURCE")
                Spacer(Modifier.height(8.dp))
                LicenseText(onnxRuntimeNotices)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun LicenseHeading(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun LicenseBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LicenseText(text: String) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
