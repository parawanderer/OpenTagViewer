package dev.wander.android.opentagviewer.ui.error;

import static android.view.View.GONE;
import static android.widget.Toast.LENGTH_LONG;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import dev.wander.android.opentagviewer.BuildConfig;
import dev.wander.android.opentagviewer.R;
import dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabase;
import dev.wander.android.opentagviewer.db.room.entity.Import;
import dev.wander.android.opentagviewer.python.AppDependencies;
import dev.wander.android.opentagviewer.python.LogRedactor;
import dev.wander.android.opentagviewer.util.LogCollectorUtil;
import dev.wander.android.opentagviewer.util.android.WebLink;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * "This one is a bug" - the screen for a failure nobody here can fix.
 *
 * <p><b>Shown only when the app cannot name the cause.</b> An {@code UnhandledProtocolError} from
 * Python, or anything that reaches {@code REASON_UNKNOWN}. Never for a rejected passcode, an
 * account with no tags, a network that is down, or a session wanting a verification code - each of
 * those has a screen that says what to do, and this one would be worse than the advice they
 * already give. A page that turns up for ordinary mistakes is one people learn to dismiss, and
 * then it is worth nothing on the day it is right.
 *
 * <p>It carries the three things a report needs and a person otherwise has to hunt for: which
 * build this is, which exporter made their bundle, and the failure verbatim. The last was the
 * reason somebody would open an export zip - a file holding their tags' private keys - to read a
 * version line out of it.
 */
public class ErrorReportActivity extends AppCompatActivity {
    private static final String TAG = ErrorReportActivity.class.getSimpleName();

    /** What went wrong, in the words the failure arrived in. Never translated - it is evidence. */
    public static final String EXTRA_CAUSE = "cause";

    /**
     * Which explanation to show above the cause.
     *
     * <p><b>Because "something came back that this app cannot read" is false for half the callers.</b>
     * It is exactly right for a protocol failure and wrong for a bundle that would not parse -
     * nothing came back from anywhere, somebody chose a file. A page that misdescribes what
     * happened is worse than a generic one, because the reader corrects for it and stops trusting
     * the rest.
     */
    public static final String EXTRA_BODY = "body";

    /** The protocol case: Apple sent something the library does not understand. */
    public static Intent intentFor(final Context context, final String cause) {
        return intentFor(context, cause, R.string.error_report_body);
    }

    public static Intent intentFor(
            final Context context, final String cause, final int bodyRes) {
        return new Intent(context, ErrorReportActivity.class)
                .putExtra(EXTRA_CAUSE, cause)
                .putExtra(EXTRA_BODY, bodyRes);
    }

    /** The redacted log, held once prepared so the share button is instant and cannot re-fail. */
    private LogRedactor.Redacted log;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.activity_error_report);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        this.<TextView>findViewById(R.id.error_report_build).setText(
                "OpenTagViewer app " + LogCollectorUtil.describeBuild(
                        BuildConfig.VERSION_NAME, BuildConfig.BUILD_COMMIT));

        this.<TextView>findViewById(R.id.error_report_cause)
                .setText(this.getIntent().getStringExtra(EXTRA_CAUSE));

        this.<TextView>findViewById(R.id.error_report_body).setText(this.getIntent()
                .getIntExtra(EXTRA_BODY, R.string.error_report_body));

        this.findViewById(R.id.error_report_button).setOnClickListener(
                v -> WebLink.open(this, IssueReport.NEW_APP_BUG));

        // finish() rather than a navigate-up: this page is always arrived at from somewhere, and
        // that somewhere is where closing it should land - the map, mid-import, wherever it was.
        this.findViewById(R.id.error_report_close).setOnClickListener(v -> this.finish());

        // Nothing to share until the log has been through the redactor, and that is a Python call
        // on a screen that exists because something already broke.
        this.findViewById(R.id.error_report_share_log).setVisibility(GONE);

        this.prepareTheEvidence();
    }

    /**
     * Reads the provenance and the log, off the main thread, and only then offers the log.
     *
     * <p><b>The share button is hidden until there is something safe to share.</b> Redaction runs
     * through Chaquopy, and this screen is reached <i>because</i> something failed - "Python did
     * not start" being one of the candidates. A button that appeared regardless and then handed
     * over a raw logcat would put somebody's Apple ID on a public issue at the moment they are
     * least inclined to read it first.
     */
    private void prepareTheEvidence() {
        final TextView importedFrom = this.findViewById(R.id.error_report_imported_from);
        final TextView note = this.findViewById(R.id.error_report_log_note);
        final Button share = this.findViewById(R.id.error_report_share_log);

        var async = Observable.fromCallable(() -> {
                    final Import last = OpenTagViewerDatabase
                            .getInstance(this.getApplicationContext()).importDao().getMostRecent();

                    final String via = last == null ? null : last.exportedVia;
                    final String raw = LogCollectorUtil.getLastLogsWithHeader(
                            BuildConfig.VERSION_NAME, BuildConfig.BUILD_COMMIT, via);

                    return new Evidence(via, AppDependencies.logRedactor().redact(raw));
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        evidence -> {
                            importedFrom.setText(evidence.via == null
                                    ? this.getString(R.string.imported_from_nothing)
                                    : this.getString(R.string.imported_from_x, evidence.via));

                            if (evidence.log == null) {
                                note.setText(R.string.error_report_log_unavailable);
                                return;
                            }

                            this.log = evidence.log;
                            note.setText(this.getString(
                                    R.string.error_report_log_cleaned, evidence.log.getSummary()));
                            share.setVisibility(android.view.View.VISIBLE);
                            share.setOnClickListener(v -> this.offerTheLog());
                        },
                        error -> {
                            Log.w(TAG, "Could not prepare the log for reporting", error);
                            note.setText(R.string.error_report_log_unavailable);
                        });
    }

    /**
     * Asks which of the two things somebody actually wants, because they are not the same thing.
     *
     * <p><b>It shipped as a share sheet, and a share sheet serves neither well.</b> With text and
     * no stream, Drive and Files do not appear as targets at all - so the file half of the sheet
     * was simply absent, on a button whose main purpose is producing a file to attach. And the
     * copy half depended on whichever clipboard target the phone happened to have.
     *
     * <p>Two named choices instead. Attaching a file to a GitHub issue on a phone goes through
     * the browser's file picker, which reads storage - so a file has to exist somewhere the user
     * chose, which is what the document picker is for. Pasting into the form's
     * {@code render: shell} box just wants the clipboard.
     */
    private void offerTheLog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.error_report_log_how)
                .setItems(
                        new CharSequence[] {
                                this.getString(R.string.error_report_log_copy),
                                this.getString(R.string.error_report_log_save)},
                        (dialog, which) -> {
                            if (which == 0) {
                                this.copyTheLog();
                            } else {
                                this.saveTheLog();
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Straight to the clipboard, for pasting into the form's log box. */
    private void copyTheLog() {
        final ClipboardManager clipboard = this.getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            Toast.makeText(this, R.string.failed_to_export_log_file, LENGTH_LONG).show();
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText("OpenTagViewer log", this.log.getText()));

        // **Android 13 shows its own confirmation, and a toast on top of it reads as a bug.**
        // Below that there is nothing at all, and silence after a tap is indistinguishable from
        // a dead button.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.error_report_log_copied, LENGTH_LONG).show();
        }
    }

    /**
     * Somewhere the user picks, through the document picker.
     *
     * <p>The same route as Settings' own Export Logs button, deliberately: a file the user chose
     * the location of is one the browser's file picker can find again, which a cache file handed
     * over by a share sheet is not.
     */
    private void saveTheLog() {
        this.saveLogLauncher.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "opentagviewer-log.txt"));
    }

    /**
     * Writes the redacted log where the picker said, off the main thread.
     *
     * <p>Registered as a field rather than made at click time: registering has to happen before
     * the activity is started, so a launcher created inside a click listener throws.
     */
    private final ActivityResultLauncher<Intent> saveLogLauncher = this.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                final Uri target = result.getData() == null ? null : result.getData().getData();
                if (result.getResultCode() != RESULT_OK || target == null || this.log == null) {
                    return; // cancelled, which is not a failure and needs no message
                }

                var async = Observable.fromCallable(() -> {
                            try (OutputStream out = this.getContentResolver()
                                    .openOutputStream(target);
                                 Writer writer = new OutputStreamWriter(
                                         out, StandardCharsets.UTF_8)) {
                                writer.write(this.log.getText());
                            }
                            return target;
                        })
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                written -> Toast.makeText(this,
                                        R.string.log_file_has_been_exported_successfully,
                                        LENGTH_LONG).show(),
                                error -> {
                                    Log.w(TAG, "Could not write the log out", error);
                                    Toast.makeText(this, R.string.failed_to_export_log_file,
                                            LENGTH_LONG).show();
                                });
            });

    /** What the background read produced, so the UI thread does one hand-off rather than two. */
    private static final class Evidence {
        private final String via;
        private final LogRedactor.Redacted log;

        private Evidence(final String via, final LogRedactor.Redacted log) {
            this.via = via;
            this.log = log;
        }
    }
}
