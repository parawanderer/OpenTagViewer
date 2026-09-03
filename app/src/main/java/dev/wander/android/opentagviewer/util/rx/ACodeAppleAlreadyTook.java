package dev.wander.android.opentagviewer.util.rx;

import java.util.concurrent.TimeUnit;

/**
 * The 503 that arrives <i>after</i> Apple has accepted a verification code.
 *
 * <p><b>Two calls hide behind one submit.</b> FindMy.py's {@code td_2fa_submit} first sends the
 * code - that is the check, and it passes - and then runs a full Grand Slam re-authentication.
 * The second half can fail on its own, and when it does the code has already been consumed.
 *
 * <p><b>So the one thing the screen used to do is the one thing that cannot work.</b> It sent the
 * user back to an empty code box holding a code Apple has already spent. Typing it again returns
 * {@code InvalidCredentialsError} - a <i>different</i> error, which reads as "you typed it wrong"
 * - and the attempt counter climbs until the screen advises changing the Anisette server. That
 * advice is wrong here and expensive: Anisette had nothing to do with it, and changing it forces
 * a re-login against a different machine identity (AGENTS.md rule 4). Somebody is sent to fix
 * something that was never broken.
 *
 * <p>Reported as
 * <a href="https://github.com/parawanderer/OpenTagViewer/issues/168">#168</a> and reproduced
 * since. The desktop exporter fixed the same bug through the same library in #169; this is the
 * app's half, deliberately reasoned the same way rather than invented differently.
 */
public final class ACodeAppleAlreadyTook {

    /**
     * How long to wait before asking for a new code, first time and second.
     *
     * <p><b>These are a measurement, not round numbers, and they are the part most likely to be
     * quietly lowered.</b> The one observed recovery went: the 503; then a whole manual round -
     * re-typing the Apple ID and password, choosing delivery, waiting for a code, typing it -
     * which was <i>refused at the password step</i>; then another round, which worked. A manual
     * round is the better part of a minute, so roughly a minute after the 503 the account was
     * still being refused, and what eventually worked was about two rounds out.
     *
     * <p>A first wait materially under a minute is known to be too short. {@code
     * ACodeAppleAlreadyTookTest} goes red if either number drops, with the reasoning attached, so
     * that lowering them has to be a decision rather than a tidy-up.
     *
     * <p>The honest caveat, carried over from the exporter's write-up: that middle refusal was on
     * the password call rather than the 2FA call, so it does not strictly prove a new code would
     * have been rejected at that instant. It is the only measurement there is, and it points one
     * way.
     */
    public static final long[] WAITS_MS = {
            TimeUnit.SECONDS.toMillis(60),
            TimeUnit.SECONDS.toMillis(120),
    };

    private ACodeAppleAlreadyTook() {
    }

    /**
     * Whether this failure spent the user's code.
     *
     * <p><b>A wide net, on purpose.</b> Everything it catches happened <i>after</i> the submit
     * returned, so the code is gone in all of them - and the cost of being wrong runs one way.
     * Treating a spent code as a typo sends somebody back to type it again, which cannot work and
     * ends in bad advice about Anisette; treating a typo as a spent code costs a wait and a fresh
     * code, which is inconvenient and correct.
     *
     * <p>Matched on the name because that is what survives the bridge: the failure arrives from
     * Chaquopy as a {@code PyException} whose message carries the Python class. FindMy.py folds
     * every non-OK status into {@code UnhandledProtocolError} with only the number in it, so
     * there is nothing better to match on until the fork grows a transient-failure type - see the
     * handover note, which explains why that was left out of the first fix.
     */
    public static boolean spentIt(final Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            final String message = cause.getMessage();
            if (message != null && message.contains("UnhandledProtocolError")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * @param attempt how many times this has already been waited out, from zero.
     * @return how long to wait before asking Apple for a new code, or -1 when there is no attempt
     *         left and the user should be told plainly that it did not clear.
     */
    public static long waitBefore(final int attempt) {
        return attempt >= 0 && attempt < WAITS_MS.length ? WAITS_MS[attempt] : -1;
    }
}
