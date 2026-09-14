package com.wordtaker.keyboard.wordtaker.di

import android.content.Context
import com.wordtaker.keyboard.BuildConfig
import com.wordtaker.keyboard.wordtaker.account.AccountRepository
import com.wordtaker.keyboard.wordtaker.account.AndroidPassportPendingStore
import com.wordtaker.keyboard.wordtaker.account.PassportLoginController
import com.wordtaker.keyboard.wordtaker.account.PassportOidcConfig
import com.wordtaker.keyboard.wordtaker.account.PassportOidcFlow
import com.wordtaker.keyboard.wordtaker.audio.ToneController
import com.wordtaker.keyboard.wordtaker.backend.BackendClient
import com.wordtaker.keyboard.wordtaker.backend.DeviceIdentity
import com.wordtaker.keyboard.wordtaker.backend.OidcTokenManager
import com.wordtaker.keyboard.wordtaker.backend.PassportOidcTokenClient
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
import com.wordtaker.keyboard.wordtaker.speech.RealSpeechEngine
import com.wordtaker.keyboard.wordtaker.speech.SpeechEngine
import com.wordtaker.keyboard.wordtaker.speech.ParaformerAndroidModelStateStore
import com.wordtaker.keyboard.wordtaker.speech.ParaformerAndroidPartialStore
import com.wordtaker.keyboard.wordtaker.speech.ParaformerModelManager
import com.wordtaker.keyboard.wordtaker.speech.ParaformerWorkManagerScheduler

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

    // ASR has no production fallback: model absence/corruption is a typed user-visible failure.
    val speechEngine: SpeechEngine by lazy {
        RealSpeechEngine(requireContext())
    }

    internal val paraformerModelManager: ParaformerModelManager by lazy {
        ParaformerModelManager(
            modelReady = { speechEngine.isReady() },
            stateStore = ParaformerAndroidModelStateStore(requireContext()),
            workScheduler = ParaformerWorkManagerScheduler(requireContext()),
            partialStore = ParaformerAndroidPartialStore(requireContext()),
        )
    }

    // Backend billing/auth stack (阶段3): token store + API client + account repo.
    val tokenStore: TokenStore by lazy { TokenStore(requireContext()) }

    private val passportConfig by lazy {
        PassportOidcConfig(
            enabled = BuildConfig.WANGSAN_PASSPORT_ENABLED,
            issuer = BuildConfig.WANGSAN_PASSPORT_ISSUER,
            clientId = BuildConfig.WANGSAN_PASSPORT_CLIENT_ID,
            redirectUri = BuildConfig.WANGSAN_PASSPORT_REDIRECT_URI,
        )
    }

    private val passportTokenClient by lazy { PassportOidcTokenClient(passportConfig) }

    private val oidcTokenManager by lazy {
        OidcTokenManager(
            store = tokenStore,
            legacyTokenProvider = tokenStore::legacyAccessToken,
            tokenApi = passportTokenClient,
            passportEnabled = passportConfig.enabled,
        )
    }

    val backendClient: BackendClient by lazy {
        BackendClient(
            deviceId = DeviceIdentity.get(requireContext()),
            tokenProvider = oidcTokenManager::accessToken,
            tokenRefresher = oidcTokenManager::refreshAfterUnauthorized,
        )
    }

    val accountRepository: AccountRepository by lazy {
        AccountRepository(backendClient, tokenStore)
    }

    val passportLogin: PassportLoginController by lazy {
        PassportLoginController(
            flow = PassportOidcFlow(
                config = passportConfig,
                pendingStore = AndroidPassportPendingStore(requireContext()),
            ),
            tokenApi = passportTokenClient,
            repository = accountRepository,
        )
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
