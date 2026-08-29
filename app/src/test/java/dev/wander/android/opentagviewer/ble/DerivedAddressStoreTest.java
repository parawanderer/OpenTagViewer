package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the derived-address cache has to guarantee, which is less than it looks.
 *
 * <p>It is allowed to lose everything at any time: a miss costs a derivation, which is what
 * would have happened without it. What it must never do is claim to hold a range it does not,
 * because nothing would ever go back and derive the part that was silently missing.
 */
public class DerivedAddressStoreTest {

    private static final String BEACON = "ABCDEF01-2345-6789-ABCD-EF0123456789";

    @Rule
    public TemporaryFolder files = new TemporaryFolder();

    private DerivedAddressStore store() {
        return new DerivedAddressStore(this.files.getRoot());
    }

    private static Map<String, Integer> addresses(final String... macs) {
        final Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < macs.length; i++) {
            out.put(macs[i], i);
        }
        return out;
    }

    @Test
    public void nothingIsHeldForATagThatWasNeverWritten() {
        assertNull(this.store().load(BEACON));
    }

    @Test
    public void whatWasWrittenComesBack() {
        final DerivedAddressStore store = this.store();
        store.save(BEACON, 100, 200, addresses("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"));

        final DerivedAddressStore.Derived held = store.load(BEACON);

        assertNotNull(held);
        assertEquals(100, held.getLo());
        assertEquals(200, held.getHi());
        assertEquals(Set.of("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
                held.getAddresses().keySet());
    }

    /**
     * The index is deliberately dropped, so it must read back as absent rather than as some
     * plausible-looking number a caller might trust. See {@link DerivedAddressStore}.
     */
    @Test
    public void theIndexIsNotKeptAndReadsBackAsUnknown() {
        final DerivedAddressStore store = this.store();
        store.save(BEACON, 0, 10, addresses("AA:BB:CC:DD:EE:01"));

        final DerivedAddressStore.Derived held = store.load(BEACON);

        assertNotNull(held);
        assertTrue(held.getAddresses().containsKey("AA:BB:CC:DD:EE:01"));
        assertNull(held.getAddresses().get("AA:BB:CC:DD:EE:01"));
    }

    @Test
    public void coverageIsReportedForTheStoredRangeOnly() {
        final DerivedAddressStore store = this.store();
        store.save(BEACON, 100, 200, addresses("AA:BB:CC:DD:EE:01"));

        final DerivedAddressStore.Derived held = store.load(BEACON);

        assertNotNull(held);
        assertTrue(held.covers(120, 180));
        assertTrue(held.covers(100, 200));
        assertFalse("a range starting below what was derived is not covered", held.covers(99, 200));
        assertFalse("a range ending above what was derived is not covered", held.covers(100, 201));
    }

    @Test
    public void writingAgainReplacesWhatWasThere() {
        final DerivedAddressStore store = this.store();
        store.save(BEACON, 100, 200, addresses("AA:BB:CC:DD:EE:01"));
        store.save(BEACON, 50, 200, addresses("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"));

        final DerivedAddressStore.Derived held = store.load(BEACON);

        assertNotNull(held);
        assertEquals(50, held.getLo());
        assertEquals(2, held.getAddresses().size());
    }

    @Test
    public void twoTagsDoNotShareAFile() {
        final DerivedAddressStore store = this.store();
        store.save(BEACON, 0, 10, addresses("AA:BB:CC:DD:EE:01"));
        store.save("OTHER-TAG", 0, 10, addresses("AA:BB:CC:DD:EE:02"));

        assertEquals(Set.of("AA:BB:CC:DD:EE:01"), store.load(BEACON).getAddresses().keySet());
        assertEquals(Set.of("AA:BB:CC:DD:EE:02"), store.load("OTHER-TAG").getAddresses().keySet());
    }

    /**
     * A half-written file must read as nothing rather than as a shorter range. Reading it as a
     * shorter range is the one failure that would not announce itself: the missing part would be
     * derived again on every launch, and nothing would ever say why.
     */
    @Test
    public void aTruncatedFileIsTreatedAsNothingHeld() throws IOException {
        final DerivedAddressStore store = this.store();
        store.save(BEACON, 100, 200, addresses("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"));

        final File file = new File(new File(this.files.getRoot(), "derived-addresses"),
                BEACON + ".bin");
        assertTrue(file.isFile());

        final byte[] whole = java.nio.file.Files.readAllBytes(file.toPath());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(whole, 0, whole.length - 3);
        }

        assertNull(store.load(BEACON));
    }

    @Test
    public void aFileFromAnotherFormatIsDiscarded() throws IOException {
        final DerivedAddressStore store = this.store();
        store.save(BEACON, 100, 200, addresses("AA:BB:CC:DD:EE:01"));

        final File file = new File(new File(this.files.getRoot(), "derived-addresses"),
                BEACON + ".bin");
        final byte[] whole = java.nio.file.Files.readAllBytes(file.toPath());
        whole[3] = (byte) 99;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(whole);
        }

        assertNull(store.load(BEACON));
    }

    @Test
    public void tagsTheUserNoLongerHasAreForgotten() {
        final DerivedAddressStore store = this.store();
        store.save(BEACON, 0, 10, addresses("AA:BB:CC:DD:EE:01"));
        store.save("GONE-FROM-THE-ACCOUNT", 0, 10, addresses("AA:BB:CC:DD:EE:02"));

        store.forgetAllExcept(Set.of(BEACON));

        assertNotNull(store.load(BEACON));
        assertNull(store.load("GONE-FROM-THE-ACCOUNT"));
    }

    /**
     * Beacon ids arrive from an imported file, and this builds a path with one. A separator in
     * the id must not put the file somewhere else, and must still round-trip.
     */
    @Test
    public void anIdThatLooksLikeAPathStaysInsideTheDirectory() {
        final DerivedAddressStore store = this.store();
        store.save("../../etc/passwd", 0, 10, addresses("AA:BB:CC:DD:EE:01"));

        final File directory = new File(this.files.getRoot(), "derived-addresses");
        final File[] written = directory.listFiles();

        assertNotNull(written);
        assertEquals(1, written.length);
        assertFalse(written[0].getName().contains("/"));
        assertNotNull(store.load("../../etc/passwd"));
    }

    @Test
    public void anAddressThatIsNotAnAddressIsDroppedRatherThanCorrupting() {
        final DerivedAddressStore store = this.store();

        final Map<String, Integer> mixed = new HashMap<>();
        mixed.put("AA:BB:CC:DD:EE:01", 1);
        mixed.put("not an address", 2);
        store.save(BEACON, 0, 10, mixed);

        final DerivedAddressStore.Derived held = store.load(BEACON);

        // The count in the header claimed two; only one was written. Reading must not invent a
        // second one out of whatever followed.
        assertNull("a short file is not a partly-good file", held);
    }
}
