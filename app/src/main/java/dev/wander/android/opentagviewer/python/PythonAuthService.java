package dev.wander.android.opentagviewer.python;

import static dev.wander.android.opentagviewer.AppKeyStoreConstants.KEYSTORE_ALIAS_ACCOUNT;

import android.util.Log;

import com.chaquo.python.Kwarg;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.wander.android.opentagviewer.anisette.AnisetteSource;
import dev.wander.android.opentagviewer.anisette.LocalAnisette;
import dev.wander.android.opentagviewer.db.repo.model.AppleUserData;
import dev.wander.android.opentagviewer.util.android.AppCryptographyUtil;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PythonAuthService {
    private static final String TAG = PythonAuthService.class.getSimpleName();

    private static final String MODULE_MAIN = "main";

    /**
     * @param localAnisette produces Anisette data on this device instead of relaying the login
     *                      through a public Anisette server. May be null, and may decline at
     *                      runtime - in either case the login falls back to
     *                      {@code anisetteServerUrl}, which is what the app has always done.
     */
    public static Observable<PythonAuthResponse> pythonLogin(
            final String email, final String password, final String anisetteServerUrl,
            final AnisetteSource localAnisette) {
        return Observable.fromCallable(() -> {
            var py = Python.getInstance();
            var module = py.getModule(MODULE_MAIN);

            var returned = module.callAttr(
                    "loginSync",
                    new Kwarg("email", email),
                    new Kwarg("password", password),
                    new Kwarg("anisetteServerUrl", anisetteServerUrl),
                    new Kwarg("localAnisette", localAnisette)
            );

            var resultMap = returned.asMap();

            if (resultMap.containsKey("error")) {
                Log.e(TAG, "Failed to log in to account! (check python for errors)");
                final String errorMessage = resultMap.get("error").toString();
                // The reason decides what the user is told; the message is the detail behind it.
                // Absent from anything that raised before main.py classified failures.
                final var reason = resultMap.get("reason");
                // Present only for a terms failure, where authentication worked and the
                // exchange after it did not - so this account still holds the session that
                // agreeing needs. Null for every other reason, which is what stops it being
                // stored in a state that fails every later fetch (issues #43 and #119).
                final var pendingAccount = resultMap.get("account");
                throw new PythonAccountLoginException(
                        errorMessage,
                        reason == null ? null : reason.toString(),
                        pendingAccount);
            }

            // need to do an annoying conversion here...
            var account = resultMap.get("account");
            LOGIN_STATE loginState = LOGIN_STATE.valueOf(resultMap.get("loginState").toInt());
            List<AuthMethod> authMethods = null;

            if (resultMap.get("loginMethods") != null) {
                authMethods = toAuthMethods(resultMap.get("loginMethods"));
            }

            return new PythonAuthResponse(
                    account,
                    loginState,
                    authMethods
            );
        }).subscribeOn(Schedulers.io());
    }

    /**
     * Fetch the terms Apple is waiting on, rendered as text a person can read.
     *
     * <p>Only for the account carried by a {@link PythonAccountLoginException#REASON_TERMS}
     * failure - it holds the authenticated session, and a fresh one would mean asking for the
     * password again to reach a screen the user is already looking at.
     */
    public static Observable<List<TermsDocument>> pendingTerms(final PyObject account) {
        return Observable.fromCallable(() -> {
            var py = Python.getInstance();
            var module = py.getModule(MODULE_MAIN);

            final var answer = new JSONObject(
                    module.callAttr("pendingTerms", account).toString());

            if (!answer.optBoolean("ok", false)) {
                throw new PythonAccountLoginException(
                        answer.optString("message", "The terms of service could not be fetched."),
                        PythonAccountLoginException.REASON_TERMS);
            }

            final JSONArray documents = answer.getJSONArray("documents");
            final List<TermsDocument> found = new ArrayList<>();
            for (int i = 0; i < documents.length(); i++) {
                final JSONObject one = documents.getJSONObject(i);
                found.add(new TermsDocument(
                        one.getString("pageId"),
                        one.getString("text"),
                        one.getBoolean("canAccept")));
            }

            Log.d(TAG, "Apple is waiting on " + found.size() + " terms document(s)");

            return found;
        }).subscribeOn(Schedulers.io());
    }

    /**
     * Agree to one document, and finish signing in once it was the last one.
     *
     * <p>One at a time, and only one that was shown - the page id names a document this sign-in
     * actually fetched, and anything else is refused rather than guessed at. Recording agreement
     * to something nobody saw is the failure worth designing against here.
     */
    public static Observable<TermsAcceptance> acceptTerms(
            final PyObject account, final String pageId) {
        return Observable.fromCallable(() -> {
            var py = Python.getInstance();
            var module = py.getModule(MODULE_MAIN);

            final var answer = new JSONObject(
                    module.callAttr("acceptTerms", account, pageId).toString());

            if (!answer.optBoolean("ok", false)) {
                throw new PythonAccountLoginException(
                        answer.optString("message", "The terms of service could not be accepted."),
                        PythonAccountLoginException.REASON_TERMS);
            }

            final int remaining = answer.getInt("remaining");
            final String loginState =
                    answer.isNull("loginState") ? null : answer.optString("loginState", null);

            Log.d(TAG, "Accepted " + pageId + "; " + remaining + " left, state " + loginState);

            return new TermsAcceptance(remaining, loginState);
        }).subscribeOn(Schedulers.io());
    }

    /**
     * The ways a second factor can be delivered, out of Python's list-of-dicts.
     *
     * <p>Shared by signing in and by rescuing a session that has gone stale mid-use - the shape
     * is the same because {@code main.py} builds both with {@code _convertToJavaDictWrapper},
     * and two readers of one format is how one of them ends up not handling a new method type.
     *
     * <p>An unrecognised type still produces an {@link AuthMethod}, with {@code obj} intact, so
     * a method Apple adds later is offered rather than silently dropped from the list.
     */
    private static List<AuthMethod> toAuthMethods(final PyObject methodList) {
        final List<AuthMethod> authMethods = new ArrayList<>();
        final var methods = methodList.asList();

        for (int i = 0; i < methods.size(); ++i) {
            final var item = methods.get(i).asMap();

            final var type = TWO_FACTOR_METHOD.valueOf(item.get("type").toInt());
            final var obj = item.get("obj");

            switch (type) {
                case PHONE:
                    authMethods.add(new AuthMethodPhone(
                            type,
                            obj,
                            item.get("phoneNumber").toString(),
                            item.get("phoneNumberId").toString()
                    ));
                    break;
                case TRUSTED_DEVICE:
                case UNKNOWN:
                    authMethods.add(new AuthMethod(
                            type,
                            obj
                    ));
                    break;
            }
        }

        return authMethods;
    }

    /**
     * What this already-restored account needs before it can be used, if anything.
     *
     * <p><b>Asked when a session that was working stops.</b> Apple can move an account to
     * {@code REQUIRE_2FA} long after it was signed in, and from that moment every fetch fails
     * its state check before a request leaves the phone. That used to be permanent and silent -
     * see {@code main.py:getSecondFactorMethodsIfNeeded}.
     *
     * <p><b>Three answers, not two, and collapsing them is a bug I already made.</b> The first
     * version returned a plain list and used empty for both "nothing needed" and "nothing can
     * help" - so a session in {@code REQUIRE_2FA} that offered no way to send a code was read as
     * healthy, and the app did nothing at all. That is the original silent dead session, with
     * more code in front of it. A real one behaves this way: FindMy.py reports
     * <i>"Unexpected login state after reauth ... Please log in again"</i>, and there is nothing
     * to type.
     *
     * @return <ul>
     *   <li>{@link Optional#empty()} - nothing is needed; carry on.</li>
     *   <li>a present, <b>non-empty</b> list - ask for a code using one of these.</li>
     *   <li>a present but <b>empty</b> list - a code is needed and cannot be asked for. Only a
     *       full sign-in fixes this, so the caller signs the user out rather than showing a box
     *       that can never be filled.</li>
     * </ul>
     */
    public static Observable<Optional<List<AuthMethod>>> secondFactorMethodsIfNeeded(
            final PythonAppleAccount account) {

        return Observable.fromCallable(() -> PythonLock.holding(() -> {
            final var py = Python.getInstance();
            final var module = py.getModule(MODULE_MAIN);

            final PyObject methods = module.callAttr(
                    "getSecondFactorMethodsIfNeeded",
                    new Kwarg("account", account.getAccountObj()));

            if (methods == null) {
                return Optional.<List<AuthMethod>>empty();
            }

            return Optional.of(toAuthMethods(methods));
        })).subscribeOn(Schedulers.io());
    }

    public static Completable requestCode(AuthMethod selectedAuthMethod) {
        return Completable.fromRunnable(() -> {
            selectedAuthMethod.getObj().callAttr("request");
            // equivalent to python call:  `method.request()`
            // we actually know this to be synchronous, and to return null...
        }).subscribeOn(Schedulers.io());
    }

    public static Completable submitCode(AuthMethod selectedAuthMethod, final String authCode) {
        return Completable.fromRunnable(() -> {
           selectedAuthMethod.getObj().callAttr(
             "submit",
                   new Kwarg("code", authCode)
           );
        }).subscribeOn(Schedulers.io());
    }

    public static Observable<byte[]> retrieveAuthData(@NonNull PythonAuthResponse authResponse) {
        return Observable.fromCallable(() -> {
            var py = Python.getInstance();
            var module = py.getModule(MODULE_MAIN);

            var returned = module.callAttr(
                    "exportToString",
                    new Kwarg("account", authResponse.getAccountObj())
            );

            return returned.toString().getBytes(StandardCharsets.UTF_8);
        }).subscribeOn(Schedulers.computation());
    }

    /**
     * @param localAnisette produces Anisette on this device rather than relaying through a
     *                      public server. May be null. Restoring is the common path - without
     *                      passing this, local Anisette would only ever apply to the single
     *                      login where the account was first created.
     */
    public static Observable<PythonAppleAccount> restoreAccount(
            final AppleUserData appleUserData, final AnisetteSource localAnisette) {
        return Observable.fromCallable(() -> {
            var data = AppCryptographyUtil.AppEncryptedData.fromFlattened(appleUserData.getData());
            var account = new AppCryptographyUtil().decrypt(data, KEYSTORE_ALIAS_ACCOUNT);

            var py = Python.getInstance();
            var module = py.getModule(MODULE_MAIN);

            // FindMy 0.9.x embeds the anisette provider state inside the account JSON
            // (see AccountStateMapping.anisette), so we no longer pass a server URL here.
            var returned = module.callAttr(
                    "getAccount",
                    new Kwarg("serializedAccountData", new String(account, StandardCharsets.UTF_8)),
                    new Kwarg("localAnisette", localAnisette)
            );

            if (returned == null) {
                throw new PythonAccountLoginException("Error occurred while restoring account! Check python logs for more details");
            }

            return new PythonAppleAccount(returned);
        }).subscribeOn(Schedulers.io());
    }

    public enum LOGIN_STATE {
        LOGGED_OUT(0),
        REQUIRE_2FA(1),
        AUTHENTICATED(2),
        LOGGED_IN(3);

        private int value;

        LOGIN_STATE(int value) {
            this.value = value;
        }

        public static LOGIN_STATE valueOf(int value) {
            for (var member : LOGIN_STATE.values()) {
                if (member.value == value) return member;
            }
            throw new RuntimeException("Unable to cast value=" + value + " to " + LOGIN_STATE.class.getSimpleName());
        }
    }

    public enum TWO_FACTOR_METHOD {
        UNKNOWN(0),
        TRUSTED_DEVICE(1),
        PHONE(2);

        private int value;

        TWO_FACTOR_METHOD(int value) {
            this.value = value;
        }

        public static TWO_FACTOR_METHOD valueOf(int value) {
            for (var member : TWO_FACTOR_METHOD.values()) {
                if (member.value == value) return member;
            }
            throw new RuntimeException("Unable to cast value=" + value + " to " + TWO_FACTOR_METHOD.class.getSimpleName());
        }
    }


    @RequiredArgsConstructor
    @Getter
    public static class AuthMethod {
        private final TWO_FACTOR_METHOD type;
        private final PyObject obj;
    }

    @Getter
    public static class AuthMethodPhone extends AuthMethod {
        private final String phoneNumber;
        private final String phoneNumberId;

        public AuthMethodPhone(TWO_FACTOR_METHOD type, PyObject obj, String phoneNumber, String phoneNumberId) {
            super(type, obj);
            this.phoneNumber = phoneNumber;
            this.phoneNumberId = phoneNumberId;
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class PythonAuthResponse {
        private final PyObject accountObj;
        private final LOGIN_STATE loginState;
        private final List<AuthMethod> authMethods;
    }
}
