package dev.wander.android.opentagviewer.update;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * When to ask GitHub, and when to tell the user what it said. Pure, so it is tested on the JVM.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UpdatePolicy {

    /** At most once a day: the request reveals the phone's IP address to GitHub. */
    public static final long CHECK_EVERY_MS = 24L * 60 * 60 * 1000;

    /**
     * Whether an automatic check is due.
     *
     * @param enabled      the user's opt-in; null (never answered) is off
     * @param lastCheckAt  when the last check was made, 0 or null if never
     */
    public static boolean checkIsDue(final Boolean enabled, final Long lastCheckAt, final long now) {
        if (!Boolean.TRUE.equals(enabled)) {
            return false;
        }
        final long last = lastCheckAt == null ? 0 : lastCheckAt;
        // A clock set backwards would otherwise stop checks until it caught up.
        return last <= 0 || last > now || now - last >= CHECK_EVERY_MS;
    }

    /** What the Settings row says after the version. */
    public enum RowState { OFF, NOT_CHECKED, UP_TO_DATE, AVAILABLE }

    /**
     * The row's state. A newer version is shown even with checks turned off since, because it
     * is still true and Settings is where somebody looks for it.
     */
    public static RowState rowState(final Boolean enabled, final long lastCheckAt,
                                    final String known, final String installed) {
        if (UpdateCheck.isNewer(known, installed)) {
            return RowState.AVAILABLE;
        }
        if (!Boolean.TRUE.equals(enabled)) {
            return RowState.OFF;
        }
        return lastCheckAt <= 0 ? RowState.NOT_CHECKED : RowState.UP_TO_DATE;
    }

    /**
     * Whether the map should offer {@code known}, the newest release the last check found.
     *
     * <p>Once per version: "Not now" stores that version as dismissed, and the next release
     * is offered again. Settings shows it either way.
     */
    public static boolean offerOnTheMap(final Boolean enabled, final String known,
                                        final String installed, final String dismissed) {
        return Boolean.TRUE.equals(enabled)
                && UpdateCheck.isNewer(known, installed)
                && !(known.equals(dismissed));
    }
}
