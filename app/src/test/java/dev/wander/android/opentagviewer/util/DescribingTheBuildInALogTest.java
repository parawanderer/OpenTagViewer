package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What a log says about the build that wrote it.
 *
 * <p><b>Because a bug report's first two questions were both answerable from the log and neither
 * was answered in it.</b> The issue template asks for the app version and for which exporter made
 * the bundle; the app knew both and said neither, so reporters guessed at the first and, for the
 * second, were told to open an export zip full of their tags' private keys to read one line.
 *
 * <p>Only the composition is tested here - reading logcat needs a device and is not the part that
 * can be subtly wrong. Rule 13.
 */
public class DescribingTheBuildInALogTest {

    /**
     * A release is exactly what its version says.
     *
     * <p>Built from a tag, so there is nothing a commit would add.
     */
    @Test
    public void areleaseIsNamedByItsVersionAlone() {
        assertEquals("1.0.5", LogCollectorUtil.describeBuild("1.0.5", null));
    }

    /**
     * <b>A build from a checkout is named by its commit, because its version lies.</b>
     *
     * <p>{@code versionName} is a committed literal: every commit after 1.0.5 reports 1.0.5, and
     * {@code build-debug.yml} publishes a debug APK, so this is not hypothetical - somebody can
     * be running a months-stale version string. Without the commit, "1.0.5-debug" is
     * indistinguishable between a build made today and one from the release itself.
     */
    @Test
    public void acheckoutBuildCarriesTheCommitItsVersionCannotGive() {
        final String described = LogCollectorUtil.describeBuild("1.0.5-debug", "25e1679");

        assertTrue("the commit is what identifies this build: " + described,
                described.contains("25e1679"));
        assertTrue("and the version is still worth having", described.contains("1.0.5-debug"));
    }

    /**
     * Nothing is invented when there is no commit to name.
     *
     * <p>A source zip off a release tag is not a checkout - there is no git to ask - and the
     * version is right again. Printing "unknown" or an empty bracket would be noise dressed as
     * information.
     */
    @Test
    public void amissingCommitIsAbsentRatherThanGuessedAt() {
        final String described = LogCollectorUtil.describeBuild("1.0.5", null);

        assertEquals("1.0.5", described);
        assertTrue("no empty brackets, no 'unknown'", described.indexOf('(') < 0);
    }
}
