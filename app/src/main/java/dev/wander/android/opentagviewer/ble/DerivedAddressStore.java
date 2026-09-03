package dev.wander.android.opentagviewer.ble;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the addresses derived for a tag, so they are derived once rather than once per app start.
 *
 * <p><b>Why this is safe to keep at all.</b> An address is a pure function of the accessory's
 * keys and a key index, so an address derived today is still one that accessory can advertise
 * tomorrow. Nothing here can go stale or wrong; it can only be incomplete. That is what lets a
 * wider range simply be written over a narrower one, with no event ever invalidating what is
 * already there. In particular the alignment moving does not, because alignment decides which
 * part of the range is worth watching, not what the range contains.
 *
 * <p><b>Why it is worth keeping.</b> Deriving costs about two to three seconds per thousand
 * indices on an idle phone, and ten times that while the app is starting up and competing with
 * itself, which is exactly when the index was being rebuilt. Paying it once per tag instead of
 * once per launch is what makes a range wide enough for a long-missing tag affordable at all.
 *
 * <p><b>The index is kept for primary keys and dropped for secondary ones.</b> A primary key
 * occurs at exactly one index and stays there, so it is worth keeping: an address recovered from
 * this file arrives with an exact hint, and confirming an alignment then costs three key
 * derivations rather than a search of a 48 hour window - measured at 0.02 seconds against about
 * one. A secondary key is different. It covers 96 consecutive indices and is reported at whatever
 * index the deriving call's own range began at, so the same address comes back against a
 * different number depending on how the work was split. That is an artefact of the split rather
 * than a fact about the tag, so it is stored as unknown and read back as no hint at all, which
 * costs the wide check that every check cost before hints existed.
 *
 * <p>A cache in the file sense too: losing it costs time and never correctness, so it lives in
 * the app's files directory rather than in the database, and may be deleted at any point.
 */
public final class DerivedAddressStore {
    private static final String TAG = DerivedAddressStore.class.getSimpleName();

    /** Bumped when the layout below changes, so an older file is discarded rather than misread. */
    private static final int FORMAT_VERSION = 2;

    /**
     * The stored index for an address whose index means nothing.
     *
     * <p>Matches {@code main._INDEX_UNKNOWN}. A secondary key is reported at whatever index the
     * deriving call's range began at, so it is not a fact about the tag and must not be read
     * back as one.
     */
    private static final int INDEX_UNKNOWN = -1;

    private static final String DIRECTORY = "derived-addresses";

    private static final String SUFFIX = ".bin";

    private final File directory;

    public DerivedAddressStore(final File filesDir) {
        this.directory = new File(filesDir, DIRECTORY);
    }

    /** What was derived for one tag, and the index range it was derived over. */
    public static final class Derived {
        private final int lo;
        private final int hi;
        private final Map<String, Integer> addresses;

        public Derived(final int lo, final int hi, final Map<String, Integer> addresses) {
            this.lo = lo;
            this.hi = hi;
            this.addresses = addresses;
        }

        public int getLo() {
            return this.lo;
        }

        public int getHi() {
            return this.hi;
        }

        /** Address to the index it was derived at, or null where that index means nothing. */
        public Map<String, Integer> getAddresses() {
            return this.addresses;
        }

        /** Whether this already holds everything an inclusive range would produce. */
        public boolean covers(final int wantedLo, final int wantedHi) {
            return this.lo <= wantedLo && wantedHi <= this.hi;
        }
    }

    /**
     * What has been derived for this tag, or null if nothing has.
     *
     * <p>Never throws for a damaged or truncated file. A cache that cannot be read is a cache
     * that has not been written yet, and the caller then derives from scratch exactly as it
     * would have done anyway.
     */
    @Nullable
    public Derived load(final String beaconId) {
        final File file = this.fileFor(beaconId);
        if (!file.isFile()) {
            return null;
        }

        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != FORMAT_VERSION) {
                Log.d(TAG, "Discarding a derived-address file written by another version");
                return null;
            }

            final int lo = in.readInt();
            final int hi = in.readInt();
            final int count = in.readInt();

            final Map<String, Integer> addresses = new HashMap<>(Math.max(16, count * 2));
            final byte[] mac = new byte[6];
            for (int i = 0; i < count; i++) {
                in.readFully(mac);
                final int index = in.readInt();
                addresses.put(formatMac(mac), index == INDEX_UNKNOWN ? null : index);
            }

            return new Derived(lo, hi, addresses);
        } catch (final IOException | RuntimeException unreadable) {
            // Truncated by a kill mid-write, or written by a build that packed it differently.
            Log.d(TAG, "Could not read the derived addresses for beaconId=" + beaconId
                    + "; deriving them again", unreadable);
            return null;
        }
    }

    /**
     * Writes what has been derived for this tag, replacing whatever was there.
     *
     * <p>Through a temporary file and a rename, so being killed halfway leaves the previous copy
     * rather than a shorter one. A truncated file would read back as a narrower covered range
     * than was actually derived, and the missing part would be derived again on every launch
     * with nothing ever reporting that it had been lost.
     */
    public void save(final String beaconId, final int lo, final int hi,
                     final Map<String, Integer> addresses) {
        if (!this.directory.isDirectory() && !this.directory.mkdirs()) {
            Log.w(TAG, "Could not create " + this.directory + "; not keeping derived addresses");
            return;
        }

        final File target = this.fileFor(beaconId);
        final File temporary = new File(target.getPath() + ".tmp");

        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(temporary))) {
            out.writeInt(FORMAT_VERSION);
            out.writeInt(lo);
            out.writeInt(hi);
            out.writeInt(addresses.size());

            for (final Map.Entry<String, Integer> entry : addresses.entrySet()) {
                final byte[] mac = parseMac(entry.getKey());
                if (mac != null) {
                    out.write(mac);
                    out.writeInt(entry.getValue() == null ? INDEX_UNKNOWN : entry.getValue());
                }
            }
        } catch (final IOException couldNotWrite) {
            Log.w(TAG, "Could not write the derived addresses for beaconId=" + beaconId,
                    couldNotWrite);
            temporary.delete();
            return;
        }

        if (!temporary.renameTo(target) && (!target.delete() || !temporary.renameTo(target))) {
            Log.w(TAG, "Could not replace the derived addresses for beaconId=" + beaconId);
            temporary.delete();
        }
    }

    /** Forgets what was derived for tags the user no longer has. */
    public void forgetAllExcept(final Set<String> beaconIds) {
        final File[] files = this.directory.listFiles();
        if (files == null) {
            return;
        }

        for (final File file : files) {
            final String name = file.getName();
            if (!name.endsWith(SUFFIX)) {
                continue;
            }

            final String stored = name.substring(0, name.length() - SUFFIX.length());

            boolean wanted = false;
            for (final String beaconId : beaconIds) {
                if (sanitise(beaconId).equals(stored)) {
                    wanted = true;
                    break;
                }
            }

            if (!wanted && !file.delete()) {
                Log.d(TAG, "Could not delete stale derived addresses at " + file);
            }
        }
    }

    private File fileFor(final String beaconId) {
        return new File(this.directory, sanitise(beaconId) + SUFFIX);
    }

    /**
     * A beacon id reduced to something that can only name a file in this directory.
     *
     * <p>Beacon ids are UUIDs in practice, but this builds a path, and a value that arrived from
     * an imported file has no business deciding which directory it lands in.
     */
    private static String sanitise(final String beaconId) {
        return beaconId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String formatMac(final byte[] mac) {
        return String.format(Locale.ROOT, "%02X:%02X:%02X:%02X:%02X:%02X",
                mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    }

    @Nullable
    private static byte[] parseMac(final String address) {
        final String[] parts = address.split(":");
        if (parts.length != 6) {
            return null;
        }

        final byte[] mac = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                mac[i] = (byte) Integer.parseInt(parts[i], 16);
            }
        } catch (final NumberFormatException notAnAddress) {
            return null;
        }
        return mac;
    }
}
