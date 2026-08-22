package dev.wander.android.opentagviewer.ui.history;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;

import org.hamcrest.Matcher;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.anisette.FakeAnisetteSource;
import dev.wander.android.opentagviewer.DeviceStateGuard;
import dev.wander.android.opentagviewer.Eventually;
import dev.wander.android.opentagviewer.HistoryViewActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.BeaconNamingRecord;
import dev.wander.android.opentagviewer.db.room.entity.DailyHistoryFetchRecord;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.db.room.entity.LocationReport;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.PythonAppleAccount;
import dev.wander.android.opentagviewer.python.PythonAppleService;
import dev.wander.android.opentagviewer.ui.maps.FakeGeocoder;
import dev.wander.android.opentagviewer.ui.maps.FakeMapProvider;
import dev.wander.android.opentagviewer.ui.maps.MapPolyline;
import dev.wander.android.opentagviewer.ui.maps.MapProviderFactory;

/**
 * What the history screen asks the map to draw for a day.
 *
 * <p><b>This screen had no test at all.</b> Not a thin one - none. It takes its Apple session
 * from {@code PythonAppleService.getInstance()}, a process-wide singleton that only
 * {@code MapsActivity} ever set up, so nothing could launch it without launching the map first
 * and nothing did. Every line below it - the day's line, the single-point marker, stepping
 * between days, what a tap on a row does - was covered by running the app by hand.
 *
 * <p><b>The map is the fake here too</b>, which is not extra work: {@code HistoryViewActivity}
 * goes through {@code MapProviderFactory} like every other screen, so one substitution covers
 * both. That is rule 7 paying out again.
 *
 * <p><b>Most days here are served from the database, deliberately.</b> What is under test is
 * the drawing, so a day is normally given a {@code DailyHistoryFetchRecord} - the app's own note
 * that the day is already complete locally - and the Python double reports nothing. The count on
 * screen is then exactly the count that was seeded, and a failure means the drawing is wrong
 * rather than that Apple's answer changed. The fetch itself is covered by
 * {@code TheWholeAppJourneyTest}.
 *
 * <p>{@link #reportsAlreadyInTheDatabaseSurviveAnEmptyAnswerFromApple} is the exception and
 * withholds that record on purpose, because the local-only path would pass it either way.
 *
 * <p><b>A beacon id per test, because the screen caches across instances.</b>
 * {@code MEMORY_REPORTS_CACHE} is static and keyed by day and beacon, so two tests using one
 * beacon would have the second read the first's answer - passing for the wrong reason, or
 * failing after an unrelated edit. The name of the running test is the id.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class TheHistoryScreenDrawsTheDayTest {

    private static final String A_TEST_USER = "history-draws@example.com";

    /** Far enough apart to be distinguishable on a line, close enough to be one day's walk. */
    private static final double[][] A_WALK = {
            {52.370216, 4.895168},
            {52.372100, 4.897900},
            {52.374500, 4.901200},
            {52.376000, 4.905500},
    };

    private static final String A_PLIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<plist version=\"1.0\"><dict>"
            + "<key>batteryLevel</key><integer>1</integer>"
            + "<key>model</key><string></string>"
            + "<key>pairingDate</key><date>2025-02-27T20:03:32Z</date>"
            + "<key>privateKey</key><dict><key>key</key><dict>"
            + "<key>data</key><data>bm90LWEtcmVhbC1rZXk=</data></dict></dict>"
            + "<key>productId</key><integer>21760</integer>"
            + "<key>stableIdentifier</key><array><string>2001~#0~#A0</string></array>"
            + "<key>systemVersion</key><string>2.0.73</string>"
            + "<key>vendorId</key><integer>76</integer>"
            + "</dict></plist>";

    @Rule
    public TestName runningTest = new TestName();

    private OpenTagViewerDatabase db;
    private DeviceStateGuard guard;
    private FakeMapProvider map;
    private PyObject appleDouble;
    private ActivityScenario<HistoryViewActivity> scenario;

    private String beaconId;
    private long importId;

    @Before
    public void substituteTheWorldAndSeedATag() {
        // Or the map/login/settings screen loads Apple's real ADI library: a download,
        // a dlopen and a native initialise, none of which this test is about - and two
        // screens reaching it at once segfaults the process and aborts the whole run.
        // See issue #135.
        AppDependencies.replaceAnisette(whateverTheSettingsSay -> FakeAnisetteSource.ready());
        final Context context = getInstrumentation().getTargetContext();

        this.guard = DeviceStateGuard.capture(context);
        this.db = OpenTagViewerDatabase.getInstance(context);
        this.beaconId = "history-" + this.runningTest.getMethodName();

        this.forgetIt();

        this.map = new FakeMapProvider();
        MapProviderFactory.replaceWith(() -> this.map);
        AppDependencies.replaceGeocoder((ctx, locale) -> new FakeGeocoder());

        // **Wired straight in, rather than by launching the map first.** The screen only needs
        // the singleton to exist; going through MapsActivity to create one would make every
        // test here depend on the map's whole startup, including its fetch.
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context));
        }
        this.appleDouble = Python.getInstance().getModule("apple_test_double");
        this.appleDouble.callAttr("installWithNothingToReport");
        PythonAppleService.setup(new PythonAppleAccount(this.appleDouble.get("theAccount")));

        this.importId = this.db.importDao().insert(Import.builder()
                .version("0.0.2").importedAt(1_700_000_000_000L).exportedAt(1_699_000_000_000L)
                .sourceUser(A_TEST_USER).exportedVia("OpenTagViewer.wizard:test").build());

        this.db.ownedBeaconDao().insertAll(OwnedBeacon.builder()
                .id(this.beaconId).importId(this.importId).content(A_PLIST).version("0.0.2")
                .fromAccount(false).isRemoved(false)
                .accessoryJson("{\"type\": \"accessory\", \"id\": \"" + this.beaconId + "\"}")
                .build());

        this.db.beaconNamingRecordDao().insertAll(BeaconNamingRecord.builder()
                .id(this.beaconId).importId(this.importId).version("0.0.2").isRemoved(false)
                .content("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                        + "<key>identifier</key><string>" + this.beaconId + "</string>"
                        + "<key>name</key><string>Bike</string>"
                        + "</dict></plist>")
                .build());
    }

    @After
    public void putEverythingBack() {
        if (this.scenario != null) {
            this.scenario.close();
        }
        if (this.appleDouble != null) {
            this.appleDouble.callAttr("uninstall");
        }

        PythonAppleService.INSTANCE = null;
        MapProviderFactory.reset();
        AppDependencies.reset();

        this.forgetIt();
        this.guard.restore();
    }

    // ------------------------------------------------------------------ the day's line

    /**
     * <b>Every point of the day is on the line, in order.</b>
     *
     * <p>Not "a line was drawn" - the count and the order are the parts that go wrong. A line
     * built from a filtered or re-sorted list still draws, still looks like a route, and is a
     * different route.
     */
    @Test
    public void thedaysReportsAreJoinedByALineThroughAllOfThem() {
        this.givenReportsOn(0, A_WALK);
        this.openTheHistory();

        Eventually.check(() -> assertTrue("no line was drawn for a day with four reports",
                !this.map.polylines().isEmpty()));

        final MapPolyline drawn = this.map.polylines().get(0);
        assertEquals("the line skipped or duplicated a point",
                A_WALK.length, drawn.getPoints().size());

        for (int i = 0; i < A_WALK.length; i++) {
            assertEquals("point " + i + " is not where the report was",
                    A_WALK[i][0], drawn.getPoints().get(i).getLatitude(), 0.000001);
            assertEquals(A_WALK[i][1], drawn.getPoints().get(i).getLongitude(), 0.000001);
        }
    }

    /**
     * <b>Two lines, not one: the route and the outline under it.</b>
     *
     * <p>The outline is what makes the route readable against a busy map. It is drawn first and
     * wider, so losing it is invisible in code review and obvious only on a dark satellite tile
     * - which is to say, not on anything CI looks at.
     */
    @Test
    public void thelineIsDrawnOverAnOutlineSoItStaysReadable() {
        this.givenReportsOn(0, A_WALK);
        this.openTheHistory();

        Eventually.check(() -> assertEquals("the route should be an outline and a line over it",
                2, this.map.polylines().size()));

        final MapPolyline outline = this.map.polylines().get(0);
        final MapPolyline route = this.map.polylines().get(1);

        assertTrue("the outline must be the wider of the two, or it is not an outline",
                outline.getWidth() > route.getWidth());
        assertEquals("both should trace the same points",
                outline.getPoints().size(), route.getPoints().size());
    }

    /**
     * <b>One report is a place, not a route.</b>
     *
     * <p>A polyline through a single point draws nothing at all on a real map, so this is the
     * case where "the screen is empty" and "the day had one sighting" look identical.
     */
    @Test
    public void asingleReportGetsAMarkerRatherThanALine() {
        this.givenReportsOn(0, new double[][] {A_WALK[0]});
        this.openTheHistory();

        Eventually.check(() -> assertEquals("a single point should be marked, not joined up",
                1, this.map.markerCount()));

        assertTrue("nothing should be joined up when there is one point",
                this.map.polylines().isEmpty());

        final FakeMapProvider.PlacedMarker only = this.map.markers().get(0);
        assertEquals(A_WALK[0][0], only.marker.getLatitude(), 0.000001);
        assertEquals(A_WALK[0][1], only.marker.getLongitude(), 0.000001);
    }

    /** And a day nobody was seen on draws neither, rather than a line to nowhere. */
    @Test
    public void adayWithNothingOnItDrawsNeither() {
        this.givenReportsOn(0, new double[][] {});
        this.openTheHistory();

        // **Waited on the count, not on the list being visible.** An empty map is what this
        // asserts, and an empty map is also what the screen looks like before the day has
        // loaded - so without a positive signal that loading finished, this test passes
        // instantly and would keep passing if the drawing broke entirely. The datapoints
        // caption is written once the day resolves, whatever the day contained.
        Eventually.check(() -> assertEquals("the day never finished loading",
                "0 data points", this.datapointsCaption()));

        assertTrue("a day with no reports drew a line", this.map.polylines().isEmpty());
        assertTrue("a day with no reports drew a marker", this.map.markers().isEmpty());
    }

    // ------------------------------------------------------------------ picking one out

    /**
     * <b>Tapping a report in the list puts a marker on it and goes there.</b>
     *
     * <p>The one way to tell which dot on the line is which. It replaces the previous marker
     * rather than adding to it - the screen keeps a single {@code single_coord_marker} - so a
     * broken replacement leaves a trail of markers behind the user as they read down the list,
     * which on a real map looks like the route being drawn twice.
     */
    @Test
    public void tappingAReportMarksItAndMovesTheCameraThere() {
        this.givenReportsOn(0, A_WALK);
        this.openTheHistory();

        Eventually.check(() -> assertTrue(this.rowCount() >= A_WALK.length));

        // **The sheet has to be opened, because that is what a person does.** It starts at its
        // peek height with the list below the fold, so the rows are genuinely not on screen -
        // Espresso was right to refuse, and the first version of this test read that refusal as
        // the list being broken.
        this.expandTheSheet();

        Eventually.check(() -> onView(withId(R.id.recycler_view_history_items))
                .check(matches(isDisplayed())));

        final int camerasBefore = this.map.cameraMoves().size();

        onView(withId(R.id.recycler_view_history_items))
                .perform(actionOnItemAtPosition(1, clickTheRowsOwnTarget()));

        Eventually.check(() -> assertEquals("tapping a report should leave exactly one marker",
                1, this.map.markerCount()));

        final FakeMapProvider.PlacedMarker marked = this.map.markers().get(0);
        assertEquals("single_coord_marker", marked.id);

        // **On the report that was tapped, not merely somewhere.** Asserting only that a marker
        // exists would pass with it on any of the four, which is the one thing this control is
        // for.
        //
        // Row order is oldest first - getInTimeRange is `ORDER BY timestamp ASC` - so position 1
        // is the second point of the walk. Checked rather than assumed: the first version of
        // this read the list as newest-first and failed pointing at the wrong end of the route.
        final double[] tapped = A_WALK[1];
        assertEquals("the marker went on a different report than the one tapped",
                tapped[0], marked.marker.getLatitude(), 0.000001);
        assertEquals(tapped[1], marked.marker.getLongitude(), 0.000001);

        Eventually.check(() -> assertTrue("the camera never moved to the tapped report",
                this.map.cameraMoves().size() > camerasBefore));
    }

    /**
     * <b>Tapping a report from a fully open sheet shrinks it to its smaller open state.</b>
     *
     * <p>Intended, and easy to lose. At full height the sheet covers the map, so placing a
     * marker under it and leaving the sheet up would make the tap appear to do nothing - and
     * showing you where a report was is the control's entire purpose.
     *
     * <p><b>Smaller, not gone.</b> It goes to half-expanded rather than all the way down to the
     * peek height: the list has to stay readable so the next report is one tap away, rather than
     * making the user re-open the sheet after every single one. Both halves of that are
     * asserted, because "it shrank" alone would be satisfied by collapsing it entirely.
     *
     * <p><b>Only from fully expanded.</b> Tapping from the half-open state leaves the sheet
     * where it is, because the map is already visible and moving it under the user's thumb would
     * be the app arguing with them. That case is pinned below.
     */
    @Test
    public void tappingAReportFromAFullyOpenSheetShrinksItToItsSmallerOpenState() {
        this.givenReportsOn(0, A_WALK);
        this.openTheHistory();

        Eventually.check(() -> assertTrue(this.rowCount() >= A_WALK.length));
        this.expandTheSheet();
        Eventually.check(() -> assertEquals(BottomSheetBehavior.STATE_EXPANDED, this.sheetState()));

        onView(withId(R.id.recycler_view_history_items))
                .perform(actionOnItemAtPosition(1, clickTheRowsOwnTarget()));

        Eventually.check(() -> assertEquals(
                "the sheet stayed at full height after a report was tapped, so the marker it"
                        + " just placed is hidden behind the list",
                BottomSheetBehavior.STATE_HALF_EXPANDED, this.sheetState()));

        // Said separately, because the assertion above would also be met by a sheet that had
        // shut entirely - and that would cost the user a re-open for every report they look at.
        assertTrue("the sheet closed all the way down instead of shrinking, so reading the next"
                        + " report means opening it again",
                this.sheetState() != BottomSheetBehavior.STATE_COLLAPSED);
    }

    /**
     * <b>And the map is told to keep its content out from under the sheet.</b>
     *
     * <p>The other half of the same gesture, and the part that makes it useful. Map padding
     * shrinks the region the camera centres within, so the screen tracks the sheet with it -
     * meaning the marker it just placed is framed in the strip still visible above the list
     * rather than behind it.
     *
     * <p>Without this the tap looks broken in a specific, maddening way: the marker <i>is</i>
     * drawn and the camera <i>does</i> move, both correctly, and the user sees neither because
     * both are underneath the sheet. Nothing logs anything.
     *
     * <p>Asserted as "the bottom is padded and the other three are not", rather than pinning the
     * exact pixel count - that number is derived from the sheet's height and peek at runtime,
     * so an exact expectation here would be this test recomputing the app's arithmetic and
     * agreeing with itself.
     */
    @Test
    public void tappingAReportKeepsTheMapClearOfTheSheet() {
        this.givenReportsOn(0, A_WALK);
        this.openTheHistory();

        Eventually.check(() -> assertTrue(this.rowCount() >= A_WALK.length));
        this.expandTheSheet();

        // The list has to be on screen before a row in it can be acted on - the sheet animates
        // open, and without this the action lands while it is still on its way up.
        Eventually.check(() -> onView(withId(R.id.recycler_view_history_items))
                .check(matches(isDisplayed())));

        onView(withId(R.id.recycler_view_history_items))
                .perform(actionOnItemAtPosition(1, clickTheRowsOwnTarget()));

        Eventually.check(() -> {
            final int[] padding = this.map.padding();
            assertTrue("the map was never told to stay clear of the sheet, so the marker is"
                    + " centred behind the list", padding != null);
            assertTrue("the map was given no bottom padding, so its content is framed behind"
                    + " the sheet", padding[3] > 0);
        });

        final int[] padding = this.map.padding();
        assertEquals("padding was applied on a side the sheet is not on", 0, padding[0]);
        assertEquals("padding was applied on a side the sheet is not on", 0, padding[1]);
        assertEquals("padding was applied on a side the sheet is not on", 0, padding[2]);
    }

    /** And from half open it is left where it is, rather than moving under the user's thumb. */
    @Test
    public void tappingAReportFromAHalfOpenSheetLeavesItAlone() {
        this.givenReportsOn(0, A_WALK);
        this.openTheHistory();

        Eventually.check(() -> assertTrue(this.rowCount() >= A_WALK.length));
        this.setTheSheetTo(BottomSheetBehavior.STATE_HALF_EXPANDED);
        Eventually.check(() -> assertEquals(
                BottomSheetBehavior.STATE_HALF_EXPANDED, this.sheetState()));

        onView(withId(R.id.recycler_view_history_items))
                .perform(actionOnItemAtPosition(1, clickTheRowsOwnTarget()));

        Eventually.check(() -> assertEquals("tapping a report moved a sheet that was already"
                        + " showing the map",
                BottomSheetBehavior.STATE_HALF_EXPANDED, this.sheetState()));
    }

    /**
     * <b>The sheet opens by dragging its handle up, which is how a person opens it.</b>
     *
     * <p>Every other test here opens the sheet by setting the behaviour's state directly, which
     * is convenient and skips the only affordance the user has. The list lives below the peek
     * height, so if the handle stops working there is no way to read the history at all - and
     * nothing would have noticed, because the tests were reaching past it.
     *
     * <p><b>Dragged, not tapped, and the difference is Material's rather than this app's.</b>
     * {@code BottomSheetDragHandleView} only makes itself <i>clickable</i> when touch
     * exploration is on - the tap is there for accessibility services, and toggling the sheet
     * from a click is what a screen reader gets instead of a drag it cannot perform. For
     * everyone else the handle is a thing to drag, so a tap correctly does nothing, and a test
     * that tapped was asserting behaviour no sighted user has.
     */
    @Test
    public void thesheetOpensWhenItsHandleIsDraggedUp() {
        this.givenReportsOn(0, A_WALK);
        this.openTheHistory();

        Eventually.check(() -> assertTrue(this.rowCount() >= A_WALK.length));
        Eventually.check(() -> assertEquals("the sheet should start at its peek height",
                BottomSheetBehavior.STATE_COLLAPSED, this.sheetState()));

        onView(withId(R.id.drag_handle)).perform(swipeUp());

        Eventually.check(() -> assertTrue(
                "dragging the handle up left the sheet in state " + this.sheetState()
                        + " (collapsed is " + BottomSheetBehavior.STATE_COLLAPSED + "), so the"
                        + " history list cannot be reached at all",
                this.sheetState() != BottomSheetBehavior.STATE_COLLAPSED));

        Eventually.check(() -> onView(withId(R.id.recycler_view_history_items))
                .check(matches(isDisplayed())));
    }

    // ------------------------------------------------------------------ moving between days

    /** <b>Stepping back a day draws that day instead.</b> */
    @Test
    public void steppingBackADayDrawsThatDayInstead() {
        this.givenReportsOn(0, new double[][] {A_WALK[0]});
        this.givenReportsOn(1, A_WALK);
        this.openTheHistory();

        Eventually.check(() -> assertEquals("today has one report, so it is a marker",
                1, this.map.markerCount()));

        onView(withId(R.id.history_move_left_button)).perform(click());

        Eventually.check(() -> assertEquals("yesterday's four reports were not joined up",
                A_WALK.length, this.map.polylines().get(0).getPoints().size()));
    }

    /**
     * <b>And there is no stepping forward from today.</b>
     *
     * <p>Disabled rather than absent, so the control does not move around under the thumb as the
     * user walks back through the week.
     */
    @Test
    public void thereIsNoSteppingForwardFromToday() {
        this.givenReportsOn(0, A_WALK);
        this.openTheHistory();

        Eventually.check(() -> onView(withId(R.id.history_move_right_button))
                .check(matches(not(isEnabled()))));

        onView(withId(R.id.history_move_left_button)).perform(click());

        Eventually.check(() -> onView(withId(R.id.history_move_right_button))
                .check(matches(isEnabled())));
    }

    // ------------------------------------------------------------------ the fixture

    /**
     * Put reports on a day, and record that day as already fetched.
     *
     * <p>The record is what keeps this deterministic: without it the screen merges a remote
     * fetch into the answer, and the count on screen stops being the count that was seeded.
     */
    private void givenReportsOn(final int daysBack, final double[][] positions) {
        this.givenReportsOn(daysBack, positions, true);
    }

    private void givenReportsOn(final int daysBack, final double[][] positions,
                                final boolean markTheDayAsAlreadyFetched) {
        final long dayStart = startOfDayLocal(daysBack);

        final List<LocationReport> reports = new ArrayList<>();
        for (int i = 0; i < positions.length; i++) {
            // Spread through the middle of the day, so none of them can fall outside it however
            // early or late the suite runs.
            final long at = dayStart + (10 * 60 * 60 * 1000L) + (i * 60_000L);

            reports.add(LocationReport.builder()
                    .hashId(this.beaconId + "-" + daysBack + "-" + i)
                    .beaconId(this.beaconId)
                    .publishedAt(at)
                    .description("Wi-Fi")
                    .timestamp(at)
                    .confidence(2)
                    .latitude(positions[i][0])
                    .longitude(positions[i][1])
                    .horizontalAccuracy(83)
                    .status(144)
                    .lastUpdate(at)
                    .build());
        }

        if (!reports.isEmpty()) {
            this.db.locationReportDao().insertAll(reports.toArray(new LocationReport[0]));
        }

        if (markTheDayAsAlreadyFetched) {
            this.db.dailyHistoryFetchRecordDao().insertAll(DailyHistoryFetchRecord.builder()
                    .beaconId(this.beaconId)
                    .dayStartTime(dayStart)
                    .lastUpdate(System.currentTimeMillis())
                    .build());
        }
    }

    /** Midnight local, {@code daysBack} days ago - the boundary the screen itself uses. */
    private static long startOfDayLocal(final int daysBack) {
        final long then = System.currentTimeMillis() - (daysBack * 24L * 60 * 60 * 1000);

        return Instant.ofEpochMilli(then)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    private void openTheHistory() {
        final Intent intent = new Intent(
                getInstrumentation().getTargetContext(), HistoryViewActivity.class);
        intent.putExtra("beaconId", this.beaconId);

        this.scenario = ActivityScenario.launch(intent);
    }

    /**
     * Tap the thing in a history row that actually listens.
     *
     * <p><b>The row's root is not clickable</b> - {@code HistoryItemsAdapter} hangs the listener
     * on {@code history_item_clickable_container} inside it. A plain {@code click()} on the row
     * therefore lands, dispatches, and is handled by nothing at all, which surfaces as the
     * assertion about markers failing rather than as anything about the click. Naming the child
     * removes the ambiguity.
     *
     * <p>It calls {@code performClick} rather than injecting a tap, so it proves the wiring from
     * this control to the map and <b>not</b> that the control is reachable by a finger. Size and
     * overlap are the layout tests' job.
     */
    private static ViewAction clickTheRowsOwnTarget() {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isAssignableFrom(View.class);
            }

            @Override
            public String getDescription() {
                return "click the row's clickable container";
            }

            @Override
            public void perform(final UiController uiController, final View view) {
                final View target = view.findViewById(R.id.history_item_clickable_container);
                if (target == null) {
                    throw new AssertionError("a history row has no clickable container in it");
                }
                target.performClick();
                uiController.loopMainThreadUntilIdle();
            }
        };
    }

    /** What the bottom sheet is currently doing, read off its behaviour. */
    private int sheetState() {
        final int[] state = {-1};
        this.scenario.onActivity(activity -> state[0] = BottomSheetBehavior
                .from(activity.findViewById(R.id.view_history_bottom_sheet_layout))
                .getState());
        return state[0];
    }

    /** Drag the sheet all the way up, as a reader of the list would. */
    private void expandTheSheet() {
        this.setTheSheetTo(BottomSheetBehavior.STATE_EXPANDED);
    }

    /**
     * Put the sheet in a given state directly.
     *
     * <p>Used to <i>arrange</i> a starting point, never to assert one - the tap that opens it is
     * covered by {@link #thesheetOpensWhenItsHandleIsTapped}, which uses the handle a person
     * actually touches.
     */
    private void setTheSheetTo(final int state) {
        getInstrumentation().runOnMainSync(() -> this.scenario.onActivity(activity ->
                BottomSheetBehavior
                        .from(activity.findViewById(R.id.view_history_bottom_sheet_layout))
                        .setState(state)));
        getInstrumentation().waitForIdleSync();
    }

    /** The "N data points" caption, read directly - it is written once the day resolves. */
    private String datapointsCaption() {
        final String[] found = {null};
        this.scenario.onActivity(activity -> {
            final TextView caption = activity.findViewById(R.id.history_datapoints_text);
            found[0] = caption == null || caption.getText() == null
                    ? null : caption.getText().toString();
        });
        return found[0];
    }

    private int rowCount() {
        final int[] found = {0};
        this.scenario.onActivity(activity -> {
            final RecyclerView list = activity.findViewById(R.id.recycler_view_history_items);
            found[0] = list == null || list.getAdapter() == null
                    ? 0 : list.getAdapter().getItemCount();
        });
        return found[0];
    }

    private void forgetIt() {
        // The reports go with it: LocationReport is keyed to the beacon and cascades on delete,
        // which is the same mechanism that made INSERT OR REPLACE destroy history elsewhere in
        // this app. Here it is the wanted behaviour, and there is no delete-by-beacon query.
        this.db.ownedBeaconDao().delete(OwnedBeacon.builder().id(this.beaconId).build());
        this.db.beaconNamingRecordDao().delete(
                BeaconNamingRecord.builder().id(this.beaconId).build());

        for (final Import stale : this.db.importDao().getImportsFromUser(A_TEST_USER)) {
            this.db.importDao().delete(stale);
        }
    }

    // --- what is already known is not thrown away --------------------------------------------

    /**
     * <b>Reports the app already holds are shown, even when Apple answers with nothing.</b>
     *
     * <p><b>The bug this exists for was reported from the app.</b> Inside the last seven days
     * the screen used to take the remote answer alone and discard everything in the database;
     * the local copy was merged in only for days <i>older</i> than a week, on the reasoning that
     * Apple stops serving history beyond that. So a tag the map had located minutes earlier
     * showed an empty history, and stepping back a day and returning made reports appear.
     *
     * <p>Apple returning nothing for a window is entirely ordinary - reports age out, and a
     * narrow key window covers less than the day asked for - so this was not a rare state.
     *
     * <p><b>No {@code DailyHistoryFetchRecord} here, deliberately.</b> That record is what sends
     * the screen down the local-only path, which would pass this test without the fix. Without
     * it the screen goes remote, and the double answers with nothing at all - so anything on
     * screen can only have come from the database.
     */
    @Test
    public void reportsAlreadyInTheDatabaseSurviveAnEmptyAnswerFromApple() {
        this.givenReportsOn(0, A_WALK, false);
        this.openTheHistory();

        Eventually.check(() -> assertEquals(
                "Apple answered with nothing and the day came back empty, so the reports this"
                        + " app had already stored were thrown away",
                A_WALK.length, this.map.polylines().get(0).getPoints().size()));
    }
}
