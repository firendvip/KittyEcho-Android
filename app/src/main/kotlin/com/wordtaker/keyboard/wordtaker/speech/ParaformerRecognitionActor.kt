package com.wordtaker.keyboard.wordtaker.speech

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

fun interface WholeUtteranceDecoder {
    suspend fun decode(samples: FloatArray): AsrResult
}

internal interface PreparableWholeUtteranceDecoder : WholeUtteranceDecoder {
    suspend fun prepare()
    fun isReady(): Boolean
    fun readinessFailure(): AsrFailureException?
}

/** Single-owner FIFO actor for the process-wide Paraformer recognizer. */
internal class ParaformerRecognitionActor(
    private val decoder: WholeUtteranceDecoder,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private sealed interface Command {
        data class Prepare(val completion: CompletableDeferred<Unit>?) : Command
        data class Decode(
            val samples: FloatArray,
            val result: CompletableDeferred<AsrResult>,
        ) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val worker: Job = scope.launch {
        for (command in commands) {
            when (command) {
                is Command.Prepare -> prepareDecoder(command.completion)
                is Command.Decode -> decode(command)
            }
        }
    }

    fun prepareAsync() {
        if (!closed.get()) commands.trySend(Command.Prepare(null))
    }

    suspend fun prepareAndAwait() {
        val completion = CompletableDeferred<Unit>()
        if (closed.get() || commands.trySend(Command.Prepare(completion)).isFailure) {
            throw AsrInitializationException()
        }
        completion.await()
    }

    fun isReady(): Boolean =
        (decoder as? PreparableWholeUtteranceDecoder)?.isReady() ?: true

    fun readinessFailure(): AsrFailureException? =
        (decoder as? PreparableWholeUtteranceDecoder)?.readinessFailure()

    fun submit(samples: FloatArray): PendingAsrResult {
        val result = CompletableDeferred<AsrResult>()
        val command = Command.Decode(samples.copyOf(), result)
        if (closed.get() || commands.trySend(command).isFailure) {
            result.completeExceptionally(AsrDecodeException())
        }
        return PendingAsrResult { result.await() }
    }

    private suspend fun prepareDecoder(completion: CompletableDeferred<Unit>?) {
        val preparable = decoder as? PreparableWholeUtteranceDecoder
        if (preparable == null) {
            completion?.complete(Unit)
            return
        }
        try {
            preparable.prepare()
            completion?.complete(Unit)
        } catch (error: Throwable) {
            completion?.completeExceptionally(error)
        }
    }

    private suspend fun decode(command: Command.Decode) {
        try {
            command.result.complete(decoder.decode(command.samples))
        } catch (error: AsrFailureException) {
            command.result.completeExceptionally(error)
        } catch (error: OutOfMemoryError) {
            command.result.completeExceptionally(AsrOutOfMemoryException(error))
        } catch (error: Throwable) {
            command.result.completeExceptionally(AsrDecodeException(error))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        commands.close()
        worker.cancel()
        runCatching { (decoder as? AutoCloseable)?.close() }
    }
}
