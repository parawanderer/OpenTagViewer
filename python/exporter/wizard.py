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

from exporter import icloud, localsource, source
from exporter.asyncui import Asker, Cancelled, run_with_progress
from exporter.tkutil import centre_on_screen, centre_over
from exporter.custom_tags import (
    CustomTagError,
    PreparedTag,
    check_advertisement_key,
    suggested_identifier,
    suggested_name,
)
from exporter.icloud import Candidate, ExportSourceError
from exporter.version import APP_TITLE, EXPORT_VIA_WIZARD, VERSION
from opentagviewer_export import (
    ExportError,
    KeyFileError,
    build_export,
    parse_key_file,
    write_zip,
)
from opentagviewer_export.hardware import is_own_device

logger = logging.getLogger(__name__)

GITHUB_ISSUES_LINK = "https://github.com/parawanderer/OpenTagViewer/issues/new"
WIKI_LINK = "https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Export-AirTags-From-Mac"

# Kept for anything still importing them from here.
EXPORT_METADATA_VIA_NAME = EXPORT_VIA_WIZARD

VERIFICATION_CODE_LENGTH = 6

TICKED = "\u2611"
UNTICKED = "\u2610"

_KEY_FILE_TYPES = [
    ("Key files", "*.json *.keys *.txt"),
    ("All files", "*.*"),
]


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
        self.route = source.detect()

        self._build()
        centre_on_screen(self)

        # After the window exists, so a failure has somewhere to report itself.
        self.after(100, self._load)

    # -- layout ---------------------------------------------------------------------------

    def _build(self) -> None:
        container = ttk.Frame(self, padding=12)
        container.pack(fill="both", expand=True)
        container.grid_rowconfigure(2, weight=1)
        container.grid_columnconfigure(0, weight=1)

        self.heading = ttk.Label(container, text="Reading your accessories…", font=("Arial", 13, "bold"))
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
        buttons.grid_columnconfigure(1, weight=1)

        # The "+" is for tags that were never in an Apple account - OpenHaystack and the like.
        # They cannot be fetched, because there is nothing to fetch them from.
        self.add_button = ttk.Button(buttons, text="+ Add from key file…", command=self._add_key_file)
        self.add_button.grid(row=0, column=0, sticky="w")

        help_label = ttk.Label(buttons, text="Need help?", cursor="hand2", foreground="#0645AD")
        help_label.grid(row=0, column=1)
        help_label.bind("<Button-1>", lambda _event: webbrowser.open(WIKI_LINK, new=2, autoraise=True))

        ttk.Button(buttons, text="Cancel", command=self.destroy).grid(row=0, column=2, padx=(0, 8))
        self.confirm_button = ttk.Button(buttons, text="Export…", command=self._export, state="disabled")
        self.confirm_button.grid(row=0, column=3)

    def _route_line(self) -> str:
        where = "this Mac's own Find My files" if self.route.is_local else "your iCloud account"
        return f"Reading from {where}, because {self.route.reason}."

    # -- loading --------------------------------------------------------------------------

    def _load(self) -> None:
        """Read whichever source this machine can use, and fill the list."""
        try:
            fetched = (
                run_with_progress(self, "Reading this Mac's Find My files…", self._read_local)
                if self.route.is_local
                else run_with_progress(self, "Signing in to iCloud…", self._read_icloud)
            )
        except Cancelled:
            self.destroy()
            return
        except (ExportSourceError, ExportError) as e:
            messagebox.showerror("Could not read your accessories", str(e))
            self.destroy()
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
            self.destroy()
            return

        self.candidates = fetched.candidates
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

            await icloud.log_in(
                account,
                email,
                password,
                choose_second_factor=lambda methods: _async(
                    asker.ask(lambda: _ask_choice(self, "Verification", "How should Apple send the code?", methods)),
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
            )

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

                passcode = asker.ask(lambda: _ask_string(
                    self, "Unlock", f"Screen-lock passcode for {chosen.serial}:", secret=True,
                ))

                await client.unlock(chosen, passcode)

                return await icloud.fetch(client)
        finally:
            await account.close()

    # -- the list -------------------------------------------------------------------------

    def _show(self, skipped: list) -> None:
        """Fill the table, and say what was set aside."""
        self.choices.delete(*self.choices.get_children())
        self.ticked.clear()

        for index, candidate in enumerate(self.candidates):
            self.choices.insert(
                "", "end", iid=f"c{index}",
                values=(
                    UNTICKED,
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
                values=(UNTICKED, tag.name, "self-generated tag", "", "", "from a key file"),
            )

        self.heading.configure(text="Choose what to export")
        self.confirm_button.configure(state="normal" if self.choices.get_children() else "disabled")

        notes = []
        if skipped:
            notes.append(f"{len(skipped)} record(s) could not be exported: they carry no key material.")
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

        self._show([])

    # -- exporting ------------------------------------------------------------------------

    def _toggle(self, event: tk.Event) -> None:
        """Tick or untick whichever row was clicked. One click, no modifier keys."""
        row = self.choices.identify_row(event.y)
        if not row:
            return

        if row in self.ticked:
            self.ticked.discard(row)
            self.choices.set(row, "tick", UNTICKED)
        else:
            self.ticked.add(row)
            self.choices.set(row, "tick", TICKED)

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

        # No password from the window yet: nothing that imports these can open a locked one, so
        # offering it here would produce a file the recipient cannot use. See the CLI's
        # --no-password, and docs/android-import-handover.md.
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


def verification_code(typed: str) -> str:
    """
    Read a verification code out of whatever was typed.

    Apple sends six digits, and people paste them with a space or a hyphen in the middle because
    that is how the notification shows them. Taking the digits is kinder than rejecting the paste.
    """
    return "".join(character for character in typed if character.isdigit())


def is_verification_code(typed: str) -> bool:
    """Whether that is a code worth sending. Apple's are six digits."""
    return len(verification_code(typed)) == VERIFICATION_CODE_LENGTH


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


async def _async(value):
    """Wrap an already-computed answer, for the awaitable callbacks `icloud.log_in` expects."""
    return value


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
    print(f"pinned roots: {len(roots)} ({', '.join(str(v) for v in sorted(roots))})")

    # A namespace package would have no __file__, and a build that produced one would be broken in
    # a way worth reporting rather than crashing on.
    anisette_module = sys.modules["anisette"].__file__
    anisette_root = Path(anisette_module).parent / "apple-root.pem" if anisette_module else None
    found = anisette_root is not None and anisette_root.is_file()
    print(f"anisette root: {'present' if found else 'MISSING'}")

    if not roots or not found:
        return 1

    print("self-test passed")
    return 0


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
