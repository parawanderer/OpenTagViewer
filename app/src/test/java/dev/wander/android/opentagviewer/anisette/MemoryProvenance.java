package dev.wander.android.opentagviewer.anisette;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link SessionProvenance}'s store as a map: what is in {@code LocalAnisette.PREFERENCES}, by key.
 *
 * <p>Tests write the keys by name, the way an install from before the emulated Mac has them, rather
 * than through {@link SessionProvenance}, so "an older install" means the file such an install
 * actually has.
 */
final class MemoryProvenance implements SessionProvenance.Store {
    final Map<String, Object> values = new HashMap<>();

    @Override
    public boolean getBoolean(final String key) {
        final Object value = this.values.get(key);
        return value instanceof Boolean && (Boolean) value;
    }

    @Override
    public String getString(final String key) {
        final Object value = this.values.get(key);
        return value instanceof String ? (String) value : null;
    }

    @Override
    public void put(final boolean establishedLocally, final String machine) {
        this.values.put(SessionProvenance.KEY_ESTABLISHED_LOCALLY, establishedLocally);
        if (machine == null) {
            this.values.remove(SessionProvenance.KEY_MACHINE);
        } else {
            this.values.put(SessionProvenance.KEY_MACHINE, machine);
        }
    }

    SessionProvenance provenance() {
        return new SessionProvenance(this);
    }
}
