package dev.wander.android.opentagviewer.anisette;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Take the ADI libraries from an Apple Music APK the user supplied themselves.
 *
 * <p>Normally they come from Apple's own CDN, which serves exactly one build. When Apple
 * replaces it, the recorded hashes stop matching and local Anisette cannot set itself up until
 * this app ships an update. Rather than leave people stuck on a broken sign-in, they can point
 * at a copy of the known-good build obtained elsewhere.
 *
 * <p><b>Where the file came from cannot make it more dangerous.</b> Every extracted library is
 * checked against the same recorded SHA-256 as Apple's own copy, and nothing is left behind
 * unless all of them match. A hostile APK is rejected by exactly the check that would reject a
 * corrupted download.
 *
 * <p>The files land in the directory {@link LocalAnisette} already reads from, so once this has
 * run, nothing downstream needs to know or care that they did not come from Apple - the fetcher
 * skips the network when they are already there.
 */
public final class AdiLibraryImporter {
    private static final String TAG = "AdiLibraryImporter";

    private AdiLibraryImporter() {}

    /**
     * Extract, verify, and keep only if everything matched.
     *
     * <p>Blocks - it reads a file of tens of megabytes and hashes what it finds.
     *
     * @return null when the libraries are in place, or a message saying what was wrong with the
     *         file, suitable for showing to whoever chose it
     */
    public static String importFrom(
            final Context context, final Uri apk, final File libraryDir, final String abi) {

        final List<String> wanted = LocalAnisette.requiredLibraries();
        final Set<String> outstanding = new HashSet<>(wanted);
        final List<File> written = new ArrayList<>();

        try {
            if (!libraryDir.exists() && !libraryDir.mkdirs()) {
                return "could not create " + libraryDir;
            }

            try (InputStream raw = context.getContentResolver().openInputStream(apk)) {
                if (raw == null) {
                    return "that file could not be opened";
                }
                extractInto(raw, libraryDir, abi, outstanding, written);
            }

            if (!outstanding.isEmpty()) {
                // Almost always the wrong APK entirely, or the right APK for another CPU.
                return discard(written, "that file does not contain " + String.join(", ",
                        outstanding) + " for this device (" + abi + ")");
            }

            final AdiLibraryManifest manifest = AdiLibraryManifest.load(context);
            for (final String name : wanted) {
                final String problem = manifest.verify(new File(libraryDir, name), abi);
                if (problem != null) {
                    return discard(written, problem);
                }
            }

            Log.i(TAG, "imported ADI libraries from a user-supplied APK");
            return null;

        } catch (final Exception e) {
            Log.w(TAG, "could not import from the supplied APK", e);
            return discard(written, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * Copy out the entries we want, and only those.
     *
     * <p>Destination names come from our own list rather than from the archive, so a crafted
     * entry name cannot write outside this directory.
     */
    private static void extractInto(
            final InputStream raw, final File libraryDir, final String abi,
            final Set<String> outstanding, final List<File> written) throws IOException {

        try (ZipInputStream zip = new ZipInputStream(raw)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) {
                    continue;
                }

                final String name = nameIfWanted(entry.getName(), abi, outstanding);
                if (name == null) {
                    continue;
                }

                final File destination = new File(libraryDir, name);
                try (OutputStream out = new FileOutputStream(destination)) {
                    final byte[] buffer = new byte[64 * 1024];
                    for (int read; (read = zip.read(buffer)) != -1; ) {
                        out.write(buffer, 0, read);
                    }
                }

                written.add(destination);
                outstanding.remove(name);

                if (outstanding.isEmpty()) {
                    return;
                }
            }
        }
    }

    /** The bare library name if this entry is one we still need, otherwise null. */
    private static String nameIfWanted(
            final String entryName, final String abi, final Set<String> outstanding) {

        final String prefix = "lib/" + abi + "/";
        if (!entryName.startsWith(prefix)) {
            return null;
        }

        final String name = entryName.substring(prefix.length());
        return outstanding.contains(name) ? name : null;
    }

    /**
     * Leave nothing half-imported.
     *
     * <p>A partial set is worse than none: the fetcher skips the network for files that are
     * already present, so unverified leftovers would be picked up on the next attempt as
     * though they had been checked.
     */
    private static String discard(final List<File> written, final String problem) {
        for (final File file : written) {
            if (!file.delete()) {
                Log.w(TAG, "could not remove " + file + " after a failed import");
            }
        }
        return problem;
    }
}
