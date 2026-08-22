package dev.wander.android.opentagviewer.util.parse;

import android.util.Log;

import androidx.annotation.Nullable;

import org.w3c.dom.Document;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

/**
 * When macOS last saw a tag, read out of its key alignment record.
 *
 * <p><b>The one thing known about a tag before anybody has ever scanned for it.</b> A
 * {@code KeyAlignmentRecord} carries {@code lastIndexObserved} and
 * {@code lastIndexObservationDate}, and the date is what says how wide the next key search will
 * be: an accessory steps its rolling key every fifteen minutes, so a record from yesterday
 * leaves a window of about a hundred keys and one from eighteen months ago leaves tens of
 * thousands. That is the difference between a fetch of a second or two and one of minutes.
 *
 * <p><b>Read here rather than stored.</b> The obvious alternative is a column on
 * {@code OwnedBeacons}, and it would cost a Room migration - see rule 1 - for a value that is
 * wanted a handful of times per fetch and takes microseconds to parse out of a plist that is
 * already in memory. Not worth a schema version.
 *
 * <p><b>Absence is normal and is not an error.</b> Exports before format {@code 0.0.2} carry no
 * alignment records at all, and macOS has none for an accessory it never observed. Every failure
 * here answers {@code null}, which callers read as "nothing is known about this one" - the same
 * answer as a record that will not parse, because there is nothing useful to distinguish them.
 */
public final class KeyAlignmentPlist {

    private static final String TAG = KeyAlignmentPlist.class.getSimpleName();

    /** The same shape {@code BeaconDataParser} uses: a key, then its typed sibling. */
    private static final String XPATH_OBSERVED_AT =
            "/plist/dict/key[.='lastIndexObservationDate']/following-sibling::date[1]";

    private KeyAlignmentPlist() {
    }

    /**
     * When the record says the accessory was last observed.
     *
     * @param alignmentPlistXml the record as exported, or null if the tag has none.
     * @return milliseconds since the epoch, or null if there is no usable date in it.
     */
    @Nullable
    public static Long observedAtMillis(@Nullable final String alignmentPlistXml) {
        if (alignmentPlistXml == null || alignmentPlistXml.isBlank()) {
            return null;
        }

        try {
            final Document document = XmlParser.parse(alignmentPlistXml);
            final XPath xPath = XPathFactory.newInstance().newXPath();
            final String value = xPath.evaluate(XPATH_OBSERVED_AT, document);

            if (value == null || value.isBlank()) {
                return null;
            }

            // Apple writes plist dates as ISO-8601 in UTC, which is what Instant reads.
            return Instant.parse(value.trim()).toEpochMilli();

        } catch (final DateTimeParseException badDate) {
            Log.w(TAG, "A key alignment record carried a date this cannot read: " + badDate);
            return null;
        } catch (final Exception unreadable) {
            // Deliberately broad. This runs on data somebody else's machine wrote, on a path
            // whose whole purpose is to make the first fetch feel quicker - so nothing here is
            // worth failing a fetch over. Unknown is a perfectly good answer.
            Log.w(TAG, "Could not read a key alignment record", unreadable);
            return null;
        }
    }
}
