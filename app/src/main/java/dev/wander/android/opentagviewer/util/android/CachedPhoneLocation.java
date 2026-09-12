package dev.wander.android.opentagviewer.util.android;

import androidx.annotation.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * A {@link PhoneLocation} that answers from memory between reads.
 *
 * <p><b>Because every read lights the location indicator.</b> Android shows it whenever an app
 * touches location, and the sighting path touches it per sighting - once to attribute the
 * position, once for the left-behind rule, for every tag in range. With the background service
 * running that is a chip blinking in the status bar every few seconds, which reads as the app
 * tracking somebody far more aggressively than it is.
 *
 * <p><b>It costs almost no accuracy, because the underlying read was never a fresh fix.</b>
 * {@code getLastLocation} hands back whatever position the system already holds; reading it more
 * often does not make it newer. Caching changes how often this app <i>asks</i>, not how current
 * the answer is.
 *
 * <p>The window is a minute, which is well inside the fifteen the write rule waits before
 * recording a stationary tag again - so a cached fix cannot suppress a row that a fresh one
 * would have written.
 *
 * <p>No Android in here on purpose: it decorates the seam rather than the implementation, so the
 * expiry rule is covered by a JVM test.
 */
public class CachedPhoneLocation implements PhoneLocation {

    /** How long an answer is reused. See the class doc for why a minute is safe here. */
    static final long FRESH_FOR_MS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Metres per second of slack added to a cached fix per second of its age.
     *
     * <p><b>Because a stale position handed back at its original accuracy is a lie.</b> Somebody
     * walking covers roughly this much a second, so a minute-old fix can be eighty metres from
     * where they are - reported as accurate to eight, which is what the map would draw and what
     * anything comparing two reports would believe.
     *
     * <p>Walking pace rather than driving: this is an upper bound on the error for the case the
     * feature is for, and inflating every fix to motorway distances would make an honest reading
     * useless. A fix taken while driving is wider than this says, and the fix's own age is
     * recorded either way.
     */
    private static final double WALKING_METRES_PER_SECOND = 1.4;

    /** Injectable so the expiry is testable without waiting a minute. */
    interface Clock {
        long nowMs();
    }

    private final PhoneLocation delegate;
    private final Clock clock;

    @Nullable
    private volatile Fix cached;
    private volatile long cachedAtMs;

    /** Separate from the timestamp: null is a real answer here, so it must be tellable from
     * "never asked". A sentinel timestamp made an empty cache look fresh. */
    private volatile boolean asked;

    public CachedPhoneLocation(final PhoneLocation delegate) {
        this(delegate, System::currentTimeMillis);
    }

    CachedPhoneLocation(final PhoneLocation delegate, final Clock clock) {
        this.delegate = delegate;
        this.clock = clock;
    }

    @Nullable
    @Override
    public Fix lastKnown() {
        final long now = this.clock.nowMs();

        if (this.asked && now - this.cachedAtMs < FRESH_FOR_MS) {
            return this.cached == null ? null : widenedByAge(this.cached, now - this.cachedAtMs);
        }

        final Fix fresh = this.delegate.lastKnown();

        // **A miss is remembered too.** Location being off, or no fix yet, is a state that lasts
        // - retrying it per sighting would light the indicator exactly as often as succeeding,
        // for an answer that is not going to change in the next few seconds.
        this.cached = fresh;
        this.cachedAtMs = now;
        this.asked = true;

        return fresh;
    }

    /**
     * The same position, with the accuracy it can still honestly claim at this age.
     *
     * <p>Widening rather than refusing: a position good to a hundred metres is worth keeping and
     * says so, while withholding it would leave the sighting with no place at all. The reader
     * that cares - {@code BeaconCombinerUtil}, comparing two reports of the same moment - gets
     * the number it needs to prefer the better one.
     */
    private static Fix widenedByAge(final Fix fix, final long ageMs) {
        final long slack = Math.round((ageMs / 1000.0) * WALKING_METRES_PER_SECOND);

        if (slack == 0) {
            return fix;
        }

        return new Fix(fix.getLatitude(), fix.getLongitude(), fix.getAccuracyMetres() + slack);
    }
}
