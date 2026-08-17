package com.wordtaker.keyboard.wordtaker.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Synchronous connectivity boundary used to decide whether cloud polish may start. */
fun interface InternetConnection {
    fun isAvailable(): Boolean
}

/** Minimal capability snapshot so all online/offline combinations are JVM-testable. */
data class ActiveNetworkState(
    val hasInternet: Boolean,
    val isValidated: Boolean,
)

/** Reads the current active network without exposing Android framework types to policy tests. */
fun interface ActiveNetworkStateReader {
    fun read(): ActiveNetworkState?
}

/**
 * Allows an HTTPS backend attempt when Android reports Internet capability.
 *
 * Android can report PARTIAL_CONNECTIVITY without VALIDATED when its captive-portal probe is
 * blocked even though this app's backend is reachable. The backend request remains the source of
 * truth and safely falls back to raw text on any network failure.
 * Missing services, permissions, networks, capabilities, or platform failures remain offline.
 */
class ValidatedInternetConnection(
    private val stateReader: ActiveNetworkStateReader,
) : InternetConnection {

    override fun isAvailable(): Boolean {
        val state = runCatching { stateReader.read() }.getOrNull() ?: return false
        return state.hasInternet
    }
}

/** Android adapter for [ValidatedInternetConnection]. */
class AndroidActiveNetworkStateReader(
    context: Context,
) : ActiveNetworkStateReader {
    private val appContext = context.applicationContext

    override fun read(): ActiveNetworkState? = try {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null
        val activeNetwork = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork) ?: return null
        ActiveNetworkState(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
