package dev.wander.android.opentagviewer.ui.importing;

import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.KeyEvent;

import com.google.android.material.textfield.TextInputEditText;

import java.util.function.Consumer;

import dev.wander.android.opentagviewer.util.parse.BundlePasscode;

/**
 * The three grouped boxes an unlock code is typed into.
 *
 * <p>Grouped by four because that is how the exporter writes the code down
 * ({@code H4K2-9WMR-7TQX}), so somebody proofreading against a piece of paper is comparing like
 * with like. Twelve single-character boxes - the shape the 2FA screen uses - does not fit on a
 * phone at any legible size.
 *
 * <p>Three things it has to get right, all of which come from what the code actually is:
 *
 * <ul>
 *   <li><b>Fold as you type.</b> {@code O} becomes {@code 0} and {@code I}/{@code L} become
 *       {@code 1} in the box itself, not silently on submit. Those letters are excluded from the
 *       alphabet <em>because</em> people write them for the digits, and showing the fold is how
 *       somebody understands why what they typed changed, rather than thinking the field is
 *       broken. What is on screen is then exactly what the bundle is opened with.</li>
 *   <li><b>Refuse what cannot be in a code.</b> Punctuation, and the excluded {@code U}, never
 *       reach the box - so the mistake surfaces while the user is still looking at the character
 *       they typed, rather than as a failed decryption a minute later.</li>
 *   <li><b>Take a paste of the whole thing.</b> A code is emailed alongside the bundle, so
 *       pasting is the normal case and retyping twelve characters is the exception. A whole code
 *       dropped into any box fills all three, hyphens and all.</li>
 * </ul>
 */
public final class BundlePasscodeInputManager {

    /** {@code PASSCODE_LENGTH / _GROUP} in the exporter: twelve characters in threes of four. */
    private static final int GROUPS = 3;

    private static final int GROUP_LENGTH = 4;

    private static final int MAX_LENGTH = GROUPS * GROUP_LENGTH;

    private final TextInputEditText[] boxes = new TextInputEditText[GROUPS];

    private final Consumer<String> onChanged;

    /** Guards the edits this class makes, so its own writes do not re-enter as user input. */
    private boolean applying = false;

    /**
     * @param onChanged called with the code so far whenever it changes. It is a complete code
     *                  only when {@link #isComplete()}.
     */
    public BundlePasscodeInputManager(
            final TextInputEditText first,
            final TextInputEditText second,
            final TextInputEditText third,
            final Consumer<String> onChanged) {

        this.boxes[0] = first;
        this.boxes[1] = second;
        this.boxes[2] = third;
        this.onChanged = onChanged;

        for (int i = 0; i < GROUPS; i++) {
            final int index = i;
            final TextInputEditText box = this.boxes[i];

            // No LengthFilter. Capping a box at four would silently swallow eight characters of
            // a pasted code - the paste would appear to work and then not open the bundle.
            // Overflow is carried into the following boxes instead, in afterTextChanged.
            box.setFilters(new InputFilter[] {new AlphabetFilter()});
            box.addTextChangedListener(new GroupWatcher(index));
            box.setOnKeyListener((v, keyCode, event) -> this.onBackspace(index, keyCode, event));
        }
    }

    /** The code as it would be handed to the unzip: folded, ungrouped, no separators. */
    public String currentCode() {
        final StringBuilder sb = new StringBuilder(MAX_LENGTH);
        for (final TextInputEditText box : this.boxes) {
            sb.append(text(box));
        }
        return sb.toString();
    }

    public boolean isComplete() {
        return this.currentCode().length() == MAX_LENGTH;
    }

    /**
     * Put a whole code into the boxes, in whatever form it arrived.
     *
     * <p>Accepts the grouped form, spacing, lower case and the confusable letters, because
     * {@link BundlePasscode#normalise} does - this is the same string the exporter would have
     * turned into the password.
     */
    public void acceptWholeCode(final String value) {
        try {
            this.spread(BundlePasscode.normalise(value));
        } catch (BundlePasscode.PasscodeFormatException e) {
            // Nothing usable in it. The per-character filter keeps this from arising through
            // the UI; a caller passing rubbish programmatically gets no change rather than a
            // half-filled set of boxes.
        }
    }

    public void focusFirstEmptyGroup() {
        for (final TextInputEditText box : this.boxes) {
            if (text(box).length() < GROUP_LENGTH) {
                box.requestFocus();
                box.setSelection(text(box).length());
                return;
            }
        }
        final TextInputEditText last = this.boxes[GROUPS - 1];
        last.requestFocus();
        last.setSelection(text(last).length());
    }

    /** Lay a code out across the boxes in fours. Characters are assumed already acceptable. */
    private void spread(final String code) {
        final String capped = code.length() > MAX_LENGTH ? code.substring(0, MAX_LENGTH) : code;

        this.applying = true;
        try {
            for (int i = 0; i < GROUPS; i++) {
                final int from = Math.min(i * GROUP_LENGTH, capped.length());
                final int to = Math.min(from + GROUP_LENGTH, capped.length());
                this.boxes[i].setText(capped.substring(from, to));
            }
        } finally {
            this.applying = false;
        }

        this.focusFirstEmptyGroup();
        this.onChanged.accept(this.currentCode());
    }

    /** Backspace in an empty box goes back to the end of the previous one. */
    private boolean onBackspace(final int index, final int keyCode, final KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_DEL
                || event.getAction() != KeyEvent.ACTION_DOWN
                || index == 0
                || !text(this.boxes[index]).isEmpty()) {
            return false;
        }

        final TextInputEditText previous = this.boxes[index - 1];
        previous.requestFocus();
        previous.setSelection(text(previous).length());
        return true;
    }

    private static String text(final TextInputEditText box) {
        return box.getText() == null ? "" : box.getText().toString();
    }

    /**
     * Keeps a box to characters a code can contain, folding the ones written for digits.
     *
     * <p>A filter rather than a watcher, because it runs before the text is committed - so a
     * refused character never appears at all. A watcher would have to delete it afterwards,
     * which flickers and moves the cursor.
     *
     * <p>Separators are dropped rather than refused, which is what lets the grouped form be
     * pasted into a single box instead of stopping at the first hyphen.
     */
    private static final class AlphabetFilter implements InputFilter {
        @Override
        public CharSequence filter(
                final CharSequence source,
                final int start,
                final int end,
                final Spanned dest,
                final int dstart,
                final int dend) {

            final StringBuilder kept = new StringBuilder(end - start);

            for (int i = start; i < end; i++) {
                final char folded = fold(Character.toUpperCase(source.charAt(i)));
                if (BundlePasscode.ALPHABET.indexOf(folded) >= 0) {
                    kept.append(folded);
                }
            }

            // Null means "no objection, use what was typed" - returned only when nothing was
            // dropped or changed, so ordinary typing keeps the platform's own cursor handling.
            if (kept.length() == end - start
                    && kept.toString().contentEquals(source.subSequence(start, end))) {
                return null;
            }
            return kept.toString();
        }

        private static char fold(final char c) {
            switch (c) {
                case 'O':
                    return '0';
                case 'I':
                case 'L':
                    return '1';
                default:
                    return c;
            }
        }
    }

    /** Moves on when a group fills, and carries an overflowing paste into the boxes after it. */
    private final class GroupWatcher implements TextWatcher {
        private final int index;

        private GroupWatcher(final int index) {
            this.index = index;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(final Editable editable) {
            final BundlePasscodeInputManager owner = BundlePasscodeInputManager.this;
            if (owner.applying) {
                return;
            }

            if (editable.length() > GROUP_LENGTH) {
                // A paste, or a character typed into an already-full box. Either way the excess
                // belongs in the boxes after this one rather than on the floor. Laid out from
                // the whole current contents so that what is already in the later boxes shifts
                // along instead of being wiped.
                owner.spread(owner.currentCode());
                return;
            }

            if (editable.length() == GROUP_LENGTH && this.index < GROUPS - 1) {
                final TextInputEditText next = owner.boxes[this.index + 1];
                if (text(next).isEmpty()) {
                    next.requestFocus();
                }
            }

            owner.onChanged.accept(owner.currentCode());
        }
    }
}
