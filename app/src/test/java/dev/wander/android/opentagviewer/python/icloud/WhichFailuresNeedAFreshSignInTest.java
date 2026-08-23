package dev.wander.android.opentagviewer.python.icloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which failures from the account mean the user has to sign in again.
 *
 * <p><b>On the JVM, because it is an enum and a walk up a cause chain.</b> See AGENTS.md rule 13.
 *
 * <p>The reason one place decides: three screens can hit the same wall - the iCloud device list,
 * a rename that writes to the account, and the six-hourly background read - and a judgement made
 * three times gets made three ways. It already had: rejected credentials landed on the "worth
 * trying again later" screen, which is false for a session that can never work again, and the
 * background read logged it at warning and carried on for six hours.
 */
public class WhichFailuresNeedAFreshSignInTest {

    /**
     * <b>Exactly one failure means it, and the test names the rest so a new one cannot be
     * forgotten.</b>
     *
     * <p>Enumerating {@code values()} rather than listing the ones expected to be false is the
     * point: adding a failure to the enum without deciding what it means here fails this test
     * rather than quietly defaulting to "retry", which is how the original bug read.
     */
    @Test
    public void onlyRejectedCredentialsNeedsAFreshSignIn() {
        final Set<ICloudFailure> needThem = EnumSet.noneOf(ICloudFailure.class);

        for (final ICloudFailure failure : ICloudFailure.values()) {
            if (ICloudFailures.meansSignInAgain(new ICloudException(failure, "x"))) {
                needThem.add(failure);
            }
        }

        assertEquals(EnumSet.of(ICloudFailure.CREDENTIALS_REJECTED), needThem);
    }

    /**
     * And the ones that would be actively harmful to treat that way.
     *
     * <p>Each of these has a remedy that costs less than a sign-in, so sending somebody to the
     * login screen for one would take away a working session to fix something that was not
     * broken.
     */
    @Test
    public void therecoverableOnesDoNotCostASignIn() {
        for (final ICloudFailure benign : new ICloudFailure[] {
                ICloudFailure.PASSCODE_REJECTED,      // try the passcode again
                ICloudFailure.SERVICE_UNSURE,         // genuinely worth waiting out
                ICloudFailure.NOTHING_TO_RECOVER_FROM, // an honest end state, not a fault
                ICloudFailure.MEMBERSHIP_UNUSABLE,    // re-join, which needs no new sign-in
                ICloudFailure.UNKNOWN}) {

            assertFalse(benign + " should not cost the user a sign-in",
                    ICloudFailures.meansSignInAgain(new ICloudException(benign, "x")));
        }
    }

    /**
     * <b>Wrapped, because RxJava hands the subscriber its own exception.</b>
     *
     * <p>Every one of the three call sites reads this off an {@code onError}, so without the
     * cause walk the classification would be correct and never fire.
     */
    @Test
    public void afailureBuriedInACauseChainStillCounts() {
        final Throwable wrapped = new RuntimeException("rx wrapper",
                new IllegalStateException("another layer",
                        new ICloudException(ICloudFailure.CREDENTIALS_REJECTED, "refused")));

        assertTrue(ICloudFailures.meansSignInAgain(wrapped));
        assertEquals(ICloudFailure.CREDENTIALS_REJECTED, ICloudFailures.failureOf(wrapped));
    }

    /** Something that is not an iCloud failure at all is not one to sign in over. */
    @Test
    public void anythingElseIsNotThis() {
        assertFalse(ICloudFailures.meansSignInAgain(new IOException("the network went away")));
        assertFalse(ICloudFailures.meansSignInAgain(null));
        assertEquals(ICloudFailure.UNKNOWN, ICloudFailures.failureOf(new IOException("x")));
    }

    /**
     * <b>The string Python sends maps to the value Java switches on.</b>
     *
     * <p>The two halves are edited in different files and nothing links them: a reason renamed on
     * one side and not the other degrades to {@code UNKNOWN}, which is the retry screen again -
     * silently, and only for the failure this whole change exists to stop mishandling.
     */
    @Test
    public void thereasonStringFromPythonIsRecognised() {
        assertEquals(ICloudFailure.CREDENTIALS_REJECTED,
                ICloudFailure.fromWire("credentials_rejected"));
    }

    /**
     * <b>A cycle in the cause chain must not hang the walk.</b>
     *
     * <p>Two elements, not one. Java refuses {@code initCause(this)}, so a self-cycle cannot be
     * constructed at all - which means a guard written against one looks careful and does
     * nothing. {@code A -> B -> A} builds fine, and the first version of this walk spun on it
     * forever, on whichever thread happened to be reporting an error.
     *
     * <p>The timeout is the assertion. Without it a regression here hangs the suite rather than
     * failing it.
     */
    @Test(timeout = 2000)
    public void acycleDoesNotSpinForever() {
        final Exception inner = new Exception("inner");
        final Exception outer = new Exception("outer", inner);
        inner.initCause(outer);

        assertFalse(ICloudFailures.meansSignInAgain(outer));
        assertEquals(ICloudFailure.UNKNOWN, ICloudFailures.failureOf(outer));
    }
}
