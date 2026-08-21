package dev.wander.android.opentagviewer.python.icloud;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;

/**
 * An iCloud that behaves however a test needs it to.
 *
 * <p><b>Every state worth getting right needs an Apple account nobody can arrange.</b> An
 * account with no device to recover from, a keychain service having a bad afternoon, a passcode
 * refused three times - none of those can be produced on demand, and a working account will
 * never be in any of them. So the screen driving them could only ever be checked by reasoning
 * about it, which is how a screen ends up telling somebody with a perfectly good account that
 * they permanently own no tags.
 *
 * <p>Records what it was asked, because several of the mistakes available here are about the
 * screen calling the wrong thing rather than drawing the wrong thing: unlocking with a device
 * the user did not pick, or spending an attempt on an empty passcode.
 */
public final class FakeICloudService implements ICloudService {

    /** A named iPhone: the ordinary case, where the user renamed their phone. */
    public static final RecoverableDevice AN_IPHONE = new RecoverableDevice(
            "F2LX9Q", "Shane’s iPhone, iPhone 15, serial F2LX9Q, escrowed 2024-03-12",
            "Shane’s iPhone", "iPhone15,2", "iPhone", 1710201600000L);

    /** A named Mac, so the icon has something to be wrong about. */
    public static final RecoverableDevice A_MAC = new RecoverableDevice(
            "C02XK", "Work MacBook, MacBook Pro, serial C02XK, escrowed 2023-11-02",
            "Work MacBook", "MacBookPro18,3", "Mac", 1698883200000L);

    /**
     * A device nobody ever renamed, which is the case the tile has to not embarrass itself on.
     *
     * <p>FindMy.py falls back to the literal "unnamed device"; the screen should say "iPad".
     */
    public static final RecoverableDevice AN_UNNAMED_IPAD = new RecoverableDevice(
            "DMPX2", "unnamed device, iPad Pro, serial DMPX2, escrowed 2022-06-01",
            "", "iPad13,4", "iPad", 1654041600000L);

    public static final ICloudAccessory A_BIKE = new ICloudAccessory(
            "F1C4A0E2-1111-4222-8333-444455556666", "Bike", "🚲", "🚲 Bike",
            "AirTag, serial HXXXXXXXXXXX, paired 2024-03-01", true, true);
    public static final ICloudAccessory A_NAMELESS_ONE = new ICloudAccessory(
            "0A0B0C0D-2222-4333-8444-555566667777", null, null, "unnamed",
            "AirTag, serial HYYYYYYYYYYY, paired 2023-11-14", false, false);

    private List<RecoverableDevice> devices = List.of(AN_IPHONE, A_MAC);
    private List<ICloudAccessory> accessories = List.of(A_BIKE, A_NAMELESS_ONE);
    private List<ICloudFetch.SkippedAccessory> skipped = List.of();

    private ICloudException openFailsWith;
    private ICloudException optionsFailsWith;
    private ICloudException unlockFailsWith;
    private ICloudException fetchFailsWith;
    private ICloudException joinFailsWith;
    private ICloudException resumeFailsWith;
    private String joinedWithPasscode;
    private String resumedWith;

    /** How many times a passcode is refused before it starts being accepted. */
    private int refusalsBeforeAccepting = 0;
    private long answerDelayMs = 0;

    private final List<String> calls = new ArrayList<>();
    private final List<String> unlockedWith = new ArrayList<>();
    private String lastPasscode;
    private boolean closed;

    /** The ordinary case: two devices to choose from and two tags on the account. */
    public static FakeICloudService withTags() {
        return new FakeICloudService();
    }

    /**
     * An account with nothing that can unlock its keychain.
     *
     * <p>The real class of user this whole flow has to answer for: an Apple ID that has never
     * had an iPhone, iPad or Mac on it has never escrowed a keychain, and no amount of retrying
     * will change that.
     */
    public static FakeICloudService withNothingToRecoverFrom() {
        final FakeICloudService fake = new FakeICloudService();
        fake.optionsFailsWith = new ICloudException(
                ICloudFailure.NOTHING_TO_RECOVER_FROM,
                "No record on this account can currently be recovered from.");
        return fake;
    }

    /** Nothing reported usable at all, which reads as a service having a bad day. */
    public static FakeICloudService whereTheServiceIsUnsure() {
        final FakeICloudService fake = new FakeICloudService();
        fake.optionsFailsWith = new ICloudException(
                ICloudFailure.SERVICE_UNSURE,
                "Nothing was reported usable at all.");
        return fake;
    }

    /** An account with a Mac on it and no tags - the empty fetch, one step later. */
    public static FakeICloudService withNoTagsOnTheAccount() {
        final FakeICloudService fake = new FakeICloudService();
        fake.accessories = List.of();
        return fake;
    }

    /** The passcode is refused this many times, then accepted. */
    public FakeICloudService refusingThePasscode(final int times) {
        this.refusalsBeforeAccepting = times;
        return this;
    }

    /**
     * An account with a great many devices, to push the list past the height of the screen.
     *
     * <p>The people most likely to have this are the ones most likely to use the app: somebody
     * with years of Apple hardware has an escrow record for every one of it, and escrow records
     * outlive the devices that made them.
     */
    public static FakeICloudService withManyDevices(final int count) {
        final FakeICloudService fake = new FakeICloudService();
        final List<RecoverableDevice> many = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            many.add(new RecoverableDevice(
                    "SERIAL" + i, "Device " + i + ", serial SERIAL" + i,
                    "Device " + i, "iPhone15,2", "iPhone", 1710201600000L));
        }
        fake.devices = many;
        return fake;
    }

    /** Only one device can be recovered from, so there is nothing to choose between. */
    public FakeICloudService withOneDevice() {
        this.devices = List.of(AN_IPHONE);
        return this;
    }

    /** Some records were set aside for being the account's own hardware. */
    public FakeICloudService alsoSkipping(final String... names) {
        final List<ICloudFetch.SkippedAccessory> setAside = new ArrayList<>();
        for (final String name : names) {
            setAside.add(new ICloudFetch.SkippedAccessory(
                    name, "no private key, so it is a device rather than a tag"));
        }
        this.skipped = setAside;
        return this;
    }

    public FakeICloudService whereFetchingFails(final ICloudException failure) {
        this.fetchFailsWith = failure;
        return this;
    }

    @Override
    public Completable open() {
        this.calls.add("open");
        return this.openFailsWith == null
                ? Completable.complete() : Completable.error(this.openFailsWith);
    }

    @Override
    public Observable<List<RecoverableDevice>> recoveryOptions() {
        this.calls.add("recoveryOptions");
        if (this.optionsFailsWith != null) {
            return Observable.error(this.optionsFailsWith);
        }

        final Observable<List<RecoverableDevice>> answer = Observable.just(this.devices);

        // A real account takes a moment. Everything else here is instant, which is right for a
        // test and useless for looking at the screen somebody sees while they wait.
        return this.answerDelayMs > 0
                ? answer.delay(this.answerDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                : answer;
    }

    /** Take this long to answer, so the waiting screen can be seen or captured. */
    public FakeICloudService takingItsTime(final long millis) {
        this.answerDelayMs = millis;
        return this;
    }

    @Override
    public Completable unlock(final String serial, final String passcode) {
        this.calls.add("unlock");
        this.unlockedWith.add(serial);
        this.lastPasscode = passcode;

        if (this.unlockFailsWith != null) {
            return Completable.error(this.unlockFailsWith);
        }

        if (this.timesCalled("unlock") <= this.refusalsBeforeAccepting) {
            return Completable.error(new ICloudException(
                    ICloudFailure.PASSCODE_REJECTED,
                    "That was not accepted. Worth trying the same passcode again."));
        }

        return Completable.complete();
    }

    @Override
    public Observable<KeychainMembership> join(final String escrowPasscode) {
        this.calls.add("join");
        this.joinedWithPasscode = escrowPasscode;

        if (this.joinFailsWith != null) {
            return Observable.error(this.joinFailsWith);
        }

        return Observable.just(new KeychainMembership(
                "{\"peer_id\":\"peer-ours\"}", "ZW50cm9weQ==", escrowPasscode, "a-label", 2));
    }

    @Override
    public Completable resume(final String peerJson) {
        this.calls.add("resume");
        this.resumedWith = peerJson;

        return this.resumeFailsWith == null
                ? Completable.complete() : Completable.error(this.resumeFailsWith);
    }

    @Override
    public Completable rename(final String beaconId, final String plistXml,
                              final String name, final String emoji) {
        this.calls.add("rename");
        this.renamedWith = new String[] {beaconId, name, emoji};
        this.renamedPlist = plistXml;

        return this.renameFailsWith == null
                ? Completable.complete() : Completable.error(this.renameFailsWith);
    }

    /** What the last rename was asked to change: beacon id, name, emoji. Null if never called. */
    public String[] renamedWith() {
        return this.renamedWith;
    }

    /**
     * The record the rename was judged from.
     *
     * <p>Worth asserting on rather than ignoring: Python decides accessory-or-device from this
     * plist, so a screen that sends the wrong one - or an empty one - would have Python answering
     * about a different tag entirely, and the rename would still look like it worked.
     */
    public String renamedPlist() {
        return this.renamedPlist;
    }

    /** The account refuses the write - the network is down, or the session lost its keys. */
    public FakeICloudService whereRenamingFails(final ICloudFailure failure) {
        this.renameFailsWith = new ICloudException(failure, "the fake was told to refuse");
        return this;
    }

    /** The stored membership has stopped working - the peer was removed from the account. */
    public FakeICloudService whereTheMembershipNoLongerWorks() {
        this.resumeFailsWith = new ICloudException(
                ICloudFailure.MEMBERSHIP_UNUSABLE, "no such peer");
        return this;
    }

    public FakeICloudService whereJoiningFails(final ICloudException failure) {
        this.joinFailsWith = failure;
        return this;
    }

    /** The passcode the app generated for its own record, so a test can check it was a real one. */
    public String joinedWithPasscode() {
        return this.joinedWithPasscode;
    }

    public String resumedWith() {
        return this.resumedWith;
    }

    @Override
    public Observable<ICloudFetch> fetch() {
        this.calls.add("fetch");
        return this.fetchFailsWith == null
                ? Observable.just(new ICloudFetch(this.accessories, this.skipped))
                : Observable.error(this.fetchFailsWith);
    }


    /**
     * A record the real converter can actually convert.
     *
     * <p><b>It used to be the string {@code "<plist/>"}</b>, which is not a small shortcut: the
     * app hands whatever comes back to Python's {@code convertPlistToJson}, and an empty document
     * fails there with {@code 'NoneType' object is not subscriptable}. The failure is swallowed by
     * design - a tag whose accessory JSON is missing is backfilled on first fetch - so every test
     * that "imported" a tag from the account was quietly writing rows with no accessory state at
     * all, and nothing downstream of that conversion was being exercised by anything.
     *
     * <p>The key material is the committed fixture's, so it is real in shape - a 28-byte master
     * key and two 32-byte shared secrets, which is what {@code FindMyAccessory.from_plist}
     * reaches for - and secret in no sense whatsoever.
     */
    public static final String AN_OWNED_BEACON_PLIST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<plist version=\"1.0\"><dict>"
                    + "<key>batteryLevel</key><integer>1</integer>"
                    + "<key>identifier</key><string>F612A183-492B-45A8-A5A2-233CA9062A94</string>"
                    + "<key>model</key><string></string>"
                    + "<key>pairingDate</key><date>2025-02-27T20:03:32Z</date>"
                    + "<key>privateKey</key><dict><key>key</key><dict><key>data</key><data>"
                    + "J1AAk7qStLSbMhZT/XEve6by7hI0H7CslD/Oh7SrOc+mlmLnAO8c"
                    + "5FGnhi/s3TDlWNiL3SMy19NQuCWg6oTS+YfBZN79RiUmZtssTp9f"
                    + "UvZjmqMX3g=="
                    + "</data></dict></dict>"
                    + "<key>productId</key><integer>21760</integer>"
                    + "<key>publicKey</key><dict><key>key</key><dict><key>data</key><data>"
                    + "k6fWaOxFGbClYV6tu/ZK4vXdyWl2joSbJhbzu12Pfmf5p09w5LxKIvnABRfysSFkOAlo/F3Ii9Dq"
                    + "</data></dict></dict>"
                    + "<key>secondarySharedSecret</key><dict><key>key</key><dict><key>data</key>"
                    + "<data>1pWMT+FI3flAWmgbUEW5H6omZy+yZOzp30zZGxEa2A8=</data></dict></dict>"
                    + "<key>sharedSecret</key><dict><key>key</key><dict><key>data</key>"
                    + "<data>vM2ZjU/sKW/novHcwzTlY5xwGLOUOZjpgcZa9cNx2Y8=</data></dict></dict>"
                    + "<key>stableIdentifier</key><array>"
                    + "<string>2001~#001234a12345aaac~#A02BCDEFG1AB</string></array>"
                    + "<key>systemVersion</key><string>2.0.73</string>"
                    + "<key>vendorId</key><integer>76</integer>"
                    + "</dict></plist>";

    /** Named after whatever the screen already calls it, so a list has readable rows. */
    private static String namingRecordFor(final String beaconId) {
        final String name = A_BIKE.getBeaconId().equals(beaconId) ? A_BIKE.getName() : "Keys";

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>"
                + "<key>identifier</key><string>" + beaconId + "</string>"
                + "<key>associatedBeacon</key><string>" + beaconId + "</string>"
                + "<key>name</key><string>" + name + "</string>"
                + "</dict></plist>";
    }

    @Override
    public Observable<List<AccessoryRecords>> records(final List<String> beaconIds) {
        this.calls.add("records");

        final List<AccessoryRecords> taken = new ArrayList<>();
        for (final String beaconId : beaconIds) {
            taken.add(new AccessoryRecords(
                    beaconId, AN_OWNED_BEACON_PLIST, namingRecordFor(beaconId), null));
        }

        return Observable.just(taken);
    }

    @Override
    public void close() {
        this.calls.add("close");
        this.closed = true;
    }

    public List<String> calls() {
        return this.calls;
    }

    private String[] renamedWith;

    private String renamedPlist;

    private ICloudException renameFailsWith;

    public long timesCalled(final String call) {
        return this.calls.stream().filter(call::equals).count();
    }

    /** Which devices' passcodes were offered, in order. */
    public List<String> unlockedWith() {
        return this.unlockedWith;
    }

    public String lastPasscode() {
        return this.lastPasscode;
    }

    public boolean wasClosed() {
        return this.closed;
    }
}
