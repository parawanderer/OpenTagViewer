package dev.wander.android.opentagviewer.anisette;

import java.util.HashMap;
import java.util.Map;

/**
 * ADI's error codes, so a failure reads as something rather than as a number.
 *
 * <p>These are not documented by Apple; the list comes from
 * <a href="https://github.com/Dadoum/Provision">Dadoum/Provision</a>
 * {@code lib/provision/adi.d}. It is not exhaustive - anything unrecognised is reported as
 * itself rather than guessed at.
 *
 * <p>Worth knowing which ones are ordinary rather than broken: {@link #NOT_PROVISIONED} is the
 * expected answer for a machine that has not been provisioned yet, and {@link #INVALID_PARAMS}
 * usually means a value of the wrong length rather than the wrong content - ADI checks the
 * size of the device identifiers even though their contents are invented.
 */
public final class AdiError {

    public static final int INVALID_PARAMS = -45001;
    public static final int NOT_PROVISIONED = -45061;

    private static final Map<Integer, String> NAMES = new HashMap<>();

    static {
        NAMES.put(-45001, "invalid parameters (often a value of the wrong length)");
        NAMES.put(-45002, "invalid parameters");
        NAMES.put(-45003, "invalid trust key");
        NAMES.put(-45006, "persistent token and trust key do not match this state");
        NAMES.put(-45018, "invalid input data parameter header");
        NAMES.put(-45019, "unknown ADI function");
        NAMES.put(-45020, "invalid input data parameter body");
        NAMES.put(-45025, "unknown session");
        NAMES.put(-45026, "empty session");
        NAMES.put(-45031, "invalid data header");
        NAMES.put(-45032, "data too short");
        NAMES.put(-45033, "invalid data body");
        NAMES.put(-45034, "unknown ADI call flags");
        NAMES.put(-45036, "time error (is the device clock correct?)");
        NAMES.put(-45046, "empty hardware identifiers");
        NAMES.put(-45054, "filesystem error (is the provisioning path writable?)");
        NAMES.put(-45061, "not provisioned");
        NAMES.put(-45062, "no provisioning to erase");
        NAMES.put(-45063, "a session is already pending");
        NAMES.put(-45066, "session already completed");
        NAMES.put(-45075, "library loading failed");
    }

    private AdiError() {
    }

    /** @return e.g. {@code "-45001 (invalid parameters...)"}, or just the number if unknown */
    public static String describe(int code) {
        final String name = NAMES.get(code);
        return name == null ? String.valueOf(code) : code + " (" + name + ")";
    }
}
