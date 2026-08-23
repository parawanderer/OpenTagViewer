package dev.wander.android.opentagviewer.python;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Takes the personal identifiers out of a log before anybody sends it somewhere public.
 *
 * <p><b>The rules live in Python and are shared with the desktop exporter</b> -
 * {@code exporter/redact.py}, whitelisted into the APK. Not ported to Java on purpose: the wizard's
 * Save logs button already runs them, they are patterns that need adding to as new identifiers turn
 * up, and two sets would mean two answers to "is my Apple ID in this file" with only one of them
 * being maintained.
 *
 * <p>Behind an interface for the usual reason - the real one needs a running interpreter, so a
 * screen that called it directly could not be tested without one.
 */
public interface LogRedactor {

    /** A cleaned log, and one line saying what came out of it. */
    @AllArgsConstructor
    @Getter
    class Redacted {
        private final String text;

        /**
         * What was removed, in words - "3 email addresses, 1 serial number".
         *
         * <p>Shown to the person about to send the file. It is the difference between trusting a
         * claim that something was cleaned and being told what was found, and it costs nothing:
         * the redactor counts as it goes.
         */
        private final String summary;
    }

    /**
     * @return the redacted log, or <b>null</b> if it could not be redacted.
     *
     * <p><b>Null means do not send this.</b> The caller's job is to withhold the log, not to fall
     * back to the raw one - this runs at the moment somebody is about to attach a file to a public
     * issue, and the failure mode of guessing wrong is their Apple ID on the internet
     * permanently. Refusing is recoverable; the alternative is not.
     *
     * <p>It can genuinely fail: the error page that offers this exists <i>because</i> something
     * broke, and "Python did not start" is one of the things that might have.
     */
    Redacted redact(String log);
}
