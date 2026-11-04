package dev.wander.android.opentagviewer.anisette;

import android.content.Context;

import dev.wander.android.opentagviewer.db.repo.model.UserSettings;

/**
 * Which Anisette a caller gets: the emulated Mac for a fresh sign-in, and for a restored session
 * whichever machine signed it in.
 *
 * <p><b>A restored session keeps its machine, and nothing is provisioned to restore it.</b> Per
 * #248 a Find My token only works alongside Anisette from the machine that obtained it. Every
 * session signed in before the emulated Mac existed belongs to {@link LocalAnisette}'s Android
 * machine (or to a remote server), and some of those still fetch reports today. Handing them the
 * emulated Mac would download CoreADI and register a new Mac at app start, then pair the restored
 * account's Android {@code X-Mme-Device-Id} with that Mac's {@code X-Apple-I-MD-M} - a session
 * that worked, made to answer 401. So they keep {@link LocalAnisette}, exactly as before.
 *
 * <p>Only a session {@link SessionProvenance} says the emulated Mac signed in is restored onto
 * it, and then in {@link EmulatedMacAnisette.Purpose#RESTORE}, which never provisions.
 *
 * <p>{@code hasExistingSession} is the same flag {@link UserSettings#usesLocalAnisette} takes:
 * false only on the sign-in screen, where nobody is signed in.
 */
public final class AnisetteForSession {

    /** What {@link #choose} picks. */
    enum Choice {
        /** A fresh sign-in: the emulated Mac, which may provision. */
        EMULATED_MAC_FOR_SIGN_IN,
        /** A session the emulated Mac signed in: the same Mac, never provisioned again. */
        EMULATED_MAC_FOR_RESTORE,
        /** Every other session: the Android machine that signed it in, or its server. */
        ANDROID
    }

    private AnisetteForSession() {
    }

    static Choice choose(final boolean hasExistingSession, final SessionProvenance provenance) {
        if (!hasExistingSession) {
            return Choice.EMULATED_MAC_FOR_SIGN_IN;
        }
        return provenance.signedInAsEmulatedMac()
                ? Choice.EMULATED_MAC_FOR_RESTORE : Choice.ANDROID;
    }

    /** {@code AppDependencies}' factory. */
    public static AnisetteSource create(final Context context, final UserSettings settings,
                                        final boolean hasExistingSession) {
        switch (choose(hasExistingSession, SessionProvenance.of(context))) {
            case EMULATED_MAC_FOR_SIGN_IN:
                return new EmulatedMacAnisette(context, settings,
                        EmulatedMacAnisette.Purpose.SIGN_IN);
            case EMULATED_MAC_FOR_RESTORE:
                return new EmulatedMacAnisette(context, settings,
                        EmulatedMacAnisette.Purpose.RESTORE);
            case ANDROID:
            default:
                return new LocalAnisette(context, settings, hasExistingSession);
        }
    }
}
