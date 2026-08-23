package dev.wander.android.opentagviewer.anisette;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Pulls Apple's ADI libraries out of the Apple Music APK on Apple's own CDN, without
 * downloading the 142 MB APK.
 *
 * <p>A zip's central directory sits at the end of the file, so with HTTP range requests we can
 * read the directory, find the members we want, and fetch only those. The libraries we need
 * come to roughly 11 MB rather than 142 MB.
 *
 * <p>This exists so the app never has to redistribute Apple's binaries - the same position
 * every public Anisette server is in.
 *
 * <p>What arrives is verified against {@code assets/adi-libraries.json} before anything is
 * loaded from it. That file records the exact SHA-256 of each library, taken from Apple's own
 * CDN when the stub symbol lists were generated. Apple serve one "latest" URL with no
 * versioned variant, so the build cannot be pinned by asking for an old one - but what we are
 * willing to execute can be, and that is the half that matters. A mismatch means Apple shipped
 * a new build whose obfuscated symbols may have moved, and the right response is to fall back
 * to a remote Anisette server rather than call into something we cannot vouch for.
 */
public final class AdiLibraryFetcher {
    private static final String TAG = "AdiLibraryFetcher";

    private static final String APK_URL =
            "https://apps.mzstatic.com/content/android-apple-music-apk/applemusic.apk";

    private static final byte[] EOCD_SIGNATURE = {'P', 'K', 5, 6};
    private static final byte[] CENTRAL_SIGNATURE = {'P', 'K', 1, 2};

    /** The most a zip comment can be, plus the record itself - how far back the EOCD can hide. */
    private static final int EOCD_SEARCH_WINDOW = 65536 + 22;

    private AdiLibraryFetcher() {
    }

    /** One member of the APK's central directory - enough to fetch it later. */
    private static final class Entry {
        final int method;
        final long compressedSize;
        final long localHeaderOffset;

        Entry(int method, long compressedSize, long localHeaderOffset) {
            this.method = method;
            this.compressedSize = compressedSize;
            this.localHeaderOffset = localHeaderOffset;
        }
    }

    /**
     * Download {@code names} (bare file names, e.g. {@code libCoreADI.so}) for {@code abi} into
     * {@code destination}, skipping any that are already there.
     *
     * @return the number of bytes actually pulled over the network
     */
    public static long fetchInto(File destination, String abi, List<String> names)
            throws IOException {
        return fetchInto(destination, abi, names, APK_URL);
    }

    /**
     * The same, against a given URL.
     *
     * <p>Exists so the zip walking can be tested, which is the half of this that can be wrong
     * without Apple changing anything: the EOCD search, the central directory parse, the range
     * arithmetic and the inflate. The weekly {@code check-adi-libraries} workflow watches Apple's
     * file from the outside and says nothing about whether this code still reads it correctly.
     *
     * <p><b>A parameter rather than a settable static.</b> These are static methods called from
     * more than one thread - the whole reason {@code LoadedOnce} exists - so a URL field swapped
     * by a test is a race in the app's own code path, and one a test could leave behind.
     */
    static long fetchInto(File destination, String abi, List<String> names, String apkUrl)
            throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("could not create " + destination);
        }

        final List<String> wanted = new ArrayList<>();
        for (final String name : names) {
            if (!new File(destination, name).isFile()) {
                wanted.add(name);
            }
        }
        if (wanted.isEmpty()) {
            Log.i(TAG, "every library is already cached in " + destination);
            return 0;
        }

        final long apkSize = contentLength(apkUrl);
        final byte[] centralDirectory = readCentralDirectory(apkUrl, apkSize);
        final Map<String, Entry> index = parseCentralDirectory(centralDirectory, abi, wanted);

        long downloaded = centralDirectory.length;
        for (final String name : wanted) {
            final Entry entry = index.get(name);
            if (entry == null) {
                throw new IOException(
                        "lib/" + abi + "/" + name + " is not in the APK - has Apple changed its "
                        + "layout, or does this build not ship " + abi + "?");
            }

            final byte[] contents = extract(apkUrl, entry);
            if (contents.length < 4 || contents[0] != 0x7f || contents[1] != 'E'
                    || contents[2] != 'L' || contents[3] != 'F') {
                throw new IOException(name + " does not look like an ELF file");
            }

            try (FileOutputStream out = new FileOutputStream(new File(destination, name))) {
                out.write(contents);
            }
            downloaded += entry.compressedSize;
            Log.i(TAG, String.format("%s: %.2f MB unpacked", name, contents.length / 1e6));
        }

        Log.i(TAG, String.format("downloaded %.1f MB of %.0f MB (%.1f%%)",
                downloaded / 1e6, apkSize / 1e6, 100.0 * downloaded / apkSize));
        return downloaded;
    }

    private static long contentLength(String apkUrl) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(apkUrl).openConnection();
        try {
            connection.setRequestMethod("HEAD");
            // Content-Length has to describe the stored file, not a compressed transfer of it.
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(30_000);
            final long length = connection.getContentLengthLong();
            if (length <= 0) {
                throw new IOException("no Content-Length on the APK (HTTP "
                        + connection.getResponseCode() + ")");
            }
            return length;
        } finally {
            connection.disconnect();
        }
    }

    /** Fetch {@code [start, end]} inclusive. Fails loudly if the CDN ignores the range. */
    private static byte[] range(String apkUrl, long start, long end) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(apkUrl).openConnection();
        try {
            connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
            // Android's HttpURLConnection adds Accept-Encoding: gzip by default and then
            // decompresses transparently. Against a range of a zip that means it tries to
            // gunzip bytes that are not gzip, and fails on the magic number. Asking for
            // identity turns the whole mechanism off - we want the bytes as stored.
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(120_000);

            if (connection.getResponseCode() != 206) {
                throw new IOException("the CDN ignored the range request (HTTP "
                        + connection.getResponseCode() + ") - fetching the whole APK is not an "
                        + "option on a phone");
            }

            try (InputStream in = connection.getInputStream()) {
                return readFully(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static byte[] readCentralDirectory(String apkUrl, long apkSize) throws IOException {
        final int window = (int) Math.min(EOCD_SEARCH_WINDOW, apkSize);
        final byte[] tail = range(apkUrl, apkSize - window, apkSize - 1);

        final int eocd = lastIndexOf(tail, EOCD_SIGNATURE);
        if (eocd < 0) {
            throw new IOException("no end-of-central-directory record - zip64?");
        }

        final ByteBuffer buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        final long size = buffer.getInt(eocd + 12) & 0xFFFFFFFFL;
        final long offset = buffer.getInt(eocd + 16) & 0xFFFFFFFFL;

        return range(apkUrl, offset, offset + size - 1);
    }

    private static Map<String, Entry> parseCentralDirectory(
            byte[] cd, String abi, List<String> wanted) {
        final String prefix = "lib/" + abi + "/";
        final ByteBuffer buffer = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN);
        final Map<String, Entry> found = new HashMap<>();

        int pos = 0;
        while (pos + 46 <= cd.length && startsWith(cd, pos, CENTRAL_SIGNATURE)) {
            final int method = buffer.getShort(pos + 10) & 0xFFFF;
            final long compressed = buffer.getInt(pos + 20) & 0xFFFFFFFFL;
            final int nameLength = buffer.getShort(pos + 28) & 0xFFFF;
            final int extraLength = buffer.getShort(pos + 30) & 0xFFFF;
            final int commentLength = buffer.getShort(pos + 32) & 0xFFFF;
            final long localOffset = buffer.getInt(pos + 42) & 0xFFFFFFFFL;

            final String name = new String(cd, pos + 46, nameLength, java.nio.charset.StandardCharsets.UTF_8);
            if (name.startsWith(prefix)) {
                final String bare = name.substring(prefix.length());
                if (wanted.contains(bare)) {
                    found.put(bare, new Entry(method, compressed, localOffset));
                }
            }

            pos += 46 + nameLength + extraLength + commentLength;
        }
        return found;
    }

    /**
     * Fetch one member's bytes. The local header repeats the name and extra fields at a
     * different length from the central directory's, so it has to be read to find where the
     * data actually starts.
     */
    private static byte[] extract(String apkUrl, Entry entry) throws IOException {
        final byte[] header = range(apkUrl, entry.localHeaderOffset, entry.localHeaderOffset + 29);
        final ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        final int nameLength = buffer.getShort(26) & 0xFFFF;
        final int extraLength = buffer.getShort(28) & 0xFFFF;

        final long start = entry.localHeaderOffset + 30 + nameLength + extraLength;
        final byte[] raw = range(apkUrl, start, start + entry.compressedSize - 1);

        if (entry.method == 0) {
            return raw;
        }
        // Raw deflate - no zlib header, hence nowrap.
        try (InflaterInputStream inflater = new InflaterInputStream(
                new java.io.ByteArrayInputStream(raw), new Inflater(true))) {
            return readFully(inflater);
        }
    }

    private static boolean startsWith(byte[] data, int offset, byte[] signature) {
        for (int i = 0; i < signature.length; i++) {
            if (data[offset + i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static int lastIndexOf(byte[] data, byte[] signature) {
        for (int i = data.length - signature.length; i >= 0; i--) {
            if (startsWith(data, i, signature)) {
                return i;
            }
        }
        return -1;
    }
}
