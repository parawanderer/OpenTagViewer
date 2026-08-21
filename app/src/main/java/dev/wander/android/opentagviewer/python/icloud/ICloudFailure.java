package dev.wander.android.opentagviewer.python.icloud;

/**
 * Why a step of the iCloud flow did not work, in the terms the screen has to distinguish.
 *
 * <p><b>A closed set, not the exception text.</b> The bridge reports a {@code reason} string
 * precisely so the wording lives in {@code strings.xml} and can be translated - branching on a
 * message would put English in the code and break the moment the message was reworded. The
 * detail is carried alongside for the log and for the cases where there is genuinely nothing
 * better to show.
 */
public enum ICloudFailure {
    /**
     * The account is not in a state that can talk to iCloud, or the client is not open.
     *
     * <p>Recovered from the same way an expired session is: send the user to sign in again.
     */
    NOT_SIGNED_IN,

    /**
     * <b>This account has nothing that can unlock its keychain, and never will.</b>
     *
     * <p>The expected answer for a real class of user rather than an error: an Apple ID that has
     * never had an iPhone, iPad or Mac on it has never escrowed a keychain. It is also, in
     * practice, the same person as "this account owns no tags" - only an iPhone or iPad can
     * register one - so the flow stops here rather than at the fetch, before asking for a
     * passcode they do not have.
     *
     * <p>The answer for them is the import path. Telling them to try again later is a lie that
     * costs them an evening.
     */
    NOTHING_TO_RECOVER_FROM,

    /**
     * Nothing was reported usable <i>at all</i>, which reads as a service having a bad day.
     *
     * <p><b>Must not be shown as {@link #NOTHING_TO_RECOVER_FROM}.</b> The advice is the opposite
     * one: this is worth trying again later and that never will be. Collapsing the two tells
     * somebody with a perfectly good account that they permanently own no tags, and sends them
     * off to find a friend with a Mac.
     */
    SERVICE_UNSURE,

    /**
     * The escrow service did not accept the passcode.
     *
     * <p><b>Not proof it was wrong.</b> FindMy.py's own first advice is to try the same passcode
     * again, because the exchange has been seen to fail intermittently and then succeed. Copy
     * that says "incorrect passcode" is stating something this app does not know.
     */
    PASSCODE_REJECTED,

    /** A device was chosen that this session never listed. A bug in the screen, not the user. */
    NO_SUCH_RECORD,

    /** A join was asked for before anything unlocked, so no peer could sponsor it. A bug here. */
    NOT_UNLOCKED,

    /**
     * The stored membership no longer reads the keychain.
     *
     * <p><b>Not a broken app, and not a retry.</b> The peer may simply have been removed from the
     * account - which is how somebody revokes this app - so the way forward is to ask for a
     * device passcode and join again, not to try the same stored keys a second time.
     */
    MEMBERSHIP_UNUSABLE,

    /** An accessory was asked for that this session never fetched. Also a bug in the screen. */
    NO_SUCH_ACCESSORY,

    /**
     * A rename was asked for on one of the owner's own devices.
     *
     * <p>An iPhone, iPad or Mac takes its name from more places than the naming record, so
     * writing that one would leave Find My disagreeing with the device itself. The app nicknames
     * those locally instead and goes on showing the real name beside the nickname.
     *
     * <p>Reaching here means the screen offered a write it should not have - the two sides
     * decide from the same {@code is_own_device}, so it is a disagreement worth logging rather
     * than a state a user can create.
     */
    NOT_AN_ACCESSORY,

    /** Anything else. The detail carries what there is to say. */
    UNKNOWN;

    /**
     * Map the bridge's wire value, defaulting to {@link #UNKNOWN} rather than throwing.
     *
     * <p>A reason added in Python and not yet known here must degrade to "something went wrong,
     * here is what it said" - which is a poor screen but a working one. Throwing would turn a
     * new failure mode into a crash on the screen reporting failures.
     */
    public static ICloudFailure fromWire(final String reason) {
        if (reason == null) {
            return UNKNOWN;
        }

        switch (reason) {
            case "not_signed_in": return NOT_SIGNED_IN;
            case "nothing_to_recover_from": return NOTHING_TO_RECOVER_FROM;
            case "service_unsure": return SERVICE_UNSURE;
            case "passcode_rejected": return PASSCODE_REJECTED;
            case "no_such_record": return NO_SUCH_RECORD;
            case "not_unlocked": return NOT_UNLOCKED;
            case "membership_unusable": return MEMBERSHIP_UNUSABLE;
            case "no_such_accessory": return NO_SUCH_ACCESSORY;
            case "not_an_accessory": return NOT_AN_ACCESSORY;
            default: return UNKNOWN;
        }
    }
}
