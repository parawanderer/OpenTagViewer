package dev.wander.android.opentagviewer.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A drawable whose colours come from the theme must be loaded with one.
 *
 * <p>This guards a bug that produced no error of any kind. The timeline tiles were converted
 * from {@code @color/md_theme_*} to {@code ?attr/colorOutline} so they would follow wallpaper
 * colours. {@code HistoryItemsAdapter} loaded them with
 * {@code ResourcesCompat.getDrawable(resources, id, null)} — a null theme — which had been fine
 * while the fills were literal colours. With an attribute there is nothing to resolve against,
 * so the paths drew as nothing and the history timeline silently went blank.
 *
 * <p>Nothing threw, no test failed, and the existing screenshot test passed because it loaded
 * the same drawable through a themed context — proving the drawable *can* render, which is not
 * the same as the app asking for it correctly.
 *
 * <p>A source scan rather than a runtime assertion, because the defect is in how the call is
 * written and there is no object to inspect afterwards. Cheap, and it runs on the JVM.
 */
public class ThemedDrawableLoadingTest {

    /** {@code ResourcesCompat.getDrawable(res, id, null)} — the third argument is the theme. */
    private static final Pattern NULL_THEME_LOAD = Pattern.compile(
            "ResourcesCompat\\s*\\.\\s*getDrawable\\s*\\([^;]*?,\\s*null\\s*\\)", Pattern.DOTALL);

    /**
     * Opt-out for a load that genuinely does not need a theme, stated at the call site.
     *
     * <p>There is one real case: the map markers overwrite every fill with
     * {@code setTint} immediately afterwards, so nothing the theme would have supplied ever
     * shows. That is only safe because of a line further down, which is exactly the kind of
     * reasoning that should be written down rather than rediscovered.
     */
    private static final String OPT_OUT = "TINTED_AFTERWARDS";

    private static final Pattern ATTR_FILL = Pattern.compile("\\?attr/");

    private static Path repoRoot() {
        // Tests run with the module directory as the working directory.
        Path here = Paths.get("").toAbsolutePath();
        return here.endsWith("app") ? here.getParent() : here;
    }

    @Test
    public void noDrawableIsLoadedWithoutATheme() throws IOException {
        final Path javaRoot = repoRoot().resolve("app/src/main/java");
        final List<String> offenders = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(javaRoot)) {
            for (Path source : (Iterable<Path>) sources.filter(p -> p.toString().endsWith(".java"))::iterator) {
                final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                final Matcher matcher = NULL_THEME_LOAD.matcher(text);

                while (matcher.find()) {
                    // The marker has to be on the call itself or the line above it, so it
                    // cannot be dropped at the top of a file and silence everything below.
                    final int lineStart = text.lastIndexOf('\n', text.lastIndexOf('\n', matcher.start()) - 1);
                    final String context = text.substring(Math.max(0, lineStart), matcher.end());

                    if (context.contains(OPT_OUT)) {
                        continue;
                    }

                    offenders.add(repoRoot().relativize(source)
                            + " (line " + (1 + (int) text.substring(0, matcher.start())
                            .chars().filter(c -> c == '\n').count()) + ")");
                }
            }
        }

        assertTrue(
                "These load a drawable with a null theme. Any drawable whose fill is a ?attr/\n"
                        + "reference then draws as nothing, with no error anywhere - see the\n"
                        + "history timeline going blank. Use AppCompatResources.getDrawable(context, id),\n"
                        + "or pass a real theme.\n\n  " + String.join("\n  ", offenders),
                offenders.isEmpty());
    }

    /**
     * Documents why the rule above matters, and fails if it ever stops mattering — at which
     * point the rule can go rather than being carried forever for no reason.
     */
    @Test
    public void someDrawablesDoDependOnTheTheme() throws IOException {
        final Path drawables = repoRoot().resolve("app/src/main/res/drawable");
        int themed = 0;

        try (Stream<Path> files = Files.walk(drawables)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml"))::iterator) {
                if (ATTR_FILL.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).find()) {
                    themed++;
                }
            }
        }

        assertTrue("expected drawables using ?attr/ to exist; if none do, delete this guard",
                themed > 0);
    }
}
