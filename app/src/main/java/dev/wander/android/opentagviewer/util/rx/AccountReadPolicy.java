package dev.wander.android.opentagviewer.util.rx;

/**
 * Whether now is a good moment to re-read the Apple account.
 *
 * <p><b>A different question from {@link RefreshPolicy}, and much less often.</b> That one asks
 * whether to fetch <i>locations</i>, which is what the map is for and happens every minute. This
 * asks whether to re-read the <i>account</i> - which tags exist, what they are called, which have
 * gone - and that changes when somebody adds a tag in Find My or renames one, which is rare and
 * never urgent.
 *
 * <p><b>Cheap is not the same as free.</b> A read decrypts every record on the account, and every
 * call into Python is serialised behind the location fetches - where a single accessory with no
 * key alignment record can run for minutes. So a read that fires too eagerly does not just waste
 * work: it sits in a queue in front of the thing the user is actually looking at.
 *
 * <p>Takes the time rather than reading a clock, so the timing can be tested without waiting.
 */
public final class AccountReadPolicy {

    /** Why a tick did or did not re-read, so the log says something useful. */
    public enum Decision {
        /** Nobody has linked an account, so there is nothing to read. */
        NOT_LINKED,
        /** Python is busy - almost always a location fetch, which can run for minutes. */
        BUSY,
        /** Read recently enough. */
        TOO_SOON,
        /** Go. */
        READ;

        public boolean shouldRead() {
            return this == READ;
        }

        public String reason() {
            switch (this) {
                case NOT_LINKED: return "no Apple account is linked";
                case BUSY: return "Python is busy with another fetch";
                case TOO_SOON: return "the account was read recently";
                default: return "it is time";
            }
        }
    }

    private final long minIntervalMillis;

    /**
     * The shorter floor for a read the user has effectively asked for by opening the app.
     *
     * <p><b>Not the same number, because the two reads answer different questions.</b> The
     * periodic one keeps a running app roughly current; the one on resume exists because
     * somebody renamed a tag on their iPad thirty seconds ago and is now looking at this screen
     * wondering why it says the old name. An iPad updates that in seconds.
     *
     * <p>A floor at all, though: resuming happens on every task switch, and reading the account
     * each time would be a Python call and a CloudKit query for flicking between two apps.
     */
    private final long resumeIntervalMillis;

    /**
     * Zero means "never read", which is the honest starting state and not "read at the epoch".
     * A first tick after linking therefore reads, which is what somebody expects to happen
     * shortly after they connect an account.
     */
    private long lastReadAt = 0L;

    public AccountReadPolicy(final long minIntervalMillis, final long resumeIntervalMillis) {
        this.minIntervalMillis = minIntervalMillis;
        this.resumeIntervalMillis = resumeIntervalMillis;
    }

    /**
     * @param now      the wall clock, passed in
     * @param linked   whether a keychain membership is stored
     * @param busy     whether a call into Python is already running
     */
    public Decision decide(final long now, final boolean linked, final boolean busy) {
        return this.decide(now, linked, busy, this.minIntervalMillis);
    }

    /**
     * The same question, asked when the user has just opened the screen.
     *
     * <p>Everything except the interval is identical - a read that would trample a running fetch
     * is still skipped, and an unlinked account still has nothing to read.
     */
    public Decision decideOnResume(final long now, final boolean linked, final boolean busy) {
        return this.decide(now, linked, busy, this.resumeIntervalMillis);
    }

    private Decision decide(
            final long now, final boolean linked, final boolean busy, final long interval) {
        if (!linked) {
            return Decision.NOT_LINKED;
        }
        // **Checked before the interval, not after.** Calls into Python are serialised, so a
        // read that decides to go while a location fetch is running does not wait its turn
        // politely - it takes the lock as soon as that fetch releases it, in front of whatever
        // the user did next. Skipping costs one interval; the account is not going anywhere.
        if (busy) {
            return Decision.BUSY;
        }
        if (this.hasEverRead() && now < this.lastReadAt + interval) {
            return Decision.TOO_SOON;
        }
        return Decision.READ;
    }

    /**
     * Records a read.
     *
     * @param at the time the read <em>started</em>, so a long read does not immediately earn
     *           another one the moment it finishes.
     */
    public void markRead(final long at) {
        this.lastReadAt = at;
    }

    public boolean hasEverRead() {
        return this.lastReadAt > 0L;
    }

    public long lastReadAt() {
        return this.lastReadAt;
    }

    /**
     * Forget that anything was ever read.
     *
     * <p>For unlinking: if an account is linked again, the first tick should read it rather than
     * wait out an interval measured against somebody else's account.
     */
    public void forget() {
        this.lastReadAt = 0L;
    }
}
