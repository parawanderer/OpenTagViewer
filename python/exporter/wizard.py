"""
The windowed exporter: pick your tags, get a zip.

Same work as `exporter.cli`, in a window. It reads from whichever source this machine can use -
see :mod:`exporter.source` - asks which accessories to export, and writes a bundle through the
shared writer in :mod:`opentagviewer_export`. Nothing about the format lives here.

**The window is the only thing this file is for.** Everything it does is in `icloud.py`,
`localsource.py` and the shared package, all of which the CLI drives too; what is here is Tk. That
split is deliberate and worth keeping: the parts worth testing are testable without a display, and
the part that needs a person to look at it does not hide anything else inside it.

Long work happens off the main thread, through :mod:`exporter.asyncui`, so the window keeps
drawing while a sign-in runs. On macOS a window that stops answering events gets the spinning
cursor and eventually an offer to force-quit, in the middle of a network call.
"""

from __future__ import annotations

import atexit
import datetime
import getpass
import logging
import sys
import tempfile
import time
import traceback
import tkinter as tk
import webbrowser
from pathlib import Path
from tkinter import messagebox, ttk
from typing import Callable, Sequence
from tkinter.filedialog import askopenfilenames, asksaveasfilename

from exporter import icloud, localsource, source, terms
from exporter.asyncui import Asker, Cancelled, run_with_progress
from exporter.codes import (
    VERIFICATION_CODE_LENGTH,
    is_verification_code,
    verification_code,
)
from exporter.redact import redact, summarise
from exporter.tkutil import centre_on_screen, centre_over
from exporter.custom_tags import (
    CustomTagError,
    PreparedTag,
    check_advertisement_key,
    suggested_identifier,
    suggested_name,
)
from findmy import (
    InvalidCredentialsError,
    LoginState,
    MobileMeDelegateError,
    TermsError,
)
from findmy.keychain.recovery import RecoveryError

from exporter.icloud import Candidate, ExportSourceError
from exporter.version import (
    APP_TITLE,
    EXPORT_VIA_WIZARD,
    GITHUB_ISSUES_LINK,
    VERSION,
    describe_build,
)
from opentagviewer_export import (
    ExportError,
    KeyFileError,
    build_export,
    parse_key_file,
    write_zip,
)
from opentagviewer_export.hardware import is_own_device

logger = logging.getLogger(__name__)

# The "Need Help?" link. It points at the page covering both routes: the older `…-From-Mac` page
# keeps its name because binaries already released open it, but a copy shipping now should send
# people to the one that describes what it actually does.
WIKI_LINK = "https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Export-AirTags"

# Kept for anything still importing them from here.
EXPORT_METADATA_VIA_NAME = EXPORT_VIA_WIZARD

# Squares rather than the ballot boxes \u2610/\u2611, which are drawn small and low against the row's text
# and change width when ticked. These are one shape, filled or not, so a column of them lines up
# and only the fill moves.
TICKED = "\u25a0"
UNTICKED = "\u25a1"

# How wide the terms are wrapped, and how wide the box that shows them is. One number rather than
# two, because they have to agree: `terms.render` hard-wraps at whatever it is given, so a box
# narrower than that wraps every line a second time and produces a ragged short line under each.
_TERMS_COLUMNS = 88

TICKED_ROW = "ticked"
"""
The tag that colours a ticked row, using this platform's own selection colour.

**The list said this by colour before it was a table.** It was a `tk.Listbox` with
`selectmode="multiple"`, so the platform coloured selected rows itself. The table replaced that
with `selectmode="none"` and a glyph in a 30px column - which is a very small difference between
a tag that is leaving your account and one that is not, on the one screen where that distinction
is the entire point.
"""

_KEY_FILE_TYPES = [
    ("Key files", "*.json *.keys *.txt"),
    ("All files", "*.*"),
]


class TermsDeclined(Exception):
    """
    The user read Apple's terms of service and said no.

    **The one answer this window cannot carry on from**, and the only reason it is not just
    another `ExportSourceError`. Everything else that goes wrong here leaves the button there to
    try again, because trying again is a sensible thing to do. This does not: Apple will not
    complete the delegate exchange for an account with terms pending, so a second attempt reaches
    the same document and asks the same question. Offering a retry would be pretending the answer
    might change by itself.
    """


class WizardApp(tk.Tk):
    """
    The window: a list of accessories, and a button that writes them.

    Everything slow is handed to :func:`~exporter.asyncui.run_with_progress`, which keeps this
    window drawing and hops any question it needs to ask back here.
    """

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)

        self.title(APP_TITLE)
        self.geometry("720x460")
        self.minsize(640, 420)

        self.candidates: list[Candidate] = []
        self.custom_tags: list[PreparedTag] = []
        # What the source could not export, kept rather than passed around: the list is redrawn
        # whenever a key file is added, and that redraw knows nothing about the original read.
        self.skipped: list = []
        self.route = source.detect()

        self._build()
        centre_on_screen(self)

        # The list starts empty and nothing is read until asked for. It used to sign in the moment
        # the window appeared, which made an Apple account the price of opening the program: a
        # failed sign-in closed it, and the button for tags that were never in an Apple account -
        # OpenHaystack and the like - was on the screen that failure never reached.
        self._show([])

    # -- layout ---------------------------------------------------------------------------

    def _build(self) -> None:
        container = ttk.Frame(self, padding=12)
        container.pack(fill="both", expand=True)
        container.grid_rowconfigure(2, weight=1)
        container.grid_columnconfigure(0, weight=1)

        self.heading = ttk.Label(container, text="Choose what to export", font=("Arial", 13, "bold"))
        self.heading.grid(row=0, column=0, columnspan=3, sticky="w")

        self.explanation = ttk.Label(container, text=self._route_line(), wraplength=680, foreground="#555")
        self.explanation.grid(row=1, column=0, columnspan=3, sticky="w", pady=(2, 10))

        # A table rather than a Listbox of padded strings. Padding to a column count only lines up
        # if every character is one column wide, and a tag called "🎒 Backpack" is not - so the
        # first attempt stairstepped exactly where somebody had used an emoji, which is most
        # accounts. Columns are laid out by the widget and cannot come apart.
        self.choices = ttk.Treeview(
            container,
            columns=("tick", "name", "what", "serial", "paired", "note"),
            show="headings",
            selectmode="none",
        )
        self.choices.heading("tick", text="")
        self.choices.column("tick", width=30, anchor="center", stretch=False)

        for column, heading, width in (
            ("name", "Name", 190),
            ("what", "What it is", 150),
            ("serial", "Serial", 130),
            ("paired", "Paired", 90),
            ("note", "", 200),
        ):
            self.choices.heading(column, text=heading)
            self.choices.column(column, width=width, anchor="w", stretch=(column == "note"))

        self.choices.grid(row=2, column=0, columnspan=3, sticky="nsew")
        self.choices.bind("<Button-1>", self._toggle)

        background, foreground = _selection_colours()
        self.choices.tag_configure(TICKED_ROW, background=background, foreground=foreground)

        scrollbar = ttk.Scrollbar(container, orient="vertical", command=self.choices.yview)
        scrollbar.grid(row=2, column=3, sticky="ns")
        self.choices.configure(yscrollcommand=scrollbar.set)

        # Which rows are ticked. Tk's own selection is turned off: it needs modifier keys for a
        # multiple selection, which is not discoverable, and this list must be explicit.
        self.ticked: set[str] = set()

        self.note = ttk.Label(container, text="", wraplength=680, foreground="#555")
        self.note.grid(row=3, column=0, columnspan=4, sticky="w", pady=(8, 6))

        buttons = ttk.Frame(container)
        buttons.grid(row=4, column=0, columnspan=4, sticky="ew")
        buttons.grid_columnconfigure(2, weight=1)

        # Reading the account is a button rather than something that happens to you, and the label
        # says which of the two routes it will take: one asks for an Apple ID password and
        # registers a device on the account, the other asks for neither.
        self.read_button = ttk.Button(buttons, text=self._read_label(), command=self._load)
        self.read_button.grid(row=0, column=0, sticky="w")

        # The "+" is for tags that were never in an Apple account - OpenHaystack and the like.
        # They cannot be fetched, because there is nothing to fetch them from.
        self.add_button = ttk.Button(buttons, text="+ Add from key file…", command=self._add_key_file)
        self.add_button.grid(row=0, column=1, sticky="w", padx=(8, 0))

        help_label = ttk.Label(buttons, text="Need help?", cursor="hand2", foreground="#0645AD")
        help_label.grid(row=0, column=2)
        help_label.bind("<Button-1>", lambda _event: webbrowser.open(WIKI_LINK, new=2, autoraise=True))

        # **Where Cancel used to be**, which did exactly what the window's own close button does
        # and so earned none of that space. This does something nothing else here can.
        #
        # A button rather than a link, because being findable is the whole point: somebody asked
        # for "the logs" who cannot find them attaches one of two wrong things instead - a
        # screenshot of an error dialog, which never contains the useful part, or their export zip,
        # which holds the keys to their tags and cannot be un-shared once it is posted.
        ttk.Button(buttons, text="Save logs…", command=self._save_logs).grid(
            row=0, column=3, padx=(0, 8),
        )
        self.confirm_button = ttk.Button(buttons, text="Export…", command=self._export, state="disabled")
        self.confirm_button.grid(row=0, column=4)

    def _save_logs(self) -> None:
        """
        Copy the log somewhere the user can find, under a name nothing else here could be.

        **The point is the name, as much as the copy.** The two files this program produces are a
        bundle of tag keys and a log, and one of them can be handed out safely. Somebody asked for
        "the file" attaches whichever they can find, and the wrong answer publishes the keys to
        their tags permanently - so the log is written as `.txt`, with `logs` in its name, and the
        dialog afterwards says which is which rather than assuming it is obvious.
        """
        log = log_file()

        if not log.is_file() or log.stat().st_size == 0:
            messagebox.showinfo(
                "Save logs",
                "There is nothing in the log yet.\n\n"
                "It is written as the exporter runs, so try again after the step that went wrong.",
                parent=self,
            )
            return

        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        chosen = asksaveasfilename(
            parent=self,
            title="Save logs",
            # Deliberately not .zip. The extension is the fastest way to tell this from an export,
            # and a text file is also one somebody can read before sending, which is the whole ask.
            defaultextension=".txt",
            initialfile=f"OpenTagViewer-logs-{stamp}.txt",
            filetypes=[("Text files", "*.txt"), ("All files", "*.*")],
        )

        if not chosen:
            return

        try:
            cleaned, counts = redact(log.read_text(encoding="utf-8", errors="replace"))
            Path(chosen).write_text(cleaned, encoding="utf-8")
        except OSError as e:
            logger.exception("Could not save the log")
            messagebox.showerror("Save logs", f"Could not write that file:\n\n{e}", parent=self)
            return

        messagebox.showinfo(
            "Save logs",
            f"Saved to:\n{chosen}\n\n"
            f"{summarise(counts)} The copy on disk is untouched.\n\n"
            "This is the log, not your tags. Your tags are the .zip the Export… button writes.\n\n"
            "**Read it before you post it.** Identifiers were removed by pattern-matching, which"
            " cannot promise it caught everything - the text comes from a library reading Apple's"
            " structures, and a field that is harmless on one account may not be on another.",
            parent=self,
        )

    def _read_label(self) -> str:
        """What the button that reads the account says, which is not the same on both routes."""
        return "Read this Mac's Find My files" if self.route.is_local else "Sign in to Apple…"

    def _route_line(self) -> str:
        where = "this Mac's own Find My files" if self.route.is_local else "your iCloud account"
        return f"Reads {where}, because {self.route.reason}."

    # -- loading --------------------------------------------------------------------------

    def _load(self) -> None:
        """
        Read whichever source this machine can use, and add what it holds to the list.

        **Almost nothing closes the window from here.** This runs when a button is pressed rather
        than when the program starts, so a sign-in that fails, or one the user changes their mind
        about half way through, leaves them where they were - with whatever they had already added
        from a key file still in the list, and the button still there to try again.

        The single exception is refusing Apple's terms of service, which is not a failure that
        trying again can get past - see :class:`TermsDeclined`.
        """
        try:
            fetched = (
                run_with_progress(self, "Reading this Mac's Find My files…", self._read_local)
                if self.route.is_local
                else run_with_progress(self, "Signing in to iCloud…", self._read_icloud)
            )
        except Cancelled:
            # They closed the progress window, which says stop this - not close everything.
            return
        except TermsDeclined as declined:
            # **The one exception to the paragraph above**, and it is deliberate. Every other
            # failure leaves the window open because trying again might work; this one cannot,
            # since the same terms are waiting on the next attempt and the answer was no. So it
            # says what that means, confirms nothing was sent, and closes.
            logger.info("Terms of service declined: %s", declined)
            messagebox.showinfo(
                "Terms not accepted",
                f"Nothing was sent, and your Apple account is unchanged.\n\n"
                f"Apple will not let anything read this account until the {declined} terms are"
                " accepted, so there is nothing more this exporter can do. You can accept them"
                " on an Apple device or at icloud.com, or run this again and read them here.",
            )
            self.destroy()
            return
        except (ExportSourceError, ExportError) as e:
            messagebox.showerror("Could not read your accessories", str(e))
            return
        except InvalidCredentialsError as e:
            # **Not the handler below, which asks for a bug report.** A password Apple rejected is
            # the most ordinary thing that can happen here, and being told to go and file an issue
            # about your own typo is both wrong and slightly insulting. Nothing was changed and the
            # button is still there, so this says that and stops.
            messagebox.showerror(
                "Apple did not accept that sign-in",
                f"{e}\n\nNothing was changed. You can try again whenever you like.",
            )
            return
        except RecoveryError as e:
            # Same reasoning, for the passcode. Its message already carries FindMy.py's advice -
            # starting with trying again, because this call fails intermittently - so it is shown
            # whole rather than summarised.
            messagebox.showerror(
                "Could not unlock your keychain",
                f"{e}\n\nNothing was changed. You can try again whenever you like.",
            )
            return
        except Exception as e:  # noqa: BLE001 - anything else is still the user's problem to see
            logger.exception("Reading accessories failed")
            messagebox.showerror(
                "Could not read your accessories",
                # The type, not just the message: "[Errno 2] No such file or directory" with
                # nothing else is a real message this produced, and it says nothing about what
                # was being opened or by whom.
                f"{type(e).__name__}: {e}\n\n"
                f"The details are in:\n{log_file()}\n\n"
                f"If this looks like a bug, please report it with that file:\n{GITHUB_ISSUES_LINK}",
            )
            return

        self.candidates = fetched.candidates
        # Read once. A second read would sign in again, register nothing new and rebuild the list
        # under the ticks somebody has already made.
        self.read_button.configure(state="disabled")
        self._show(fetched.skipped)

    async def _read_local(self, _asker: Asker):
        """The local route needs nothing from the user that macOS does not ask for itself."""
        return localsource.fetch(localsource.read_key())

    async def _read_icloud(self, asker: Asker):
        """
        Sign in, unlock the keychain and fetch.

        Every question is drawn on the main thread through `asker`, because Tk is not thread-safe
        and this coroutine is not on it.
        """
        account = icloud.make_account()

        try:
            # Said before the password is asked for, and naming the entry as it will appear: the
            # model and OS come from FindMy.py, which presents as a MacBook Pro, so somebody told
            # only the serial goes looking for a Mac they own.
            asker.ask(lambda: messagebox.showinfo(
                "Signing in registers a device",
                "Signing in adds an entry to your Apple account's device list: a MacBook Pro on"
                " macOS 13.4.1, serial 0PENTAGXPORT.\n\n"
                "That is this program, not a Mac you own. You can remove it at any time at"
                " account.apple.com under Devices, from any browser.",
            ))

            # One dialog, both fields. They are one credential as far as the user is concerned,
            # and asking twice makes the second window look like the first one failed.
            email, password = asker.ask(lambda: _ask_credentials(self))
            if not email or not password:
                raise ExportSourceError("Signing in was cancelled.")

            try:
                await icloud.log_in(
                    account,
                    email,
                    password,
                    choose_second_factor=lambda methods: _async(
                        asker.ask(lambda: _ask_choice(
                            self, "Verification", "How should Apple send the code?", methods,
                        )),
                    ),
                    get_code=lambda: _async(
                        asker.ask(lambda: _ask_string(
                            self,
                            "Verification",
                            f"The {VERIFICATION_CODE_LENGTH}-digit code Apple sent:",
                            valid=is_verification_code,
                            transform=verification_code,
                        )),
                    ),
                    retry_credentials=lambda error, attempt: _async(
                        _ask_again_for_credentials(self, asker, error, attempt),
                    ),
                    retry_code=lambda error, attempt: _async(
                        _ask_again_for_code(self, asker, error, attempt),
                    ),
                )
            except MobileMeDelegateError as e:
                # Authentication worked; the exchange that follows it did not. Unaccepted terms
                # are the one cause of that with a remedy here - and which error value means
                # "terms pending" is not established, so this looks rather than assumes.
                await _accept_pending_terms(self, account, asker, e)

            # Registered a device by now; remembering it stops the next export registering another.
            icloud.remember(account)

            async with await icloud.open_client(account) as client:
                options = await client.recovery_options()
                if not options.recoverable:
                    raise ExportSourceError(
                        "No device on this account can currently be recovered from, so its keychain"
                        " cannot be unlocked.",
                    )

                index = asker.ask(lambda: _ask_choice(
                    self,
                    "Unlock",
                    "Which device's screen-lock passcode do you have?\n"
                    "That is its PIN or login password, not your Apple ID password.",
                    [record.describe() for record in options.recoverable],
                ))
                chosen = options.recoverable[index]

                async def ask_passcode(attempt: int, chosen=chosen) -> str:
                    again = "\n\nThat last one was not accepted." if attempt > 1 else ""
                    return asker.ask(lambda: _ask_string(
                        self,
                        "Unlock",
                        f"Screen-lock passcode for {chosen.serial}:{again}",
                        secret=True,
                    ))

                async def rejected(error, attempt: int) -> bool:
                    # Offered rather than taken: the attempt cap is Apple's and unknown, so
                    # spending another one is the user's call. The library's text says why the
                    # first thing to try is the same passcode over again.
                    if asker.ask(lambda: messagebox.askyesno(
                        "Unlock",
                        f"That passcode was not accepted (attempt {attempt} of"
                        f" {icloud.MAX_UNLOCK_ATTEMPTS}).\n\n{error}\n\nTry again?",
                        parent=self,
                    )):
                        return True

                    raise _stopped()

                await icloud.unlock(client, chosen, ask_passcode, rejected)

                return await icloud.fetch(client)
        finally:
            await account.close()

    # -- the list -------------------------------------------------------------------------

    def _show(self, skipped: list | None = None) -> None:
        """
        Fill the table, and say what was set aside.

        **Ticks survive this**, because it is not only called once. Adding a key file rebuilds the
        list to show the new row, and a rebuild that starts everything from unticked throws away a
        decision the user has already made - on the screen whose whole job is to state what is
        about to be handed over, without saying that it did.

        :param skipped: What could not be exported, when this is showing a fresh read. None keeps
            whatever was said last time, since a rebuild does not un-skip anything.
        """
        if skipped is not None:
            self.skipped = skipped

        self.choices.delete(*self.choices.get_children())

        for index, candidate in enumerate(self.candidates):
            self.choices.insert(
                "", "end", iid=f"c{index}",
                tags=(TICKED_ROW,) if f"c{index}" in self.ticked else (),
                values=(
                    TICKED if f"c{index}" in self.ticked else UNTICKED,
                    candidate.label,
                    candidate.hardware or "",
                    candidate.serial_number or "",
                    f"{candidate.paired_at:%Y-%m-%d}" if candidate.paired_at else "",
                    "" if candidate.has_alignment else "\u26a0 slow first locate",
                ),
            )

        for index, tag in enumerate(self.custom_tags):
            self.choices.insert(
                "", "end", iid=f"k{index}",
                tags=(TICKED_ROW,) if f"k{index}" in self.ticked else (),
                values=(
                    TICKED if f"k{index}" in self.ticked else UNTICKED,
                    tag.name, "self-generated tag", "", "", "from a key file",
                ),
            )

        # Rows are only ever added, so this drops nothing in practice - but a tick for a row that
        # is no longer there would export whatever later took its place.
        self.ticked &= set(self.choices.get_children())

        has_rows = bool(self.choices.get_children())

        self.heading.configure(text="Choose what to export" if has_rows else "Nothing to export yet")
        self.confirm_button.configure(state="normal" if has_rows else "disabled")
        self._say_where_things_stand()

    def _say_where_things_stand(self) -> None:
        """
        The line under the list: what was set aside, and how much is about to leave.

        **The count is not decoration.** "Nothing is selected to begin with" was a fixed string,
        which passed unnoticed while a 30px glyph was the only sign of a selection; under a list
        of coloured rows it is a caption contradicting the picture above it. It also gives the
        one number that matters at the moment of pressing Export, next to the warning about what
        that cannot be undone.
        """
        notes = []

        if self.skipped:
            notes.append(
                f"{len(self.skipped)} record(s) could not be exported: they carry no key material.",
            )

        if not self.choices.get_children():
            # The empty list is the first thing anybody sees now, so it has to say what the two
            # buttons are for - including that the second one needs no Apple account, which is the
            # whole reason somebody with only self-generated tags can get anywhere here.
            notes.append(
                f"“{self._read_label()}” reads the accessories on your account."
                " “+ Add from key file…” adds a tag you generated yourself, which needs no account.",
            )
        elif self.ticked:
            notes.append(f"{len(self.ticked)} selected. What you export cannot be taken back.")
        else:
            notes.append("Nothing is selected to begin with. What you export cannot be taken back.")

        self.note.configure(text="  ".join(notes))

    def _add_key_file(self) -> None:
        """Read a self-generated tag out of a file, and add it to the list."""
        paths = askopenfilenames(title="Add tags from key files", filetypes=_KEY_FILE_TYPES)

        for path in paths or ():
            try:
                for tag in parse_key_file(Path(path).read_bytes(), filename=Path(path).name):
                    check_advertisement_key(tag)

                    suggestion = suggested_name(tag)
                    name = _ask_string(self, "Name this tag", "What should this tag be called?", suggestion)
                    if not name:
                        continue

                    self.custom_tags.append(PreparedTag(
                        tag=tag, name=name, identifier=suggested_identifier(tag),
                    ))
            except (KeyFileError, CustomTagError, OSError) as e:
                messagebox.showwarning("Could not read that file", str(e))

        # No argument: nothing here changes what could not be exported, and passing an empty list
        # would replace that note with silence.
        self._show()

    # -- exporting ------------------------------------------------------------------------

    def _toggle(self, event: tk.Event) -> None:
        """Tick or untick whichever row was clicked. One click, no modifier keys."""
        row = self.choices.identify_row(event.y)
        if not row:
            return

        self._set_tick(row, row not in self.ticked)

    def _set_tick(self, row: str, ticked: bool) -> None:
        """
        Tick or untick one row: the set, the mark and the colour, which must not disagree.

        Three representations of one fact, which is two more than anybody wants - but the set is
        what exports, the mark is what a person reads, and the colour is what they see without
        reading. Keeping them in one method is what stops a redraw restoring two of them.
        """
        if ticked:
            self.ticked.add(row)
        else:
            self.ticked.discard(row)

        self.choices.set(row, "tick", TICKED if ticked else UNTICKED)
        self.choices.item(row, tags=(TICKED_ROW,) if ticked else ())
        self._say_where_things_stand()

    def _selected(self) -> tuple[list[Candidate], list[PreparedTag]]:
        """Split what is ticked back into where each row came from."""
        return (
            [self.candidates[int(row[1:])] for row in sorted(self.ticked) if row.startswith("c")],
            [self.custom_tags[int(row[1:])] for row in sorted(self.ticked) if row.startswith("k")],
        )

    def _export(self) -> None:
        chosen, custom = self._selected()

        if not chosen and not custom:
            messagebox.showinfo("Nothing selected", "Tick the accessories you want in the bundle.")
            return

        chosen = self._confirm_devices(chosen)
        if chosen is None:
            return

        exports = []
        for candidate in chosen:
            name = candidate.name
            if candidate.naming_record is None:
                # The importer drops any accessory it cannot pair with a name, so an unnamed one
                # would be quietly missing from the bundle rather than visibly absent.
                name = _ask_string(self, "Name this accessory", f"{candidate.beacon_id}\nhas no name. Call it:")
                if not name:
                    return

            exports.append(icloud.to_export(candidate, name=name))

        exports.extend(tag.to_export() for tag in custom)

        path = asksaveasfilename(
            initialfile=f"OpenTagViewer_export_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}.zip",
            defaultextension=".zip",
            filetypes=[("Zip Archives", "*.zip")],
        )
        if not path:
            return

        try:
            bundle = build_export(
                exports,
                via=EXPORT_VIA_WIZARD,
                # getpass.getuser() rather than os.getenv("USER"), which is empty on Windows.
                source_user=getpass.getuser(),
                exported_at_ms=int(time.time() * 1000),
            )
        except ExportError as e:
            messagebox.showerror("That selection cannot be exported", str(e))
            return

        # **No password yet, and the reason is release ordering rather than a missing feature.**
        # The app on `main` imports locked bundles - zip4j is in `app/build.gradle.kts` and
        # `AppleZipImporterUtil` uses it - but no *released* APK does: the newest is 1.0.5, from
        # before that work, and `versionName` has not moved past it.
        #
        # So locking bundles now would produce files that nobody's installed app can open, and the
        # people worst affected would be recipients, who did not choose the exporter's version and
        # cannot fix it from their side.
        #
        # **What unblocks this is an Android release containing zip4j, not a change here.** Once
        # one exists, this becomes a decision about how long to keep supporting the versions before
        # it. See the CLI's --no-password, and docs/android-import-handover.md.
        write_zip(bundle, path, password=None)

        messagebox.showinfo(
            "Exported",
            f"{len(exports)} accessory(s) written to:\n{path}\n\n"
            "Anyone who has this file can locate them, and that cannot be undone.",
        )
        self.destroy()

    def _confirm_devices(self, chosen: list[Candidate]) -> list[Candidate] | None:
        """
        Ask again when the selection includes one of the owner's own devices.

        Not on every export - a warning that fires when nothing is wrong is one people learn to
        dismiss. Only for the case that is not what "select all" reads like.
        """
        devices = [candidate for candidate in chosen if is_own_device(candidate.owned_beacon)]

        if not devices:
            return chosen

        named = ", ".join(candidate.label for candidate in devices)
        keep = messagebox.askyesno(
            "That includes your own devices",
            f"Your selection includes {named}.\n\n"
            "Those are your own devices, not tags. Anyone you give this bundle to can locate them"
            " for as long as their keys are valid, and that cannot be undone.\n\n"
            "Include them anyway?",
        )

        if keep:
            return chosen

        kept = [candidate for candidate in chosen if not is_own_device(candidate.owned_beacon)]

        return kept or None


# -- small dialogs ------------------------------------------------------------------------


def _selection_colours() -> tuple[str, str]:
    """
    The colours this desktop already uses for a selected row, as (background, foreground).

    Looked up from the theme rather than written down here, so a ticked row is coloured the way
    every other list on the machine colours one - including greying out when the window loses
    focus, which Tk does itself by resolving the named system colour.

    ttk states arrive as a bare string on the aqua theme and as a tuple of them elsewhere, so this
    looks for one containing `selected` rather than indexing. The fallbacks are a plain blue and
    white, for a theme that maps neither: a colour that looks slightly wrong is recoverable, and a
    row that does not colour at all is the thing this exists to prevent.
    """
    style = ttk.Style()

    def _for_selected(option: str, fallback: str) -> str:
        for state, value in style.map("Treeview", option):
            states = (state,) if isinstance(state, str) else tuple(state)
            if "selected" in states and isinstance(value, str) and value:
                return value

        return fallback

    return _for_selected("background", "#3875d7"), _for_selected("foreground", "white")


def _ask_credentials(parent: tk.Tk) -> tuple[str, str]:
    """
    Ask for an Apple ID and its password together, in one window.

    One dialog rather than two: they are a single credential to the person typing them, and a
    second window appearing after the first reads as though the first one was rejected.

    Hand-rolled for the same reason :func:`_ask_string` is - `tkinter.simpledialog` cannot hide
    what is typed, and half of what this asks for is a password.
    """
    window = tk.Toplevel(parent)
    window.title("Sign in to iCloud")
    window.transient(parent)
    window.resizable(width=False, height=False)

    frame = ttk.Frame(window, padding=20)
    frame.pack(fill="both", expand=True)
    frame.grid_columnconfigure(1, weight=1)

    ttk.Label(
        frame,
        text="Your Apple ID and password are used to sign in and are not saved anywhere.",
        wraplength=320,
        justify="left",
        foreground="#555",
    ).grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 14))

    email = tk.StringVar()
    password = tk.StringVar()

    ttk.Label(frame, text="Apple ID").grid(row=1, column=0, sticky="w", padx=(0, 10), pady=4)
    email_entry = ttk.Entry(frame, textvariable=email, width=28)
    email_entry.grid(row=1, column=1, sticky="ew", pady=4)

    ttk.Label(frame, text="Password").grid(row=2, column=0, sticky="w", padx=(0, 10), pady=4)
    password_entry = ttk.Entry(frame, textvariable=password, width=28, show="\u2022")
    password_entry.grid(row=2, column=1, sticky="ew", pady=4)

    answer: dict[str, tuple[str, str]] = {}

    def _accept() -> None:
        if not (email.get().strip() and password.get()):
            return
        answer["value"] = (email.get().strip(), password.get())
        window.destroy()

    buttons = ttk.Frame(frame)
    buttons.grid(row=3, column=0, columnspan=2, sticky="e", pady=(16, 0))
    ttk.Button(buttons, text="Cancel", command=window.destroy).pack(side="right", padx=(8, 0))
    sign_in = ttk.Button(buttons, text="Sign in", command=_accept)
    sign_in.pack(side="right")

    # Half a credential is not worth a round trip to Apple to be told so.
    def _revalidate(*_args) -> None:
        sign_in.configure(state="normal" if email.get().strip() and password.get() else "disabled")

    email.trace_add("write", _revalidate)
    password.trace_add("write", _revalidate)
    _revalidate()

    # Enter moves on from the first field and submits from the second, which is what every other
    # sign-in form does.
    email_entry.bind("<Return>", lambda _event: password_entry.focus_set())
    password_entry.bind("<Return>", lambda _event: _accept())
    window.bind("<Escape>", lambda _event: window.destroy())

    email_entry.focus_set()
    centre_over(window, parent)
    window.grab_set()
    parent.wait_window(window)

    return answer.get("value", ("", ""))


def _ask_string(
    parent: tk.Tk,
    title: str,
    prompt: str,
    initial: str = "",
    *,
    secret: bool = False,
    valid: Callable[[str], bool] = bool,
    transform: Callable[[str], str] = str.strip,
) -> str:
    """
    Ask for one line of text, in a modal window.

    Hand-rolled rather than `tkinter.simpledialog`, which cannot hide what is typed - and one of
    the things this asks for is an Apple ID password.

    :param valid: Whether what has been typed can be sent. **OK stays disabled until it is**, so
        an empty box or a half-typed code cannot be submitted - which otherwise costs a round trip
        to Apple to be told what the dialog already knew. Defaults to "not empty".
    :param transform: Applied before validating and before returning, so the two cannot disagree
        about what was entered.
    """
    window = tk.Toplevel(parent)
    window.title(title)
    window.transient(parent)
    window.resizable(width=False, height=False)

    frame = ttk.Frame(window, padding=16)
    frame.pack(fill="both", expand=True)

    ttk.Label(frame, text=prompt, wraplength=380, justify="left").pack(anchor="w", pady=(0, 8))

    value = tk.StringVar(value=initial)
    entry = ttk.Entry(frame, textvariable=value, width=30, show="\u2022" if secret else "")
    entry.pack(fill="x")
    entry.focus_set()
    entry.select_range(0, "end")

    answer: dict[str, str] = {}

    def _accept() -> None:
        if not valid(transform(value.get())):
            return
        answer["value"] = transform(value.get())
        window.destroy()

    buttons = ttk.Frame(frame)
    buttons.pack(fill="x", pady=(12, 0))
    ttk.Button(buttons, text="Cancel", command=window.destroy).pack(side="right", padx=(8, 0))
    ok = ttk.Button(buttons, text="OK", command=_accept)
    ok.pack(side="right")

    def _revalidate(*_args) -> None:
        ok.configure(state="normal" if valid(transform(value.get())) else "disabled")

    value.trace_add("write", _revalidate)
    _revalidate()

    window.bind("<Return>", lambda _event: _accept())
    window.bind("<Escape>", lambda _event: window.destroy())

    centre_over(window, parent)
    window.grab_set()
    parent.wait_window(window)

    return answer.get("value", "")


def _ask_choice(parent: tk.Tk, title: str, prompt: str, options: Sequence[str]) -> int:
    """Pick one of several, by position. Returns 0 if the window is closed."""
    window = tk.Toplevel(parent)
    window.title(title)
    window.transient(parent)

    frame = ttk.Frame(window, padding=16)
    frame.pack(fill="both", expand=True)

    ttk.Label(frame, text=prompt, wraplength=420, justify="left").pack(anchor="w", pady=(0, 8))

    chosen = tk.IntVar(value=0)
    for index, option in enumerate(options):
        ttk.Radiobutton(frame, text=option, variable=chosen, value=index).pack(anchor="w")

    ttk.Button(frame, text="OK", command=window.destroy).pack(pady=(12, 0))

    centre_over(window, parent)
    window.grab_set()
    parent.wait_window(window)

    return chosen.get()


def _build_terms_window(parent: tk.Tk, document, index: int, total: int):
    """
    Build the terms dialog, and hand back the answer it will write into.

    **Split from :func:`_show_terms` so that it can be tested.** Everything worth checking here is
    in the widgets - the document is shown whole, no markup survives, closing means no - and all
    of it is unreachable from a test once `grab_set` and `wait_window` have been called, because
    those hand control to a nested event loop and a window manager. Building and showing are two
    steps for that reason and no other.

    :returns: The window, and a dict whose `value` is the answer. It starts False: the default for
        a contract nobody answered has to be the one that sends nothing, so Reject, Escape and the
        window's close button all leave it alone and only Accept sets it.

    **Fixed-pitch, and that is not a cosmetic choice.** :func:`exporter.terms.render` wraps to a
    column count and underlines its headings with a row of dashes as long as the heading - which
    lines up in a terminal and in nothing else. In a proportional font every heading rule comes
    out the wrong length, and a contract that looks broken invites the reasonable conclusion that
    it has been tampered with.

    Nothing here shortens or summarises: what is on screen is the document Apple sent, because it
    is what pressing Accept agrees to.
    """
    window = tk.Toplevel(parent)
    window.title(f"Apple's terms of service - {document.page_id}")
    window.transient(parent)

    frame = ttk.Frame(window, padding=16)
    frame.pack(fill="both", expand=True)
    frame.grid_columnconfigure(0, weight=1)
    frame.grid_rowconfigure(1, weight=1)

    # Said above the document rather than after it: somebody who has just typed a password and is
    # suddenly looking at a contract needs to know why before they start reading it.
    counted = f" ({index} of {total})" if total > 1 else ""
    ttk.Label(
        frame,
        text=(
            f"Apple will not finish signing you in until these terms are accepted{counted}."
            " They are shown in full - this is what you would be agreeing to.\n\n"
            "Accepting records your agreement on your Apple account. Nothing has been sent yet."
        ),
        wraplength=560,
        justify="left",
    ).grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 12))

    text = tk.Text(frame, wrap="word", width=_TERMS_COLUMNS, height=28, font="TkFixedFont")
    text.grid(row=1, column=0, sticky="nsew")

    scrollbar = ttk.Scrollbar(frame, orient="vertical", command=text.yview)
    scrollbar.grid(row=1, column=1, sticky="ns")
    text.configure(yscrollcommand=scrollbar.set)

    text.insert("1.0", terms.render(document.html, _TERMS_COLUMNS))
    # Read-only rather than merely discouraged. A Text is editable by default, and a contract you
    # can type into is not the document that was fetched.
    text.configure(state="disabled")

    accepted = {"value": False}

    def _accept() -> None:
        accepted["value"] = True
        window.destroy()

    buttons = ttk.Frame(frame)
    buttons.grid(row=2, column=0, columnspan=2, sticky="e", pady=(12, 0))
    ttk.Button(buttons, text="Reject", command=window.destroy).pack(side="right", padx=(8, 0))
    ttk.Button(buttons, text="Accept", command=_accept).pack(side="right")

    # No Return binding, deliberately. Every other dialog here submits on Return because its
    # answer is something the user typed; this one's answer is agreement to a contract, and a
    # stray keypress landing on it is not agreement.
    window.bind("<Escape>", lambda _event: window.destroy())

    return window, accepted


def _show_terms(parent: tk.Tk, document, index: int, total: int) -> bool:
    """
    Show one terms document in full, and wait for it to be agreed to or refused.

    :returns: True only if Accept was pressed. See :func:`_build_terms_window`, which is where
        everything except the waiting lives.
    """
    window, accepted = _build_terms_window(parent, document, index, total)

    centre_over(window, parent)
    window.grab_set()
    parent.wait_window(window)

    return accepted["value"]


async def _accept_pending_terms(parent: tk.Tk, account, asker: Asker, error) -> None:
    """
    Show whatever Apple wants agreeing to, and finish the sign-in if it is agreed to.

    **Only reached when signing in has already failed** on the delegate exchange, which is what an
    account with unaccepted terms does. Apple takes acceptance on one of its own devices or on
    iCloud.com and nowhere else, so somebody with neither is stuck without this - which is the
    whole reason the CLI grew it, and there was no reason for the window not to have it too.

    **Unlike the CLI, this does not ask permission to fetch first.** The CLI asks because it is
    about to page a document at a terminal and cannot take that back. Here the fetch decides
    whether there is anything to show at all, so asking first would mean offering to look and then
    reporting that there was nothing - where simply looking reports Apple's own message unchanged.

    :param error: What the delegate exchange said. Carried so that a failure for some other reason
        is reported as Apple worded it, rather than as "no terms found".
    :raises TermsDeclined: If any document is rejected. Nothing is sent for it, and no later
        document is shown.
    """
    try:
        documents = await account.fetch_terms()
    except TermsError as e:
        raise ExportSourceError(
            f"Signing in stopped at your account:\n\n{error}\n\n"
            f"Asking Apple which terms are pending also failed:\n\n{e}",
        ) from e

    if not documents:
        # Signing in failed for some other reason, and accepting nothing would not fix it. Apple's
        # own words, because they are the only description of the actual problem anyone has.
        raise ExportSourceError(
            f"Signing in got as far as your account and then stopped:\n\n{error}\n\n"
            "There are no terms of service waiting to be accepted, so this is something else.",
        )

    for index, document in enumerate(documents, start=1):
        # Bound as a default argument: the lambda runs on the main thread after this iteration has
        # moved on, and a closure over the loop variable would show the last document every time.
        agreed = asker.ask(
            lambda d=document, i=index: _show_terms(parent, d, i, len(documents)),
        )
        if not agreed:
            raise TermsDeclined(document.page_id)

        try:
            await account.accept_terms(document)
        except TermsError as e:
            raise ExportSourceError(
                f"Apple did not record agreement to the {document.page_id} terms:\n\n{e}",
            ) from e

    # The step `login` would have run itself had the terms not been pending. Without it the
    # account is left at AUTHENTICATED - readable, and unusable for everything after this.
    state = await account.complete_login()
    if state != LoginState.LOGGED_IN:
        raise ExportSourceError(f"The terms were accepted, but signing in ended at {state}.")


async def _async(value):
    """Wrap an already-computed answer, for the awaitable callbacks `icloud.log_in` expects."""
    return value


def _ask_again_for_credentials(parent: tk.Tk, asker: Asker, error, attempt: int):
    """
    Offer the Apple ID and password again after Apple rejects them.

    Both fields, not only the password: the Apple ID may be the one that is wrong, and a retry that
    will not let it be corrected is a dead end with a text box in it.
    """
    keep_going = asker.ask(lambda: messagebox.askretrycancel(
        "Sign in",
        f"Apple would not accept that sign-in (attempt {attempt} of"
        f" {icloud.MAX_LOGIN_ATTEMPTS}).\n\n{error}\n\n"
        "Apple locks an account after enough failed sign-ins, so it is worth being sure"
        " rather than guessing.",
        parent=parent,
    ))
    if not keep_going:
        raise _stopped()

    email, password = asker.ask(lambda: _ask_credentials(parent))
    if not email or not password:
        raise _stopped()

    return email, password


def _stopped() -> ExportSourceError:
    """
    What a user pressing Cancel means, as distinct from what Apple said.

    **Returning None here would re-raise Apple's rejection**, and the window would then report
    "Password authentication failed" to somebody whose last action was to deliberately stop. Worse,
    it used to arrive through the generic handler, so choosing to give up on a typo ended in a
    dialog asking them to file a bug with a log attached.
    """
    return ExportSourceError("Signing in was stopped. Nothing was changed.")


def _ask_again_for_code(parent: tk.Tk, asker: Asker, error, attempt: int):
    """
    Offer the verification code again, and send a new one only if asked.

    **Yes re-types, No sends a new one, Cancel stops**, and that order is deliberate: a resend
    invalidates the code Apple already sent, so the common case - a mistyped code that is still
    sitting on somebody's phone - must be the answer that does not destroy it. The legend is in the
    message because a three-button dialog cannot label its own buttons here.
    """
    answer = asker.ask(lambda: messagebox.askyesnocancel(
        "Verification",
        f"Apple would not accept that code (attempt {attempt} of"
        f" {icloud.MAX_CODE_ATTEMPTS}).\n\n{error}\n\n"
        "Yes - type the code again. The one Apple sent is still valid.\n"
        "No - send a new code. This cancels the one already sent.\n"
        "Cancel - stop signing in.",
        parent=parent,
    ))

    if answer is None:
        raise _stopped()

    return icloud.CODE_AGAIN if answer else icloud.CODE_RESEND


def log_file() -> Path:
    """
    Where this writes its log.

    **A windowed application has nowhere else to write.** PyInstaller builds one with no console,
    so `sys.stderr` is None and every log line and traceback goes nowhere at all - which is how
    this app came to report "[Errno 2] No such file or directory" with nothing to say what file.

    macOS has a conventional place for this and every other platform gets a temporary directory,
    which is not tidy but is findable, and findable is the whole point.
    """
    if sys.platform == "darwin":
        directory = Path.home() / "Library" / "Logs" / "OpenTagViewer"
    else:
        directory = Path(tempfile.gettempdir()) / "OpenTagViewer"

    directory.mkdir(parents=True, exist_ok=True)

    return directory / "exporter.log"


_WARNED_AT_THE_BOTTOM = False

_CAUTION = (
    "=" * 88,
    "  This log is for debugging and is NOT safe to publish as-is.",
    "",
    "  It names your devices by name, model and serial, records keychain item attributes",
    "  as Apple stores them, and identifies every device in your account's trust circle.",
    "  No key, password or passcode is written here, and payloads appear only as byte",
    "  counts - but nothing can promise a given identifier never appears, because the text",
    "  comes from a library reading Apple's own structures.",
    "",
    "  READ THIS FILE AND REMOVE ANYTHING THAT IDENTIFIES YOU BEFORE SENDING IT ANYWHERE.",
    "=" * 88,
)


def _warn_at_the_top_of_the_log() -> None:
    """
    Put the caution in the file, at the start of every run.

    **The CLI can warn the person before they turn logging on. The wizard cannot**: it logs at
    INFO unconditionally, because a windowed build has no console and a log that has to be
    enabled is a log nobody has when it is needed. So the file exists on every machine that has
    ever run this, and it holds what INFO holds - escrow records described by device name, model
    and serial, keychain item attributes as Apple stores them, an identifier per peer in the
    trust circle.

    The warning goes *in the file* rather than only in the window because that is what travels.
    Somebody asked to "attach exporter.log" sends the file and never sees the window again, so
    the caution has to be in what they open.

    **At both ends, for different reasons.** The file is appended to across runs and the end is
    where anybody looks first, because that is where the failure they came for is - a banner only
    at the top of a run is buried under that run's own output within seconds. But the end can only
    be written on the way out, and the interesting runs are the ones that die. So the head copy is
    the one that is always there, and the tail copy is the one that is actually read.
    """
    for line in _CAUTION:
        logging.getLogger("exporter.privacy").warning(line)

    atexit.register(_warn_at_the_bottom_of_the_log)


def _warn_at_the_bottom_of_the_log() -> None:
    """
    Leave the caution as the last thing in the file, since that is where a reader starts.

    Guarded against running twice: `configure_logging` is called again by `--self-test`, and two
    registrations would print the banner twice at exit. Harmless, and it reads like a bug in the
    thing whose whole job is to be believed.
    """
    global _WARNED_AT_THE_BOTTOM
    if _WARNED_AT_THE_BOTTOM:
        return

    _WARNED_AT_THE_BOTTOM = True

    for line in _CAUTION:
        logging.getLogger("exporter.privacy").warning(line)

    logging.shutdown()


def configure_logging() -> None:
    """Log to a file always, and to the console as well when there is one."""
    handlers: list[logging.Handler] = [logging.FileHandler(log_file(), encoding="utf-8")]

    # `sys.stderr` is None in a windowed build, and handing that to StreamHandler produces its own
    # failure on the first log line - inside the error handling, which is the worst place for one.
    if sys.stderr is not None:
        handlers.append(logging.StreamHandler(sys.stderr))

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
        handlers=handlers,
        force=True,
    )

    # **Before the caution, so it is the first line of every run.** A log that arrives attached
    # to a report used to say nothing about what produced it, so `exporter-bug.yml` had to ask -
    # and the answer people give is the version they remember, which on a checkout is whatever the
    # last release set. This is the same gap the app has with `Import.via`.
    logging.getLogger("exporter.version").info("OpenTagViewer exporter %s", describe_build())

    _warn_at_the_top_of_the_log()

    # Anything that escapes Tk's callback handling as well, which otherwise vanishes the same way.
    def _report(exc_type, value, tb) -> None:
        logger.error("Unhandled: %s", "".join(traceback.format_exception(exc_type, value, tb)))

    sys.excepthook = _report


def self_test() -> int:
    """
    Prove a frozen build can reach everything it needs, without a network or an account.

    **Starting is not enough of a smoke test**, and this exists because relying on it shipped a
    binary that signed in successfully and then died: FindMy.py keeps Apple's pinned root
    certificates as data files beside its code, and PyInstaller bundles code. The failure was
    `[Errno 2] No such file or directory`, several minutes and one Apple ID into the flow.

    So this touches the things that live in files rather than in modules. Anything else that
    starts doing so belongs here too.
    """
    from findmy.keychain.enrolment import PinnedRoots  # noqa: PLC0415

    # Reads every `.crt` beside the library's code and checks each against its own fingerprint,
    # so this proves the files are both present and intact.
    roots = PinnedRoots.bundled().by_version
    _say(f"pinned roots: {len(roots)} ({', '.join(str(v) for v in sorted(roots))})")

    # A namespace package would have no __file__, and a build that produced one would be broken in
    # a way worth reporting rather than crashing on.
    anisette_module = sys.modules["anisette"].__file__
    anisette_root = Path(anisette_module).parent / "apple-root.pem" if anisette_module else None
    found = anisette_root is not None and anisette_root.is_file()
    _say(f"anisette root: {'present' if found else 'MISSING'}")

    if not roots or not found:
        return 1

    if not _emulator_runs():
        return 1

    _say("self-test passed")
    return 0


def _say(line: str) -> None:
    """
    Print, and flush before the next thing can kill the process.

    **A native fault loses buffered output**, and stdout is block-buffered the moment it is a pipe
    rather than a terminal - which it always is in CI. So the first run of this on Windows died
    with no output at all: every line was still sitting in the buffer when the process was killed,
    and a self-test that cannot say how far it got is only marginally better than no self-test.
    """
    print(line, flush=True)


def _emulator_runs() -> bool:
    """
    Actually run the CPU emulator, rather than checking its file is present.

    **The two are not the same check, and the difference is a hard crash.** `unicorn` loads its
    native library through ctypes at runtime, so PyInstaller never sees it and `--collect-all
    unicorn` is what puts the file in the bundle. That makes the file *present*; it says nothing
    about whether the library loads and executes on the machine that ends up running it.

    A Windows user on 1.2.0 reported `OpenTagViewer.exe parou de funcionar` - the process killed
    by Windows, no Python traceback, nothing in any log, because a native fault does not raise. The
    checks above would all have passed: the data files were there. Nothing exercised the code.

    So this emulates four bytes of ARM64 and reads the register back. Local, instant, no network
    and no account - and it fails a build rather than a user's machine, which is the entire point.
    Anisette needs this to work, because emulating Apple's ADI library is how a sign-in happens
    without a third-party server.

    **Reported one call at a time**, because a native fault produces no exception and no
    traceback - so the only thing that says where it died is the last line that made it out.
    Each stage announces itself before it runs, and the failing call is whichever one has no
    answer after it. That is what turned "the Windows build dies somewhere" into a line number.
    """
    state: dict = {}

    for name, step in (
        ("import", _emu_import),
        ("construct", _emu_construct),
        ("mem_map", _emu_map),
        ("mem_write", _emu_write),
        ("emu_start", _emu_run),
    ):
        _say(f"cpu emulator: {name} …")
        try:
            step(state)
        except Exception as e:  # noqa: BLE001 - a broken bundle, not a bug worth raising
            _say(f"cpu emulator: {name} FAILED ({type(e).__name__}: {e})")
            return False

    answer = state["answer"]
    _say(f"cpu emulator: ran ARM64 and read back {answer}")

    return answer == 42


def _emu_import(state: dict) -> None:
    import unicorn  # noqa: PLC0415
    from unicorn import arm64_const  # noqa: PLC0415

    state["unicorn"] = unicorn
    state["arm64"] = arm64_const
    # The path matters: a bundle can carry the Python package and miss the native library beside
    # it, and then this is the last line before the process disappears.
    _say(f"cpu emulator: loaded {getattr(unicorn, '__file__', '?')}")


def _emu_construct(state: dict) -> None:
    unicorn = state["unicorn"]
    state["uc"] = unicorn.Uc(unicorn.UC_ARCH_ARM64, unicorn.UC_MODE_ARM)


def _emu_map(state: dict) -> None:
    state["uc"].mem_map(0x1000, 0x1000)


def _emu_write(state: dict) -> None:
    state["uc"].mem_write(0x1000, bytes.fromhex("400580d2"))  # movz x0, #42


def _emu_run(state: dict) -> None:
    state["uc"].emu_start(0x1000, 0x1004)
    state["answer"] = state["uc"].reg_read(state["arm64"].UC_ARM64_REG_X0)


if __name__ == "__main__":
    if "--self-test" in sys.argv[1:]:
        import anisette  # noqa: F401, PLC0415 - imported for its path, above

        sys.exit(self_test())

    # What the release build's smoke test runs. It does not avoid tkinter - the imports above have
    # already happened - and that is the point: it proves the frozen bundle can import everything
    # it was built with, including Tk, without needing a display to do it.
    if "--version" in sys.argv[1:]:
        print(VERSION)
        sys.exit(0)

    configure_logging()
    logger.info("Starting %s", APP_TITLE)

    WizardApp().mainloop()
