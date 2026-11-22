package dev.wander.android.opentagviewer.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Value;

/**
 * Asks GitHub whether a newer app release than this one has been published.
 *
 * <p><b>Opt-in, and the reason is the request itself:</b> it tells GitHub this phone's IP
 * address. So nothing calls this unless the user turned update checks on, in onboarding or in
 * Settings, and then at most once a day ({@link UpdatePolicy}).
 *
 * <p>Only finding out. "Update" opens the release page, and the user installs the APK the way
 * they installed this one. No download and no install permission, by @parawanderer's choice.
 *
 * <p><b>The releases list is shared with the desktop exporter</b>, so the newest release overall
 * is often an {@code exporter-v*} one. Only {@code android-app-v<numbers>} tags count, and drafts
 * and pre-releases never do.
 */
public final class UpdateCheck {

    public static final String RELEASES_URL =
            "https://api.github.com/repos/parawanderer/OpenTagViewer/releases?per_page=30";

    /**
     * The only pages this will ever send somebody to. The URL comes from GitHub's answer, and
     * an answer is not something to open in a browser unchecked.
     */
    static final String RELEASE_PAGES = "https://github.com/parawanderer/OpenTagViewer/releases/";

    private static final Pattern APP_TAG = Pattern.compile("^android-app-v(\\d+(?:\\.\\d+)*)$");
    private static final Pattern VERSION = Pattern.compile("^(\\d+(?:\\.\\d+)*)");
    private static final int TIMEOUT_MS = 15_000;

    /** A published app release: its version, and where to read about and download it. */
    @Value
    public static class Release {
        String version;
        String pageUrl;
    }

    private final String url;

    public UpdateCheck() {
        this(RELEASES_URL);
    }

    /** For UpdateCheckTest, which serves the list from a local socket. */
    UpdateCheck(final String url) {
        this.url = url;
    }

    /** The newest published app release, or empty if GitHub lists none. */
    public Optional<Release> newest() throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(this.url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            // GitHub refuses API requests that carry no User-Agent. A generic one: this is the
            // app asking, and nothing about the user belongs in it.
            connection.setRequestProperty("User-Agent", "OpenTagViewer-update-check");
            final int status = connection.getResponseCode();
            if (status >= 400) {
                throw new IOException("GitHub answered HTTP " + status + " for the releases list");
            }
            try (InputStream in = connection.getInputStream()) {
                return newestIn(readAll(in));
            }
        } finally {
            connection.disconnect();
        }
    }

    /** The newest app release in a releases-list answer. */
    static Optional<Release> newestIn(final String json) throws IOException {
        final JsonNode releases = new ObjectMapper().readTree(json);
        Release newest = null;
        if (releases == null || !releases.isArray()) {
            return Optional.empty();
        }
        for (final JsonNode release : releases) {
            if (release.path("draft").asBoolean(false)
                    || release.path("prerelease").asBoolean(false)) {
                continue;
            }
            final Matcher tag = APP_TAG.matcher(release.path("tag_name").asText(""));
            final String page = release.path("html_url").asText("");
            if (!tag.matches() || !page.startsWith(RELEASE_PAGES)) {
                continue;
            }
            if (newest == null || compare(tag.group(1), newest.getVersion()) > 0) {
                newest = new Release(tag.group(1), page);
            }
        }
        return Optional.ofNullable(newest);
    }

    /**
     * Whether {@code candidate} is newer than {@code installed}.
     *
     * <p>{@code installed} is {@code BuildConfig.VERSION_NAME}, which a debug build suffixes with
     * {@code -debug}; only the leading numbers are compared. A version this cannot read is never
     * reported as older or newer, so a strange name cannot produce an offer.
     */
    public static boolean isNewer(final String candidate, final String installed) {
        final Matcher theirs = VERSION.matcher(candidate == null ? "" : candidate);
        final Matcher ours = VERSION.matcher(installed == null ? "" : installed);
        return theirs.find() && ours.find() && compare(theirs.group(1), ours.group(1)) > 0;
    }

    /** Dotted numbers, part by part; a missing part is 0, so 1.1 equals 1.1.0. */
    static int compare(final String a, final String b) {
        final String[] left = a.split("\\.");
        final String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            final long l = i < left.length ? Long.parseLong(left[i]) : 0;
            final long r = i < right.length ? Long.parseLong(right[i]) : 0;
            if (l != r) {
                return Long.compare(l, r);
            }
        }
        return 0;
    }

    private static String readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
