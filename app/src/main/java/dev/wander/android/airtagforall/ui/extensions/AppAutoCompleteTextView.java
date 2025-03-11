package dev.wander.android.airtagforall.ui.extensions;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;

/**
 * Dealing with this bug which is still not fixed using this proposed solution:
 * https://github.com/material-components/material-components-android/issues/1464#issuecomment-913567518
 */
public class AppAutoCompleteTextView extends MaterialAutoCompleteTextView {
    private boolean isCallingSetText = false;

    public AppAutoCompleteTextView(@NonNull Context context) {
        super(context);
    }

    public AppAutoCompleteTextView(@NonNull Context context, @Nullable AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    public AppAutoCompleteTextView(@NonNull Context context, @Nullable AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        if (this.isCallingSetText || getInputType() != EditorInfo.TYPE_NULL) {
            super.setText(text, type);
        } else {
            this.isCallingSetText = true;
            this.setText(text, false);
            this.isCallingSetText = false;
        }
    }
}
