package dev.wander.android.opentagviewer.python;

import static dev.wander.android.opentagviewer.AppKeyStoreConstants.KEYSTORE_ALIAS_ACCOUNT;

import android.util.Log;

import com.chaquo.python.Kwarg;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    public static Observable<PythonAuthResponse> pythonLogin(final String email, final String password, final String anisetteServerUrl) {
        return Observable.fromCallable(() -> {
            var py = Python.getInstance();
            var module = py.getModule(MODULE_MAIN);

            var returned = module.callAttr(
                    "loginSync",
                    new Kwarg("email", email),
                    new Kwarg("password", password),
                    new Kwarg("anisetteServerUrl", anisetteServerUrl)
            );

            var resultMap = returned.asMap();

            if (resultMap.containsKey("error")) {
                Log.e(TAG, "Failed to log in to account! (check python for errors)");
                final String errorMessage = resultMap.get("error").toString();
                throw new PythonAccountLoginException(errorMessage);
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

    public static Observable<PythonAppleAccount> restoreAccount(final AppleUserData appleUserData, final String anisetteServerUrl) {
        return Observable.fromCallable(() -> {
            var data = AppCryptographyUtil.AppEncryptedData.fromFlattened(appleUserData.getData());
            var account = new AppCryptographyUtil().decrypt(data, KEYSTORE_ALIAS_ACCOUNT);

            var py = Python.getInstance();
            var module = py.getModule(MODULE_MAIN);

            var returned = module.callAttr(
                    "getAccount",
                    new Kwarg("serializedAccountData", new String(account, StandardCharsets.UTF_8)),
                    new Kwarg("anisetteServerUrl", anisetteServerUrl)
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
