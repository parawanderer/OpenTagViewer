package dev.wander.android.opentagviewer.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Optional;

import dev.wander.android.opentagviewer.source.FakeGooglefindServer;

/**
 * Finding the newest app release in GitHub's list, which it shares with the desktop exporter.
 */
public class UpdateCheckTest {

    private static String release(final String tag, final boolean draft, final boolean pre) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":" + draft + ",\"prerelease\":" + pre
                + ",\"html_url\":\"https://github.com/parawanderer/OpenTagViewer/releases/tag/"
                + tag + "\"}";
    }

    private static String list(final String... releases) {
        return "[" + String.join(",", releases) + "]";
    }

    private FakeGooglefindServer github;

    @Before
    public void startGitHub() throws IOException {
        this.github = new FakeGooglefindServer();
    }

    @After
    public void stopGitHub() throws IOException {
        this.github.close();
    }

    private UpdateCheck againstTheFake() {
        return new UpdateCheck(this.github.url() + "/repos/parawanderer/OpenTagViewer/releases");
    }

    @Test
    public void theNewestAppReleaseWinsOverANewerExporterRelease() throws IOException {
        final Optional<UpdateCheck.Release> newest = UpdateCheck.newestIn(list(
                release("exporter-v1.5.2", false, false),
                release("android-app-v1.1.2", false, false),
                release("android-app-v1.2.0", false, false),
                release("macos-exporter-v1.0.3.2-test", false, false)));

        assertEquals("1.2.0", newest.get().getVersion());
        assertEquals("https://github.com/parawanderer/OpenTagViewer/releases/tag/android-app-v1.2.0",
                newest.get().getPageUrl());
    }

    @Test
    public void draftsAndPreReleasesAreNeverOffered() throws IOException {
        final Optional<UpdateCheck.Release> newest = UpdateCheck.newestIn(list(
                release("android-app-v1.3.0", true, false),
                release("android-app-v1.2.0", false, true),
                release("android-app-v1.1.2", false, false)));

        assertEquals("1.1.2", newest.get().getVersion());
    }

    @Test
    public void listOrderDoesNotMatter() throws IOException {
        final Optional<UpdateCheck.Release> newest = UpdateCheck.newestIn(list(
                release("android-app-v1.0.10", false, false),
                release("android-app-v1.0.9", false, false)));

        assertEquals("1.0.10", newest.get().getVersion());
    }

    @Test
    public void aPageOutsideThisRepositoryIsNeverOffered() throws IOException {
        final String elsewhere = "{\"tag_name\":\"android-app-v9.0.0\",\"draft\":false,"
                + "\"prerelease\":false,\"html_url\":\"https://example.com/releases/evil.apk\"}";

        assertFalse(UpdateCheck.newestIn(list(elsewhere)).isPresent());
    }

    @Test
    public void noAppReleaseAtAllIsEmpty() throws IOException {
        assertFalse(UpdateCheck.newestIn(list(release("exporter-v1.5.2", false, false)))
                .isPresent());
        assertFalse(UpdateCheck.newestIn("[]").isPresent());
        assertFalse(UpdateCheck.newestIn("{\"message\":\"Not Found\"}").isPresent());
    }

    @Test
    public void versionsCompareByNumberNotByText() {
        assertTrue(UpdateCheck.isNewer("1.2.0", "1.1.2"));
        assertTrue(UpdateCheck.isNewer("1.0.10", "1.0.9"));
        assertTrue(UpdateCheck.isNewer("1.0.3.1", "1.0.3"));
        assertFalse(UpdateCheck.isNewer("1.1.2", "1.1.2"));
        assertFalse(UpdateCheck.isNewer("1.1", "1.1.0"));
        assertFalse(UpdateCheck.isNewer("1.1.1", "1.1.2"));
    }

    @Test
    public void aDebugBuildComparesItsNumbersOnly() {
        assertTrue(UpdateCheck.isNewer("1.2.0", "1.1.2-debug"));
        assertFalse(UpdateCheck.isNewer("1.1.2", "1.1.2-debug"));
    }

    @Test
    public void anUnreadableVersionIsNeverAnOffer() {
        assertFalse(UpdateCheck.isNewer("", "1.1.2"));
        assertFalse(UpdateCheck.isNewer(null, "1.1.2"));
        assertFalse(UpdateCheck.isNewer("1.2.0", "unknown"));
    }

    @Test
    public void itAsksGitHubWithAUserAgentAndNothingElseAboutTheUser() throws IOException {
        this.github.answer("/repos/parawanderer/OpenTagViewer/releases", 200,
                list(release("android-app-v1.2.0", false, false)));

        final Optional<UpdateCheck.Release> newest = this.againstTheFake().newest();

        assertEquals("1.2.0", newest.get().getVersion());
        final FakeGooglefindServer.Request asked = this.github.lastRequest();
        assertEquals("GET", asked.method);
        assertEquals("OpenTagViewer-update-check", asked.headers.get("user-agent"));
        assertFalse("no credentials go to GitHub", asked.headers.containsKey("authorization"));
    }

    @Test
    public void aRefusalIsAnErrorNotNoUpdate() {
        this.github.answer("/repos/parawanderer/OpenTagViewer/releases", 403,
                "{\"message\":\"API rate limit exceeded\"}");

        assertThrows(IOException.class, () -> this.againstTheFake().newest());
    }
}
