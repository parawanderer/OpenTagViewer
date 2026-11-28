package dev.wander.android.opentagviewer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Nothing the app stores is backed up, or copied to a new phone by device-to-device transfer.
 *
 * <p>{@code allowBackup="false"} does not cover device-to-device transfer on Android 12 and
 * newer; only a {@code <device-transfer>} section in the data extraction rules does. That file
 * was the Android Studio template, so the database with every tag's private keys and history was
 * eligible. Nothing fails when a section or a domain goes missing from these files, so this pins
 * them. {@code data_extraction_rules.xml} says why everything is excluded, not just the database.
 */
public class KeepingAppDataOnThisPhoneTest {

    // Unit tests run from the module directory
    private static final String MAIN = "src/main/";

    /** Every domain the backup rules accept. The cache directories are never copied anyway. */
    private static final Set<String> EVERY_DOMAIN = new TreeSet<>(List.of(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref"));

    @Test
    public void theManifestTurnsBackupOffAndPointsAtBothRuleFiles() throws Exception {
        final Element application = (Element) parse(MAIN + "AndroidManifest.xml")
                .getElementsByTagName("application").item(0);

        assertEquals("false", application.getAttribute("android:allowBackup"));
        assertEquals("@xml/data_extraction_rules",
                application.getAttribute("android:dataExtractionRules"));
        assertEquals("@xml/backup_rules", application.getAttribute("android:fullBackupContent"));
    }

    @Test
    public void deviceToDeviceTransferMovesNothing() throws Exception {
        assertExcludesEverything("device-transfer", sectionOf("device-transfer"));
    }

    @Test
    public void cloudBackupWouldMoveNothingEvenIfAllowBackupWereTurnedOn() throws Exception {
        assertExcludesEverything("cloud-backup", sectionOf("cloud-backup"));
    }

    @Test
    public void olderAndroidVersionsWouldBackUpNothingEither() throws Exception {
        assertExcludesEverything("full-backup-content",
                parse(MAIN + "res/xml/backup_rules.xml").getDocumentElement());
    }

    private static Element sectionOf(final String name) throws Exception {
        final NodeList sections = parse(MAIN + "res/xml/data_extraction_rules.xml")
                .getElementsByTagName(name);
        assertEquals("expected exactly one <" + name + ">", 1, sections.getLength());
        return (Element) sections.item(0);
    }

    /**
     * An {@code <include>} anywhere would switch the section to "only what is included", so
     * there must be none, and every domain must be excluded as a whole.
     */
    private static void assertExcludesEverything(final String what, final Element section) {
        final Set<String> excluded = new TreeSet<>();
        final NodeList children = section.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            final Element rule = (Element) child;
            assertEquals(what + " has a rule that is not an <exclude>: <" + rule.getTagName() + ">",
                    "exclude", rule.getTagName());
            assertEquals(what + " excludes only part of " + rule.getAttribute("domain"),
                    ".", rule.getAttribute("path"));
            excluded.add(rule.getAttribute("domain"));
        }
        assertEquals(what + " does not exclude every domain", EVERY_DOMAIN, excluded);
    }

    private static Document parse(final String path) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path);
    }
}
