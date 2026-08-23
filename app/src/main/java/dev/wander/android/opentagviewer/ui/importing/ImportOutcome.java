package dev.wander.android.opentagviewer.ui.importing;

import dev.wander.android.opentagviewer.util.parse.ZipImporterException;

/**
 * What was wrong with an import that did not complete, and so what to say about it.
 *
 * <p><b>Only reachable now for failures that really are the import's.</b> Reading the zip and
 * committing the tags is one chain; going to Apple for their locations is another, started only
 * once the first has succeeded. They used to be joined, sharing an error handler, so a network
 * timeout or a bad Anisette server - neither consulted while reading a zip - reported "Error
 * occurred while importing new devices" for an import that had already succeeded and could be
 * seen in the device list. Issues
 * <a href="https://github.com/parawanderer/OpenTagViewer/issues/19">#19</a> and
 * <a href="https://github.com/parawanderer/OpenTagViewer/issues/26">#26</a> are 34 comments of
 * people working that out for themselves.
 *
 * <p>So the trap this replaces is {@code reasonOf} returning {@code UNKNOWN}. It is a reasonable
 * answer from a function about zip problems - "not one of mine" - and the caller read it as "an
 * unknown import problem", which is a different claim entirely.
 */
public enum ImportOutcome {

    /** Locked, or the code was wrong. A question rather than a failure - ask again. */
    ASK_FOR_THE_PASSCODE,

    /** A named problem with the file, which has advice worth giving. */
    EXPLAIN_THE_FILE,

    /**
     * Nothing was stored and nothing here can name why.
     *
     * <p>The only case where "the app could not read that file" is true, and now the only case
     * that says it. Rare, by construction: the file has already been through the importer's own
     * checks, so what is left is something below it going wrong.
     */
    REPORT_THE_IMPORT;

    public static ImportOutcome of(final Throwable error) {
        switch (ZipImporterException.reasonOf(error)) {
            case LOCKED:
            case WRONG_PASSCODE:
                return ASK_FOR_THE_PASSCODE;
            case UNKNOWN:
                return REPORT_THE_IMPORT;
            default:
                return EXPLAIN_THE_FILE;
        }
    }
}
