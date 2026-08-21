package dev.wander.android.opentagviewer.util.parse;

import androidx.annotation.NonNull;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.StringWriter;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Put a new name or emoji into a stored naming record.
 *
 * <p><b>Only ever run after iCloud has accepted the same change.</b> The naming record is what
 * {@link BeaconDataParser} reads a tag's real name and emoji out of, so this is what makes the
 * screen agree with the account instead of covering it with a nickname. A nickname would have
 * been far less code and quietly wrong: it wins at display time forever, so the next time
 * somebody renamed the tag on their iPhone the app would go on showing the old local value and
 * look like it had stopped syncing.
 *
 * <p><b>Edited rather than rebuilt.</b> The record carries a good deal this app never reads -
 * {@code cloudKitMetadata} above all - and a document reconstructed from the handful of fields
 * that are understood would silently drop the rest. Only the one value moves; everything else is
 * the bytes that were there before.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NamingRecordEditor {

    /**
     * A copy of {@code plistXml} with the given values replaced.
     *
     * @param name  the new name, or null to leave it alone.
     * @param emoji the new emoji, or null to leave it alone.
     * @throws BeaconDataParsingException if the record cannot be read or written back.
     */
    @NonNull
    public static String with(@NonNull final String plistXml,
                              final String name,
                              final String emoji) {
        try {
            final Document document = XmlParser.parse(plistXml);

            if (name != null) {
                set(document, "name", name);
            }
            if (emoji != null) {
                set(document, "emoji", emoji);
            }

            return serialise(document);
        } catch (final Exception e) {
            throw new BeaconDataParsingException("Could not write the new name into the record", e);
        }
    }

    /**
     * Set one {@code <key>k</key><string>v</string>} pair, adding it if it is not there.
     *
     * <p><b>Adding it matters.</b> CloudKit holds no emoji for an accessory nobody has ever given
     * one, so the first emoji somebody picks is an insert rather than an edit - and a version of
     * this that only replaced would silently do nothing for exactly that tag.
     */
    private static void set(final Document document, final String key, final String value) {
        final NodeList keys = document.getElementsByTagName("key");

        for (int i = 0; i < keys.getLength(); i++) {
            final Node candidate = keys.item(i);
            if (!key.equals(candidate.getTextContent())) {
                continue;
            }

            final Node existing = nextElement(candidate);
            if (existing != null) {
                existing.setTextContent(value);
                return;
            }
        }

        final Node dictionary = document.getElementsByTagName("dict").item(0);
        if (dictionary == null) {
            throw new BeaconDataParsingException("The record has no dict to add " + key + " to");
        }

        final Element addedKey = document.createElement("key");
        addedKey.setTextContent(key);

        final Element addedValue = document.createElement("string");
        addedValue.setTextContent(value);

        dictionary.appendChild(addedKey);
        dictionary.appendChild(addedValue);
    }

    /** The next element after a node, skipping the whitespace a pretty-printed plist is full of. */
    private static Node nextElement(final Node after) {
        for (Node node = after.getNextSibling(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                return node;
            }
        }
        return null;
    }

    private static String serialise(final Document document) throws Exception {
        final Transformer transformer = TransformerFactory.newInstance().newTransformer();
        // The DOCTYPE the record came with, put back by hand: a Transformer drops it otherwise,
        // and the result would no longer look like the plists everything else here reads.
        transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "-//Apple//DTD PLIST 1.0//EN");
        transformer.setOutputProperty(
                OutputKeys.DOCTYPE_SYSTEM, "http://www.apple.com/DTDs/PropertyList-1.0.dtd");

        final StringWriter written = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(written));

        return written.toString();
    }
}
