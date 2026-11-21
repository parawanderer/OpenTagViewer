package dev.wander.android.opentagviewer.util;

import java.io.File;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Moving a freshly written file over the one it replaces.
 *
 * <p>{@link File#renameTo} replaces an existing target on Linux, so on Android it is an atomic
 * swap. On Windows it fails when the target exists, which made every second save of the
 * emulated Mac's identity fail in the JVM tests there (19 of them). {@code Files.move} with
 * {@code REPLACE_EXISTING} would do both, but it is API 26 and minSdk is 24.
 *
 * <p>So: rename, and only if that fails, delete the target and rename again. The second branch
 * is never taken on Android, so the app keeps its atomic swap.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FileReplace {

    /** Whether {@code written} now sits at {@code target}. */
    public static boolean moveOver(final File written, final File target) {
        if (written.renameTo(target)) {
            return true;
        }
        // Not with nothing to put in its place: deleting the target first would lose it.
        return written.isFile() && target.exists() && target.delete()
                && written.renameTo(target);
    }
}
