package dev.wander.android.opentagviewer.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The settings screen's "How to set this up" opens this page, and it is the only place the app
 * points to for setting up the googlefind service. The page lives in the wiki, where nothing
 * checks it, so the address is pinned here: a typo or a rename would otherwise surface as a
 * GitHub 404 that only a user sees.
 */
public class GoogleFindHubGuideLinkTest {

    @Test
    public void theGuideLinkPointsAtTheWikiPage() throws IOException {
        final Properties properties = new Properties();
        // Unit tests run from the module directory
        try (InputStream in = new FileInputStream("src/main/assets/app.properties")) {
            properties.load(in);
        }

        final String page = properties.getProperty("googleFindHubWikiPage");

        assertEquals("https://github.com/parawanderer/OpenTagViewer/wiki/%5BGoogle%5D-How-To:-Find-Hub-Trackers", page);
        assertTrue("it should be a page of this project's wiki",
                page.startsWith(properties.getProperty("projectWiki") + "/"));
    }
}
