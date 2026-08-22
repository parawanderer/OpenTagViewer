package dev.wander.android.opentagviewer.ble;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

/**
 * A JVM test on purpose: no Android in {@link BleAccessoryMatcher}, so this runs in the fast
 * suite rather than needing an emulator - see its class doc for why the comparison was pulled
 * out this way in the first place.
 */
public class BleAccessoryMatcherTest {

    private static final String CANDIDATE = "AA:BB:CC:DD:EE:FF";

    @Test
    public void matchesAnExactCandidate() {
        assertTrue(BleAccessoryMatcher.matches(CANDIDATE, Set.of(CANDIDATE)));
    }

    @Test
    public void matchesRegardlessOfCase() {
        assertTrue(BleAccessoryMatcher.matches(
                CANDIDATE.toLowerCase(), Set.of(CANDIDATE.toUpperCase())));
        assertTrue(BleAccessoryMatcher.matches(
                CANDIDATE.toUpperCase(), Set.of(CANDIDATE.toLowerCase())));
    }

    @Test
    public void matchesOneOfSeveralCandidates() {
        assertTrue(BleAccessoryMatcher.matches(
                CANDIDATE, Set.of("11:22:33:44:55:66", CANDIDATE, "77:88:99:AA:BB:CC")));
    }

    @Test
    public void doesNotMatchAnUnrelatedAddress() {
        assertFalse(BleAccessoryMatcher.matches("11:22:33:44:55:66", Set.of(CANDIDATE)));
    }

    @Test
    public void doesNotMatchAgainstAnEmptyCandidateSet() {
        assertFalse(BleAccessoryMatcher.matches(CANDIDATE, Set.of()));
    }

    @Test
    public void doesNotMatchANullScannedAddress() {
        assertFalse(BleAccessoryMatcher.matches(null, Set.of(CANDIDATE)));
    }
}
