package com.wordtaker.keyboard.wordtaker.di

import android.content.Context
import com.wordtaker.keyboard.wordtaker.account.AccountRepository
import com.wordtaker.keyboard.wordtaker.audio.ToneController
import com.wordtaker.keyboard.wordtaker.backend.BackendClient
import com.wordtaker.keyboard.wordtaker.backend.DeviceIdentity
import com.wordtaker.keyboard.wordtaker.backend.TokenStore
import com.wordtaker.keyboard.wordtaker.history.HistoryDatabase
import com.wordtaker.keyboard.wordtaker.history.HistoryRepository
import com.wordtaker.keyboard.wordtaker.network.AndroidActiveNetworkStateReader
import com.wordtaker.keyboard.wordtaker.network.InternetConnection
import com.wordtaker.keyboard.wordtaker.network.ValidatedInternetConnection
import com.wordtaker.keyboard.wordtaker.polish.OnlineOnlyPolisher
import com.wordtaker.keyboard.wordtaker.polish.Polisher
import com.wordtaker.keyboard.wordtaker.polish.RealPolisher
import com.wordtaker.keyboard.wordtaker.settings.SettingsRepository
import com.wordtaker.keyboard.wordtaker.speech.ParaformerModelManager
import com.wordtaker.keyboard.wordtaker.speech.RealSpeechEngine
import com.wordtaker.keyboard.wordtaker.speech.SpeechEngine

/**
 * Minimal manual dependency container (no Hilt). Holds process-wide singletons
 * constructed lazily from an application [Context].
 *
 * Engine wiring:
 *  - ASR     -> RealSpeechEngine (whole-utterance Paraformer, on-device, fail closed)
 *  - Polish  -> validated-network gate -> RealPolisher(billing backend only)
 *  - History -> Room repository            [real, 入库]
 *  - Settings-> SettingsRepository (DataStore) [real]
 *  - Tone    -> ToneController             [real]
 */
object AppGraph {

    private val appContextRef = java.util.concurrent.atomic.AtomicReference<Context?>(null)

    fun init(context: Context) {
        appContextRef.compareAndSet(null, context.applicationContext)
    }

    private fun requireContext(): Context =
        appContextRef.get() ?: error("AppGraph.init(context) must be called before use")

    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(HistoryDatabase.get(requireContext()).historyDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(requireContext())
    }

    val toneController: ToneController by lazy { ToneController(requireContext()) }

    // ASR has no network fallback: missing/corrupt private bytes are repaired from APK assets.
    private val realSpeechEngine: RealSpeechEngine by lazy { RealSpeechEngine(requireContext()) }

    val speechEngine: SpeechEngine by lazy { realSpeechEngine }

    internal val paraformerModelManager: ParaformerModelManager by lazy {
        ParaformerModelManager(
            state = realSpeechEngine.modelState,
            retryPreparation = realSpeechEngine::retryModelPreparation,
        )
    }

    // Backend billing/auth stack (阶段3): token store + API client + account repo.
    val tokenStore: TokenStore by lazy { TokenStore(requireContext()) }

    val backendClient: BackendClient by lazy {
        BackendClient(
            deviceId = DeviceIdentity.get(requireContext()),
            authSessionProvider = tokenStore::authRequestSession,
        )
    }

    val accountRepository: AccountRepository by lazy {
        AccountRepository(backendClient, tokenStore)
    }

    val internetConnection: InternetConnection by lazy {
        ValidatedInternetConnection(AndroidActiveNetworkStateReader(requireContext()))
    }

    // Offline returns raw locally. Online uses the billing backend as the only cloud boundary.
    val polisher: Polisher by lazy {
        OnlineOnlyPolisher(
            internetConnection = internetConnection,
            onlineDelegate = RealPolisher(
                backend = backendClient,
                onAuthExpired = { accountRepository.invalidateAuthentication() },
            ),
        )
    }
}
