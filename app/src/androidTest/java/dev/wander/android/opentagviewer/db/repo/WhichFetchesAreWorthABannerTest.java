package dev.wander.android.opentagviewer.db.repo;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AccessoryRequest;

/**
 * Which fetches put the "still locating your tags" banner up.
 *
 * <p><b>The bug, from using the app:</b> the banner appeared during loads that finished
 * immediately. It went up for any fetch still running after six seconds, which on a slow
 * network is most of them - and a warning that shows when nothing is wrong is one people stop
 * reading before the day it matters.
 *
 * <p>What makes a fetch genuinely long is the key search, and how far back that starts is
 * decided by the accessory's {@code KeyAlignmentRecord} - so it is knowable before a single
 * request goes out. {@code SlowFirstFetchTest} covers the arithmetic on the JVM; this covers
 * the part that needs a database, which is reading the record off the stored beacon at all.
 *
 * <p>Both halves are needed. The predicate is right and useless if the plist never parses, and
 * a wrong XPath here would silently answer "no alignment, so slow" for every tag - restoring
 * exactly the behaviour being fixed, with the JVM tests still green.
 */
@RunWith(AndroidJUnit4.class)
public class WhichFetchesAreWorthABannerTest {

    private static final String A_PLIST = "<?xml version=\"1.0\"?><plist><dict></dict></plist>";

    private OpenTagViewerDatabase db;
    private BeaconRepository repo;

    @Before
    public void openAnInMemoryDatabase() {
        this.db = Room.inMemoryDatabaseBuilder(
                        getInstrumentation().getTargetContext(), OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();

        this.repo = new BeaconRepository(this.db, (plist, alignment) -> "{\"type\":\"accessory\"}");
    }

    @After
    public void closeIt() {
        this.db.close();
    }

    /** The shape the exporter writes: a key, then its typed sibling. */
    private static String alignedAt(final Instant when) {
        return "<?xml version=\"1.0\"?><plist><dict>"
                + "<key>lastIndexObservationDate</key>"
                + "<date>" + when.toString() + "</date>"
                + "</dict></plist>";
    }

    private void givenABeacon(final String id, final String alignmentPlist) {
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(id)
                .content(A_PLIST)
                .alignmentPlist(alignmentPlist)
                .version("0.0.2")
                .fromAccount(false)
                .isRemoved(false)
                .build());
    }

    private boolean wouldBeSlow(final String... beaconIds) {
        final List<AccessoryRequest> requests = new java.util.ArrayList<>();
        for (final String id : beaconIds) {
            requests.add(new AccessoryRequest(id, "{\"type\":\"accessory\"}"));
        }
        return this.repo.aFetchOfTheseWouldBeSlow(requests).blockingFirst();
    }

    // ------------------------------------------------------------------ quick, so stay quiet

    @Test
    public void aTagAlignedThisMorningNeedsNoBanner() {
        this.givenABeacon("recent", alignedAt(Instant.now().minus(6, ChronoUnit.HOURS)));

        assertFalse("a few hours of keys is one request", this.wouldBeSlow("recent"));
    }

    /**
     * <b>The regression this is really for.</b> If the plist stops parsing - a changed XPath, a
     * date format nobody anticipated - every tag reads as unaligned and the banner comes back
     * for everything, which is the behaviour being fixed. Only a real record through the real
     * reader catches that.
     */
    @Test
    public void aStoredAlignmentRecordIsActuallyRead() {
        this.givenABeacon("aligned", alignedAt(Instant.now().minus(2, ChronoUnit.DAYS)));

        assertFalse("the alignment record was stored but not read, so this tag looked unaligned",
                this.wouldBeSlow("aligned"));
    }

    @Test
    public void aWholeBatchOfRecentlyAlignedTagsNeedsNoBanner() {
        this.givenABeacon("a", alignedAt(Instant.now().minus(1, ChronoUnit.DAYS)));
        this.givenABeacon("b", alignedAt(Instant.now().minus(2, ChronoUnit.DAYS)));
        this.givenABeacon("c", alignedAt(Instant.now().minus(3, ChronoUnit.DAYS)));

        assertFalse("three quick tags is still a quick fetch", this.wouldBeSlow("a", "b", "c"));
    }

    // ------------------------------------------------------------------ slow, so say so

    @Test
    public void aTagWithNoAlignmentRecordIsWorthABanner() {
        this.givenABeacon("never-aligned", null);

        assertTrue("with no record it searches from the pairing date",
                this.wouldBeSlow("never-aligned"));
    }

    @Test
    public void aTagAlignedMonthsAgoIsWorthABanner() {
        this.givenABeacon("stale", alignedAt(Instant.now().minus(90, ChronoUnit.DAYS)));

        assertTrue("three months is roughly 8,600 keys", this.wouldBeSlow("stale"));
    }

    @Test
    public void oneUnalignedTagAmongQuickOnesStillWarrantsIt() {
        this.givenABeacon("quick", alignedAt(Instant.now().minus(1, ChronoUnit.DAYS)));
        this.givenABeacon("unaligned", null);

        assertTrue("the batch is fetched one at a time, so the slow one holds up the rest",
                this.wouldBeSlow("quick", "unaligned"));
    }

    /**
     * A tag being fetched that this app has no row for - a self-generated one, or a request
     * built from a fallback plist. Unknown, so warn rather than stay silent.
     */
    @Test
    public void aTagWithNoStoredRowAtAllWarnsRatherThanStaysSilent() {
        assertTrue("nothing is known about it, and silence is the failure being avoided",
                this.wouldBeSlow("never-heard-of-it"));
    }

    /** Damaged rather than absent: unreadable is the same answer as unknown, not a crash. */
    @Test
    public void anUnreadableAlignmentRecordIsTreatedAsUnaligned() {
        this.givenABeacon("damaged", "<?xml version=\"1.0\"?><plist><dict><key>lastIndex"
                + "ObservationDate</key><date>not a date at all</date></dict></plist>");

        assertTrue("an unreadable record tells us nothing about where the search starts",
                this.wouldBeSlow("damaged"));
    }
}
