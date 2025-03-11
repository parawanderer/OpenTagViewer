package dev.wander.android.airtagforall.db.repo.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public class AppleUserData {
    /**
     * Some user-facing account details for display in the UI
     */
    private final UserAuthData user;

    /**
     * Encrypted account data
     */
    private final byte[] data;
}
