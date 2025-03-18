package dev.wander.android.opentagviewer.ui.login;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import dev.wander.android.opentagviewer.R;
import lombok.NonNull;

public class Apple2FACodeInputManager {
    private static final String TAG = Apple2FACodeInputManager.class.getSimpleName();

    private final AppCompatActivity context;

    private final TextInputEditText[] slots = new TextInputEditText[6];

    private final InputFieldEventHandler[] handlers = new InputFieldEventHandler[6];

    private int currentIndex = 0;

    private final Consumer<String> onFullyFilledCallback;

    public Apple2FACodeInputManager(@NonNull AppCompatActivity context, @NonNull Consumer<String> onFullyFilledCallback) {
        this.context = context;
        this.onFullyFilledCallback = onFullyFilledCallback;
    }

    public void init() {
        TextInputEditText slot1 = this.context.findViewById(R.id.twofactorauth_textinput_1);
        TextInputEditText slot2 = this.context.findViewById(R.id.twofactorauth_textinput_2);
        TextInputEditText slot3 = this.context.findViewById(R.id.twofactorauth_textinput_3);
        TextInputEditText slot4 = this.context.findViewById(R.id.twofactorauth_textinput_4);
        TextInputEditText slot5 = this.context.findViewById(R.id.twofactorauth_textinput_5);
        TextInputEditText slot6 = this.context.findViewById(R.id.twofactorauth_textinput_6);

        this.slots[0] = slot1;
        this.slots[1] = slot2;
        this.slots[2] = slot3;
        this.slots[3] = slot4;
        this.slots[4] = slot5;
        this.slots[5] = slot6;

        // clear
        for (int i = 0; i < this.slots.length; ++i) {
            var slot = this.slots[i];
            slot.setText(null);

            var handler = new InputFieldEventHandler(i, slot, this);

            slot.setOnFocusChangeListener(handler);
            slot.addTextChangedListener(handler);

            this.handlers[i] = handler;
        }
    }

    int getCurrentIndex() {
        return currentIndex;
    }

    void fillNextAvailable(final String totalInput, Integer lastPlacedAt, int stringOffset) {
        if (lastPlacedAt != null) {
            this.currentIndex = lastPlacedAt + 1;
        }

        final int loopSlots = Math.min(6 - this.currentIndex, totalInput.length() - stringOffset);
        int itemIndex = this.currentIndex;

        for (int i = 0; i < loopSlots; ++i) {
            char nextChar = totalInput.charAt(stringOffset + i);
            var nextSlot = this.handlers[itemIndex];

            nextSlot.setCurrentValue(nextChar);
            itemIndex++;
        }

        if (this.currentIndex >= this.handlers.length) {
            // focus last one:
            this.handlers[this.handlers.length-1].setFocus();

        } else {
            // focus previous one:
            this.handlers[this.currentIndex-1].setFocus();
        }
    }

    void requestFocusSwitch(int switchToIndex) {
        if (switchToIndex >= 0 && switchToIndex < this.handlers.length) {
            this.handlers[switchToIndex].setFocus();
        }
    }

    void moveToNextIndexFrom(int lastPlacedAt) {
        this.currentIndex = Math.min(this.handlers.length, lastPlacedAt + 1);
        this.raiseEventIfFullyFilled();
    }

    void moveToPreviousIndexFrom(int lastRemovedAt) {
        this.currentIndex = Math.max(0, lastRemovedAt - 1);
    }

    private void raiseEventIfFullyFilled() {
        if (this.currentIndex == this.handlers.length) {
            final String authCode = this.getCurrentInput();

            if (!authCode.contains(" ")) {
                this.onFullyFilledCallback.accept(authCode);
            }
        }
    }

    static class InputFieldEventHandler implements View.OnFocusChangeListener, TextWatcher {
        private final int index;
        private final TextInputEditText slot;

        private final Apple2FACodeInputManager manager;

        private String beforeTextChangedValue = "";
        private String lastValue = "";

        public InputFieldEventHandler(int index, TextInputEditText slot, Apple2FACodeInputManager manager) {
            this.index = index;
            this.slot = slot;
            this.manager = manager;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            //Log.d(TAG, "Text is about to be changed at index " + this.index);
            this.beforeTextChangedValue = s.toString();
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            //Log.d(TAG, "Text is being changed at index " + this.index);
        }

        @Override
        public void afterTextChanged(Editable s) {
            //Log.d(TAG, "Text was just changed at index " + this.index);

            final int managerCurrentIndex = this.manager.getCurrentIndex();
            final String newInput = s.toString();
            final int lastValueLength = this.lastValue.length();

            if (!(
                    newInput.indexOf(this.beforeTextChangedValue) == 0
                            || this.beforeTextChangedValue.indexOf(newInput) == 0)) {
                s.delete(1, newInput.length());
            } else {
                if (managerCurrentIndex == this.index) {
                    // if it's 1 character long, then save it.
                    if (newInput.length() > lastValueLength) {
                        // new length is longer than prev
                        if (newInput.length() > 1) {
                            // more than 1 character attempted to be entered
                            // the rest of the characters need to be passed on to the next slots...
                            this.manager.fillNextAvailable(newInput, this.index, 1);
                            s.delete(1, newInput.length());
                        } else {
                            // just one character attempting to be entered
                            this.manager.moveToNextIndexFrom(this.index);
                        }
                    } else if (newInput.length() < lastValueLength) {
                        // character was erased, this is ok but we want to
                        // request the focussed text box to be moved to the left
                        this.manager.requestFocusSwitch(this.index - 1);
                        this.manager.moveToPreviousIndexFrom(this.index);
                    }
                    // WHEN: same length, this was just a basic replace
                    // and there's nothing to do

                } else {
                    if (newInput.length() < lastValueLength) {
                        // character was erased, this is ok but we want to
                        // request the focussed text box to be moved to the left
                        this.manager.requestFocusSwitch(this.index - 1);

                        if (managerCurrentIndex - 1 == this.index) {
                            this.manager.moveToPreviousIndexFrom(this.index);
                        }

                    } else if (newInput.length() > lastValueLength) {
                        // character(s) added
                        // ask manager to handle this for us instead
                        this.manager.fillNextAvailable(newInput, null, 1);
                        s.delete(1, newInput.length());
                    }
                    // WHEN: same length, this was just a basic replace
                    // and there's nothing to do
                }
            }

            this.lastValue = s.toString();
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            //Log.d(TAG, "Focus was changed at " + this.index);

            if (this.index > this.manager.getCurrentIndex()) {
                this.manager.requestFocusSwitch(this.manager.getCurrentIndex());
            }
        }

        public void setCurrentValue(final char currentValue) {
            this.slot.setText("" + currentValue);
        }

        public void clear() {
            this.slot.setText("");
        }

        public void setFocus() {
            this.slot.setSelection(Objects.requireNonNull(this.slot.getText()).length());
            this.slot.requestFocus();
        }

        public String getCurrentValue() {
            final String textContent = Objects.requireNonNull(this.slot.getText()).toString();
            final int length = textContent.length();
            if (length == 0) {
                return " ";
            }
            return "" + textContent.charAt(0);
        }
    }

    public void clear() {
        for (var handler : this.handlers) {
            handler.clear();
        }
    }

    public String getCurrentInput() {
        return Arrays.stream(this.handlers)
                .map(InputFieldEventHandler::getCurrentValue)
                .collect(Collectors.joining());
    }
}
