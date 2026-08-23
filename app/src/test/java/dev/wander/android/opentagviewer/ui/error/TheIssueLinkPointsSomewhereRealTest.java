package dev.wander.android.opentagviewer.ui.error;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;

/**
 * The report link names a template that exists.
 *
 * <p><b>Because GitHub does not complain when it does not.</b> An unknown {@code ?template=} is
 * not an error there: the reporter is dropped on a blank issue, with none of the questions the
 * form asks and none of the labels it applies. So renaming
 * {@code .github/ISSUE_TEMPLATE/app-bug.yml} breaks the error page silently, and the only symptom
 * is worse bug reports for however many months it takes somebody to notice.
 *
 * <p>The exporter has the same assertion over its own link, for the same reason.
 */
public class TheIssueLinkPointsSomewhereRealTest {

    @Test
    public void thetemplateTheLinkNamesIsInTheRepository() {
        final File templates = findTemplateDirectory();

        final File named = new File(templates, IssueReport.TEMPLATE);
        assertTrue("the error page links at " + IssueReport.TEMPLATE + ", which is not in "
                        + templates + " - GitHub will silently serve a blank issue form instead",
                named.isFile());
    }

    /**
     * And the URL really does name it, rather than having drifted from the constant beside it.
     */
    @Test
    public void thelinkCarriesThatTemplateAndNotLabels() {
        assertTrue("the link must name the template: " + IssueReport.NEW_APP_BUG,
                IssueReport.NEW_APP_BUG.contains("template=" + IssueReport.TEMPLATE));

        // Labels in a URL are applied only for somebody who can already label the repository -
        // which a bug reporter is not - so a link relying on them would arrive unlabelled.
        assertTrue("labels belong in the template's front matter, not the URL: "
                        + IssueReport.NEW_APP_BUG,
                !IssueReport.NEW_APP_BUG.contains("labels="));
    }

    /**
     * Walks up from wherever the test runner started, because that is not fixed.
     *
     * <p>Gradle runs unit tests with the module directory as the working directory, but that is a
     * convention rather than a promise, and a test that hardcoded {@code ../.github} would fail
     * for a reason having nothing to do with its subject.
     */
    private static File findTemplateDirectory() {
        File here = new File(System.getProperty("user.dir")).getAbsoluteFile();

        while (here != null) {
            final File candidate = new File(here, ".github/ISSUE_TEMPLATE");
            if (candidate.isDirectory()) {
                return candidate;
            }
            here = here.getParentFile();
        }

        fail("no .github/ISSUE_TEMPLATE directory above " + System.getProperty("user.dir"));
        return null;
    }
}
