package dev.wander.android.opentagviewer.viewmodel;

import android.view.View;

import java.util.HashMap;
import java.util.Map;

import dev.wander.android.opentagviewer.db.repo.model.UserSettings;
import dev.wander.android.opentagviewer.python.PythonAuthService;
import lombok.Data;

@Data
public class LoginActivityState {
    private PAGE currentPage = PAGE.NONE;
    private UserSettings userSettings = null;

    // account login "page"
    private boolean isLoggingIn = false;
    private boolean isValidEmailOrPhone = false;
    private boolean isValidPassword = false;

    private PythonAuthService.PythonAuthResponse authResponse = null;

    // 2FA choice "page"
    private final Map<View, PythonAuthService.AuthMethodPhone> sms2FAButtonToAuthMethod = new HashMap<>();
    private PythonAuthService.AuthMethod chosenAuthMethod = null;


    // 2FA entry "page"
    //private String twoFactorAuthCode = null;
    private int failed2FAAttemptCount = 0;

    public boolean hasSpecifiedCurrentPage() {
        return this.currentPage != null && this.currentPage != PAGE.NONE;
    }

    public boolean currentPageIs2faEntry() {
        return this.currentPage == PAGE.ENTER_2FA_CODE;
    }

    public enum PAGE {
        NONE,
        SETUP,
        LOGIN,
        CHOOSE_2FA,
        ENTER_2FA_CODE;
    }
}
