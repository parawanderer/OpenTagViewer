package dev.wander.android.opentagviewer.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class LogCollectorUtil {
    private static final String TAG = LogCollectorUtil.class.getSimpleName();

    private static final int NUM_LINES_UP = 500;

    public static String getLastLogs() {
        try {
            Log.d(TAG, String.format("Reading last %d log lines from logcat...", NUM_LINES_UP));
            Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-t", String.valueOf(NUM_LINES_UP)});

            var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            var sb = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The log, with a few lines at each end saying what produced it.
     *
     * <p><b>Because the questions a bug report opens with are all answerable here.</b> The issue
     * template asks for the app version and for which exporter wrote the bundle, and both were
     * things the log already knew and never said - so every report either guessed, or went and
     * opened an export zip full of private keys to read one line out of it.
     *
     * <p><b>At both ends, which is not belt and braces.</b> Five hundred lines is more than most
     * people paste: somebody who has found the interesting part copies the tail around it, and a
     * header is exactly the part that gets left behind. Repeating it costs three lines and means
     * either end of an excerpt still says what it came from.
     *
     * <p>Kept to what identifies the build and the data, and nothing about the person: no account,
     * no tag names, no identifiers. A header that leaked would be worse than none, because it
     * arrives above content people have been told to read before posting, in the position they
     * skim past as boilerplate.
     *
     * @param appVersion  what the app calls itself - {@code BuildConfig.VERSION_NAME}.
     * @param importedVia the {@code via:} of the most recent import, or null if nothing has been
     *                    imported: an account-connected install genuinely has no bundle behind it,
     *                    and saying so is an answer rather than a gap.
     */
    /**
     * What to call this build in a log, so a report says which one produced it.
     *
     * <p><b>{@code versionName} alone is not the answer on a checkout.</b> It is a committed
     * literal, so every commit after a release reports the old version perfectly confidently -
     * and {@code build-debug.yml} publishes a debug APK artifact, so somebody can be running a
     * build whose version string is months stale. {@code BUILD_COMMIT} is set for debug builds
     * only and is what identifies those.
     *
     * <p>The same three cases the exporter's {@code describe_build()} distinguishes, for the same
     * reason: a release is exactly what its version says, a checkout is its commit, and anything
     * without one falls back to the version rather than inventing something.
     */
    public static String describeBuild(final String appVersion, final String buildCommit) {
        return buildCommit == null ? appVersion : appVersion + " (" + buildCommit + ")";
    }

    public static String getLastLogsWithHeader(
            final String appVersion, final String buildCommit, final String importedVia) {
        final String what = "OpenTagViewer app " + describeBuild(appVersion, buildCommit)
                + " | tags imported from: "
                + (importedVia == null ? "nothing - no bundle imported" : importedVia);

        return what + "\n"
                + "The last " + NUM_LINES_UP + " lines of this device's log follow, "
                + "unfiltered by the app.\n\n"
                + getLastLogs()
                + "\n" + what + "\n";
    }
}
