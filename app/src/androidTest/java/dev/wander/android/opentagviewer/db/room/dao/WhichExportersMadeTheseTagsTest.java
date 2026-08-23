package dev.wander.android.opentagviewer.db.room.dao;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.Import;

/**
 * Which exporters produced the bundles on this install.
 *
 * <p><b>The question the Information screen used to answer with {@code getMostRecent}.</b> That
 * names one producer and reads as though it accounts for every tag on the phone - and importing
 * twice is ordinary: a second Mac, a re-export after buying a tag, an old bundle alongside a
 * current one. A report saying "exported with 1.3.0" when half the tags came out of 1.1.0 sends
 * whoever reads it looking in the wrong place, which is the whole failure this screen exists to
 * prevent.
 *
 * <p>An in-memory database rather than the device's own, so this says nothing about whatever the
 * emulator happens to have imported and cannot disturb it.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WhichExportersMadeTheseTagsTest {

    private OpenTagViewerDatabase db;

    @Before
    public void openAnEmptyOne() {
        this.db = Room.inMemoryDatabaseBuilder(
                        getInstrumentation().getTargetContext(), OpenTagViewerDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void closeIt() {
        if (this.db != null) {
            this.db.close();
        }
    }

    /** <b>Every producer, not the last one.</b> */
    @Test
    public void twoBundlesFromTwoExportersAreBothNamed() {
        this.imported("OpenTagViewer.wizard:1.1.0", 1_000L);
        this.imported("OpenTagViewer.cli:1.3.0", 2_000L);

        assertEquals(
                List.of("OpenTagViewer.cli:1.3.0", "OpenTagViewer.wizard:1.1.0"),
                this.dao().getDistinctProducers());
    }

    /**
     * <b>Most recently used first, which is not the same as most recently inserted.</b>
     *
     * <p>Somebody who imports from 1.3.0, then re-imports an older bundle, then imports from
     * 1.3.0 again has three rows and two producers. The ordering is by each producer's newest
     * import - so 1.3.0 leads, despite its <i>first</i> row being the oldest of the three.
     */
    @Test
    public void theOrderIsByEachProducersNewestImport() {
        this.imported("OpenTagViewer.wizard:1.3.0", 1_000L);
        this.imported("OpenTagViewer.wizard:1.1.0", 2_000L);
        this.imported("OpenTagViewer.wizard:1.3.0", 3_000L);

        assertEquals(
                List.of("OpenTagViewer.wizard:1.3.0", "OpenTagViewer.wizard:1.1.0"),
                this.dao().getDistinctProducers());
    }

    /** The same producer twice is one answer, not two. */
    @Test
    public void thesameExporterTwiceIsNamedOnce() {
        this.imported("OpenTagViewer.wizard:1.3.0", 1_000L);
        this.imported("OpenTagViewer.wizard:1.3.0", 2_000L);

        assertEquals(1, this.dao().getDistinctProducers().size());
    }

    /**
     * <b>An export from before {@code via:} existed contributes nothing rather than a blank.</b>
     *
     * <p>Null and empty are both real in this column - format 0.0.1 predates the field entirely.
     * Letting either through puts "Tags imported from , OpenTagViewer.wizard:1.3.0" on the
     * screen, which reads as a rendering bug rather than as an old bundle.
     */
    @Test
    public void anexportThatNeverRecordedItselfIsNotAnEmptyEntry() {
        this.imported(null, 1_000L);
        this.imported("", 2_000L);
        this.imported("OpenTagViewer.wizard:1.3.0", 3_000L);

        assertEquals(List.of("OpenTagViewer.wizard:1.3.0"), this.dao().getDistinctProducers());
    }

    /** Nothing imported is an empty list, which the screen turns into words of its own. */
    @Test
    public void nothingImportedIsEmptyRatherThanNull() {
        final List<String> producers = this.dao().getDistinctProducers();

        assertTrue("expected no producers, got " + producers, producers.isEmpty());
    }

    private ImportDao dao() {
        return this.db.importDao();
    }

    private void imported(final String via, final long at) {
        this.dao().insert(Import.builder()
                .version("0.0.2")
                .importedAt(at)
                .exportedAt(at)
                .sourceUser("someone@example.com")
                .exportedVia(via)
                .build());
    }
}
