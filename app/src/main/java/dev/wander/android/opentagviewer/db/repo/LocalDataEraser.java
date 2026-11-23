package dev.wander.android.opentagviewer.db.repo;

import io.reactivex.rxjava3.core.Completable;

/**
 * Erases what this app keeps about the user's tags on this phone.
 *
 * <p>An interface so that Settings can be driven in a test without erasing the database the test
 * runs against: the instrumented suite shares the real on-device database, and on somebody's own
 * phone that holds their tags. See {@code AppDependencies#replaceDataEraser}.
 */
public interface LocalDataEraser {
    /**
     * Every tag and its keys, location history, imports and the records about them; the iCloud
     * link's keys; the last map position; and the tags the background scan is listening for.
     * Not the settings, not the Apple session (signing out is the caller's next step) and not the
     * device identity, which would make the next sign-in register a second device on the account
     * (AGENTS.md rule 11).
     */
    Completable eraseEverything();
}
