package dev.wander.android.opentagviewer.anisette;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough XML property list to talk to Apple's provisioning endpoints.
 *
 * <p>The app already parses plists through Python, but that path exists for reading Apple's
 * exported keychain data and drags the Python interpreter into the call. These responses are
 * three levels of dictionary holding strings, so a plain XmlPullParser is a better fit than
 * starting an interpreter for it.
 *
 * <p>Only the types Apple actually sends here are handled. Anything else raises rather than
 * being quietly dropped, because a silently missing value in an authentication flow is worse
 * than a failed parse.
 */
public final class SimplePlist {

    private SimplePlist() {
    }

    /** Parse a plist document. The root is normally a {@code Map<String, Object>}. */
    public static Object parse(String xml) throws XmlPullParserException, IOException {
        final XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new StringReader(xml));

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.START_TAG
                    && !"plist".equals(parser.getName())) {
                return readValue(parser);
            }
        }
        throw new XmlPullParserException("no value in the plist");
    }

    /**
     * Look up a nested string, e.g. {@code string(plist, "Response", "spim")}.
     *
     * @return null if any step is missing or is not a string
     */
    @SuppressWarnings("unchecked")
    public static String string(Object plist, String... path) {
        Object current = plist;
        for (final String key : path) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(key);
        }
        return current instanceof String ? (String) current : null;
    }

    private static Object readValue(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        final String tag = parser.getName();

        switch (tag) {
            case "dict":
                return readDict(parser);
            case "array":
                return readArray(parser);
            case "string":
            case "integer":
            case "real":
            case "date":
            case "data":
                // Everything Apple sends here is consumed as text - base64 payloads are
                // decoded by the caller, which knows which ones they are.
                return parser.nextText();
            case "true":
            case "false":
                parser.nextTag();
                return "true".equals(tag);
            default:
                throw new XmlPullParserException("unsupported plist type <" + tag + ">");
        }
    }

    private static Map<String, Object> readDict(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        final Map<String, Object> out = new HashMap<>();

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }
            if (!"key".equals(parser.getName())) {
                throw new XmlPullParserException(
                        "expected <key> in <dict>, found <" + parser.getName() + ">");
            }

            final String key = parser.nextText();
            parser.nextTag();
            out.put(key, readValue(parser));
        }
        return out;
    }

    private static List<Object> readArray(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        final List<Object> out = new ArrayList<>();

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() == XmlPullParser.START_TAG) {
                out.add(readValue(parser));
            }
        }
        return out;
    }
}
