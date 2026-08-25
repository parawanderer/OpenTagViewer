package dev.wander.android.opentagviewer.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class LogCollectorUtil {
    private static final String TAG = LogCollectorUtil.class.getSimpleName();

    /**
     * How many lines of logcat to ask for.
     *
     * <p><b>Five hundred was fourteen seconds on the phone that showed it up.</b> A report of a
     * tag showing no location arrived with a log covering 17:37:54 to 17:38:08 - and in it, one
     * of the reporter's four tags had finished fetching. Fetches run one at a time and take
     * several seconds each, so the log ended before the app had said anything about three of the
     * four things being asked about. Nothing was wrong with the capture; there was simply no
     * room in it.
     *
     * <p>Line count is a bad proxy for time, and how bad depends on the phone. In that capture
     * only a quarter of the lines came from this app at all: 196 of 501 were a single MIUI
     * layout message repeating, and the rest was rendering and vendor chatter from libraries
     * running inside our own process. A user on a quieter device gets minutes out of the same
     * budget; this one got a quarter of a minute.
     *
     * <p>Deliberately not solved by filtering. Every tag that could be dropped is chatter
     * <i>today</i>, and a deny-list that silently removes a line which turns out to matter fails
     * in the way this project can least afford - invisibly, in a file somebody has already sent.
     * Asking for ten times as much costs a larger attachment and hides nothing.
     *
     * <p>The device's own ring buffer is the real ceiling and is smaller than this on many
     * phones, which is why {@link #describeVolume} reports what came back rather than what was
     * asked for.
     */
    private static final int LINES_REQUESTED = 5000;

    public static String getLastLogs() {
        try {
            Log.d(TAG, String.format("Reading last %d log lines from logcat...", LINES_REQUESTED));
            Process process = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-t", String.valueOf(LINES_REQUESTED)});

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

    /**
     * How much log this is, and - the point of it - <b>whether anything was cut off</b>.
     *
     * <p>A short log and a truncated one are the same file from outside, and they mean opposite
     * things. If the buffer ran dry, what is here is everything the device had and there is no
     * point asking for more. If this limit cut it, the interesting part may be just above the
     * first line, and the answer is to reproduce and capture again sooner.
     *
     * <p>Reading the report that prompted this, there was no way to tell which had happened. It
     * turned out to be the limit - but only someone with the source open could know that, which
     * is the wrong person to need.
     *
     * @param requested what was asked of logcat.
     * @param logs      what came back, one line per {@code \n} as {@link #getLastLogs} writes it.
     */
    static String describeVolume(final int requested, final String logs) {
        final int returned = countLines(logs);

        // >= rather than ==: logcat is free to hand back a line more than asked for, and a
        // report saying "the limit cut it" is right in that case too.
        return returned >= requested
                ? returned + " lines, which is this app's limit - anything older was cut here"
                : returned + " lines, which is everything the device still had (asked for "
                        + requested + ")";
    }

    /**
     * Lines in a block of text, counting a final line that has no newline after it.
     *
     * <p>Split out because getting it wrong is off-by-one in a number somebody uses to decide
     * whether to re-capture, and because it is the only part of {@link #describeVolume} with
     * anywhere to hide.
     */
    static int countLines(final String logs) {
        if (logs == null || logs.isEmpty()) {
            return 0;
        }

        int lines = 0;
        for (int i = 0; i < logs.length(); i++) {
            if (logs.charAt(i) == '\n') {
                lines++;
            }
        }

        // A trailing newline ends the last line rather than starting another.
        return logs.charAt(logs.length() - 1) == '\n' ? lines : lines + 1;
    }

    /**
     * The log, with a few lines at each end saying what produced it.
     *
     * <p><b>Because the questions a bug report opens with are all answerable here.</b> The issue
     * template asks for the app version and for which exporter wrote the bundle, and both were
     * things the log already knew and never said - so every report either guessed, or went and
     * opened an export zip full of private keys to read one line out of it.
     *
     * <p><b>At both ends, which is not belt and braces.</b> Thousands of lines is far more than
     * anyone pastes: somebody who has found the interesting part copies the tail around it, and a
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
    public static String getLastLogsWithHeader(
            final String appVersion, final String buildCommit, final String importedVia) {
        final String what = "OpenTagViewer app " + describeBuild(appVersion, buildCommit)
                + " | tags imported from: "
                + (importedVia == null ? "nothing - no bundle imported" : importedVia);

        final String logs = getLastLogs();

        return what + "\n"
                + "This device's log follows, unfiltered by the app: "
                + describeVolume(LINES_REQUESTED, logs) + ".\n\n"
                + logs
                + "\n" + what + "\n";
    }
}
