package dev.wander.android.opentagviewer.util.android;

import android.text.Editable;
import android.text.TextWatcher;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TextChangedWatcherFactory {
    public interface TextChangedCallback {
        void onTextChanged(CharSequence s, int start, int before, int count);
    }

    public static TextWatcher justWatchOnChanged(TextChangedCallback callback) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // this utility ignores this
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                callback.onTextChanged(s, start, before, count);
            }

            @Override
            public void afterTextChanged(Editable s) {
                // this utility ignores this
            }
        };
    }
}
