package dev.wander.android.opentagviewer.anisette;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import dev.wander.android.opentagviewer.BuildConfig;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Anisette from an emulated Mac on this device: Apple's macOS CoreADI under an x86_64 emulator.
 *
 * <p><b>Why fresh sign-ins use this rather than {@link LocalAnisette}.</b> That one runs Apple's
 * Android libraries, which mint Android machines. Since 2026-10-12 Apple issues the Find My token
 * only to a sign-in made with a Mac- or iPhone-minted machine, and from about 2026-10-20 its edge
 * refuses any client that admits to being Android - so an Android-minted machine can no longer
 * sign in to Find My by any combination of headers. A Mac-minted one can, and this mints them.
 *
 * <p><b>Sessions {@link LocalAnisette} signed in stay on it.</b> {@link AnisetteForSession} decides,
 * from {@link SessionProvenance}. This class is only handed a restored session that it signed in
 * itself, as {@link Purpose#RESTORE}, and then it <b>never provisions</b>: a Mac that has lost its
 * provisioning cannot be the machine that obtained the session's token, so registering a new one
 * would only add a device-list entry to a session that is going to fail anyway.
 *
 * <p><b>The machine must be the same one for the life of the session</b>, and not only at
 * sign-in: the Find My token only works alongside Anisette from the machine that obtained it
 * (os-findmy-e EVIDENCE.md section 6). So everything this hands out comes from one identity,
 * stored under {@code files/anisette/mac/}, and the same identity answers before and after it is
 * ready.
 *
 * <p><b>The emulator runs in another process.</b> {@link RemoteMacAdi} reaches
 * {@link MacAdiService} in {@code :macadi}; nothing here loads native code. A native fault there
 * reaches this class as {@link MacAdi.EmulatorDiedException}, is recorded per app version and
 * library build, and from then on this answers "unavailable" without trying - the same contract
 * {@link TryItElsewhereFirst} keeps for {@link LocalAnisette}.
 *
 * <p><b>Once per process.</b> The emulator connection and the identity are {@link Shared}, for the
 * same reason {@link LocalAnisette}'s are: {@code AppDependencies} builds a new source for every
 * caller, and two emulators over one store would each think they owned it.
 *
 * <p>Every collaborator can be replaced, so the state machine below is tested on the JVM
 * (AGENTS.md rule 13) - see {@code EmulatedMacAnisetteTest}.
 */
public final class EmulatedMacAnisette implements AnisetteSource {
    private static final String TAG = "EmulatedMacAnisette";

    /** Why this was asked for. See {@link AnisetteForSession}. */
    public enum Purpose {
        /** Nobody is signed in: may create and provision a Mac. */
        SIGN_IN,
        /** Restoring a session this Mac signed in: never creates or provisions anything. */
        RESTORE
    }

    /**
     * In {@link LocalAnisette#PREFERENCES}, beside its own crash records: the attempt (app version,
     * library build) whose emulator process died during a call.
     */
    static final String KEY_EMULATOR_DIED = "emulatedMacDied";

    /** What {@link #unavailableReason()} says once the emulator has died on this device. */
    public static final String CRASHES_HERE_REASON =
            "The emulated Mac crashed its process on this device, so a remote Anisette server is"
                    + " used instead";

    /** How long one generated set is reused. See {@code LocalAnisette.CACHE_FOR_MS}. */
    private static final long CACHE_FOR_MS = 5_000;

    /** Gets CoreADI onto disk, checked against its pinned hash, and says where. */
    interface Library {
        File ensure() throws Exception;
    }

    /** Registers a machine with Apple and returns the routing number Apple assigned it. */
    interface Provisioner {
        long provision(MacIdentity identity, MacAdi adi) throws Exception;
    }

    /** Everything that is one per process. Guarded by {@link #lock}. */
    static final class Shared {
        final Object lock = new Object();
        MacAdi adi;
        MacIdentity identity;
        Map<String, String> cached;
        long cachedAt;
    }

    private static final Shared SHARED = new Shared();

    private final File directory;
    private final UserSettings settings;
    private final Purpose purpose;
    private final SessionProvenance provenance;
    private final MacAdi.Opener opener;
    private final Library library;
    private final Provisioner provisioner;
    private final NativeLoadGuard.Store deaths;
    private final String attempt;
    private final Base64Codec base64;
    private final Shared shared;
    private volatile String unavailableReason;

    public EmulatedMacAnisette(Context context, UserSettings settings, Purpose purpose) {
        this(directoryFor(context.getApplicationContext()), settings, purpose,
                SessionProvenance.of(context),
                RemoteMacAdi.opener(context),
                libraryIn(directoryFor(context.getApplicationContext())),
                (identity, adi) -> new MacProvisioning(identity, adi).provision(),
                deathStore(context.getApplicationContext()),
                NativeLoadGuard.attemptFor(BuildConfig.VERSION_CODE, "macadi",
                        MacCoreAdi.BUILD_SHA256),
                Base64Codec.ANDROID,
                SHARED);
    }

    EmulatedMacAnisette(File directory, UserSettings settings, Purpose purpose,
                        SessionProvenance provenance, MacAdi.Opener opener, Library library,
                        Provisioner provisioner, NativeLoadGuard.Store deaths, String attempt,
                        Base64Codec base64, Shared shared) {
        this.directory = directory;
        this.settings = settings;
        this.purpose = purpose;
        this.provenance = provenance;
        this.opener = opener;
        this.library = library;
        this.provisioner = provisioner;
        this.deaths = deaths;
        this.attempt = attempt;
        this.base64 = base64;
        this.shared = shared;
    }

    private static File directoryFor(Context context) {
        return new File(context.getFilesDir(), "anisette/mac");
    }

    private static Library libraryIn(File directory) {
        return () -> {
            final File target = new File(directory, "CoreADI-4.19-x86_64");
            MacCoreAdiFetcher.ensure(target);
            return target;
        };
    }

    /** {@code commit()}, never {@code apply()}: see {@link NativeLoadGuard.Store}. */
    private static NativeLoadGuard.Store deathStore(Context context) {
        final SharedPreferences preferences =
                context.getSharedPreferences(LocalAnisette.PREFERENCES, Context.MODE_PRIVATE);
        return new NativeLoadGuard.Store() {
            @Override
            public String get() {
                return preferences.getString(KEY_EMULATOR_DIED, null);
            }

            @Override
            public void set(String value) {
                if (!preferences.edit().putString(KEY_EMULATOR_DIED, value).commit()) {
                    Log.w(TAG, "could not record that the emulated Mac crashed");
                }
            }

            @Override
            public void clear() {
                preferences.edit().remove(KEY_EMULATOR_DIED).commit();
            }
        };
    }

    private File storeDirectory() {
        return new File(this.directory, "store");
    }

    @Override
    public boolean ensureReady() {
        synchronized (this.shared.lock) {
            if (this.shared.adi != null) {
                if (!this.shared.adi.isGone()) {
                    return true;
                }
                // Gone between calls, which is not a fault: open a new one from the store on disk.
                this.shared.adi = null;
                this.shared.cached = null;
            }
            if (!this.settings.usesLocalAnisette(this.purpose == Purpose.RESTORE)) {
                this.unavailableReason = "a remote Anisette server is selected in Settings";
                return false;
            }
            if (this.purpose == Purpose.RESTORE && !this.provenance.establishedLocally()) {
                // Signed in through a server when the emulated Mac was unavailable. That server's
                // machine holds the session now, and moving it onto this one is a different
                // machine to Apple.
                this.unavailableReason = "this session was signed in through a remote Anisette"
                        + " server, and stays on it";
                return false;
            }
            if (emulatorDiedHereBefore()) {
                this.unavailableReason = CRASHES_HERE_REASON;
                return false;
            }

            MacAdi opened = null;
            try {
                final File image = this.library.ensure();

                MacIdentity current = this.purpose == Purpose.RESTORE
                        ? storedIdentity() : loadOrCreateIdentity();
                opened = open(image, current);

                if (current.provisionedAtMillis() == 0) {
                    if (this.purpose == Purpose.RESTORE) {
                        throw new IllegalStateException("the emulated Mac that signed this session"
                                + " in was never provisioned; sign in again to register it");
                    }
                    current = provision(opened, current);
                } else if (!isProvisioned(opened)) {
                    if (this.purpose == Purpose.RESTORE) {
                        throw new IllegalStateException("the emulated Mac has lost its provisioning,"
                                + " so it is no longer the machine this session was signed in with;"
                                + " sign in again");
                    }
                    // The identity says provisioned but the store has lost it. Apple will not
                    // provision the same device id twice (HTTP 433), so this is a new machine:
                    // a second device-list entry, which is better than never signing in again.
                    Log.w(TAG, "the emulated Mac's store no longer holds its provisioning;"
                            + " registering a new machine");
                    opened.close();
                    opened = null;
                    current = MacIdentity.generate();
                    current.save(this.directory);
                    clearStore();
                    opened = open(image, current);
                    current = provision(opened, current);
                }

                this.shared.adi = opened;
                this.shared.identity = current;
                this.shared.cached = null;
                this.unavailableReason = null;
                Log.i(TAG, "ready as an emulated Mac, device " + current.deviceId());
                return true;
            } catch (final MacAdi.EmulatorDiedException e) {
                recordEmulatorDeath(e);
                this.unavailableReason = CRASHES_HERE_REASON;
                return false;
            } catch (final Exception | LinkageError e) {
                if (opened != null) {
                    opened.close();
                }
                this.unavailableReason = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.w(TAG, "the emulated Mac is unavailable, falling back to a remote server: "
                        + this.unavailableReason, e);
                return false;
            }
        }
    }

    /**
     * Whether this app version's emulator has already died during a call on this device. A record
     * from another version or library build is stale, and cleared, so an update tries again.
     */
    private boolean emulatorDiedHereBefore() {
        final String recorded = this.deaths.get();
        if (recorded == null) {
            return false;
        }
        if (recorded.equals(this.attempt)) {
            return true;
        }
        this.deaths.clear();
        return false;
    }

    private void recordEmulatorDeath(MacAdi.EmulatorDiedException e) {
        Log.w(TAG, "the emulated Mac's process died during a call; using a remote Anisette server"
                + " until the app or the library changes", e);
        this.deaths.set(this.attempt);
        this.shared.adi = null;
        this.shared.cached = null;
    }

    private MacAdi open(File image, MacIdentity current) throws IOException {
        final MacAdi opened = this.opener.open(image);
        try {
            opened.setHostUuid(current.hostUuidBytes());
            final File[] stored = storeDirectory().listFiles();
            if (stored != null) {
                for (final File file : stored) {
                    if (file.isFile() && !file.getName().endsWith(".partial")) {
                        opened.storePut(file.getName(), MacCoreAdiFetcher.readFile(file));
                    }
                }
            }
            return opened;
        } catch (final IOException | RuntimeException e) {
            if (!(e instanceof MacAdi.EmulatorDiedException)) {
                opened.close();
            }
            throw e;
        }
    }

    private static boolean isProvisioned(MacAdi opened) {
        try {
            opened.headers(MacAdi.ANONYMOUS_DS_ID);
            return true;
        } catch (final MacAdi.NotProvisionedException e) {
            return false;
        }
    }

    /**
     * Register this machine with Apple and keep the result.
     *
     * <p>The store is written before the identity is marked provisioned. If the process dies in
     * between, the next launch finds a provisioned store under an unprovisioned identity, the
     * library refuses to provision again, and the failure is visible - rather than the other way
     * round, an identity marked provisioned over an empty store, which costs a second device.
     */
    private MacIdentity provision(MacAdi opened, MacIdentity current) throws Exception {
        final long routing = this.provisioner.provision(current, opened);
        saveStore(opened);
        final MacIdentity done = current.provisioned(routing, System.currentTimeMillis());
        done.save(this.directory);
        return done;
    }

    private void saveStore(MacAdi opened) throws IOException {
        final File dir = storeDirectory();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir);
        }
        for (final Map.Entry<String, byte[]> file : opened.storeFiles().entrySet()) {
            // The legacy whole-machine blob makes the library report "not provisioned" whenever
            // it is present, and is never read back - see CoreAdi::beginSession.
            if ("adi.pb".equals(file.getKey())) {
                continue;
            }
            final File target = new File(dir, file.getKey());
            final File partial = new File(dir, file.getKey() + ".partial");
            try (FileOutputStream out = new FileOutputStream(partial)) {
                out.write(file.getValue());
                out.getFD().sync();
            }
            if (!partial.renameTo(target)) {
                throw new IOException("could not store " + target);
            }
        }
    }

    private void clearStore() {
        final File[] stored = storeDirectory().listFiles();
        if (stored != null) {
            for (final File file : stored) {
                if (!file.delete()) {
                    Log.w(TAG, "could not delete " + file);
                }
            }
        }
    }

    /** The stored identity, for a restore, which must not create one. */
    private MacIdentity storedIdentity() throws IOException {
        synchronized (this.shared.lock) {
            if (this.shared.identity != null) {
                return this.shared.identity;
            }
            final MacIdentity stored = MacIdentity.load(this.directory);
            if (stored == null) {
                throw new IllegalStateException("this install has no emulated Mac, so it cannot be"
                        + " the machine this session was signed in with; sign in again");
            }
            return stored;
        }
    }

    /**
     * The identity, read or - on a fresh install - created and saved. Never provisions: that
     * needs the library, and this has to answer without it.
     */
    private MacIdentity loadOrCreateIdentity() throws IOException {
        synchronized (this.shared.lock) {
            if (this.shared.identity != null) {
                return this.shared.identity;
            }
            final MacIdentity stored = MacIdentity.load(this.directory);
            if (stored != null) {
                return stored;
            }
            final MacIdentity fresh = MacIdentity.generate();
            fresh.save(this.directory);
            Log.i(TAG, "generated a new emulated Mac identity - this must now be kept");
            return fresh;
        }
    }

    private MacIdentity currentIdentity() {
        try {
            return loadOrCreateIdentity();
        } catch (final IOException e) {
            throw new IllegalStateException("could not read or create the emulated Mac", e);
        }
    }

    @Override
    public String unavailableReason() {
        return this.unavailableReason;
    }

    @Override
    public boolean isChangingMachineIdentity() {
        return wasSessionEstablishedLocally() && !ensureReady();
    }

    @Override
    public String otp() {
        return header("X-Apple-I-MD");
    }

    @Override
    public String machine() {
        return header("X-Apple-I-MD-M");
    }

    /**
     * The routing number the library reports, which is the one Apple assigned at provisioning.
     * Not FindMy.py's constant: that is an Android or Windows value, and this is a Mac.
     */
    @Override
    public String router() {
        final String value = header("X-Apple-I-MD-RINFO");
        return value.isEmpty() ? AnisetteSource.super.router() : value;
    }

    private String header(String name) {
        if (!ensureReady()) {
            return "";
        }
        synchronized (this.shared.lock) {
            final long now = System.currentTimeMillis();
            if (this.shared.cached == null || now - this.shared.cachedAt > CACHE_FOR_MS) {
                try {
                    final MacAdi.Headers generated =
                            this.shared.adi.headers(MacAdi.ANONYMOUS_DS_ID);
                    // RequestOTP rewrites the store, and a store that goes back in time is a
                    // machine Apple has seen move backwards.
                    saveStore(this.shared.adi);
                    this.shared.cached = Map.of(
                            "X-Apple-I-MD-M", this.base64.encode(generated.machineId),
                            "X-Apple-I-MD", this.base64.encode(generated.oneTimePassword),
                            "X-Apple-I-MD-RINFO", Long.toString(generated.routing));
                    this.shared.cachedAt = now;
                } catch (final MacAdi.EmulatorDiedException e) {
                    recordEmulatorDeath(e);
                    this.unavailableReason = CRASHES_HERE_REASON;
                    return "";
                } catch (final Exception e) {
                    Log.w(TAG, "could not produce Anisette data", e);
                    this.unavailableReason = String.valueOf(e.getMessage());
                    return "";
                }
            }
            final String value = this.shared.cached.get(name);
            return value == null ? "" : value;
        }
    }

    /**
     * Recorded in {@link SessionProvenance}, in {@link LocalAnisette}'s file, as a session the
     * emulated Mac signed in - so the next restore comes back here rather than to the Android
     * machine, and the Android machine's sessions keep reading as its own.
     */
    @Override
    public void recordSessionProvenance(boolean establishedLocally) {
        this.provenance.recordEmulatedMacSession(establishedLocally);
        Log.i(TAG, "session established with " + (establishedLocally ? "the emulated Mac" : "remote")
                + " Anisette");
    }

    @Override
    public boolean wasSessionEstablishedLocally() {
        return this.provenance.establishedLocally();
    }

    @Override
    public String hardwareProfileJson() {
        return MacIdentity.hardwareJson();
    }

    @Override
    public String deviceIdsJson() {
        final MacIdentity current = currentIdentity();
        try {
            return new JSONObject()
                    .put("uid", current.localUserUuid())
                    .put("devid", current.deviceId())
                    .toString();
        } catch (final JSONException e) {
            throw new IllegalStateException("could not describe the emulated Mac", e);
        }
    }

    @Override
    public String serial() {
        return currentIdentity().serial();
    }

    /**
     * What CloudKit is told this device is, as an uppercase UUID: the emulated Mac's host UUID.
     *
     * <p>Called from Python's {@code icloud_bridge._forCloudKit}. FindMy.py's CloudKit client
     * parses the account's device id as a UUID, and a Mac's is not one. Not on
     * {@link AnisetteSource}: no other source has a device id that needs it.
     */
    public String cloudKitDeviceUuid() {
        final String h = currentIdentity().hostUuid().toUpperCase(Locale.ROOT);
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-"
                + h.substring(16, 20) + "-" + h.substring(20);
    }

    @Override
    public String describe() {
        synchronized (this.shared.lock) {
            if (this.shared.identity == null) {
                return "emulated Mac not ready: " + this.unavailableReason;
            }
            return "ready as an emulated Mac, device " + this.shared.identity.deviceId();
        }
    }
}
