package com.wordtaker.keyboard.asrbenchmark.runner

import com.wordtaker.keyboard.asrbenchmark.core.BenchmarkContractException
import com.wordtaker.keyboard.asrbenchmark.core.StabilityPolicy
import java.io.File

enum class ContinuousStabilityDuration(
    val wireValue: String,
    val minimumDurationNanos: Long,
) {
    TEN_SECONDS("10", 10_000_000_000L),
    TEN_MINUTES("600", 600_000_000_000L),
    ;

    val policy: StabilityPolicy
        get() = when (this) {
            TEN_SECONDS -> StabilityPolicy.development(minimumDurationNanos)
            TEN_MINUTES -> StabilityPolicy.developmentEmulator600()
        }

    companion object {
        fun parse(value: String): ContinuousStabilityDuration =
            entries.singleOrNull { it.wireValue == value }
                ?: throw BenchmarkContractException(
                    "continuous stability duration is invalid",
                )
    }
}

class ParaformerStabilityInstrumentationCommand private constructor(
    val smokeCommand: ParaformerInstrumentationCommand,
    val duration: ContinuousStabilityDuration,
) {
    companion object {
        fun parse(
            entries: List<Pair<String, String>>,
        ): ParaformerStabilityInstrumentationCommand {
            val parsed = ContinuousStabilityCommandParser.parse(
                entries = entries,
                expectedMode = "paraformer-stability",
                smokeMode = "paraformer-smoke",
                parseSmoke = ParaformerInstrumentationCommand::parse,
            )
            requireAbsolutePaths(
                parsed.smokeCommand.modelPath,
                parsed.smokeCommand.tokensPath,
                parsed.smokeCommand.pcmPath,
            )
            return ParaformerStabilityInstrumentationCommand(
                smokeCommand = parsed.smokeCommand,
                duration = parsed.duration,
            )
        }
    }
}

class ZipformerStabilityInstrumentationCommand private constructor(
    val smokeCommand: ZipformerInstrumentationCommand,
    val duration: ContinuousStabilityDuration,
) {
    companion object {
        fun parse(
            entries: List<Pair<String, String>>,
        ): ZipformerStabilityInstrumentationCommand {
            val parsed = ContinuousStabilityCommandParser.parse(
                entries = entries,
                expectedMode = "zipformer-stability",
                smokeMode = "zipformer-smoke",
                parseSmoke = ZipformerInstrumentationCommand::parse,
            )
            requireAbsolutePaths(
                parsed.smokeCommand.encoderPath,
                parsed.smokeCommand.decoderPath,
                parsed.smokeCommand.joinerPath,
                parsed.smokeCommand.tokensPath,
                parsed.smokeCommand.pcmPath,
            )
            return ZipformerStabilityInstrumentationCommand(
                smokeCommand = parsed.smokeCommand,
                duration = parsed.duration,
            )
        }
    }
}

class FunAsrNanoStabilityInstrumentationCommand private constructor(
    val smokeCommand: FunAsrNanoInstrumentationCommand,
    val duration: ContinuousStabilityDuration,
) {
    companion object {
        fun parse(
            entries: List<Pair<String, String>>,
        ): FunAsrNanoStabilityInstrumentationCommand {
            val parsed = ContinuousStabilityCommandParser.parse(
                entries = entries,
                expectedMode = "funasr-nano-stability",
                smokeMode = "funasr-nano-smoke",
                parseSmoke = FunAsrNanoInstrumentationCommand::parse,
            )
            requireAbsolutePaths(
                parsed.smokeCommand.embeddingPath,
                parsed.smokeCommand.encoderAdaptorPath,
                parsed.smokeCommand.llmPath,
                parsed.smokeCommand.tokenizerDirectoryPath,
                parsed.smokeCommand.pcmPath,
            )
            return FunAsrNanoStabilityInstrumentationCommand(
                smokeCommand = parsed.smokeCommand,
                duration = parsed.duration,
            )
        }
    }
}

private data class ParsedContinuousStabilityCommand<T>(
    val smokeCommand: T,
    val duration: ContinuousStabilityDuration,
)

private object ContinuousStabilityCommandParser {
    fun <T> parse(
        entries: List<Pair<String, String>>,
        expectedMode: String,
        smokeMode: String,
        parseSmoke: (List<Pair<String, String>>) -> T,
    ): ParsedContinuousStabilityCommand<T> {
        val durationEntries = entries.filter { it.first == DURATION_KEY }
        val modeEntries = entries.filter { it.first == MODE_KEY }
        if (
            durationEntries.size != 1 ||
            modeEntries.size != 1 ||
            modeEntries.single().second != expectedMode
        ) {
            throw BenchmarkContractException(
                "continuous stability instrumentation arguments are invalid",
            )
        }
        val duration = ContinuousStabilityDuration.parse(
            durationEntries.single().second,
        )
        val smokeEntries = entries
            .filterNot { it.first == DURATION_KEY }
            .map { entry ->
                if (entry.first == MODE_KEY) MODE_KEY to smokeMode else entry
            }
        return ParsedContinuousStabilityCommand(
            smokeCommand = parseSmoke(smokeEntries),
            duration = duration,
        )
    }

    private const val MODE_KEY = "mode"
    private const val DURATION_KEY = "duration_seconds"
}

private fun requireAbsolutePaths(vararg values: String) {
    if (values.any { !File(it).isAbsolute }) {
        throw BenchmarkContractException(
            "continuous stability path argument is invalid",
        )
    }
}
