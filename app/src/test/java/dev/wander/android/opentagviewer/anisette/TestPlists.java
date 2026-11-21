package dev.wander.android.opentagviewer.anisette;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.Base64;

/**
 * {@link SimplePlist#parse}'s shapes, from the JDK's DOM parser.
 *
 * <p>{@code SimplePlist} uses {@code android.util.Xml}, which is a stub on the JVM. This reads the
 * same documents into the same maps and strings, so {@link SimplePlist#string} works on the result
 * unchanged and what is under test is {@link MacProvisioning}, not a parser.
 */
final class TestPlists {

    private TestPlists() {
    }

    static Object parse(final String xml) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Apple's DOCTYPE points at a URL; nothing here should fetch it.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        final Element plist = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
        for (final Element child : children(plist)) {
            return value(child);
        }
        throw new IllegalArgumentException("no value in the plist");
    }

    private static Object value(final Element element) {
        switch (element.getTagName()) {
            case "dict": {
                final Map<String, Object> out = new HashMap<>();
                final List<Element> items = children(element);
                for (int i = 0; i + 1 < items.size(); i += 2) {
                    out.put(items.get(i).getTextContent(), value(items.get(i + 1)));
                }
                return out;
            }
            case "array": {
                final List<Object> out = new ArrayList<>();
                for (final Element child : children(element)) {
                    out.add(value(child));
                }
                return out;
            }
            case "true":
            case "false":
                return "true".equals(element.getTagName());
            default:
                return element.getTextContent();
        }
    }

    private static List<Element> children(final Element parent) {
        final List<Element> out = new ArrayList<>();
        final NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) nodes.item(i));
            }
        }
        return out;
    }

    /** A plist dictionary of strings, with one nested level, as Apple's answers are written. */
    static String dict(final Map<String, ?> entries) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">" + body(entries) + "</plist>";
    }

    private static String body(final Map<String, ?> entries) {
        final StringBuilder out = new StringBuilder("<dict>");
        for (final Map.Entry<String, ?> entry : entries.entrySet()) {
            out.append("<key>").append(entry.getKey()).append("</key>");
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                final Map<String, ?> nested = (Map<String, ?>) entry.getValue();
                out.append(body(nested));
            } else {
                out.append("<string>").append(entry.getValue()).append("</string>");
            }
        }
        return out.append("</dict>").toString();
    }

    /** {@code java.util.Base64}, for the tests; the app's is {@code android.util.Base64}. */
    static final Base64Codec BASE64 = new Base64Codec() {
        @Override
        public String encode(final byte[] data) {
            return Base64.getEncoder().encodeToString(data);
        }

        @Override
        public byte[] decode(final String text) {
            return Base64.getMimeDecoder().decode(text);
        }
    };
}
