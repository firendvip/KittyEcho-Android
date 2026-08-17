package com.wordtaker.keyboard.wordtaker.speech

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ParaformerModelManagerTest : FunSpec({

    test("missing model asks once and wifi confirmation schedules one unique unmetered job") {
        val scheduler = RecordingParaformerWorkScheduler()
        val store = MemoryParaformerModelStateStore()
        val manager = ParaformerModelManager(
            modelReady = { false },
            stateStore = store,
            workScheduler = scheduler,
            partialStore = RecordingParaformerPartialStore(),
        )

        manager.onVoiceRequested() shouldBe ParaformerLifecycleAction.ShowConfirmation
        manager.confirmWifi() shouldBe ParaformerLifecycleAction.EnqueueWifi
        manager.confirmWifi() shouldBe ParaformerLifecycleAction.None

        scheduler.operations shouldContainExactly listOf(
            "enqueueUnique:${ParaformerWorkNetwork.Unmetered}",
        )
        store.saved.last().phase shouldBe ParaformerModelPhase.Queued
    }

    test("mobile confirmation uses connected network and a metered transition pauses wifi-only work") {
        val mobileScheduler = RecordingParaformerWorkScheduler()
        val mobile = ParaformerModelManager(
            modelReady = { false },
            stateStore = MemoryParaformerModelStateStore(),
            workScheduler = mobileScheduler,
            partialStore = RecordingParaformerPartialStore(),
        )
        mobile.onVoiceRequested()
        mobile.confirmMobile()
        mobileScheduler.operations shouldContainExactly listOf(
            "enqueueUnique:${ParaformerWorkNetwork.Connected}",
        )

        val wifiScheduler = RecordingParaformerWorkScheduler()
        val wifi = ParaformerModelManager(
            modelReady = { false },
            stateStore = MemoryParaformerModelStateStore(),
            workScheduler = wifiScheduler,
            partialStore = RecordingParaformerPartialStore(),
        )
        wifi.onVoiceRequested()
        wifi.confirmWifi()
        wifi.onWorkerProgress(42L)
        wifi.onNetworkChanged(connected = true, metered = true)

        wifi.state.value.phase shouldBe ParaformerModelPhase.Paused
        wifi.state.value.pauseReason shouldBe ParaformerPauseReason.MeteredNetwork
        wifiScheduler.operations shouldContainExactly listOf(
            "enqueueUnique:${ParaformerWorkNetwork.Unmetered}",
            "pauseUnique",
        )
    }

    test("process recreation restores resumable bytes and retry uses the original network consent") {
        val store = MemoryParaformerModelStateStore()
        val firstScheduler = RecordingParaformerWorkScheduler()
        val first = ParaformerModelManager(
            modelReady = { false },
            stateStore = store,
            workScheduler = firstScheduler,
            partialStore = RecordingParaformerPartialStore(),
        )
        first.onVoiceRequested()
        first.confirmMobile()
        first.onWorkerProgress(12_345L)
        first.pause(ParaformerPauseReason.User)

        val restoredScheduler = RecordingParaformerWorkScheduler()
        val restored = ParaformerModelManager(
            modelReady = { false },
            stateStore = store,
            workScheduler = restoredScheduler,
            partialStore = RecordingParaformerPartialStore(),
        )
        restored.state.value.downloadedBytes shouldBe 12_345L
        restored.state.value.phase shouldBe ParaformerModelPhase.Paused
        restored.resume() shouldBe ParaformerLifecycleAction.EnqueueConnected
        restoredScheduler.operations shouldContainExactly listOf(
            "enqueueUnique:${ParaformerWorkNetwork.Connected}",
        )
    }

    test("cancel atomically cancels unique work deletes partial files and clears persisted state") {
        val scheduler = RecordingParaformerWorkScheduler()
        val partials = RecordingParaformerPartialStore()
        val store = MemoryParaformerModelStateStore()
        val manager = ParaformerModelManager(
            modelReady = { false },
            stateStore = store,
            workScheduler = scheduler,
            partialStore = partials,
        )
        manager.onWorkerProgress(99L)

        manager.cancel()

        scheduler.operations shouldContainExactly listOf("cancelUnique")
        partials.deleteCalls shouldBe 1
        store.cleared shouldBe 1
        manager.state.value.phase shouldBe ParaformerModelPhase.Uninstalled
        manager.state.value.downloadedBytes shouldBe 0L
    }

    test("ready model starts voice immediately while install completion never defers a microphone start") {
        val ready = ParaformerModelManager(
            modelReady = { true },
            stateStore = MemoryParaformerModelStateStore(),
            workScheduler = RecordingParaformerWorkScheduler(),
            partialStore = RecordingParaformerPartialStore(),
        )
        ready.onVoiceRequested() shouldBe ParaformerLifecycleAction.StartVoice

        val installing = ParaformerModelManager(
            modelReady = { false },
            stateStore = MemoryParaformerModelStateStore(),
            workScheduler = RecordingParaformerWorkScheduler(),
            partialStore = RecordingParaformerPartialStore(),
        )
        installing.onInstalled()
        installing.onInitialized()
        installing.consumePendingVoiceStart() shouldBe false
    }
})

private class MemoryParaformerModelStateStore(
    private var snapshot: ParaformerLifecycleSnapshot = ParaformerLifecycleSnapshot(),
) : ParaformerModelStateStore {
    val saved = mutableListOf<ParaformerLifecycleSnapshot>()
    var cleared = 0

    override fun load(): ParaformerLifecycleSnapshot = snapshot

    override fun save(snapshot: ParaformerLifecycleSnapshot) {
        this.snapshot = snapshot
        saved += snapshot
    }

    override fun clear() {
        cleared += 1
        snapshot = ParaformerLifecycleSnapshot()
    }
}

private class RecordingParaformerWorkScheduler : ParaformerWorkScheduler {
    val operations = mutableListOf<String>()

    override fun enqueueUnique(network: ParaformerWorkNetwork) {
        operations += "enqueueUnique:$network"
    }

    override fun pauseUnique() {
        operations += "pauseUnique"
    }

    override fun cancelUnique() {
        operations += "cancelUnique"
    }
}

private class RecordingParaformerPartialStore : ParaformerPartialStore {
    var deleteCalls = 0

    override fun deleteAll() {
        deleteCalls += 1
    }
}
