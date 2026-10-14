package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The two Apple roots that local provisioning needs, and the hosts they are trusted for.
 *
 * <p>Android trusts neither root, so without these anchors the handshake fails, provisioning
 * falls back to a remote server, and the user sees whatever that server says rather than a
 * certificate error. {@code gsd.apple.com} began chaining to Apple Root CA - G3 in October 2026,
 * which is how this was found (issue #248). Nothing else fails when an anchor or a host goes
 * missing from the XML, so this pins both, and pins each PEM to Apple's published SHA-256 so a
 * swapped certificate cannot pass as the real one.
 */
public class TrustingApplesProvisioningHostsTest {

    // Unit tests run from the module directory
    private static final String RES = "src/main/res/";

    private static final String APPLE_ROOT_CA =
            "b0b1730ecbc7ff4505142c49f1295e6eda6bcaed7e2c68c5be91b5a11001f024";
    private static final String APPLE_ROOT_CA_G3 =
            "63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179";

    @Test
    public void bothRootsAreApplesOwn() throws Exception {
        assertEquals(APPLE_ROOT_CA, sha256Of("raw/apple_root_ca.pem"));
        assertEquals(APPLE_ROOT_CA_G3, sha256Of("raw/apple_root_ca_g3.pem"));
    }

    @Test
    public void signInAndProvisioningHostsTrustBothRootsAndTheSystemOnes() throws Exception {
        for (final String host : List.of("gsa.apple.com", "gsd.apple.com")) {
            final List<String> anchors = anchorsFor(host);
            assertTrue(host + " lost the system roots: " + anchors, anchors.contains("system"));
            assertTrue(host + " does not trust Apple Root CA: " + anchors,
                    anchors.contains("@raw/apple_root_ca"));
            assertTrue(host + " does not trust Apple Root CA - G3: " + anchors,
                    anchors.contains("@raw/apple_root_ca_g3"));
        }
    }

    private static String sha256Of(final String resource) throws Exception {
        try (InputStream in = new FileInputStream(RES + resource)) {
            final X509Certificate certificate = (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(in);
            final StringBuilder hex = new StringBuilder();
            for (final byte b : MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
    }

    private static List<String> anchorsFor(final String host) throws Exception {
        final Document config = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(RES + "xml/network_security_config.xml");
        final NodeList domainConfigs = config.getElementsByTagName("domain-config");
        for (int i = 0; i < domainConfigs.getLength(); i++) {
            final Element domainConfig = (Element) domainConfigs.item(i);
            final NodeList domains = domainConfig.getElementsByTagName("domain");
            for (int j = 0; j < domains.getLength(); j++) {
                if (host.equals(domains.item(j).getTextContent().trim())) {
                    final List<String> anchors = new ArrayList<>();
                    final NodeList certificates = domainConfig.getElementsByTagName("certificates");
                    for (int k = 0; k < certificates.getLength(); k++) {
                        anchors.add(((Element) certificates.item(k)).getAttribute("src"));
                    }
                    return anchors;
                }
            }
        }
        throw new AssertionError(host + " has no domain-config of its own");
    }
}
