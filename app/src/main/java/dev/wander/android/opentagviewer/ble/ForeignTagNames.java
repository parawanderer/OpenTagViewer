package dev.wander.android.opentagviewer.ble;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The names a user gave foreign Find My devices, by key prefix.
 *
 * <p><b>SharedPreferences, and deliberately not Room.</b> A schema change in Room means a
 * migration, a migration test and a committed schema - the project's first rule - and a map
 * from twelve hex characters to a name is precisely the thing SharedPreferences exists for.
 * The prefix is the only key a foreign tag offers until it rotates, and when it does the name
 * is simply keyed to bytes nobody broadcasts any more; re-joining a rotated tag to its old
 * name is a different feature, not a schema problem.
 *
 * <p>Prefix-keyed, not MAC-keyed: the prefix is what stays constant when a tag flips between
 * nearby and separated frames inside one rotation, so a name survives that flip for free.
 */
public final class ForeignTagNames {

    private static final String PREFERENCES = "foreign_tag_names";
    private static final String KEY_PREFIX = "name_";

    private final SharedPreferences preferences;

    public ForeignTagNames(final Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @Nullable
    public String nameFor(final String keyPrefix) {
        return this.preferences.getString(KEY_PREFIX + keyPrefix, null);
    }

    /** Names an identity; an empty or blank name removes it instead. */
    public void rename(final String keyPrefix, @Nullable final String name) {
        if (name == null || name.isBlank()) {
            this.preferences.edit().remove(KEY_PREFIX + keyPrefix).apply();
            return;
        }
        this.preferences.edit().putString(KEY_PREFIX + keyPrefix, name.trim()).apply();
    }

    /** Everything currently named, prefix → name, e.g. for an export. */
    public Map<String, String> all() {
        final Map<String, String> names = new HashMap<>();
        for (final Map.Entry<String, ?> entry : this.preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(KEY_PREFIX) && entry.getValue() instanceof String) {
                names.put(entry.getKey().substring(KEY_PREFIX.length()),
                        (String) entry.getValue());
            }
        }
        return names;
    }
}
