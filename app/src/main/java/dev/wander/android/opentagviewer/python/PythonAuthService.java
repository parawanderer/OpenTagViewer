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
                authMethods = new ArrayList<>();
                var loginMethods = resultMap.get("loginMethods").asList();
                for (int i = 0; i < loginMethods.size(); ++i) {
                    // convert them
                    var item = loginMethods.get(i).asMap();

                    var type = TWO_FACTOR_METHOD.valueOf(item.get("type").toInt());
                    var obj = item.get("obj");

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
