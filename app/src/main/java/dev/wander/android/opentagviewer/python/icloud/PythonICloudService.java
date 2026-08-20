package dev.wander.android.opentagviewer.python.icloud;

import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import dev.wander.android.opentagviewer.python.PythonAppleAccount;
import dev.wander.android.opentagviewer.python.PythonLock;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * {@link ICloudService} over {@code icloud_bridge}, the Python module that drives
 * {@code exporter.icloud}.
 *
 * <p>Thin on purpose. The flow, the retry policy and every decision about what a failure means
 * live on the Python side, where the library is; this translates JSON into types and reasons into
 * an enum, and does nothing else. A second implementation of the flow in Java would be a second
 * thing to get wrong.
 */
public class PythonICloudService implements ICloudService {
    private static final String TAG = PythonICloudService.class.getSimpleName();

    private static final String MODULE = "icloud_bridge";

    private final PyObject session;

    private PythonICloudService(final PyObject session) {
        this.session = session;
    }

    /**
     * Start a session on the account the app is already signed in with.
     *
     * <p>Returns null when FindMy.py's account internals have moved and the flow cannot be driven
     * - which the caller should treat exactly as an expired session, by sending the user to sign
     * in again. A null here is not a crash and must not become one.
     */
    public static PythonICloudService openFor(final PythonAppleAccount account) {
        try {
            final PyObject made = Python.getInstance().getModule(MODULE)
                    .callAttr("openSession", account.getAccountObj());

            // **Null is the whole check.** Chaquopy hands a Python `None` back as a Java null,
            // so this is already the answer - and the belt-and-braces version of it,
            // `made.toJava(Object.class)`, is not redundant but fatal: Chaquopy cannot convert an
            // arbitrary Python object to java.lang.Object and throws ClassCastException, which
            // happens on the path where a session was successfully created. That killed the real
            // iCloud flow on every device while every test went on passing, because the tests
            // replace this class with a fake and nothing else calls it.
            if (made == null) {
                Log.e(TAG, "icloud_bridge.openSession returned nothing; the iCloud flow is"
                        + " unavailable on this account");
                return null;
            }

            return new PythonICloudService(made);
        } catch (Exception e) {
            Log.e(TAG, "Could not start an iCloud session", e);
            return null;
        }
    }

    @Override
    public Completable open() {
        return Completable.fromAction(() -> answered("open"))
                .subscribeOn(Schedulers.io());
    }

    @Override
    public Observable<List<RecoverableDevice>> recoveryOptions() {
        return Observable.fromCallable(() -> {
            final JSONArray devices = answered("recoveryOptions").getJSONArray("devices");

            final List<RecoverableDevice> found = new ArrayList<>();
            for (int i = 0; i < devices.length(); i++) {
                final JSONObject device = devices.getJSONObject(i);
                found.add(new RecoverableDevice(
                        device.getString("serial"),
                        device.getString("description"),
                        device.optString("name", ""),
                        device.optString("model", ""),
                        device.optString("modelClass", ""),
                        // Absent rather than zero would be a date in 1970 on the tile.
                        device.isNull("escrowedAtMs") ? 0L : device.optLong("escrowedAtMs", 0L)));
            }

            return found;
        }).subscribeOn(Schedulers.io());
    }

    @Override
    public Completable unlock(final String serial, final String passcode) {
        return Completable.fromAction(() -> answered("unlock", serial, passcode))
                .subscribeOn(Schedulers.io());
    }

    @Override
    public Observable<KeychainMembership> join(final String escrowPasscode) {
        return Observable.fromCallable(() -> {
            final JSONObject answer = answered("join", escrowPasscode);

            return new KeychainMembership(
                    // Stored as the bytes Python produced. This app has no business parsing a
                    // circle member's keys; it keeps what it was given and hands the same back.
                    answer.getJSONObject("peer").toString(),
                    answer.getString("entropy"),
                    escrowPasscode,
                    answer.optString("label", ""),
                    answer.optInt("shares", 0));
        }).subscribeOn(Schedulers.io());
    }

    @Override
    public Completable resume(final String peerJson) {
        return Completable.fromAction(() -> answered("resume", peerJson))
                .subscribeOn(Schedulers.io());
    }

    @Override
    public Completable rename(final String beaconId, final String plistXml,
                              final String name, final String emoji) {
        return Completable.fromAction(
                        () -> answered("rename", beaconId, plistXml, name, emoji))
                .subscribeOn(Schedulers.io());
    }

    @Override
    public Observable<ICloudFetch> fetch() {
        return Observable.fromCallable(() -> {
            final JSONObject answer = answered("fetch");

            final JSONArray found = answer.getJSONArray("accessories");
            final List<ICloudAccessory> accessories = new ArrayList<>();
            for (int i = 0; i < found.length(); i++) {
                final JSONObject one = found.getJSONObject(i);
                accessories.add(new ICloudAccessory(
                        one.getString("beaconId"),
                        // Null rather than the string "null", which is what optString gives for
                        // a JSON null and would reach the screen as a tag called null.
                        one.isNull("name") ? null : one.getString("name"),
                        one.isNull("emoji") ? null : one.getString("emoji"),
                        one.getString("label"),
                        one.getString("details"),
                        one.getBoolean("hasAlignment"),
                        one.getBoolean("hasName")));
            }

            final JSONArray setAside = answer.getJSONArray("skipped");
            final List<ICloudFetch.SkippedAccessory> skipped = new ArrayList<>();
            for (int i = 0; i < setAside.length(); i++) {
                final JSONObject one = setAside.getJSONObject(i);
                skipped.add(new ICloudFetch.SkippedAccessory(
                        one.getString("beaconId"), one.getString("reason")));
            }

            return new ICloudFetch(accessories, skipped);
        }).subscribeOn(Schedulers.io());
    }

    @Override
    public Observable<List<AccessoryRecords>> records(final List<String> beaconIds) {
        return Observable.fromCallable(() -> {
            final JSONArray selection = new JSONArray();
            for (final String beaconId : beaconIds) {
                selection.put(new JSONObject().put("beaconId", beaconId));
            }

            final JSONArray taken =
                    answered("records", selection.toString()).getJSONArray("accessories");

            final List<AccessoryRecords> records = new ArrayList<>();
            for (int i = 0; i < taken.length(); i++) {
                final JSONObject one = taken.getJSONObject(i);
                records.add(new AccessoryRecords(
                        one.getString("beaconId"),
                        one.getString("ownedBeaconPlist"),
                        // Null where nothing ever named it, which the importer handles.
                        one.isNull("namingRecordPlist") ? null : one.getString("namingRecordPlist"),
                        one.isNull("keyAlignmentPlist") ? null : one.getString("keyAlignmentPlist")));
            }

            return records;
        }).subscribeOn(Schedulers.io());
    }

    @Override
    public void close() {
        try {
            PythonLock.holding(() -> {
                this.session.callAttr("close");
                return null;
            });
        } catch (Exception e) {
            // Callers close from a `finally`. Throwing here would replace whatever real failure
            // sent them there with a confusing one about closing.
            Log.w(TAG, "Closing the iCloud session failed", e);
        }
    }

    /**
     * Make one call into the bridge and return its answer, or throw what it reported.
     *
     * <p>Two things happen here and both matter.
     *
     * <p><b>The Python lock is taken for the call and given straight back.</b> The iCloud flow and
     * the app's periodic location refresh drive the same asyncio event loop, and two threads in
     * {@code run_until_complete} on one loop fails permanently. Scoping it to the call rather
     * than the flow is what keeps a passcode dialog from freezing every location update in the
     * app until the user types.
     *
     * <p><b>A reported failure becomes an exception; a raised one is not expected.</b> The bridge
     * returns failures as values precisely so their message is never empty, so anything actually
     * thrown here is a bug rather than a user-facing condition - and is reported as
     * {@link ICloudFailure#UNKNOWN} with whatever text it had.
     */
    private JSONObject answered(final String step, final Object... arguments) throws Exception {
        final String returned = PythonLock.holding(
                () -> this.session.callAttr(step, arguments).toString());

        final JSONObject answer = new JSONObject(returned);

        if (!answer.optBoolean("ok", false)) {
            final ICloudFailure failure = ICloudFailure.fromWire(answer.optString("reason", null));
            final String detail = answer.optString("message", "");

            Log.w(TAG, "icloud_bridge." + step + " reported " + failure + ": " + detail);

            throw new ICloudException(failure, detail);
        }

        return answer;
    }
}
