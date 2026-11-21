package dev.wander.android.opentagviewer.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Saving over a file that is already there, which is every save after the first.
 *
 * <p>The second case is the one that failed on Windows, where {@code renameTo} will not replace.
 * On Linux it passes either way, so it is CI's check that the fallback does not break anything
 * and this laptop's check that the fallback exists.
 */
public class FileReplaceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File written(final String name, final String text) throws IOException {
        final File file = new File(this.folder.getRoot(), name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void aFirstSaveLandsAtTheTarget() throws IOException {
        final File partial = this.written("identity.json.partial", "first");
        final File target = new File(this.folder.getRoot(), "identity.json");

        assertTrue(FileReplace.moveOver(partial, target));

        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(target.toPath()));
        assertFalse(partial.exists());
    }

    @Test
    public void aSecondSaveReplacesTheFirst() throws IOException {
        final File target = this.written("identity.json", "first");
        final File partial = this.written("identity.json.partial", "second");

        assertTrue(FileReplace.moveOver(partial, target));

        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(target.toPath()));
        assertFalse(partial.exists());
    }

    @Test
    public void aMissingSourceIsAFailureAndLeavesTheTargetAlone() throws IOException {
        final File target = this.written("identity.json", "first");
        final File partial = new File(this.folder.getRoot(), "identity.json.partial");

        assertFalse(FileReplace.moveOver(partial, target));
        assertTrue("the old file was deleted with nothing to replace it", target.exists());
    }
}
