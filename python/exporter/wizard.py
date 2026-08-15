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
import time
import tkinter as tk
import webbrowser
from pathlib import Path
from tkinter import messagebox, ttk
from typing import Sequence
from tkinter.filedialog import askopenfilenames, asksaveasfilename

from exporter import icloud, localsource, source
from exporter.asyncui import Asker, Cancelled, run_with_progress
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

        self.choices = tk.Listbox(container, selectmode="multiple", activestyle="none", font=("Menlo", 11))
        self.choices.grid(row=2, column=0, columnspan=3, sticky="nsew")

        scrollbar = ttk.Scrollbar(container, orient="vertical", command=self.choices.yview)
        scrollbar.grid(row=2, column=3, sticky="ns")
        self.choices.configure(yscrollcommand=scrollbar.set)

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
                f"{e}\n\nIf this looks like a bug, please report it:\n{GITHUB_ISSUES_LINK}",
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

            email = asker.ask(lambda: _ask_string(self, "Apple ID", "Your Apple ID:"))
            password = asker.ask(lambda: _ask_string(self, "Password", "Apple ID password:", secret=True))

            await icloud.log_in(
                account,
                email,
                password,
                choose_second_factor=lambda methods: _async(
                    asker.ask(lambda: _ask_choice(self, "Verification", "How should Apple send the code?", methods)),
                ),
                get_code=lambda: _async(
                    asker.ask(lambda: _ask_string(self, "Verification", "The code Apple sent:")),
                ),
            )

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
        """Fill the list, and say what was set aside."""
        self.choices.delete(0, "end")

        for candidate in self.candidates:
            self.choices.insert("end", _row(candidate.label, candidate.details, candidate.has_alignment))

        for tag in self.custom_tags:
            self.choices.insert("end", _row(tag.name, "added from a key file", has_alignment=True))

        self.heading.configure(text="Choose what to export")
        self.confirm_button.configure(state="normal" if self.choices.size() else "disabled")

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

    def _selected(self) -> tuple[list[Candidate], list[PreparedTag]]:
        """Split the selection back into where each row came from."""
        indices = list(self.choices.curselection())
        boundary = len(self.candidates)

        return (
            [self.candidates[i] for i in indices if i < boundary],
            [self.custom_tags[i - boundary] for i in indices if i >= boundary],
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


def _row(label: str, details: str, has_alignment: bool) -> str:
    """One line of the list: what it is, then what is worth knowing before choosing it."""
    warning = "" if has_alignment else "  ⚠ no alignment record: slow first locate"

    return f"{label:<28}{details}{warning}"


def _ask_string(parent: tk.Tk, title: str, prompt: str, initial: str = "", *, secret: bool = False) -> str:
    """
    Ask for one line of text, in a modal window.

    Hand-rolled rather than `tkinter.simpledialog`, which cannot hide what is typed - and one of
    the two things this asks for is an Apple ID password.
    """
    window = tk.Toplevel(parent)
    window.title(title)
    window.transient(parent)
    window.resizable(width=False, height=False)

    frame = ttk.Frame(window, padding=16)
    frame.pack(fill="both", expand=True)

    ttk.Label(frame, text=prompt, wraplength=380, justify="left").pack(anchor="w", pady=(0, 8))

    value = tk.StringVar(value=initial)
    entry = ttk.Entry(frame, textvariable=value, width=44, show="•" if secret else "")
    entry.pack(fill="x")
    entry.focus_set()
    entry.select_range(0, "end")

    answer: dict[str, str] = {}

    def _accept() -> None:
        answer["value"] = value.get().strip()
        window.destroy()

    buttons = ttk.Frame(frame)
    buttons.pack(fill="x", pady=(12, 0))
    ttk.Button(buttons, text="Cancel", command=window.destroy).pack(side="right", padx=(8, 0))
    ttk.Button(buttons, text="OK", command=_accept).pack(side="right")

    window.bind("<Return>", lambda _event: _accept())
    window.bind("<Escape>", lambda _event: window.destroy())

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

    window.grab_set()
    parent.wait_window(window)

    return chosen.get()


async def _async(value):
    """Wrap an already-computed answer, for the awaitable callbacks `icloud.log_in` expects."""
    return value


if __name__ == "__main__":
    # What the release build's smoke test runs. It does not avoid tkinter - the imports above have
    # already happened - and that is the point: it proves the frozen bundle can import everything
    # it was built with, including Tk, without needing a display to do it.
    if "--version" in sys.argv[1:]:
        print(VERSION)
        sys.exit(0)

    logging.basicConfig(level=logging.INFO, format="%(levelname)-8s %(name)s: %(message)s")

    WizardApp().mainloop()
