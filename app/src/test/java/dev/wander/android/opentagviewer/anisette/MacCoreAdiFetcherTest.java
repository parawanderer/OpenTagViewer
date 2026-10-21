package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Getting Apple's macOS CoreADI out of the iTunes installer, without Apple's bytes.
 *
 * <p>The real payload is Apple's and is not in the repository, so the pinned hashes cannot be
 * satisfied here; {@code scripts/locate_mac_coreadi.py} checks those against the real image. What
 * this covers is everything between the hashes - the cpio walk, the universal-binary slice - and
 * that a server which ignores the range is refused before the 291 MB arrive.
 */
public class MacCoreAdiFetcherTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** One "odc" cpio entry, as Apple's package payloads write them. */
    private static void entry(ByteArrayOutputStream out, String name, byte[] data) {
        final byte[] nameBytes = (name + "\0").getBytes(StandardCharsets.UTF_8);
        final String header = "070707"
                + "000001" + "000002" + "100644" + "000000" + "000000" + "000001" + "000000"
                + String.format("%011o", 0)
                + String.format("%06o", nameBytes.length)
                + String.format("%011o", data.length);
        out.write(header.getBytes(StandardCharsets.US_ASCII), 0, header.length());
        out.write(nameBytes, 0, nameBytes.length);
        out.write(data, 0, data.length);
    }

    private static byte[] cpio(Object... namesAndData) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < namesAndData.length; i += 2) {
            entry(out, (String) namesAndData[i], (byte[]) namesAndData[i + 1]);
        }
        entry(out, "TRAILER!!!", new byte[0]);
        return out.toByteArray();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void theLibraryIsFoundUnderItsDotSlashName() throws Exception {
        final byte[] archive = cpio(
                "./System/Library/PrivateFrameworks/CoreADI.framework/Versions/A/adid", bytes("adid"),
                "./" + MacCoreAdiFetcher.FRAMEWORK_PATH, bytes("the library"));

        assertArrayEquals(bytes("the library"),
                MacCoreAdiFetcher.fileFromCpio(archive, MacCoreAdiFetcher.FRAMEWORK_PATH));
    }

    @Test
    public void aHardLinkTwinWithNoDataIsPassedOver() throws Exception {
        final byte[] archive = cpio(
                "./" + MacCoreAdiFetcher.FRAMEWORK_PATH, new byte[0],
                "./" + MacCoreAdiFetcher.FRAMEWORK_PATH, bytes("the data-bearing one"));

        assertArrayEquals(bytes("the data-bearing one"),
                MacCoreAdiFetcher.fileFromCpio(archive, MacCoreAdiFetcher.FRAMEWORK_PATH));
    }

    @Test
    public void aPayloadWithoutTheLibrarySaysSo() {
        final byte[] archive = cpio("./something/else", bytes("x"));

        final MacCoreAdiFetcher.FetchException thrown = assertThrows(
                MacCoreAdiFetcher.FetchException.class,
                () -> MacCoreAdiFetcher.fileFromCpio(archive, MacCoreAdiFetcher.FRAMEWORK_PATH));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("is not in the installer"));
    }

    @Test
    public void anArchiveThatIsNotOdcIsRefused() {
        final byte[] newc = new byte[206];
        Arrays.fill(newc, (byte) '0');
        System.arraycopy(bytes("070701"), 0, newc, 0, 6);

        assertThrows(MacCoreAdiFetcher.FetchException.class,
                () -> MacCoreAdiFetcher.fileFromCpio(newc, MacCoreAdiFetcher.FRAMEWORK_PATH));
    }

    /** A universal binary with an i386 slice and an x86_64 one, in that order, as Apple's is. */
    private static byte[] fat(byte[] i386, byte[] x8664) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int first = 8 + 2 * 20;
        writeBe32(out, 0xCAFEBABE);
        writeBe32(out, 2);
        for (final int[] arch : new int[][]{
                {0x00000007, first, i386.length},
                {0x01000007, first + i386.length, x8664.length}}) {
            writeBe32(out, arch[0]);
            writeBe32(out, 3);
            writeBe32(out, arch[1]);
            writeBe32(out, arch[2]);
            writeBe32(out, 0);
        }
        out.write(i386, 0, i386.length);
        out.write(x8664, 0, x8664.length);
        return out.toByteArray();
    }

    private static void writeBe32(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    @Test
    public void theX8664SliceIsTheOneTaken() throws Exception {
        final byte[] universal = fat(bytes("thirty-two bit"), bytes("sixty-four bit"));

        assertArrayEquals(bytes("sixty-four bit"), MacCoreAdiFetcher.slice(universal, 0x01000007));
    }

    @Test
    public void aThinBinaryIsNotMistakenForAUniversalOne() {
        assertThrows(MacCoreAdiFetcher.FetchException.class,
                () -> MacCoreAdiFetcher.slice(bytes("not a fat header"), 0x01000007));
    }

    @Test
    public void bytesThatAreNotThePinnedPayloadAreNeverUnpacked() {
        final MacCoreAdiFetcher.FetchException thrown = assertThrows(
                MacCoreAdiFetcher.FetchException.class,
                () -> MacCoreAdiFetcher.extract(bytes("anything at all")));
        assertTrue(thrown.getMessage(), thrown.getMessage().contains("not the expected ones"));
    }

    @Test
    public void aServerThatIgnoresTheRangeIsRefusedRatherThanRead() throws Exception {
        try (FakeApkServer server = FakeApkServer.ignoringRanges(new byte[64 * 1024])) {
            final File target = new File(this.folder.getRoot(), "CoreADI");

            final MacCoreAdiFetcher.FetchException thrown = assertThrows(
                    MacCoreAdiFetcher.FetchException.class,
                    () -> MacCoreAdiFetcher.ensure(target, server.url()));

            assertTrue(thrown.getMessage(), thrown.getMessage().contains("expected 206"));
            assertTrue("nothing should have been written", !target.exists());
        }
    }

    @Test
    public void aStoredFileOfTheWrongBuildIsFetchedAgainRatherThanUsed() throws Exception {
        final File target = new File(this.folder.getRoot(), "CoreADI");
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes("some other build"));
        }
        try (FakeApkServer server = FakeApkServer.ignoringRanges(new byte[16])) {
            final MacCoreAdiFetcher.FetchException thrown = assertThrows(
                    MacCoreAdiFetcher.FetchException.class,
                    () -> MacCoreAdiFetcher.ensure(target, server.url()));
            // Refused by the server's answer, which means it went to the server: the stored
            // file of the wrong build was not handed out.
            assertTrue(thrown.getMessage(), thrown.getMessage().contains("expected 206"));
            assertArrayEquals(bytes("some other build"), MacCoreAdiFetcher.readFile(target));
        }
    }
}
