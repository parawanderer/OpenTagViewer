package dev.wander.android.opentagviewer.anisette;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;

/**
 * Apple's macOS CoreADI, fetched from Apple: 3.5 MB of a 291 MB installer, in one request.
 *
 * <p><b>Where it is.</b> The library ships in iTunes 12.8.3 for Mac, which Apple still serves
 * (KB DL1977). It sits several layers down - the disk image's HFS partition, then
 * {@code Install iTunes.pkg}, then {@code CoreADI.pkg/Payload}, a gzipped cpio archive. But
 * the image is a fixed file, and in it the payload happens to be stored uncompressed and in one
 * piece, so its position is a constant: bytes {@link #PAYLOAD_OFFSET} to
 * {@code PAYLOAD_OFFSET + PAYLOAD_LENGTH}. {@code scripts/locate_mac_coreadi.py} derives those
 * numbers from the image and checks them, and can be rerun by anybody who doubts them.
 *
 * <p><b>Nothing is trusted that is not pinned.</b> The payload is checked against
 * {@link #PAYLOAD_SHA256} before it is unpacked, and the library against
 * {@link MacCoreAdi#BUILD_SHA256} before it is written - so a server that answered with
 * something else, or ignored the range, produces an error rather than a library.
 *
 * <p>Apple's code is never redistributed by this project: it is fetched, at runtime, from Apple.
 */
public final class MacCoreAdiFetcher {
    private static final String TAG = "MacCoreAdiFetcher";

    public static final String DMG_URL = "https://secure-appldnld.apple.com/itunes12/"
            + "041-56365-20210413-79A5AC6D-6B0A-46EC-B399-5A381183E3BE/iTunes12.8.3.dmg";

    static final long PAYLOAD_OFFSET = 184_678_769L;
    static final int PAYLOAD_LENGTH = 3_471_595;
    static final String PAYLOAD_SHA256 =
            "8a6c068c95036326eb2d2885fa60912ed0f7229d0939ae92934a70a3bb6cf025";

    /** The library inside the payload. Apple's cpio names carry a leading {@code ./}. */
    static final String FRAMEWORK_PATH =
            "System/Library/PrivateFrameworks/CoreADI.framework/Versions/A/CoreADI";

    private static final int CPU_TYPE_X86_64 = 0x01000007;

    private MacCoreAdiFetcher() {
    }

    /** Thrown when what came back is not the library this app knows how to run. */
    public static final class FetchException extends Exception {
        public FetchException(String message) {
            super(message);
        }

        public FetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Make sure {@code target} holds the library, fetching it if not.
     *
     * @return the library's bytes
     */
    public static byte[] ensure(File target) throws FetchException {
        return ensure(target, DMG_URL);
    }

    /** As {@link #ensure(File)}, from {@code url}. Tests serve a fixture here. */
    static byte[] ensure(File target, String url) throws FetchException {
        if (target.isFile()) {
            try {
                final byte[] existing = readFile(target);
                if (MacCoreAdi.BUILD_SHA256.equals(sha256(existing))) {
                    return existing;
                }
                Log.w(TAG, "the stored library is not the expected build; fetching it again");
            } catch (final IOException e) {
                Log.w(TAG, "could not read the stored library; fetching it again", e);
            }
        }

        final byte[] payload = fetchPayload(url);
        final byte[] library = extract(payload);
        write(target, library);
        Log.i(TAG, String.format("fetched Apple's macOS CoreADI (%.1f MB downloaded)",
                payload.length / 1e6));
        return library;
    }

    private static byte[] fetchPayload(String url) throws FetchException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("Range", "bytes=" + PAYLOAD_OFFSET + "-"
                    + (PAYLOAD_OFFSET + PAYLOAD_LENGTH - 1));

            // Anything but a partial response means the range was not honoured, and reading on
            // would download the whole 291 MB image to throw nearly all of it away.
            final int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_PARTIAL) {
                throw new FetchException("Apple answered HTTP " + status
                        + " to a range request for the library; expected 206");
            }

            final byte[] payload = readAtMost(connection.getInputStream(), PAYLOAD_LENGTH + 1);
            if (payload.length != PAYLOAD_LENGTH) {
                throw new FetchException("expected " + PAYLOAD_LENGTH + " bytes of the installer,"
                        + " got " + payload.length);
            }
            return payload;
        } catch (final IOException e) {
            throw new FetchException("could not download the library from Apple: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * The x86_64 library out of the installer's payload. No network; pure bytes in, bytes out.
     *
     * @throws FetchException if either the payload or the library is not the pinned one
     */
    static byte[] extract(byte[] payload) throws FetchException {
        final String payloadHash = sha256(payload);
        if (!PAYLOAD_SHA256.equals(payloadHash)) {
            throw new FetchException("the installer bytes are not the expected ones (sha256 "
                    + payloadHash + ")");
        }

        final byte[] cpio;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(payload))) {
            cpio = readAtMost(in, 64 << 20);
        } catch (final IOException e) {
            throw new FetchException("could not decompress the installer payload", e);
        }

        final byte[] framework = fileFromCpio(cpio, FRAMEWORK_PATH);
        final byte[] library = slice(framework, CPU_TYPE_X86_64);

        final String libraryHash = sha256(library);
        if (!MacCoreAdi.BUILD_SHA256.equals(libraryHash)) {
            throw new FetchException("the library is not the expected build (sha256 "
                    + libraryHash + ")");
        }
        return library;
    }

    /**
     * One file's contents from an "odc" (old ASCII) cpio archive, which is what Apple's package
     * payloads are.
     *
     * <p>Each entry: the magic {@code 070707}, then dev, ino, mode, uid, gid, nlink, rdev as six
     * octal digits each, mtime as eleven, namesize as six and filesize as eleven - 76 bytes - then
     * the name with its NUL, then the data. No padding.
     */
    static byte[] fileFromCpio(byte[] cpio, String wanted) throws FetchException {
        int pos = 0;
        while (pos + 76 <= cpio.length) {
            final String magic = new String(cpio, pos, 6, StandardCharsets.US_ASCII);
            if (!"070707".equals(magic)) {
                throw new FetchException("not an odc cpio archive at offset " + pos);
            }
            final int nameSize = (int) octal(cpio, pos + 59, 6);
            final long fileSize = octal(cpio, pos + 65, 11);
            final int nameStart = pos + 76;
            if (nameSize < 1 || nameStart + nameSize > cpio.length) {
                throw new FetchException("truncated cpio entry at offset " + pos);
            }
            String name = new String(cpio, nameStart, nameSize - 1, StandardCharsets.UTF_8);
            final int dataStart = nameStart + nameSize;
            if (fileSize < 0 || dataStart + fileSize > cpio.length) {
                throw new FetchException("truncated cpio data for " + name);
            }
            if ("TRAILER!!!".equals(name)) {
                break;
            }
            if (name.startsWith("./")) {
                name = name.substring(2);
            }
            // A hard link's twins carry no data; the entry that matters is the one that does.
            if (wanted.equals(name) && fileSize > 0) {
                final byte[] out = new byte[(int) fileSize];
                System.arraycopy(cpio, dataStart, out, 0, out.length);
                return out;
            }
            pos = dataStart + (int) fileSize;
        }
        throw new FetchException(wanted + " is not in the installer payload");
    }

    /** The slice for {@code cpuType} out of a Mach-O universal binary. */
    static byte[] slice(byte[] fat, int cpuType) throws FetchException {
        if (fat.length < 8 || be32(fat, 0) != 0xCAFEBABE) {
            throw new FetchException("the library is not a universal binary");
        }
        final int count = be32(fat, 4);
        for (int i = 0; i < count; i++) {
            final int entry = 8 + 20 * i;
            if (entry + 20 > fat.length) {
                break;
            }
            if (be32(fat, entry) == cpuType) {
                final int offset = be32(fat, entry + 8);
                final int size = be32(fat, entry + 12);
                if (offset < 0 || size < 0 || (long) offset + size > fat.length) {
                    throw new FetchException("the library's slice lies outside the file");
                }
                final byte[] out = new byte[size];
                System.arraycopy(fat, offset, out, 0, size);
                return out;
            }
        }
        throw new FetchException("the library has no x86_64 slice");
    }

    private static void write(File target, byte[] library) throws FetchException {
        final File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new FetchException("could not create " + dir);
        }
        // Written beside and renamed over, so a process killed mid-write never leaves a
        // truncated library that the hash check would then reject on every launch until cleared.
        final File partial = new File(target.getPath() + ".partial");
        try (FileOutputStream out = new FileOutputStream(partial)) {
            out.write(library);
            out.getFD().sync();
        } catch (final IOException e) {
            throw new FetchException("could not store the library", e);
        }
        if (!partial.renameTo(target)) {
            throw new FetchException("could not store the library at " + target);
        }
    }

    /**
     * A whole file. Not {@code java.nio.file.Files}, which is API 26 and which core library
     * desugaring does not cover - minSdk is 24.
     */
    static byte[] readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAtMost(in, Integer.MAX_VALUE - 1);
        }
    }

    private static long octal(byte[] b, int offset, int length) throws FetchException {
        long v = 0;
        for (int i = 0; i < length; i++) {
            final int c = b[offset + i];
            if (c < '0' || c > '7') {
                throw new FetchException("bad octal field in the cpio archive");
            }
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static int be32(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24) | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF);
    }

    private static byte[] readAtMost(InputStream in, int limit) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > limit) {
                throw new IOException("more data than expected");
            }
        }
        return out.toByteArray();
    }

    static String sha256(byte[] data) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
