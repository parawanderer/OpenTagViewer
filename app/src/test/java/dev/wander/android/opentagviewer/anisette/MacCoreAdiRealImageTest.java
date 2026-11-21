package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Apple's real iTunes 12.8.3 disk image through {@link MacCoreAdiFetcher#importFrom}, against the
 * real pins. Everything else here uses miniature fakes, because Apple's bytes cannot be checked
 * in; this is the one place the constants meet the actual file.
 *
 * <p><b>Opt-in.</b> Skipped unless {@code COREADI_DMG} names the image (291 MB, from
 * {@link MacCoreAdiFetcher#DMG_URL}):
 *
 * <pre>
 * COREADI_DMG=/path/to/iTunes12.8.3.dmg ./gradlew :app:testDebugUnitTest --tests '*MacCoreAdiRealImageTest'
 * </pre>
 */
public class MacCoreAdiRealImageTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theRealDiskImageGivesThePinnedLibrary() throws Exception {
        final String path = System.getenv("COREADI_DMG");
        assumeTrue("set COREADI_DMG to the iTunes 12.8.3 disk image to run this",
                path != null && new File(path).isFile());

        final File target = new File(this.folder.getRoot(), "CoreADI-4.19-x86_64");
        final byte[] library;
        try (InputStream in = new FileInputStream(path)) {
            library = MacCoreAdiFetcher.importFrom(in, target);
        }

        assertEquals(MacCoreAdi.BUILD_SHA256, MacCoreAdiFetcher.sha256(library));
        assertEquals(MacCoreAdi.BUILD_SHA256,
                MacCoreAdiFetcher.sha256(MacCoreAdiFetcher.readFile(target)));
    }
}
