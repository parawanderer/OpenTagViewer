package dev.wander.android.opentagviewer.python;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Builds the files of an export bundle, which Java then zips.
 *
 * <p><b>The format lives in Python and is shared with the desktop exporter</b> -
 * {@code opentagviewer_export.build_export}, whitelisted into the APK. Three programs write this
 * format and a second implementation of it would drift; the symptom of drift is a bundle that
 * imports into one version of this app and not another.
 *
 * <p>Behind an interface for the usual reason, and one more. The usual one: the real
 * implementation needs a running interpreter, so a screen that called it directly could not be
 * tested without one. The extra one: **this is the path that has to fail well.** An export that
 * throws leaves somebody holding no file and no explanation, having just decided to share the
 * keys to their tags with another person - so the failure path needs driving in a test, and it is
 * not reachable on demand any other way.
 */
public interface BundleBuilder {

    /** What Python produced: the files, and anything it had to leave out. */
    @AllArgsConstructor
    @Getter
    class Built {
        /** Path within the zip, to its bytes. Insertion-ordered, so the archive is predictable. */
        private final Map<String, byte[]> entries;

        /**
         * What was dropped to make this work, or null.
         *
         * <p>The case that exists today is an unusable key alignment record. Losing one costs the
         * recipient a slow first fetch; refusing the export over it would cost them everything,
         * so the bundle is written and this says what happened. Logged, not shown: the user
         * cannot act on it and the export did what they asked.
         */
        private final String warning;
    }

    /** One accessory, as the app stores it. The naming record is not optional. */
    @AllArgsConstructor
    @Getter
    class Accessory {
        private final String ownedBeaconPlist;
        private final String namingRecordPlist;
        /** Null when the app holds none, which is normal for an older import. */
        private final String alignmentPlist;
    }

    /**
     * @param via what to stamp as the producer, {@code OpenTagViewer.android:<versionName>}.
     *            Passed rather than built here because the shared package refuses to invent one,
     *            and rightly: three programs write this format and {@code via:} is the only thing
     *            in a zip that says which.
     * @throws BundleBuildException if the bundle cannot be built, carrying a sentence to show.
     */
    Built build(List<Accessory> accessories, String via, String sourceUser, long exportedAtMs)
            throws BundleBuildException;

    /**
     * Something about the selection cannot be exported, with a reason worth reading.
     *
     * <p>Checked rather than unchecked, deliberately: this is the one call in the export path
     * that is expected to fail on real input - a record with no key material, an accessory with
     * no naming record - and a caller that forgets to handle it should not compile.
     */
    class BundleBuildException extends Exception {
        public BundleBuildException(final String message) {
            super(message);
        }

        public BundleBuildException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
