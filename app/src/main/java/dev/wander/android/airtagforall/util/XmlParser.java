package dev.wander.android.airtagforall.util;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public final class XmlParser {
    private static final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    public static DocumentBuilder newBuilder() throws ParserConfigurationException {
        return factory.newDocumentBuilder();
    }

    public static Document parse(final String xmlInputUtf8) throws ParserConfigurationException, IOException, SAXException {
        return newBuilder().parse(
                new ByteArrayInputStream(xmlInputUtf8.getBytes(StandardCharsets.UTF_8))
        );
    }
}
