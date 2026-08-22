package dev.wander.android.opentagviewer.db.repo.model;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class UserSettings {
    private Boolean useDarkTheme;

    /**
     * Whether to colour the app from the user's wallpaper (Material You) instead of the
     * app's own palette.
     *
     * <p><b>Null is meaningful and must not be defaulted away</b>, for the same reason it is
     * on {@link #anisetteMode}: "nobody has decided yet" is not "decided no". The two cases it
     * separates want opposite answers. Somebody installing for the first time should get a app
     * that matches their phone - the first thing they see is the login screen, which they
     * cannot reach Settings from, so a default of off means their first impression never
     * matches. Somebody updating should get exactly what they had yesterday, because an
     * unannounced restyle on update is its own kind of bug.
     *
     * <p>So the decision belongs where the context is known - whether this install has ever
     * been updated - rather than in a getter. See
     * {@code OpenAirTagApplication.setupSystemColors()}, which resolves it once and stores the
     * answer, after which this is a plain boolean.
     *
     * <p>Only has an effect on Android 12+, where the system exposes wallpaper-derived
     * colours. Below that the setting is hidden rather than shown and ignored.
     */
    private Boolean useSystemColors;
    private String anisetteServerUrl;
    private String language;
    private Boolean enableDebugData;
    private String mapProvider; // "google" or "amap"

    /**
     * The user's own AMap (高德地图) API key.
     * <br>
     * Not shipped with the app: AMap keys are issued per developer account, bound to a
     * package name and signing fingerprint, and their terms expect the key holder to be
     * the app's operator. So anyone wanting AMap supplies their own, the same way the
     * Anisette server URL works.
     */
    private String amapApiKey;

    /**
     * Where Anisette data comes from: {@link #ANISETTE_LOCAL}, {@link #ANISETTE_REMOTE}, or
     * null meaning nobody has decided yet.
     *
     * <p><b>Null is meaningful and must not be defaulted away.</b> A session is bound to the
     * machine identity that established it, and local and remote Anisette present different
     * ones, so switching requires signing in again. Someone updating from an earlier version
     * has a session established against their server and nothing stored here; reading that as
     * "local" would put every existing user through a 2FA cycle on update, for no reason they
     * could see.
     *
     * <p>So the decision belongs where the context is known, not in a getter - see
     * {@link #resolveAnisetteMode(boolean)}. New sessions get local, existing ones keep what
     * already works, and {@link #anisetteUpgradeOffered} covers inviting the latter to switch.
     */
    private String anisetteMode;

    /**
     * Whether the one-time offer to move an existing login to local Anisette has been made.
     *
     * <p>Existing users are deliberately left on their server so an update does not break
     * their session. But local is the better position - nothing leaves the device, and no
     * third party can take the app down - so it is worth asking once, at the moment someone
     * can see what they would gain. Once is the operative word: a prompt that returns is a
     * prompt people learn to dismiss without reading.
     */
    private Boolean anisetteUpgradeOffered;

    /**
     * An Apple Music APK the user supplied themselves, if any.
     *
     * <p>The app normally fetches Apple's libraries from Apple's own CDN, which serves exactly
     * one build. When Apple replaces it, the checked-in symbol lists may no longer describe it
     * and local Anisette stops working until the app is updated. Rather than leave people
     * stuck, they can point at a copy of a known-good build obtained elsewhere. It is verified
     * against the same recorded hashes either way, so an untrusted source cannot introduce
     * anything the app would not already have accepted from Apple.
     */
    private String anisetteApkUri;

    /**
     * Whether the owner's own Apple devices are shown alongside their tags, and searched for.
     *
     * <p><b>Off unless somebody turns it on, and that is not a taste decision.</b> An iPhone,
     * iPad or Mac is in the Find My zone and carries key material, so the app can locate one -
     * but only through the crowd-sourced network, the same way it finds an AirTag. Apple's own
     * app does not do that: a device reports its position to iCloud directly over its own
     * network connection, which is a service this app does not speak. So a device shown here
     * updates when some stranger's iPhone happens to walk past it, which for something the
     * owner is carrying is rare and arbitrary.
     *
     * <p>Shown by default, that produces exactly one bug report, over and over: <i>my iPad is
     * in the list and never moves, but the real Find My app has it - your app is broken</i>.
     * It is not broken, it is incomplete, and the difference is invisible from the outside.
     * Leaving these out by default means the app only shows what it can actually keep up to
     * date, and the setting says plainly what turning them on gets you.
     *
     * <p>Null reads as off. See {@link #shouldShowAppleDevices()}.
     */
    private Boolean showAppleDevices;

    public static final String ANISETTE_LOCAL = "local";
    public static final String ANISETTE_REMOTE = "remote";

    public boolean hasDarkThemeEnabled() {
        return this.useDarkTheme == Boolean.TRUE;
    }

    /** Whether anybody has chosen yet. False for anyone updating from an earlier version. */
    public boolean hasChosenAnisetteMode() {
        return ANISETTE_LOCAL.equals(this.anisetteMode)
                || ANISETTE_REMOTE.equals(this.anisetteMode);
    }

    /**
     * What to use, deciding for anyone who has not chosen.
     *
     * @param hasExistingSession whether there is already a signed-in account. If there is, an
     *                           unchosen mode resolves to remote: that session is bound to the
     *                           machine identity of whatever produced its Anisette, and moving
     *                           it would force a re-login on update. New sessions get local.
     */
    public String resolveAnisetteMode(boolean hasExistingSession) {
        if (this.hasChosenAnisetteMode()) {
            return this.anisetteMode;
        }
        return hasExistingSession ? ANISETTE_REMOTE : ANISETTE_LOCAL;
    }

    /** What is stored, or null if unchosen. Prefer {@link #resolveAnisetteMode(boolean)}. */
    public String getAnisetteMode() {
        return this.hasChosenAnisetteMode() ? this.anisetteMode : null;
    }

    public boolean usesLocalAnisette(boolean hasExistingSession) {
        return ANISETTE_LOCAL.equals(this.resolveAnisetteMode(hasExistingSession));
    }

    /**
     * Whether to offer moving this login to local Anisette.
     *
     * <p>Only for someone already signed in, still on a server, who has not been asked before
     * and has not chosen for themselves. Anyone who deliberately picked remote is not asked
     * again - they answered the question by choosing.
     */
    public boolean shouldOfferLocalAnisette(boolean hasExistingSession) {
        return hasExistingSession
                && !this.hasChosenAnisetteMode()
                && this.anisetteUpgradeOffered != Boolean.TRUE;
    }

    public boolean hasOwnAnisetteApk() {
        return this.anisetteApkUri != null && !this.anisetteApkUri.isBlank();
    }

    /**
     * The selected map provider, defaulting to Google Maps.
     */
    public String getMapProvider() {
        return mapProvider != null && !mapProvider.isEmpty() ? mapProvider : "google";
    }

    public boolean hasAmapApiKey() {
        return this.amapApiKey != null && !this.amapApiKey.isBlank();
    }

    /**
     * Whether to show and search for the owner's own Apple devices - see
     * {@link #showAppleDevices}. Null means nobody has turned it on, which is off.
     */
    public boolean shouldShowAppleDevices() {
        return this.showAppleDevices == Boolean.TRUE;
    }
}
