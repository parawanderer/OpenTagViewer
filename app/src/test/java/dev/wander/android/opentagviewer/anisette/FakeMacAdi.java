package dev.wander.android.opentagviewer.anisette;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * The emulated Mac's CoreADI with no emulator: a store that behaves the way the library's does.
 *
 * <p>It is "provisioned" exactly when its store holds {@link #PROVISIONED_FILE}, which is what the
 * real one comes down to - provisioning writes per-user state, and a store that lost it is a
 * machine that has to be registered again. It also writes {@code adi.pb} whenever it is asked
 * for anything, as the real library does, so a test can check that file never reaches disk.
 */
final class FakeMacAdi implements MacAdi {

    static final String PROVISIONED_FILE = "2a4d5f.pb";

    final Map<String, byte[]> store = new LinkedHashMap<>();
    final List<String> calls = new ArrayList<>();
    byte[] hostUuid;
    boolean closed;
    boolean gone;
    long routingAssigned;

    /** When set, the next call to this method throws it. */
    RuntimeException failHeadersWith;

    @Override
    public void setHostUuid(final byte[] uuid) {
        this.calls.add("setHostUuid");
        this.hostUuid = uuid.clone();
    }

    @Override
    public void storePut(final String name, final byte[] contents) {
        this.calls.add("storePut " + name);
        this.store.put(name, contents.clone());
    }

    @Override
    public Map<String, byte[]> storeFiles() {
        return new LinkedHashMap<>(this.store);
    }

    @Override
    public Headers headers(final long dsId) {
        this.calls.add("headers");
        if (this.failHeadersWith != null) {
            final RuntimeException e = this.failHeadersWith;
            this.failHeadersWith = null;
            throw e;
        }
        this.store.put("adi.pb", bytes("legacy whole-machine blob"));
        if (!this.store.containsKey(PROVISIONED_FILE)) {
            throw new NotProvisionedException("ADIOTPRequest returned -45061");
        }
        // RequestOTP advances the store, which is why the caller saves it after every call.
        final byte[] state = this.store.get(PROVISIONED_FILE);
        this.store.put(PROVISIONED_FILE, (new String(state, StandardCharsets.UTF_8) + "+")
                .getBytes(StandardCharsets.UTF_8));
        return new Headers(bytes("mid"), bytes("otp"), this.routingAssigned);
    }

    @Override
    public byte[] provisioningBegin(final long dsId, final byte[] spim, final int[] sessionOut) {
        this.calls.add("provisioningBegin " + new String(spim, StandardCharsets.UTF_8));
        sessionOut[0] = 42;
        return bytes("cpim for " + new String(spim, StandardCharsets.UTF_8));
    }

    @Override
    public void provisioningFinish(final long dsId, final int session, final byte[] ptm,
                                   final byte[] tk, final long routing) {
        this.calls.add("provisioningFinish " + session + " " + new String(ptm, StandardCharsets.UTF_8)
                + " " + new String(tk, StandardCharsets.UTF_8) + " " + routing);
        this.routingAssigned = routing;
        this.store.put(PROVISIONED_FILE, bytes("provisioned"));
        this.store.put("adi.pb", bytes("legacy whole-machine blob"));
    }

    @Override
    public boolean isGone() {
        return this.gone;
    }

    @Override
    public void close() {
        this.closed = true;
    }

    static byte[] bytes(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Opens a new {@link FakeMacAdi} each time, and keeps them all. */
    static final class Opener implements MacAdi.Opener {
        final List<FakeMacAdi> opened = new ArrayList<>();
        final List<File> libraries = new ArrayList<>();
        IOException failWith;
        /** Applied to each new one before it is handed over. */
        Consumer<FakeMacAdi> configure = adi -> { };

        @Override
        public MacAdi open(final File library) throws IOException {
            if (this.failWith != null) {
                throw this.failWith;
            }
            this.libraries.add(library);
            final FakeMacAdi adi = new FakeMacAdi();
            this.configure.accept(adi);
            this.opened.add(adi);
            return adi;
        }

        FakeMacAdi last() {
            return this.opened.get(this.opened.size() - 1);
        }
    }
}
