package dev.wander.android.opentagviewer.anisette;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import dev.wander.android.opentagviewer.db.repo.model.UserSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Anisette, produced on this device instead of by somebody else's server.
 *
 * <p>This is the single entry point for the whole mechanism: it downloads Apple's ADI
 * libraries on first use, verifies them, provisions once, and then hands out the two values a
 * login needs. Everything it does is lazy - nothing happens until a login actually asks - and
 * everything it does can fail without breaking the app, because the remote Anisette server
 * remains as a fallback.
 *
 * <p><b>Why this is worth having.</b> Today every login is relayed through a public Anisette
 * server: an operator sees the traffic, and logins stop working when their machine does. None
 * of that is necessary - Apple's libraries are native Android code and run perfectly well in
 * this process.
 *
 * <p><b>Threading.</b> Everything here blocks: it downloads, hashes and talks to Apple. Call
 * it from a background thread. It is safe to call {@link #ensureReady} repeatedly; the
 * expensive parts happen once.
 */
public final class LocalAnisette {
    private static final String TAG = "LocalAnisette";

    private static final String PREFERENCES = "anisette-identity";
    private static final String KEY_DEVICE_ID = "uniqueDeviceIdentifier";
    private static final String KEY_ADI_ID = "adiIdentifier";
    private static final String KEY_LOCAL_USER = "localUserUuid";

    /** Apple's, in dependency order. CoreFoundation and mediaplatform are our stubs. */
    private static final List<String> FROM_APPLE = Arrays.asList(
            "libc++_shared.so", "libstoreservicescore.so");

    private static final List<String> STUBS = Arrays.asList("CoreFoundation", "mediaplatform");

    private final Context context;
    private final String abi;
    private final UserSettings settings;
    private final boolean hasExistingSession;

    private AdiLibrary adi;
    private AdiDeviceIdentity identity;
    private String unavailableReason;

    /**
     * @param settings           consulted for which kind of Anisette the user wants, and for
     *                           an Apple Music APK they supplied themselves
     * @param hasExistingSession whether somebody is already signed in. Only decides what to do
     *                           when nobody has chosen a mode: an existing session stays on
     *                           its server, because moving it would force a re-login.
     */
    public LocalAnisette(Context context, UserSettings settings, boolean hasExistingSession) {
        this.context = context.getApplicationContext();
        this.abi = Build.SUPPORTED_ABIS[0];
        this.settings = settings;
        this.hasExistingSession = hasExistingSession;
    }

    /**
     * Get everything ready, doing only the parts not already done.
     *
     * @return true if local Anisette can be used; false means fall back to a remote server,
     *         and {@link #unavailableReason()} says why
     */
    public synchronized boolean ensureReady() {
        if (this.adi != null) {
            return true;
        }

        // Someone asking for a remote server gets a remote server. Checked before anything
        // else so that choosing remote costs no download and no provisioning, and so that a
        // machine which cannot run this at all is not made to find out repeatedly.
        if (!this.settings.usesLocalAnisette(this.hasExistingSession)) {
            this.unavailableReason = "a remote Anisette server is selected in Settings";
            return false;
        }

        try {
            final AdiLibraryManifest manifest = AdiLibraryManifest.load(this.context);
            final File libraryDir = new File(this.context.getFilesDir(), "anisette/lib/" + abi);

            download(manifest, libraryDir);
            verify(manifest, libraryDir);
            load(libraryDir);

            this.unavailableReason = null;
            return true;
        } catch (final Exception e) {
            // Deliberately broad. Anything at all going wrong here has exactly one correct
            // response - use the remote server - and a login is not the place to discover a
            // new exception type.
            this.unavailableReason = e.getMessage() == null ? e.toString() : e.getMessage();
            Log.w(TAG, "local Anisette unavailable, falling back to a remote server: "
                    + this.unavailableReason, e);
            this.adi = null;
            return false;
        }
    }

    private void download(AdiLibraryManifest manifest, File libraryDir) throws Exception {
        final List<String> needed = new ArrayList<>(FROM_APPLE);
        needed.add(AdiLibrary.CORE_ADI);

        final long bytes = AdiLibraryFetcher.fetchInto(libraryDir, this.abi, needed);
        if (bytes > 0) {
            Log.i(TAG, String.format("fetched Apple Music %s libraries (%.1f MB)",
                    manifest.apkVersion(), bytes / 1e6));
        }
    }

    /**
     * Nothing is loaded until every file matches the manifest. A mismatch is not repaired by
     * re-downloading - if Apple shipped a new build, the bytes are legitimately different and
     * the checked-in symbol lists may no longer describe them.
     */
    private void verify(AdiLibraryManifest manifest, File libraryDir) throws Exception {
        final List<String> everything = new ArrayList<>(FROM_APPLE);
        everything.add(AdiLibrary.CORE_ADI);

        for (final String name : everything) {
            final String problem = manifest.verify(new File(libraryDir, name), this.abi);
            if (problem != null) {
                throw new AdiLibrary.AdiUnavailableException(problem);
            }
        }
    }

    private void load(File libraryDir) throws Exception {
        for (final String stub : STUBS) {
            System.loadLibrary(stub);
        }

        final AdiLibrary library = AdiLibrary.open(libraryDir, FROM_APPLE);
        final AdiDeviceIdentity deviceIdentity = loadOrCreateIdentity();
        final File provisioningDir = new File(this.context.getFilesDir(), "anisette/provisioning");

        library.initialise(libraryDir, provisioningDir, deviceIdentity.adiIdentifier());
        new AdiProvisioning(deviceIdentity, library)
                .provisionIfNeeded(AdiProvisioning.ANONYMOUS_DS_ID);

        this.adi = library;
        this.identity = deviceIdentity;
    }

    /**
     * The device identity, generated once and then kept forever.
     *
     * <p>Regenerating it would make every login look like a brand new machine to Apple, which
     * is exactly what two-factor authentication exists to notice. So it is written on first
     * use and read thereafter.
     *
     * <p>SharedPreferences rather than the DataStore the rest of the app uses: this runs on a
     * background thread inside a blocking sequence, and does not want an Rx round trip in the
     * middle of it.
     */
    private AdiDeviceIdentity loadOrCreateIdentity() {
        final SharedPreferences preferences =
                this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);

        final String deviceId = preferences.getString(KEY_DEVICE_ID, null);
        final String adiId = preferences.getString(KEY_ADI_ID, null);
        final String localUser = preferences.getString(KEY_LOCAL_USER, null);

        if (deviceId != null && adiId != null && localUser != null) {
            return new AdiDeviceIdentity(deviceId, adiId, localUser);
        }

        final AdiDeviceIdentity fresh = AdiDeviceIdentity.generate();
        preferences.edit()
                .putString(KEY_DEVICE_ID, fresh.uniqueDeviceIdentifier())
                .putString(KEY_ADI_ID, fresh.adiIdentifier())
                .putString(KEY_LOCAL_USER, fresh.localUserUuid())
                .apply();

        Log.i(TAG, "generated a new device identity - this must now be kept");
        return fresh;
    }

    /** Why local Anisette is not being used, or null if it is. */
    public synchronized String unavailableReason() {
        return this.unavailableReason;
    }

    private static final String KEY_SESSION_WAS_LOCAL = "sessionEstablishedLocally";

    /**
     * Record which kind of Anisette established the current session.
     *
     * <p>This exists because Apple ties trust to the machine identity presented at login, and
     * local and remote Anisette present different ones. A session established here and later
     * continued through a public server is, from Apple's side, the same account arriving from
     * a different machine - which can invalidate device trust and demand re-authentication.
     *
     * <p>Knowing which happened is the difference between telling someone "Anisette changed,
     * you may need to sign in again" and leaving them in a 2FA loop with no explanation.
     *
     * <p>Written on every successful login, not only local ones, so it cannot go stale when
     * somebody signs in again over a remote server.
     */
    public synchronized void recordSessionProvenance(boolean establishedLocally) {
        this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SESSION_WAS_LOCAL, establishedLocally)
                .apply();
        Log.i(TAG, "session established with " + (establishedLocally ? "local" : "remote")
                + " Anisette");
    }

    /** Whether the stored session was established with local Anisette. */
    public synchronized boolean wasSessionEstablishedLocally() {
        return this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_SESSION_WAS_LOCAL, false);
    }

    /**
     * True when a session established locally is about to continue against a remote server -
     * the case where Apple may stop recognising the machine.
     *
     * <p>Not an error, and not a reason to refuse: the alternative is not working at all. It
     * is a reason to say something, so a subsequent re-authentication looks like a
     * consequence rather than a fault.
     *
     * <p>Calls {@link #ensureReady} rather than reading the field, so that asking the question
     * before anything has been loaded gives the right answer instead of a false alarm.
     */
    public synchronized boolean isChangingMachineIdentity() {
        return wasSessionEstablishedLocally() && !ensureReady();
    }

    /**
     * The one-time password, base64. Empty string if unavailable, matching what FindMy's own
     * providers return rather than throwing into the middle of a login.
     */
    public synchronized String otp() {
        return header("X-Apple-I-MD");
    }

    /** The machine identifier, base64. */
    public synchronized String machine() {
        return header("X-Apple-I-MD-M");
    }

    private Map<String, String> cached;
    private long cachedAt;

    /**
     * How long a generated set is reused. The password was measured rotating within about 16
     * seconds, so this stays well inside that.
     *
     * <p>The cache is not an optimisation. FindMy asks for the password and the machine
     * identifier through two separate properties, and generating each independently could
     * straddle a rotation and hand Apple two values from different moments.
     */
    private static final long CACHE_FOR_MS = 5_000;

    private String header(String name) {
        if (this.adi == null && !ensureReady()) {
            return "";
        }

        final long now = System.currentTimeMillis();
        if (this.cached == null || now - this.cachedAt > CACHE_FOR_MS) {
            try {
                this.cached = new AnisetteHeaders(this.adi, this.identity)
                        .generate(AdiProvisioning.ANONYMOUS_DS_ID);
                this.cachedAt = now;
            } catch (final Exception e) {
                Log.w(TAG, "could not produce Anisette data", e);
                this.unavailableReason = String.valueOf(e.getMessage());
                return "";
            }
        }

        final String value = this.cached.get(name);
        return value == null ? "" : value;
    }

    /** Purely for diagnostics - the identity is not secret, but it is identifying. */
    public synchronized String describe() {
        if (this.identity == null) {
            return "not ready: " + this.unavailableReason;
        }
        return "ready as " + this.identity.uniqueDeviceIdentifier()
                + " (ADI " + this.identity.adiIdentifier() + ")";
    }
}
