package android.util;

/**
 * Narrow JVM-test replacement for the two Android Log methods used by VoiceViewModel.
 *
 * This avoids globally returning defaults for every unmocked Android API.
 */
public final class Log {
    private Log() {}

    public static int d(String tag, String message) {
        return 0;
    }

    public static int i(String tag, String message) {
        return 0;
    }
}
