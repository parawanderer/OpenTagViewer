package dev.wander.android.opentagviewer.ui.error;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Where the app sends somebody who has hit a bug.
 *
 * <p><b>One constant, because nothing tests a link.</b> The desktop exporter keeps its own as
 * {@code GITHUB_ISSUES_LINK} in {@code exporter/version.py} for the same reason: a URL inlined at
 * two call sites goes stale at one of them, and the symptom is a worse bug report months later
 * with nothing to connect it to the change.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IssueReport {

    /**
     * The template file {@link #NEW_APP_BUG} names, relative to {@code .github/ISSUE_TEMPLATE/}.
     *
     * <p><b>Named separately so a test can check it exists.</b> GitHub does not error on an
     * unknown {@code ?template=} - it quietly drops the reporter on a blank issue with none of the
     * questions and none of the labels. So renaming the file breaks this with no error anywhere,
     * and the only symptom is worse reports, indefinitely.
     */
    public static final String TEMPLATE = "app-bug.yml";
    /**
     * The issue form for app problems.
     *
     * <p><b>{@code ?template=} and not {@code ?labels=}.</b> Labels in a URL are applied only for
     * somebody with permission to label the repository, which a person reporting a bug is not -
     * the template's own front matter applies them whoever files. The template is also what puts
     * the questions in front of the reporter at all.
     *
     * <p>An unauthenticated visitor is redirected to a sign-in page and returned here afterwards,
     * so the query survives the round trip. There is no way to file anonymously and GitHub has no
     * social sign-in, which is why the screen says an account is needed rather than letting
     * somebody discover it after writing everything out.
     */
    public static final String NEW_APP_BUG =
            "https://github.com/parawanderer/OpenTagViewer/issues/new?template=" + TEMPLATE;

}
