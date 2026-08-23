package dev.wander.android.opentagviewer.util.export;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.BundleBuilder;
import dev.wander.android.opentagviewer.util.parse.BundlePasscode;

/**
 * Turning a selection of tags into a locked zip somebody else can import.
 *
 * <p><b>Sharing, not backing up.</b> An owner signed into their own account does not need this -
 * their tags arrive when they sign in. What a bundle is for is giving a tag to another person,
 * and that is worth saying because the act is irreversible: exported key material cannot be
 * withdrawn, and the only way to revoke it is to unpair the accessory.
 *
 * <p>Blocking throughout - a Python call and a zip write. Never on the main thread.
 */
public final class TagExporter {

    private TagExporter() {}

    /** What was written, and the code it was locked with. */
    public static final class Exported {
        private final String passcode;
        private final String warning;
        private final int count;

        Exported(final String passcode, final String warning, final int count) {
            this.passcode = passcode;
            this.warning = warning;
            this.count = count;
        }

        /** Never null: this app always locks what it writes. */
        public String getPasscode() {
            return this.passcode;
        }

        /** What the bundle had to be written without, or null. Worth logging, not showing. */
        public String getWarning() {
            return this.warning;
        }

        public int getCount() {
            return this.count;
        }
    }

    /**
     * A tag that cannot go in a bundle, and why.
     *
     * <p>Separate from {@link BundleBuilder.BundleBuildException} because the two want different
     * screens: this one names something the user picked and can change, and the other is the app
     * failing at something it should be able to do.
     */
    public static class NothingToExportException extends Exception {
        public NothingToExportException(final String message) {
            super(message);
        }
    }

    /**
     * Build the bundle, lock it, write it.
     *
     * <p><b>Always locked.</b> The desktop exporter keeps an opt-out for recipients running an app
     * older than 1.1.0; this one does not need it, because a bundle written by 1.1.0 is being sent
     * by somebody who has 1.1.0, and the recipient they are most likely to be helping install it
     * will get the same version. Adding a switch here would mostly serve people who do not know
     * what it does.
     *
     * @param destination where the user chose to put it. Closed by the caller, which opened it.
     * @param via         {@code OpenTagViewer.android:<versionName>}, from {@code BuildConfig}.
     * @throws NothingToExportException            if the selection cannot make a bundle
     * @throws BundleBuilder.BundleBuildException  if the app failed at building one
     * @throws java.io.IOException                 if the destination will not take it
     */
    public static Exported writeTo(
            final OutputStream destination,
            final List<Pairing> selection,
            final String via,
            final String sourceUser,
            final long exportedAtMs)
            throws NothingToExportException, BundleBuilder.BundleBuildException,
                   java.io.IOException {

        if (selection.isEmpty()) {
            throw new NothingToExportException("Nothing was selected.");
        }

        final List<BundleBuilder.Accessory> accessories = new ArrayList<>(selection.size());
        for (final Pairing pairing : selection) {
            // **A tag read from the Apple account has no naming record of its own here**, and the
            // importer inner-joins the two - so exporting one would produce a bundle that imports
            // and silently contains nothing. Refused with a name rather than written.
            if (pairing.naming == null || pairing.naming.content == null) {
                throw new NothingToExportException(pairing.displayName);
            }
            accessories.add(new BundleBuilder.Accessory(
                    pairing.beacon.content,
                    pairing.naming.content,
                    pairing.beacon.alignmentPlist));
        }

        final BundleBuilder.Built built = AppDependencies.bundleBuilder()
                .build(accessories, via, sourceUser, exportedAtMs);

        final String passcode = BundlePasscode.generate();
        BundleZipWriter.write(destination, built.getEntries(), passcode);

        return new Exported(passcode, built.getWarning(), accessories.size());
    }

    /** One tag's two records, joined, with a name for saying which one went wrong. */
    public static final class Pairing {
        private final OwnedBeacon beacon;
        private final BeaconNamingRecord naming;
        private final String displayName;

        public Pairing(
                final OwnedBeacon beacon,
                final BeaconNamingRecord naming,
                final String displayName) {
            this.beacon = beacon;
            this.naming = naming;
            this.displayName = displayName;
        }
    }
}
