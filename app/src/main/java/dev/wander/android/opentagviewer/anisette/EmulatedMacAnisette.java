package dev.wander.android.opentagviewer.anisette;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

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
 * <p><b>Why this replaced {@link LocalAnisette}.</b> That one runs Apple's Android libraries,
 * which mint Android machines. Since 2026-10-12 Apple issues the Find My token only to a sign-in
 * made with a Mac- or iPhone-minted machine, and from about 2026-10-20 its edge refuses any client
 * that admits to being Android - so an Android-minted machine can no longer sign in to Find My by
 * any combination of headers. A Mac-minted one can, and this mints them. See {@link MacCoreAdi}.
 *
 * <p><b>The machine must be the same one for the life of the session</b>, and not only at
 * sign-in: the Find My token only works alongside Anisette from the machine that obtained it
 * (os-findmy-e EVIDENCE.md section 6). So everything this hands out comes from one identity,
 * stored under {@code files/anisette/mac/}, and the same identity answers before and after it is
 * ready.
 *
 * <p><b>Once per process.</b> The emulator, the library and the identity are static, for the same
 * reason {@link LocalAnisette}'s are: {@code AppDependencies} builds a new source for every caller,
 * and two emulators over one store would each think they owned it.
 */
public final class EmulatedMacAnisette implements AnisetteSource {
    private static final String TAG = "EmulatedMacAnisette";

    private static final String PREFERENCES = "anisette-emulated-mac";
    private static final String KEY_SESSION_WAS_LOCAL = "sessionEstablishedLocally";

    /** How long one generated set is reused. See {@code LocalAnisette.CACHE_FOR_MS}. */
    private static final long CACHE_FOR_MS = 5_000;

    /** Guards everything static below, and every call into the emulator. */
    private static final Object LOCK = new Object();
    private static MacCoreAdi adi;
    private static MacIdentity identity;
    private static Map<String, String> cached;
    private static long cachedAt;

    private final Context context;
    private final UserSettings settings;
    private final boolean hasExistingSession;
    private volatile String unavailableReason;

    public EmulatedMacAnisette(Context context, UserSettings settings, boolean hasExistingSession) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.hasExistingSession = hasExistingSession;
    }

    private File directory() {
        return new File(this.context.getFilesDir(), "anisette/mac");
    }

    private File storeDirectory() {
        return new File(directory(), "store");
    }

    @Override
    public boolean ensureReady() {
        synchronized (LOCK) {
            if (adi != null) {
                return true;
            }
            if (!this.settings.usesLocalAnisette(this.hasExistingSession)) {
                this.unavailableReason = "a remote Anisette server is selected in Settings";
                return false;
            }

            MacCoreAdi opened = null;
            try {
                final byte[] library = MacCoreAdiFetcher.ensure(
                        new File(directory(), "CoreADI-4.19-x86_64"));

                MacIdentity current = loadOrCreateIdentity();
                opened = open(library, current);

                if (current.provisionedAtMillis() == 0) {
                    current = provision(opened, current);
                } else if (!isProvisioned(opened)) {
                    // The identity says provisioned but the store has lost it. Apple will not
                    // provision the same device id twice (HTTP 433), so this is a new machine:
                    // a second device-list entry, which is better than never signing in again.
                    Log.w(TAG, "the emulated Mac's store no longer holds its provisioning;"
                            + " registering a new machine");
                    opened.close();
                    current = MacIdentity.generate();
                    current.save(directory());
                    clearStore();
                    opened = open(library, current);
                    current = provision(opened, current);
                }

                adi = opened;
                identity = current;
                cached = null;
                this.unavailableReason = null;
                Log.i(TAG, "ready as an emulated Mac, device " + current.deviceId());
                return true;
            } catch (final Exception | LinkageError e) {
                // LinkageError too: an UnsatisfiedLinkError loading libmacadi.so is the likeliest
                // failure on a device nobody has tried, and it must fall back, not crash sign-in.
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

    private MacCoreAdi open(byte[] library, MacIdentity current) throws IOException {
        final MacCoreAdi opened = new MacCoreAdi(library);
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
    }

    private static boolean isProvisioned(MacCoreAdi opened) {
        try {
            opened.headers(MacCoreAdi.ANONYMOUS_DS_ID);
            return true;
        } catch (final MacCoreAdi.NotProvisionedException e) {
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
    private MacIdentity provision(MacCoreAdi opened, MacIdentity current) throws Exception {
        final long routing = new MacProvisioning(current, opened).provision();
        saveStore(opened);
        final MacIdentity done = current.provisioned(routing, System.currentTimeMillis());
        done.save(directory());
        return done;
    }

    private void saveStore(MacCoreAdi opened) throws IOException {
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

    /**
     * The identity, read or - on a fresh install - created and saved. Never provisions: that
     * needs the library, and this has to answer without it.
     */
    private MacIdentity loadOrCreateIdentity() throws IOException {
        synchronized (LOCK) {
            if (identity != null) {
                return identity;
            }
            final MacIdentity stored = MacIdentity.load(directory());
            if (stored != null) {
                return stored;
            }
            final MacIdentity fresh = MacIdentity.generate();
            fresh.save(directory());
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
        synchronized (LOCK) {
            final long now = System.currentTimeMillis();
            if (cached == null || now - cachedAt > CACHE_FOR_MS) {
                try {
                    final MacCoreAdi.Headers generated =
                            adi.headers(MacCoreAdi.ANONYMOUS_DS_ID);
                    // RequestOTP rewrites the store, and a store that goes back in time is a
                    // machine Apple has seen move backwards.
                    saveStore(adi);
                    cached = Map.of(
                            "X-Apple-I-MD-M",
                            Base64.encodeToString(generated.machineId, Base64.NO_WRAP),
                            "X-Apple-I-MD",
                            Base64.encodeToString(generated.oneTimePassword, Base64.NO_WRAP),
                            "X-Apple-I-MD-RINFO", Long.toString(generated.routing));
                    cachedAt = now;
                } catch (final Exception e) {
                    Log.w(TAG, "could not produce Anisette data", e);
                    this.unavailableReason = String.valueOf(e.getMessage());
                    return "";
                }
            }
            final String value = cached.get(name);
            return value == null ? "" : value;
        }
    }

    @Override
    public void recordSessionProvenance(boolean establishedLocally) {
        preferences().edit().putBoolean(KEY_SESSION_WAS_LOCAL, establishedLocally).apply();
        Log.i(TAG, "session established with " + (establishedLocally ? "the emulated Mac" : "remote")
                + " Anisette");
    }

    @Override
    public boolean wasSessionEstablishedLocally() {
        return preferences().getBoolean(KEY_SESSION_WAS_LOCAL, false);
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
        synchronized (LOCK) {
            if (identity == null) {
                return "emulated Mac not ready: " + this.unavailableReason;
            }
            return "ready as an emulated Mac, device " + identity.deviceId();
        }
    }

    private SharedPreferences preferences() {
        return this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
