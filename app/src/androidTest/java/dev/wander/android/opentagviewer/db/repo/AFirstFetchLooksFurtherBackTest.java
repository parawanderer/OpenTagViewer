package dev.wander.android.opentagviewer.db.repo;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;

/**
 * Which tags get the wider first window.
 *
 * <p><b>The bug, in @parawanderer's words:</b> a tag showed "No last location known" in My
 * Devices, and opening its history and paging back a few days found locations perfectly well.
 * The reports were on Apple's servers the whole time; the fetch had only ever asked about the
 * last twenty-four hours, which is right for a tag the app has been watching and wrong for one
 * that arrived five minutes ago with no history at all.
 *
 * <p>So a beacon nothing has ever searched for is asked about across the whole week Apple
 * retains. This is the query that decides which those are, and the properties that matter are
 * that it starts true and stops being true - a window that never narrowed would make every
 * routine refresh seven times the work forever.
 */
@RunWith(AndroidJUnit4.class)
public class AFirstFetchLooksFurtherBackTest {

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

    private void givenAbeacon(final String id) {
        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(id)
                .content(A_PLIST)
                .version("0.0.2")
                .fromAccount(false)
                .isRemoved(false)
                .build());
    }

    private Set<String> neverScanned() {
        return this.repo.neverScanned().blockingFirst();
    }

    /** A tag that has just been imported has never been searched for. */
    @Test
    public void afreshlyImportedTagGetsTheWiderWindow() {
        this.givenAbeacon("just-arrived");

        assertTrue("a tag with no scan history was not offered the wider first window",
                this.neverScanned().contains("just-arrived"));
    }

    /**
     * <b>And it stops after the first search, whatever that search found.</b>
     *
     * <p>The property that keeps this from becoming permanent. Keyed on having been searched,
     * not on having succeeded - a tag that is genuinely silent would otherwise be asked for a
     * full week every single refresh, forever, which is the opposite of what the backoff is for.
     */
    @Test
    public void itstopsOnceThetagHasBeenSearchedForAtAll() {
        this.givenAbeacon("searched-and-found");
        this.givenAbeacon("searched-and-empty");

        this.db.ownedBeaconDao().recordSuccessfulScan("searched-and-found", 1_000L);
        this.db.ownedBeaconDao().recordFruitlessScan("searched-and-empty", 1_000L);

        final Set<String> stillNew = this.neverScanned();

        assertFalse("a tag that answered is still being asked for a whole week",
                stillNew.contains("searched-and-found"));
        assertFalse("a tag that was searched and found nothing is still being asked for a "
                + "whole week, every refresh, forever", stillNew.contains("searched-and-empty"));
    }

    /**
     * A tag set aside as silent is not treated as new either.
     *
     * <p>It has been searched - exhaustively, which is why it was set aside. Looking again is
     * something the user asks for on the tag page, and that path widens its own window.
     */
    @Test
    public void anignoredTagIsNotMistakenForAnewOne() {
        this.givenAbeacon("given-up-on");
        this.db.ownedBeaconDao().markIgnored("given-up-on", 2_000L);

        assertFalse(this.neverScanned().contains("given-up-on"));
    }

    /** A removed tag is nobody's business, wide window or otherwise. */
    @Test
    public void aremovedTagIsNotOfferedAnything() {
        this.givenAbeacon("gone");
        this.db.ownedBeaconDao().setRemoved("gone");

        assertFalse(this.neverScanned().contains("gone"));
    }

    /** Nothing stored, nothing offered - and no exception on the way. */
    @Test
    public void anemptyDatabaseOffersNothing() {
        assertEquals(Set.of(), this.neverScanned());
    }
}
