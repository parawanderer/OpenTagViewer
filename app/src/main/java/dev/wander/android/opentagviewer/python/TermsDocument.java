package dev.wander.android.opentagviewer.python;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One terms-of-service document Apple is waiting on, ready to put on a screen.
 *
 * <p>The text arrives already rendered from HTML by the same renderer the desktop exporter uses.
 * <b>Nothing here shortens, reorders or summarises it</b>, because what is displayed is what gets
 * agreed to - so there is no "summary" field and there should not be one.
 */
@Getter
@AllArgsConstructor
public class TermsDocument {
    /**
     * Which document this is, and the id it is accepted by.
     *
     * <p>Apple's own, e.g. {@code iCloud}. Passed back to accept this exact document - the
     * document itself never crosses into Java, because FindMy.py refuses agreement to a
     * {@code Terms} that was rebuilt rather than fetched.
     */
    private final String pageId;

    /** The document, as plain text with its structure intact. Long - tens of kilobytes. */
    private final String text;

    /**
     * Whether Apple will record agreement to this one.
     *
     * <p>False when the document arrived with no URL to agree at, which FindMy.py refuses to
     * send. Better a screen that says so than a button that fails.
     */
    private final boolean canAccept;
}
