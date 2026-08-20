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

    public static final RecoverableDevice AN_IPHONE =
            new RecoverableDevice("F2LX9Q", "iPhone 15, serial F2LX9Q, last used 2 days ago");
    public static final RecoverableDevice A_MAC =
            new RecoverableDevice("C02XK", "MacBook Pro, serial C02XK, last used 3 months ago");

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

    /** How many times a passcode is refused before it starts being accepted. */
    private int refusalsBeforeAccepting = 0;

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
        return this.optionsFailsWith == null
                ? Observable.just(this.devices) : Observable.error(this.optionsFailsWith);
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
    public Observable<ICloudFetch> fetch() {
        this.calls.add("fetch");
        return this.fetchFailsWith == null
                ? Observable.just(new ICloudFetch(this.accessories, this.skipped))
                : Observable.error(this.fetchFailsWith);
    }

    @Override
    public Observable<List<AccessoryRecords>> records(final List<String> beaconIds) {
        this.calls.add("records");

        final List<AccessoryRecords> taken = new ArrayList<>();
        for (final String beaconId : beaconIds) {
            taken.add(new AccessoryRecords(beaconId, "<plist/>", "<plist/>", null));
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
