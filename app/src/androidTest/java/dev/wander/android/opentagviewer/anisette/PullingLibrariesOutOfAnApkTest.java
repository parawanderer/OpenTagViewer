package dev.wander.android.opentagviewer.anisette;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Reading Apple's libraries out of an APK, without Apple.
 *
 * <p><b>There are two ways this breaks, and only one of them was watched.</b> The weekly
 * {@code check-adi-libraries} workflow looks at Apple's APK from the outside and says whether the
 * symbols we need are still in it. Nothing said whether <i>this</i> code still reads that file
 * correctly - the EOCD search, the central directory parse, the range arithmetic, the inflate.
 * All of it is zip and HTTP logic that can be broken by an edit here, with Apple changing
 * nothing.
 *
 * <p>It was untested because it looked untestable: it downloads from Apple's CDN. It is not -
 * the URL is a parameter, and a zip served off a socket on {@code 127.0.0.1} exercises every
 * line of it. No network, no Apple, about a second.
 *
 * <p><b>What this deliberately does not cover</b> is whether Apple's real APK is still laid out
 * the way the parser expects. Nothing on a build machine can know that, which is exactly why the
 * weekly workflow exists. The two are complements: that one watches the file, this one watches
 * the reader.
 */
@RunWith(AndroidJUnit4.class)
public class PullingLibrariesOutOfAnApkTest {

    private static final String ABI = "arm64-v8a";
    private static final String CORE_ADI = "libCoreADI.so";
    private static final String STORE_SERVICES = "libstoreservicescore.so";

    /** Big enough that "we only fetched a slice" is a measurable claim rather than a rounding. */
    private static final int PADDING_BYTES = 400_000;

    private File destination;
    private FakeApkServer server;

    @Before
    public void makeSomewhereToDownloadTo() throws IOException {
        this.destination = new File(
                getInstrumentation().getTargetContext().getCacheDir(),
                "adi-fetch-test-" + System.nanoTime());
    }

    @After
    public void tidyUp() throws IOException {
        if (this.server != null) {
            this.server.close();
        }
        if (this.destination.isDirectory()) {
            final File[] files = this.destination.listFiles();
            if (files != null) {
                for (final File file : files) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            this.destination.delete();
        }
    }

    /**
     * <b>The libraries come out, byte for byte.</b>
     *
     * <p>The whole point of the class: what lands on disk is what was in the archive. A parser
     * that is off by a few bytes still produces a plausible file, and the failure surfaces later
     * as {@code dlopen} refusing it - a long way from the code that got it wrong.
     */
    @Test
    public void thelibrariesArriveExactlyAsTheyWereInTheArchive() throws IOException {
        final byte[] core = elf("the core adi library");
        final byte[] store = elf("the store services library");

        this.server = FakeApkServer.serving(anApkContaining(core, store));

        AdiLibraryFetcher.fetchInto(
                this.destination, ABI, List.of(CORE_ADI, STORE_SERVICES), this.server.url());

        assertArrayEquals("libCoreADI.so did not survive the trip",
                core, Files.readAllBytes(new File(this.destination, CORE_ADI).toPath()));
        assertArrayEquals("libstoreservicescore.so did not survive the trip",
                store, Files.readAllBytes(new File(this.destination, STORE_SERVICES).toPath()));
    }

    /**
     * <b>And it fetches slices, not the file.</b>
     *
     * <p>This is the design, not an optimisation: the real APK is 142 MB and this runs on
     * somebody's phone, on their data. A change that quietly started reading the whole thing
     * would pass every other test in this class, because the bytes it extracted would be right.
     */
    @Test
    public void itreadsSlicesRatherThanTheWholeArchive() throws IOException {
        final byte[] apk = anApkContaining(elf("core"), elf("store"));
        this.server = FakeApkServer.serving(apk);

        AdiLibraryFetcher.fetchInto(
                this.destination, ABI, List.of(CORE_ADI, STORE_SERVICES), this.server.url());

        assertFalse("it made no range requests at all", this.server.rangesRequested().isEmpty());
        assertTrue("it pulled " + this.server.bytesServed() + " of " + apk.length + " bytes -"
                        + " that is the whole archive, which is the thing this design exists to"
                        + " avoid",
                this.server.bytesServed() < apk.length / 2);
    }

    /**
     * <b>A cached library is not fetched again.</b>
     *
     * <p>Anisette is asked for on more or less every call to Apple. Re-downloading 11 MB each
     * time would be a bug nobody sees on wifi and everybody feels on a train.
     */
    @Test
    public void whatisalreadyOnDiskIsLeftAlone() throws IOException {
        this.server = FakeApkServer.serving(anApkContaining(elf("core"), elf("store")));

        AdiLibraryFetcher.fetchInto(
                this.destination, ABI, List.of(CORE_ADI, STORE_SERVICES), this.server.url());
        final int afterTheFirstRun = this.server.rangesRequested().size();

        final long secondRun = AdiLibraryFetcher.fetchInto(
                this.destination, ABI, List.of(CORE_ADI, STORE_SERVICES), this.server.url());

        assertEquals("a second fetch reported downloading something", 0, secondRun);
        assertEquals("a second fetch went back to the network", afterTheFirstRun,
                this.server.rangesRequested().size());
    }

    /**
     * <b>A CDN that ignores the range is caught, not parsed.</b>
     *
     * <p>The dangerous answer is not an error - it is a {@code 200} carrying the whole file where
     * a slice was asked for, which is what an intercepting proxy does. Read as though it were the
     * slice, the offsets are all wrong and the result is a corrupt library that the app would go
     * on to {@code dlopen}.
     */
    @Test
    public void aserverThatIgnoresRangesIsRefused() throws IOException {
        this.server = FakeApkServer.ignoringRanges(anApkContaining(elf("core"), elf("store")));

        try {
            AdiLibraryFetcher.fetchInto(
                    this.destination, ABI, List.of(CORE_ADI), this.server.url());
            fail("a server that ignored the range was accepted, so whatever it sent was parsed"
                    + " as though it were the slice that was asked for");
        } catch (final IOException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("the error should say the range was ignored, not something further"
                            + " downstream: " + expected.getMessage(),
                    expected.getMessage().contains("range"));
        }
    }

    /**
     * <b>Something that is not an ELF file never reaches disk.</b>
     *
     * <p>The check exists because the failure it prevents is loading it. If Apple ever ship a
     * placeholder, a stub, or an HTML error page under this name, the app must decline rather
     * than hand it to {@code dlopen}.
     */
    @Test
    public void amemberThatIsNotAnElfFileIsRejected() throws IOException {
        final byte[] notALibrary = "<!doctype html><title>404</title>"
                .getBytes(StandardCharsets.UTF_8);

        this.server = FakeApkServer.serving(anApkContaining(notALibrary, elf("store")));

        try {
            AdiLibraryFetcher.fetchInto(
                    this.destination, ABI, List.of(CORE_ADI), this.server.url());
            fail("a non-ELF member was accepted");
        } catch (final IOException expected) {
            assertTrue("the error should name ELF: " + expected.getMessage(),
                    expected.getMessage().contains("ELF"));
        }

        assertFalse("it was rejected but written anyway, so the next run would find it cached"
                        + " and skip the download that would have replaced it",
                new File(this.destination, CORE_ADI).exists());
    }

    /**
     * <b>A library that is not in the archive says so, naming the ABI.</b>
     *
     * <p>The two real causes are opposite - Apple changed the layout, or this build does not ship
     * that ABI - and the message has to leave both open, because the person reading it is
     * debugging on a device whose ABI they may not have thought about.
     */
    @Test
    public void alibraryTheArchiveDoesNotHaveIsReportedClearly() throws IOException {
        this.server = FakeApkServer.serving(anApkContaining(elf("core"), elf("store")));

        try {
            AdiLibraryFetcher.fetchInto(
                    this.destination, "x86_64", List.of(CORE_ADI), this.server.url());
            fail("a missing library was not reported");
        } catch (final IOException expected) {
            assertTrue("the error should name the ABI that was looked for: "
                    + expected.getMessage(), expected.getMessage().contains("x86_64"));
        }
    }

    // --- the fixture ----------------------------------------------------------------------------

    /**
     * An APK-shaped zip: the two libraries under {@code lib/<abi>/}, plus bulk around them.
     *
     * <p>The padding is not decoration. It makes the archive large enough for "only a slice was
     * fetched" to mean something, and - more importantly - it puts the members somewhere other
     * than the start, so an offset the parser gets wrong produces wrong bytes rather than
     * accidentally-right ones.
     *
     * <p>The libraries are <b>stored</b> and the padding is <b>deflated</b>, matching a real APK,
     * where native libraries are left uncompressed so they can be mapped in place. Both paths
     * through {@code extract} are therefore live: get one wrong and the padding still decompresses
     * while the libraries come out as noise.
     */
    private static byte[] anApkContaining(final byte[] core, final byte[] store)
            throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(padding(2_048));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/bulk.bin"));
            zip.write(padding(PADDING_BYTES));
            zip.closeEntry();

            stored(zip, "lib/" + ABI + "/" + CORE_ADI, core);
            stored(zip, "lib/" + ABI + "/" + STORE_SERVICES, store);

            zip.putNextEntry(new ZipEntry("resources.arsc"));
            zip.write(padding(4_096));
            zip.closeEntry();
        }

        return bytes.toByteArray();
    }

    /** Written with no compression, which is how a real APK stores its native libraries. */
    private static void stored(final ZipOutputStream zip, final String name, final byte[] contents)
            throws IOException {
        final ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(contents.length);
        entry.setCompressedSize(contents.length);

        final CRC32 crc = new CRC32();
        crc.update(contents);
        entry.setCrc(crc.getValue());

        zip.putNextEntry(entry);
        zip.write(contents);
        zip.closeEntry();
    }

    /** Something with an ELF header on it, and a recognisable body. */
    private static byte[] elf(final String label) {
        final byte[] body = label.getBytes(StandardCharsets.UTF_8);
        final byte[] out = new byte[4 + body.length + 1_024];

        out[0] = 0x7f;
        out[1] = 'E';
        out[2] = 'L';
        out[3] = 'F';
        System.arraycopy(body, 0, out, 4, body.length);

        for (int i = 4 + body.length; i < out.length; i++) {
            out[i] = (byte) (i % 251);
        }
        return out;
    }

    /**
     * Bulk that does not compress away, from a fixed seed.
     *
     * <p><b>It was {@code i % 64} first, and that made the test lie.</b> Four hundred thousand
     * bytes of a repeating pattern deflate to about nothing, so the "large" archive came out at
     * 4 KB - smaller than the 64 KB window the fetcher reads to find the end-of-directory record.
     * Every request therefore covered the whole file, and a test whose entire subject is "it does
     * not fetch the whole file" had built an archive where fetching it whole was unavoidable.
     *
     * <p>A linear congruential generator rather than {@code Random}: same bytes on every run, so
     * a failure is reproducible rather than occasionally interesting.
     */
    private static byte[] padding(final int size) {
        final byte[] out = new byte[size];
        long state = 0x5DEECE66DL;

        for (int i = 0; i < size; i++) {
            state = (state * 6364136223846793005L) + 1442695040888963407L;
            out[i] = (byte) (state >>> 33);
        }
        return out;
    }
}
