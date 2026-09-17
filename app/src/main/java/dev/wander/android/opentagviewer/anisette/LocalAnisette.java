package dev.wander.android.opentagviewer.anisette;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import dev.wander.android.opentagviewer.BuildConfig;
import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.util.LoadedOnce;

import org.json.JSONException;
import org.json.JSONObject;

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
public final class LocalAnisette implements AnisetteSource {
    private static final String TAG = "LocalAnisette";

    /**
     * Where the identity lives.
     *
     * <p>Visible because this is a <b>storage contract with installs already in the field</b>,
     * not an implementation detail. What is written here decides whether an upgrade keeps
     * somebody signed in, and the only way to test that is to write the older shape and read it
     * back - so the test names these rather than a copy of them, which would keep passing after
     * the real ones moved.
     */
    public static final String PREFERENCES = "anisette-identity";
    public static final String KEY_DEVICE_ID = "uniqueDeviceIdentifier";
    public static final String KEY_ADI_ID = "adiIdentifier";
    public static final String KEY_LOCAL_USER = "localUserUuid";

    /**
     * Which machine this install claims to be.
     *
     * <p>Added after the other three, so <b>its absence beside them is meaningful</b>: it marks
     * an install from before there was a choice, which can only have been the Mac.
     */
    public static final String KEY_HARDWARE = "hardwareProfile";

    /**
     * The serial Apple prints against this install's device-list entry.
     *
     * <p>Added last, and <b>its absence beside the others is meaningful in the same way</b>: it
     * marks an install from before serials were drawn per install, which can only have been
     * presenting {@link AdiDeviceIdentity#LEGACY_SERIAL}. That install keeps it - a new serial
     * on a working install is a second device-list entry and possibly a sign-in, in exchange for
     * nothing the user asked for.
     *
     * <p>Not back-filled for those installs on read, deliberately. Writing the legacy value here
     * would make an old install indistinguishable from one that drew that value, and the whole
     * point of the alphabet excluding {@code I} is that it cannot have drawn it.
     */
    public static final String KEY_SERIAL = "serial";

    /**
     * Present only while Apple's library is being loaded - or after a load that killed the
     * process. See {@link NativeLoadGuard}.
     */
    static final String KEY_LOADING_APPLES_LIBRARY = "loadingApplesLibrary";

    /** Apple's, in dependency order. CoreFoundation and mediaplatform are our stubs. */
    private static final List<String> FROM_APPLE = Arrays.asList(
            "libc++_shared.so", "libstoreservicescore.so");

    private static final List<String> STUBS = Arrays.asList("CoreFoundation", "mediaplatform");

    /**
     * Apple's library and the identity it was initialised with - <b>one per process</b>.
     *
     * <p><b>Static, because what it holds is.</b> The libraries land at one path, are loaded into
     * one process, and ADI keeps one lot of state. The old guard was {@code synchronized} on a
     * {@code LocalAnisette}, and {@code AppDependencies} builds a new one for every caller - so
     * two callers held two locks and proceeded together, unpacking the same {@code .so} and
     * {@code dlopen}-ing it. That segfaults. See issue #135.
     *
     * <p>{@link LoadedOnce} covers the download and the unpack as well as the load, because the
     * race starts at the file writes rather than at ADI.
     */
    private static final LoadedOnce<ApplesLibrary> APPLES_LIBRARY = new LoadedOnce<>();

    /** Apple's library and the identity it was initialised with, which travel together. */
    private static final class ApplesLibrary {
        private final AdiLibrary adi;
        private final AdiDeviceIdentity identity;

        ApplesLibrary(final AdiLibrary adi, final AdiDeviceIdentity identity) {
            this.adi = adi;
            this.identity = identity;
        }
    }

    /**
     * Every library that has to be present before any of this can run.
     *
     * <p>Public because {@link AdiLibraryImporter} has to extract exactly this set from an APK
     * somebody supplied by hand, and two lists that must agree is one list too many.
     */
    public static List<String> requiredLibraries() {
        final List<String> everything = new ArrayList<>(FROM_APPLE);
        everything.add(AdiLibrary.CORE_ADI);
        return everything;
    }

    /** Where the libraries for this device live, supplied by Apple or by the user. */
    public static File libraryDirectory(final Context context, final String abi) {
        return new File(context.getFilesDir(), "anisette/lib/" + abi);
    }

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
    @Override
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
            // **Once per process, one thread at a time.** Every caller of
            // AppDependencies.anisette gets a *new* LocalAnisette, so the instance lock this
            // used to rely on excluded nothing - and two threads getting here together is
            // ordinary: the map restores a session on one, the restore fails, and the login
            // screen it redirects to asks on another. They were unpacking the same .so files to
            // the same paths and dlopening them, which segfaults. See issue #135.
            //
            // The download and the unpack are inside, not just the load, because the race starts
            // at the file writes.
            final ApplesLibrary loaded = APPLES_LIBRARY.get(() -> {
                final AdiLibraryManifest manifest = AdiLibraryManifest.load(this.context);

                // **Before anything else, and inside the lock.** On some devices Apple's library
                // kills the process while it is being loaded (issue #232), which no catch below
                // can see - so the only evidence is a record that the last load never returned.
                // Inside the lock because two threads loading at once is ordinary (#135): checked
                // outside, the second would read the first one's in-progress record as a crash.
                final NativeLoadGuard guard = this.nativeLoadGuard(manifest);
                if (guard.previousAttemptCrashed()) {
                    throw new AdiLibrary.AdiUnavailableException(
                            "Apple's library crashed this app the last time it was loaded on this"
                                    + " device, so a remote Anisette server is used instead");
                }

                final File libraryDir = libraryDirectory(this.context, this.abi);

                download(manifest, libraryDir);
                verify(manifest, libraryDir);
                return load(libraryDir, guard);
            });

            this.adi = loaded.adi;
            this.identity = loaded.identity;

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
        // Skips the network entirely when the files are already there - which is also how a
        // user-supplied APK takes effect: AdiLibraryImporter writes into this same directory.
        final long bytes = AdiLibraryFetcher.fetchInto(libraryDir, this.abi, requiredLibraries());
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
        for (final String name : requiredLibraries()) {
            final String problem = manifest.verify(new File(libraryDir, name), this.abi);
            if (problem != null) {
                throw new AdiLibrary.AdiUnavailableException(problem);
            }
        }
    }

    /**
     * Load Apple's library and initialise it - <b>once per process, not once per instance</b>.
     *
     * <p><b>Everything below the first line happens exactly once.</b> A shared object is loaded
     * into a process, not into an object, and ADI keeps its state inside that library. The old
     * code guarded this with {@code this.adi != null} on an instance that
     * {@code AppDependencies} creates fresh for every caller, so the guard never fired and each
     * caller re-ran the lot: another {@code dlopen}, another {@code ADILoadLibraryWithPath},
     * another {@code ADISetProvisioningPath}, against a library already holding all of it.
     *
     * <p><b>Only ever called from inside {@link #APPLES_LIBRARY}</b>, which is what makes "once"
     * true - see {@code ensureReady}.
     *
     * <p>Identity is cached with the library rather than re-derived, and not only to save the
     * read: {@code loadOrCreateIdentity} *creates* one when there is none, so two threads
     * arriving on a fresh install could mint two different identities and register the app twice
     * on the user's Apple account. Rule 11 is emphatic that there is one identity.
     *
     * <p><b>If {@code ADIProvisioningErase} is ever wired up, it has to clear these.</b> Nothing
     * calls it today, which is the only reason there is no invalidation here; resetting Anisette
     * against a cached, already-initialised library would appear to work and change nothing.
     */
    private ApplesLibrary load(File libraryDir, NativeLoadGuard guard) throws Exception {
        for (final String stub : STUBS) {
            System.loadLibrary(stub);
        }

        // Read before the library is opened rather than after: it touches only preferences, and
        // moving it lets the guarded window below hold exactly the calls into Apple's code.
        final AdiDeviceIdentity deviceIdentity = loadOrCreateIdentity();
        final File provisioningDir = new File(this.context.getFilesDir(), "anisette/provisioning");

        // **The window is the load and the initialise, and nothing longer.** Both are native,
        // both take milliseconds, and #232's crash is in the first. Provisioning is left out on
        // purpose: it waits on the network for seconds, and a user closing the app during it
        // would leave the same record a crash does, switching local Anisette off for nothing.
        final AdiLibrary library = guard.around(() -> {
            final AdiLibrary opened = AdiLibrary.open(libraryDir, FROM_APPLE);
            opened.initialise(libraryDir, provisioningDir, deviceIdentity.adiIdentifier());
            return opened;
        });
        new AdiProvisioning(deviceIdentity, library)
                .provisionIfNeeded(AdiProvisioning.ANONYMOUS_DS_ID);

        // Returned rather than assigned: LoadedOnce caches only on success, so a throw above
        // leaves nothing behind and the next caller tries again. A half-initialised library in
        // the cache would be handed to everyone after it, and would read as Apple rejecting the
        // app rather than as a bad load.
        return new ApplesLibrary(library, deviceIdentity);
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
    /**
     * The guard for loading Apple's library, keyed to this app build, ABI and library build.
     *
     * <p>{@code commit()}, never {@code apply()}: the write before the load is only worth anything
     * if it is on disk when the process dies, and {@code apply()} leaves it in a queue that dies
     * with it. See {@link NativeLoadGuard.Store}.
     */
    private NativeLoadGuard nativeLoadGuard(final AdiLibraryManifest manifest) {
        final SharedPreferences preferences =
                this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);

        final NativeLoadGuard.Store store = new NativeLoadGuard.Store() {
            @Override
            public String get() {
                return preferences.getString(KEY_LOADING_APPLES_LIBRARY, null);
            }

            @Override
            public void set(final String attempt) {
                if (!preferences.edit().putString(KEY_LOADING_APPLES_LIBRARY, attempt).commit()) {
                    Log.w(TAG, "Could not record that Apple's library is loading; a crash during"
                            + " the load would not be noticed on the next launch");
                }
            }

            @Override
            public void clear() {
                preferences.edit().remove(KEY_LOADING_APPLES_LIBRARY).commit();
            }
        };

        return new NativeLoadGuard(store, NativeLoadGuard.attemptFor(
                BuildConfig.VERSION_CODE, this.abi, manifest.apkVersion()));
    }

    private AdiDeviceIdentity loadOrCreateIdentity() {
        final SharedPreferences preferences =
                this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);

        final String deviceId = preferences.getString(KEY_DEVICE_ID, null);
        final String adiId = preferences.getString(KEY_ADI_ID, null);
        final String localUser = preferences.getString(KEY_LOCAL_USER, null);

        if (deviceId != null && adiId != null && localUser != null) {
            // **An install that already has an identity keeps the machine it has always
            // claimed to be.** The absence of the hardware key is what says "from before
            // profiles existed", and that can only mean the Mac: it is the only thing this app
            // ever sent. Defaulting a pre-existing install to the new profile instead would
            // re-identify it to Apple, which costs the user a sign-in and leaves a second
            // device-list entry - to change the icon on a row they already recognise.
            final AdiDeviceIdentity.Hardware hardware =
                    hardwareFrom(preferences.getString(KEY_HARDWARE, null),
                            AdiDeviceIdentity.Hardware.LEGACY_MAC);

            // Same argument for the serial, for the same reason: an install with an identity
            // and no stored serial has been telling Apple it is 0PENTAGVIEWR, and drawing it a
            // new one now would register a second device beside the row it already has.
            return new AdiDeviceIdentity(deviceId, adiId, localUser,
                    serialFrom(preferences), hardware);
        }

        final AdiDeviceIdentity fresh = AdiDeviceIdentity.generate();
        preferences.edit()
                .putString(KEY_DEVICE_ID, fresh.uniqueDeviceIdentifier())
                .putString(KEY_ADI_ID, fresh.adiIdentifier())
                .putString(KEY_LOCAL_USER, fresh.localUserUuid())
                .putString(KEY_HARDWARE, fresh.hardware().name())
                .putString(KEY_SERIAL, fresh.serial())
                .apply();

        Log.i(TAG, "generated a new device identity as " + fresh.hardware()
                + " - this must now be kept");
        return fresh;
    }

    /**
     * Which machine this install presents as, for showing the user - and nothing else.
     *
     * <p><b>Read-only on purpose.</b> {@link #loadOrCreateIdentity()} writes an identity when
     * there is none, because a caller that needs one needs it to persist. A screen describing the
     * install must not have that side effect: opening it on a device that has never signed in
     * would mint and store an identity, and the profile chosen for a fresh install is then fixed
     * by having looked at a page. Nothing stored means nothing stored, and the answer is the
     * default that {@link AdiDeviceIdentity#generate()} would pick.
     *
     * <p>Touches no native library, so it is safe on the main thread - see
     * {@code ensureReady}, which is the expensive one.
     */
    public static AdiDeviceIdentity.Hardware profileToShow(final Context context) {
        return hardwareFrom(
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        .getString(KEY_HARDWARE, null),
                AdiDeviceIdentity.Hardware.DEFAULT);
    }

    /**
     * The serial this install presents, for showing the user - and nothing else.
     *
     * <p><b>Read-only, like {@link #profileToShow}</b>, and for the same reason: a screen must
     * not be the thing that decides what this install's identity is.
     *
     * <p><b>Only ask this where an identity already exists.</b> The screen it is for is the one
     * shown after a sign-in registered a device, so by then one has been drawn and stored. An
     * install with nothing stored gets {@link AdiDeviceIdentity#LEGACY_SERIAL}, which is right
     * for every install that has an identity without a serial and wrong for one that has no
     * identity at all - but the alternative is minting one to render a label, which fixes the
     * install's identity by having looked at a page.
     */
    public static String serialToShow(final Context context) {
        return serialFrom(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE));
    }

    /** The stored serial, or the value an install from before this change has been sending. */
    private static String serialFrom(final SharedPreferences preferences) {
        final String stored = preferences.getString(KEY_SERIAL, null);
        return stored != null && !stored.isEmpty() ? stored : AdiDeviceIdentity.LEGACY_SERIAL;
    }

    /**
     * The stored profile, or {@code fallback} when there is nothing usable stored.
     *
     * <p>An unrecognised name falls back rather than throwing. A profile removed in a later
     * version would otherwise make an install that has one unable to start at all, and the
     * identity is not worth crashing over - the fallback re-identifies that install, which is
     * bad, but it is recoverable and a crash loop is not.
     */
    private static AdiDeviceIdentity.Hardware hardwareFrom(
            final String stored, final AdiDeviceIdentity.Hardware fallback) {
        if (stored == null) {
            return fallback;
        }
        try {
            return AdiDeviceIdentity.Hardware.valueOf(stored);
        } catch (final IllegalArgumentException e) {
            Log.w(TAG, "unknown stored hardware profile " + stored + ", using " + fallback);
            return fallback;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deliberately does not go through {@link #ensureReady}. A sign-in that falls back to a
     * remote server needs this answer too, and it must be the <i>same</i> answer - which kind
     * of Anisette produced a session is a transport detail, and a user whose local Anisette
     * failed must not thereby become a different machine.
     *
     * <p>Reads the loaded identity when there is one, and otherwise reads - or, on a genuinely
     * fresh install, writes - the persisted one. Generating it here rather than in
     * {@link #load} is intentional: an install that only ever uses a remote server still has an
     * identity, and it is the same one it will use if local Anisette is turned on later.
     */
    @Override
    public synchronized String hardwareProfileJson() {
        return currentIdentity().hardware().toJson();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The local user id is handed over <b>as stored</b>, not as the header renders it.
     * FindMy.py base64-encodes it on the way out, so passing the encoded form would encode it
     * twice - which is a value Apple has never seen, from a client claiming to be the same
     * installation. See {@code AdiDeviceIdentity.Hardware#localUserHeader}.
     */
    /**
     * {@inheritDoc}
     *
     * <p>Answerable without ADI, for the same reason as {@link #hardwareProfileJson} - and it
     * has to be, because a sign-in that fell back to a remote server must present the same
     * serial as one that did not. The serial is not a property of how Anisette was obtained.
     */
    @Override
    public synchronized String serial() {
        return currentIdentity().serial();
    }

    @Override
    public synchronized String deviceIdsJson() {
        final AdiDeviceIdentity current = currentIdentity();
        try {
            return new JSONObject()
                    .put("uid", current.localUserUuid())
                    .put("devid", current.uniqueDeviceIdentifier())
                    .toString();
        } catch (final JSONException e) {
            throw new IllegalStateException("could not describe this installation", e);
        }
    }

    /** The identity in memory if it has been loaded, and the persisted one otherwise. */
    private AdiDeviceIdentity currentIdentity() {
        return this.identity != null ? this.identity : loadOrCreateIdentity();
    }

    /** Why local Anisette is not being used, or null if it is. */
    @Override
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
    @Override
    public synchronized void recordSessionProvenance(boolean establishedLocally) {
        this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SESSION_WAS_LOCAL, establishedLocally)
                .apply();
        Log.i(TAG, "session established with " + (establishedLocally ? "local" : "remote")
                + " Anisette");
    }

    /** Whether the stored session was established with local Anisette. */
    @Override
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
    @Override
    public synchronized boolean isChangingMachineIdentity() {
        return wasSessionEstablishedLocally() && !ensureReady();
    }

    /**
     * The one-time password, base64. Empty string if unavailable, matching what FindMy's own
     * providers return rather than throwing into the middle of a login.
     */
    @Override
    public synchronized String otp() {
        return header("X-Apple-I-MD");
    }

    /** The machine identifier, base64. */
    @Override
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
    @Override
    public synchronized String describe() {
        if (this.identity == null) {
            return "not ready: " + this.unavailableReason;
        }
        return "ready as " + this.identity.uniqueDeviceIdentifier()
                + " (ADI " + this.identity.adiIdentifier() + ")";
    }
}
