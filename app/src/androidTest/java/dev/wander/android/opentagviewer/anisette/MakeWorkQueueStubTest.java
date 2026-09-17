package dev.wander.android.opentagviewer.anisette;

import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Our stand-in for {@code mediaplatform::WorkQueue::makeWorkQueue} returns what its caller reads.
 *
 * <p><b>Issue #232.</b> The real function returns a {@code std::shared_ptr} by value, which comes
 * back through a buffer the caller provides. The generated stub returned 0 in a register and never
 * wrote that buffer, and a static constructor in Apple's {@code libstoreservicescore.so} read the
 * leftover stack there as a control block and incremented through it. On a Pixel 5 and a Redmi Note
 * 11 Pro+ 5G the leftover was a live pointer, and {@code dlopen} died with {@code SIGBUS}; the
 * emulator happened to leave zeros, so nothing here ever saw it.
 *
 * <p><b>This needs no Apple library and no luck.</b> The native side fills the buffer with garbage
 * before calling the stub with the real {@code shared_ptr} return type, so a stub that does not
 * write it fails on every ABI and every device, including CI's x86_64 emulator. Checked against
 * the old generated stub on arm64 and x86_64: it fails there, and passes with the fix.
 */
@RunWith(AndroidJUnit4.class)
public class MakeWorkQueueStubTest {

    @Test
    public void theStandInForMakeWorkQueueReturnsAnEmptySharedPtr() {
        System.loadLibrary("mediaplatform");

        assertNull("Apple's library would read this as a control block and write through it",
                NativeAdi.checkMakeWorkQueueStub());
    }
}
