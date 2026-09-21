package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow

class ParaformerModelManagerTest : FunSpec({

    test("manager exposes automatic preparation state and retry without network consent") {
        val state = MutableStateFlow(ParaformerLifecycleState())
        var retries = 0
        val manager = ParaformerModelManager(state) { retries += 1 }

        manager.state.value.phase shouldBe ParaformerModelPhase.Installing
        state.value = ParaformerLifecycleState(
            ParaformerModelPhase.Error,
            ParaformerModelFailure.Integrity,
        )
        manager.retry()

        manager.state.value.failure shouldBe ParaformerModelFailure.Integrity
        retries shouldBe 1
    }

    test("every automatic preparation state and failure has user-visible presentation") {
        ParaformerModelPhase.entries.forEach { phase ->
            ParaformerModelPresentation.forState(
                ParaformerLifecycleState(phase = phase),
            ).status.isNotBlank() shouldBe true
        }
        ParaformerModelFailure.entries.forEach { failure ->
            ParaformerModelPresentation.forFailure(failure).detail.isNotBlank() shouldBe true
        }
    }
})
