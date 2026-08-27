package dev.wander.android.opentagviewer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.Map;
import java.util.Set;

import dev.wander.android.opentagviewer.MapsActivity;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.AccessorySightingPersister;
import dev.wander.android.opentagviewer.ble.BlePermissions;
import dev.wander.android.opentagviewer.ble.NearbyTagWatcher;
import dev.wander.android.opentagviewer.db.repo.BeaconRepository;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.util.android.CachedPhoneLocation;
import dev.wander.android.opentagviewer.util.android.FusedPhoneLocation;
import dev.wander.android.opentagviewer.db.datastore.UserSettingsDataStore;
import dev.wander.android.opentagviewer.db.repo.UserSettingsRepository;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import io.reactivex.rxjava3.schedulers.Schedulers;
import android.text.format.DateUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import dev.wander.android.opentagviewer.util.LeftBehind;
import dev.wander.android.opentagviewer.util.LocalFixWorthKeeping;
import dev.wander.android.opentagviewer.util.android.PhoneLocation;
import io.reactivex.rxjava3.core.Observable;
import dev.wander.android.opentagviewer.ble.NearbyAccessoryScanner;
import java.util.HashMap;
import java.util.List;
import dev.wander.android.opentagviewer.data.model.BeaconInformation;
import dev.wander.android.opentagviewer.db.repo.model.BeaconData;
import dev.wander.android.opentagviewer.db.room.entity.OwnedBeacon;
import dev.wander.android.opentagviewer.util.parse.BeaconDataParser;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Keeps listening for the owner's tags while the app is closed.
 *
 * <p><b>Opt-in, and that is not caution for its own sake.</b> Everything else in this app
 * listens only while a screen is open, which makes it a display feature. This one records: it
 * runs continuously, writes down where tags were heard, and shows a permanent notification for
 * as long as it does. The people who install an app to avoid Apple's tracking network have
 * specific reasons to decide that for themselves rather than receive it in an update.
 *
 * <p><b>It is also what makes the local position history worth having.</b> The question a
 * history answers is "where did I leave it", and the app is shut at exactly that moment. Without
 * this, the history records where somebody was while they had the app open, which is mostly at
 * home with the tag in their pocket.
 *
 * <p><b>A foreground service because Android gives no other way.</b> A background scan without
 * one is throttled to the point of uselessness, and from Android 14 the service must declare
 * what it is for - {@code location}, since it reads the phone's position to attribute a sighting.
 * The permanent notification is the price of that, and it is honest: something is listening.
 *
 * <p>Same {@link NearbyTagWatcher} and the same {@code SCAN_MODE_BALANCED} the screens use.
 * Low power was the obvious choice for a service that runs all day - a tenth of the radio time
 * against a quarter - but it produced gaps of over a minute while a tag sat in a pocket, and a
 * gap is what the left-behind rule has to see through. Fewer gaps also means fewer verification
 * bursts at full power, so the cheaper mode is not obviously the cheaper answer.
 *
 * <p><b>Which of the two actually costs less has not been measured yet</b>, and the duty cycles
 * alone do not settle it. Worth making the user's choice once there is a number to put beside
 * it.
 */
public class NearbyScanService extends Service {
    private static final String TAG = NearbyScanService.class.getSimpleName();

    private static final String CHANNEL_ID = "nearby_scan";
    private static final int NOTIFICATION_ID = 4711;

    /**
     * Sent when the user swipes the notification away.
     *
     * <p><b>Since Android 14 that gesture is available even on a foreground service, and it
     * removes only the notification.</b> The service keeps scanning, invisibly, which is exactly
     * the state a permanent notification exists to prevent. So the swipe is read as what it
     * plainly means - stop doing this - and turns the setting off too, leaving the switch in
     * Settings agreeing with reality.
     */
    private static final String ACTION_DISMISSED = "dev.wander.opentagviewer.SCAN_DISMISSED";

    /**
     * Swiping the left-behind alert away, which silences the sound and nothing else.
     *
     * <p>Deliberately not {@link #ACTION_DISMISSED}: that one is the permanent notification being
     * swiped, and means "stop listening". Dismissing an alarm means "I have read it", and
     * turning the whole feature off because somebody answered it would be the worst possible
     * reading of that gesture.
     */
    private static final String ACTION_SILENCE_ALARM =
            "dev.wander.opentagviewer.SILENCE_LEFT_BEHIND";

    /**
     * Channel for the left-behind alert, which is loud on purpose - see {@link #alertLeftBehind}.
     *
     * <p><b>The suffix is not decoration.</b> A notification channel is immutable once created:
     * changing the sound or the vibration in code does nothing for anybody who already has the
     * old one, and there is no way to update it. The only way to change how an alert sounds is
     * to publish a new channel, so the id carries a version.
     */
    private static final String ALERT_CHANNEL_ID = "tag_left_behind_chosen_sound";

    /** Earlier {@link #ALERT_CHANNEL_ID} values, deleted so they stop appearing in settings. */
    private static final List<String> RETIRED_ALERT_CHANNEL_IDS =
            List.of("tag_left_behind", "tag_left_behind_alarm");

    /**
     * How often the left-behind rule is evaluated.
     *
     * <p><b>This is latency, not work.</b> The check is arithmetic over a handful of tags and
     * touches the radio only for one that has gone quiet - but whatever it is, it is added to
     * every alert. At a minute it was the largest single delay in the chain, longer than the
     * silence it was watching for.
     *
     * <p>Five seconds because the silence to wait for is now the user's to choose and goes as
     * low as ten - see {@code UserSettings.LEFT_BEHIND_AFTER_SECONDS_MIN}. A tick coarser than
     * the setting makes the setting a lie: at fifteen, asking for ten and asking for fifteen
     * produced the same alert at the same moment. The two reads it costs are a query against a
     * tiny table and an in-memory preferences lookup, next to a radio that is scanning
     * continuously the whole time either way.
     */
    private static final long CHECK_INTERVAL_MS = 5_000L;

    /** What is known about a tag right now: heard since when, and where it turned up. */
    private static final class Presence {
        private long lastHeardMs;
        private final Double appearedLatitude;
        private final Double appearedLongitude;
        private boolean gone;

        private Presence(final long lastHeardMs, final Double latitude, final Double longitude) {
            this.lastHeardMs = lastHeardMs;
            this.appearedLatitude = latitude;
            this.appearedLongitude = longitude;
        }
    }

    /**
     * Per tag, when it was last heard and where this phone was then.
     *
     * <p>In memory rather than in the database, and lost on a restart on purpose: the rule is
     * about a walk somebody is taking right now. A stale entry from before a reboot would fire
     * as soon as the service came back somewhere else, which is the phone having moved rather
     * than a tag having been left.
     */
    private final Map<String, Presence> presence = new ConcurrentHashMap<>();

    private BeaconRepository beaconRepo;

    /** The key material the watch was started with, for the verification scan. */
    private Map<String, String> accessoryJsonByBeaconId = Map.of();

    /**
     * What to call each tag, read once when the watch starts.
     *
     * <p>The same name the screens show - the user's own nickname where they set one, Apple's
     * otherwise. An alert that names a beacon id tells somebody a tag is missing without telling
     * them which, which is most of the message gone.
     */
    private Map<String, String> namesByBeaconId = Map.of();

    /**
     * The tags their owner has asked to be warned about, re-read on every check.
     *
     * <p>Held as the permissions because undecided means no - see
     * {@code UserBeaconOptions.alertOnSeparation}. Every other tag is still scanned for and still
     * recorded; only the noise is off, and it stays off until somebody asks for it by name.
     *
     * <p><b>Re-read rather than read once at startup.</b> The switch lives in the app and this
     * runs in a service that outlives it, so a set read when the watch started is a snapshot of
     * what the user wanted before they went to change it. Reading it once meant turning the
     * switch on did nothing at all until the service happened to restart, which from the outside
     * is indistinguishable from the feature being broken.
     */
    private volatile Set<String> alertsOn = Set.of();

    /**
     * The silence a tag has to keep before it is worth checking, in milliseconds.
     *
     * <p>Re-read alongside {@link #alertsOn} and for the same reason: it is the user's to change
     * from a screen that this service outlives.
     */
    private volatile long quietForMs = LeftBehind.QUIET_FOR_MS;

    /** The user's chosen alarm sound, re-read with the rest. Empty means the system default. */
    private volatile String alarmSoundUri = "";

    /** Plays that sound, on repeat, until the alert is answered. */
    private LeftBehindAlarm alarm;

    /**
     * Whether the settings have been read yet in this service's life.
     *
     * <p>Only so the first read is logged even when it agrees with the defaults. "Nothing
     * changed" and "the check never ran" produce the same silence in a log otherwise, and
     * telling those two apart was the whole difficulty the last time this was wrong.
     */
    private boolean haveReadSettings = false;
    private AccessorySightingPersister sightingPersister;
    private PhoneLocation phoneLocation;

    @Nullable
    private Disposable watch;

    /** The periodic left-behind check, running for as long as the service does. */
    @Nullable
    private Disposable leftBehindCheck;

    /** Starts the service, or does nothing if it is already running. */
    public static void start(final Context context) {
        final Intent intent = new Intent(context.getApplicationContext(), NearbyScanService.class);
        context.getApplicationContext().startForegroundService(intent);
    }

    /** Stops the service and its scan. Safe to call when it is not running. */
    public static void stop(final Context context) {
        final Intent intent = new Intent(context.getApplicationContext(), NearbyScanService.class);
        context.getApplicationContext().stopService(intent);
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        this.beaconRepo = new BeaconRepository(
                OpenTagViewerDatabase.getInstance(this.getApplicationContext()));
        this.phoneLocation = new CachedPhoneLocation(
                new FusedPhoneLocation(this.getApplicationContext()));
        this.sightingPersister = new AccessorySightingPersister(this.beaconRepo);
        this.alarm = new LeftBehindAlarm(this.getApplicationContext());
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        // Logged because a restart wipes the presence map, and a tag heard again afterwards is
        // then a fresh arrival that can alert a second time. Two alerts for one departure looked
        // like a false alarm and could not be told apart from one after the fact.
        Log.i(TAG, "onStartCommand: action=" + (intent == null ? "restart" : intent.getAction()));

        if (intent != null && ACTION_DISMISSED.equals(intent.getAction())) {
            this.turnBackgroundScanningOff();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_SILENCE_ALARM.equals(intent.getAction())) {
            this.alarm.stop();
            // Falls through to goToForeground below rather than returning: the service is still
            // meant to be listening, and returning here would leave it started without the
            // notification the platform requires it to have.
        }

        this.goToForeground();

        if (this.watch == null || this.watch.isDisposed()) {
            this.startWatching();
        }

        // Restarted if the system kills it for memory, which is what somebody who turned this
        // on is asking for. Without a redelivered intent: there is no work in it, the state
        // lives in the setting.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (this.watch != null && !this.watch.isDisposed()) {
            this.watch.dispose();
        }
        this.watch = null;
        if (this.leftBehindCheck != null && !this.leftBehindCheck.isDisposed()) {
            this.leftBehindCheck.dispose();
        }
        this.leftBehindCheck = null;
        if (this.alarm != null) {
            // Nothing else would: the player holds no reference to the service, so a sounding
            // alarm would outlive the thing that started it.
            this.alarm.stop();
        }
        super.onDestroy();
    }

    /**
     * Subscribes the watch, over every tag that has usable key material.
     *
     * <p>Silent when it cannot run - no Bluetooth permission, radio off, or nothing backfilled
     * yet - for the same reason the screens are: there is nothing the user can do about it from
     * here, and a service that cannot scan should sit quietly rather than complain.
     */
    private void startWatching() {
        if (!BlePermissions.granted(this)) {
            Log.d(TAG, "Not scanning in the background: BLE permission not granted");
            return;
        }

        this.watch = this.beaconRepo.getAllBeacons()
                .subscribeOn(Schedulers.io())
                .subscribe(beacons -> {
                    this.namesByBeaconId = readNames(beacons);
                    this.watchThese(keyMaterialOf(beacons));
                }, error -> Log.w(TAG, "Could not read the tags to watch for", error));
    }

    /**
     * The key material worth listening for, keyed by beacon.
     *
     * <p>Retired tags are left out - there is nothing to listen for - but tags the network gave
     * up on are kept: those stopped being findable over Apple's network, and hearing one
     * directly is exactly what could still find it.
     */
    private static Map<String, String> keyMaterialOf(final List<BeaconData> beacons) {
        final Map<String, String> byBeaconId = new HashMap<>();

        for (final BeaconData beacon : beacons) {
            final OwnedBeacon owned = beacon.getOwnedBeaconInfo();

            if (owned == null || owned.isRemoved
                    || owned.accessoryJson == null || owned.accessoryJson.isEmpty()) {
                continue;
            }
            byBeaconId.put(beacon.getBeaconId(), owned.accessoryJson);
        }

        return byBeaconId;
    }

    /**
     * Display names, through the same parser the screens use.
     *
     * <p>Doing it here rather than reading a column: a name can come from the user's override,
     * from Apple's naming record, or out of the accessory JSON for a tag that was never in an
     * account, and {@code BeaconDataParser} is where that precedence already lives. A second
     * implementation of it would eventually disagree with the one on screen.
     */
    private static Map<String, String> readNames(final List<BeaconData> beacons) {
        final Map<String, String> names = new HashMap<>();

        try {
            for (final BeaconInformation information : BeaconDataParser.parse(beacons)) {
                if (information.getName() != null && !information.getName().isBlank()) {
                    names.put(information.getBeaconId(), information.getName());
                }
            }
        } catch (final Exception e) {
            // A tag with no name still deserves its alert, and the beacon id is at least true.
            Log.w(TAG, "Could not read the tag names; alerts will name beacon ids", e);
        }

        return names;
    }

    private void watchThese(final Map<String, String> accessoryJsonByBeaconId) {
        if (accessoryJsonByBeaconId.isEmpty()) {
            Log.d(TAG, "Not scanning in the background: no tags with key material");
            return;
        }

        this.accessoryJsonByBeaconId = accessoryJsonByBeaconId;

        this.watch = new NearbyTagWatcher(
                AppDependencies.accessoryMacResolver(),
                (sighting, mac) -> {
                    this.sightingPersister.onSighting(sighting, mac);
                    this.noteHeard(sighting.getBeaconId());
                },
                android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED)
                .watch(this.getApplicationContext(), accessoryJsonByBeaconId)
                .subscribe(
                        sighting -> { },
                        error -> Log.w(TAG, "Background watch ended with an error", error),
                        () -> Log.i(TAG, "Background watch ended"));

        Log.i(TAG, "Left-behind check starting, every " + (CHECK_INTERVAL_MS / 1000) + "s");

        this.leftBehindCheck = Observable
                .interval(CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS,
                        Schedulers.io())
                .subscribe(tick -> this.checkForLeftBehind(),
                        error -> Log.w(TAG, "The left-behind check stopped", error));
    }

    /**
     * Notes that a tag was heard, and reads the position only if it had been away.
     *
     * <p><b>Nothing is read or written while a tag keeps being heard.</b> A tag in range is with
     * whoever is holding the phone, so a position taken then describes where the <i>user</i>
     * went - it tracks a person rather than a thing, and records "still here" over and over. The
     * two moments that carry information are the edges: a tag turning up somewhere, and a tag
     * going quiet.
     *
     * <p>Turning up again also re-arms the alert. A tag that comes back is one that came along,
     * and the next time it goes quiet is a new event worth its own alert.
     */
    private void noteHeard(final String beaconId) {
        final long now = System.currentTimeMillis();
        final Presence known = this.presence.get(beaconId);

        if (known != null && !known.gone) {
            known.lastHeardMs = now;
            return;
        }

        final PhoneLocation.Fix fix = this.phoneLocation.lastKnown();

        this.presence.put(beaconId, new Presence(now,
                fix == null ? null : fix.getLatitude(),
                fix == null ? null : fix.getLongitude()));

        if (fix != null) {
            this.beaconRepo.recordLocalSighting(beaconId, fix.getLatitude(), fix.getLongitude(),
                            fix.getAccuracyMetres(), 0, now)
                    .subscribe(written -> { }, error ->
                            Log.w(TAG, "Could not record where beaconId=" + beaconId
                                    + " turned up", error));
        }

        // Answered by the tag itself: whatever the alert was about has resolved, and a siren
        // going while the thing it is about is back in earshot is just wrong.
        this.alarm.stop();

        Log.d(TAG, "beaconId=" + beaconId + " is in range again");
    }

    /**
     * Alerts once for each tag that has gone quiet while this phone moved on.
     *
     * <p>Needs a position now as well as then - without one there is no way to tell walking away
     * from standing still, and silence on its own is not worth waking somebody for. See
     * {@link LeftBehind} for the rule and why it is deliberately hard to satisfy.
     */
    private void checkForLeftBehind() {
        final long now = System.currentTimeMillis();

        // One small query against UserBeaconOptions per tick, on the IO scheduler this runs on.
        // Kept cheap enough to do unconditionally rather than guessing when it might have moved.
        // A failure keeps the previous answer: stale permissions beat none at all.
        try {
            final Set<String> wanted = this.beaconRepo.getBeaconsWithAlertsOn().blockingFirst();
            if (!wanted.equals(this.alertsOn) || !this.haveReadSettings) {
                // Logged on change rather than every tick: this is the one place that says the
                // switch in the app actually reached the service, which is exactly what is
                // invisible when it does not.
                Log.i(TAG, "Left-behind alerts are now wanted for " + wanted.size() + " tag(s)");
                this.alertsOn = wanted;
            }

            final UserSettings settings = new UserSettingsRepository(
                    UserSettingsDataStore.getInstance(this)).getUserSettings();

            final long configured = settings.resolveLeftBehindAfterSeconds() * 1000L;
            if (configured != this.quietForMs || !this.haveReadSettings) {
                Log.i(TAG, "Left-behind silence is now " + (configured / 1000) + "s");
                this.quietForMs = configured;
            }

            this.alarmSoundUri = settings.getLeftBehindSoundUri() == null
                    ? "" : settings.getLeftBehindSoundUri();
            this.haveReadSettings = true;
        } catch (final Exception couldNotRead) {
            Log.w(TAG, "Could not re-read the left-behind settings", couldNotRead);
        }

        for (final Map.Entry<String, Presence> entry : this.presence.entrySet()) {
            final Presence known = entry.getValue();

            if (known.gone || now - known.lastHeardMs < this.quietForMs) {
                continue;
            }

            // Quiet long enough to be worth checking. This is the second of the two edges, and
            // the only other moment the position is worth reading.
            known.gone = true;

            if (!this.alertsOn.contains(entry.getKey())) {
                // Still scanned for, still recorded - the owner has just not asked to be woken
                // for this one, which is the default. Skipping the verification scan too, since
                // nothing would be done with the answer.
                continue;
            }

            // **A missing position must not swallow the alert.** It used to, left over from when
            // distance was half the rule; the verification scan decides now, and "your keys are
            // not with you" is worth saying whether or not the phone can say where. It also
            // happens to be the state the service is in after a reboot until the app is next
            // opened, so the gate turned the whole feature off exactly when it was meant to be
            // working on its own.
            this.verifyThenAlert(entry.getKey(), known, this.phoneLocation.lastKnown(), now);
        }
    }

    /**
     * Listens hard for one tag before saying it is gone.
     *
     * <p><b>Silence from a low-power scan is not evidence.</b> {@code SCAN_MODE_LOW_POWER}
     * listens for roughly half a second in five, so a tag in a pocket with a body in the way
     * misses windows in runs - a gap of 66 seconds was measured while carrying one, against a
     * threshold of 90. Any threshold short enough to be useful while walking out of a cafe sits
     * inside that noise, and the alert that fired 20 minutes into a walk was exactly this: the
     * tag was in the pocket the whole time.
     *
     * <p>So the timer no longer decides. When it runs out, the radio listens properly for a few
     * seconds - the same targeted, low-latency scan the ring button uses - and only silence
     * <i>then</i> earns an alert. It costs one short burst per suspicion instead of running the
     * radio hard all day, and it makes a short threshold safe: about a minute of quiet plus a
     * few seconds of listening, rather than five minutes of waiting and still being wrong.
     */
    private void verifyThenAlert(final String beaconId, final Presence known,
                                 @Nullable final PhoneLocation.Fix here, final long nowMs) {

        final String accessoryJson = this.accessoryJsonByBeaconId.get(beaconId);
        if (accessoryJson == null) {
            return;
        }

        final Map<String, Integer> candidates =
                AppDependencies.accessoryMacResolver().currentMacAddresses(accessoryJson);

        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        NearbyAccessoryScanner
                .findNearby(this.getApplicationContext(), candidates.keySet(), VERIFY_SCAN_MS)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        device -> {
                            // It was a gap. Put the tag back to present so the next silence is
                            // judged from here rather than from before the burst.
                            known.gone = false;
                            known.lastHeardMs = System.currentTimeMillis();
                            Log.d(TAG, "beaconId=" + beaconId
                                    + " answered the verification scan; no alert");
                        },
                        notNearby -> {
                            if (here != null) {
                                this.recordContactLost(beaconId, known, here, nowMs);
                            }
                            this.alertLeftBehind(beaconId, known.lastHeardMs);
                        });
    }

    /**
     * How long the verification scan listens.
     *
     * <p>A tag in range advertises every second or two, so a few seconds of low-latency
     * listening hears it several times over. Long enough to be conclusive, short enough that the
     * burst costs nothing next to the day the radio spends idling.
     */
    private static final long VERIFY_SCAN_MS = 6_000L;

    /**
     * Writes where contact with a tag was lost, as well as it can be known.
     *
     * <p><b>The position is where the phone is now, and the accuracy says how little that is
     * worth.</b> Contact could have been lost anywhere in the quiet window - five minutes of
     * walking is several hundred metres - so the row claims that whole radius rather than the
     * metres the fix itself would claim. A tight circle drawn around where somebody noticed the
     * silence would be the app inventing a place it never observed.
     *
     * <p>Nothing is written when the phone has not moved: the tag going quiet on a desk beside
     * somebody is a radio gap, not a place worth recording.
     */
    private void recordContactLost(final String beaconId, final Presence known,
                                   final PhoneLocation.Fix here, final long nowMs) {

        if (known.appearedLatitude != null && LocalFixWorthKeeping.metresBetween(
                known.appearedLatitude, known.appearedLongitude,
                here.getLatitude(), here.getLongitude()) < LocalFixWorthKeeping.MOVED_METRES) {
            return;
        }

        final long couldBeAnywhereWithin = here.getAccuracyMetres()
                + Math.round((this.quietForMs / 1000.0) * WALKING_METRES_PER_SECOND);

        this.beaconRepo.recordLocalSighting(beaconId, here.getLatitude(), here.getLongitude(),
                        couldBeAnywhereWithin, 0, nowMs)
                .subscribe(written -> { }, error ->
                        Log.w(TAG, "Could not record where contact with beaconId=" + beaconId
                                + " was lost", error));
    }

    /** Walking pace, for turning the quiet window into the radius it implies. */
    private static final double WALKING_METRES_PER_SECOND = 1.4;

    /**
     * The one notification in this app that is allowed to interrupt.
     *
     * <p><b>High importance, with sound.</b> Everything else here is a status line somebody can
     * find when they go looking; this is the opposite - it is only useful in the half minute
     * while walking away is still reversible, and a silent entry in the shade would be read
     * hours later at home. That is also why the rule behind it is strict: an alert that cries
     * wolf gets switched off, and then it is not there on the day it matters.
     */
    private void alertLeftBehind(final String beaconId, final long lastHeardMs) {
        final NotificationManager manager = this.getSystemService(NotificationManager.class);

        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            final NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    this.getString(R.string.left_behind_channel),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(this.getString(R.string.left_behind_channel_description));

            // **Silent on purpose, and this is not the alert going quiet.** The sound is played
            // by LeftBehindAlarm instead, on the alarm stream and on repeat. A channel's sound
            // is fixed when the channel is created and cannot be changed afterwards, so a sound
            // the user picks cannot live here; and a channel plays it once, which is a chime,
            // and a chime from a pocket during a walk is the thing that gets missed.
            channel.setSound(null, null);

            // Long enough to be felt through a coat, and unlike a message buzz.
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[] {0, 500, 250, 500, 250, 800});

            manager.createNotificationChannel(channel);
        }

        final Intent open = new Intent(this, MapsActivity.class)
                .putExtra("beaconId", beaconId);

        final PendingIntent show = PendingIntent.getActivity(
                this, beaconId.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Intent quiet = new Intent(this, NearbyScanService.class)
                .setAction(ACTION_SILENCE_ALARM);
        final PendingIntent silence = PendingIntent.getService(
                this, 2, quiet, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final CharSequence howLongAgo = DateUtils.getRelativeTimeSpanString(
                lastHeardMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);

        final Notification alert = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle(this.getString(R.string.left_behind_title,
                        this.namesByBeaconId.getOrDefault(beaconId, beaconId)))
                .setContentText(this.getString(R.string.left_behind_text, howLongAgo))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                // An alarm rather than a reminder: it says to the system, and to anything
                // summarising notifications, that this is time-critical rather than something
                // to read later.
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(show)
                // Swiping it away is the answer to it, so that is where the sound stops.
                .setDeleteIntent(silence)
                .setAutoCancel(true)
                .build();

        // One notification per tag rather than one that replaces the last: leaving two things
        // behind is two things to go back for.
        manager.notify(beaconId.hashCode(), alert);

        this.alarm.start(this.alarmSoundUri);

        if (this.alarm.isAlarmStreamSilent()) {
            // Worth saying out loud rather than leaving as a mystery: the alert did everything
            // it was asked to and still made no noise, and the reason is not in this app.
            Log.w(TAG, "Alarm volume is at zero; the left-behind alert will be silent");
        }

        Log.i(TAG, "Alerted that beaconId=" + beaconId + " looks left behind");
    }

    /**
     * Turns the setting off and stops, after the notification was swiped away.
     *
     * <p><b>The setting is written, not just the service stopped.</b> Otherwise the switch in
     * Settings would still read as on while nothing was running, and the next app start would
     * bring the service back - which is the same swipe undone, without the user asking for it.
     */
    private void turnBackgroundScanningOff() {
        Log.i(TAG, "Notification dismissed; turning background scanning off");

        Schedulers.io().scheduleDirect(() -> {
            try {
                final UserSettingsRepository settingsRepo =
                        new UserSettingsRepository(UserSettingsDataStore.getInstance(this));
                final UserSettings settings = settingsRepo.getUserSettings();

                settings.setScanInBackground(false);
                settingsRepo.storeUserSettings(settings).blockingAwait();
            } catch (final Exception e) {
                Log.w(TAG, "Could not turn the background scan setting off", e);
            }
        });

        this.stopSelf();
    }

    /**
     * The permanent notification, which is what buys the right to keep scanning.
     *
     * <p>Low importance: it must be visible, and it must not make a sound or push anything else
     * off the screen. Tapping it opens the map, because "what is this doing" and "what has it
     * found" are the same question.
     *
     * <p><b>Not {@code setSilent}, which is a different thing from a quiet channel.</b> The
     * channel's own {@code IMPORTANCE_LOW} already means no sound. {@code setSilent} additionally
     * files the notification under "Silent", where it gets no status bar icon at all - so the
     * service ran with nothing to see unless somebody pulled the shade down, which defeats the
     * one thing a permanent notification is for.
     */
    private void goToForeground() {
        final NotificationManager manager = this.getSystemService(NotificationManager.class);

        // **Tidying up after our own versioning.** Each change to how the alert sounds had to
        // publish a new channel, because a channel's settings are fixed once created. The old
        // ones are unused but stay in the user's notification settings forever, so each retired
        // id would leave another dead entry there under a name that still looks live.
        for (final String retired : RETIRED_ALERT_CHANNEL_IDS) {
            manager.deleteNotificationChannel(retired);
        }

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    this.getString(R.string.background_scan_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(this.getString(R.string.background_scan_channel_description));
            manager.createNotificationChannel(channel);
        }

        final PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MapsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Intent dismissed = new Intent(this, NearbyScanService.class)
                .setAction(ACTION_DISMISSED);
        final PendingIntent onSwipe = PendingIntent.getService(
                this, 1, dismissed,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(this.getString(R.string.background_scan_notification_title))
                .setContentText(this.getString(R.string.background_scan_notification_text))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentIntent(open)
                .setDeleteIntent(onSwipe)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            this.startForeground(NOTIFICATION_ID, notification);
            return;
        }

        // Android 14 wants the type declared at the call site as well as in the manifest.
        try {
            this.startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (final SecurityException notEligibleForLocation) {
            // **Starting at boot lands here, and it is not a misconfiguration.** Location is a
            // foreground-only permission: BOOT_COMPLETED exempts the app from the ban on
            // starting a foreground service from the background, but not from the rule that it
            // may not *use* a while-in-use permission with nothing visible. Asking for the
            // location type then throws, and the throw killed the service - which START_STICKY
            // dutifully restarted, into the same throw, until Android gave up on the app.
            //
            // Scanning is a connected-device job on its own terms, so it carries on as one. The
            // position reads simply return nothing until the app is next opened, which
            // FusedPhoneLocation already treats as an ordinary answer.
            Log.i(TAG, "Not eligible for the location type here; running as connected-device", 
                    notEligibleForLocation);

            this.startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        }
    }
}
