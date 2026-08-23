"""
Export AirTags from an iCloud account, without a Mac and without a window.

    python -m exporter.cli --output tags.zip

What it does, in order: sign in, ask which recovery record to unlock the keychain with, fetch the
accessories, ask which to export, and write a zip. The windowed wizard does the same things with
the same code underneath - this exists because a terminal can show a two-factor prompt and a
terms-of-service page without any of it being built first, and because it is what a headless
machine can run.

**Nothing is stored.** No account file, no keys on disk, no cached passcode. Both passwords are
used inside one call each and dropped; a second run asks again. That is a deliberate departure
from FindMy.py's examples, which save the account - password included - to `account.json`.

**What it writes cannot be taken back.** An exported accessory can only be revoked by unpairing
it, so the bundle is locked with a code by default, and the code is printed once.
"""

from __future__ import annotations

import argparse
import asyncio
import getpass
import logging
import os
import pydoc
import sys
import time
import traceback
from pathlib import Path
from typing import Sequence

from findmy import InvalidCredentialsError, LoginState, MobileMeDelegateError, TermsError
from findmy.errors import UnhandledProtocolError
from findmy.keychain.recovery import RecoveryError

from exporter import icloud, localsource, prompts, secrets, source, terms
from exporter.codes import (
    VERIFICATION_CODE_LENGTH,
    is_verification_code,
    verification_code,
)
from exporter.custom_tags import (
    CustomTagError,
    PreparedTag,
    check_advertisement_key,
    suggested_identifier,
    suggested_name,
)
from exporter.icloud import Candidate, ExportSourceError
from exporter.version import EXPORT_VIA_CLI, GITHUB_ISSUES_LINK, VERSION, describe_build
from opentagviewer_export import (
    ExportError,
    KeyFileError,
    build_export,
    format_passcode,
    generate_passcode,
    parse_key_file,
    write_zip,
)
from opentagviewer_export.hardware import is_own_device, where_to_look_up

logger = logging.getLogger(__name__)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="opentagviewer-export",
        description="Export AirTags from an iCloud account into a bundle OpenTagViewer imports.",
        epilog=(
            "Nothing is saved: your Apple ID password and your device passcode are used once and"
            " dropped, and no account file is written."
        ),
        # **Off because it silently recreates the flag this deliberately does not have.** argparse
        # accepts any unambiguous prefix by default, so `--password hunter2` is taken as
        # `--password-file hunter2` - the exact spelling somebody reaches for when they want to put
        # a secret on the command line, quietly accepted. It then fails looking for a file named
        # `hunter2`, by which point the password is already in `ps` output and shell history.
        #
        # Abbreviations worth having are spelled out as aliases instead, which is the same
        # convenience without a rule that invents flag names nobody wrote down.
        allow_abbrev=False,
    )

    parser.add_argument("--version", action="version", version=VERSION)
    parser.add_argument(
        "-o",
        "--output",
        # Spelled out rather than left to prefix matching, which is off - see allow_abbrev above.
        "--out",
        type=Path,
        help="Where to write the bundle. Defaults to a timestamped zip in the current directory.",
    )
    parser.add_argument(
        "--source-user",
        help=(
            "What the recipient sees as 'exported by'. Defaults to this machine's username."
            " Never your Apple ID - nothing identifying the account travels in a bundle."
        ),
    )
    parser.add_argument(
        "--add-keys",
        action="append",
        type=Path,
        metavar="FILE",
        default=[],
        help=(
            "Add a self-generated tag from a key file - OpenHaystack, Macless Haystack, or a list"
            " of keys. Repeatable. These are not in any Apple account, so they are read from disk"
            " rather than fetched."
        ),
    )
    parser.add_argument(
        "--no-password",
        action="store_true",
        help=(
            "Write a plain zip. The bundle is locked with a generated code by default, because it"
            " holds keys that cannot be revoked - but no released version of the Android app can"
            " open a locked one yet, so a bundle for somebody else needs this."
        ),
    )
    parser.add_argument(
        "--accept-terms",
        action="store_true",
        help=(
            "Agree to any pending iCloud terms without reading them. For a run nobody is watching."
            " What is being agreed to is still named, and it is still a contract."
        ),
    )
    parser.add_argument(
        "--source",
        choices=source.CHOICES,
        default=source.AUTO,
        help=(
            "Where to read from. `auto` uses this Mac's own Find My files when it can - which asks"
            " for no Apple ID password and registers no device on your account - and iCloud"
            " otherwise, which is every other machine and every macOS 15 or newer. `none` reads no"
            " account at all, for a bundle of nothing but --add-keys tags."
        ),
    )
    scripted = parser.add_argument_group(
        "Running without a person",
        "Answers given up front are not asked for. Sign in once by hand first: this machine is"
        " then a device Apple knows, and later runs usually skip the verification code.",
    )
    scripted.add_argument(
        "--apple-id",
        help="The Apple ID to sign in as, instead of being asked for it.",
    )
    scripted.add_argument(
        "--password-file",
        type=Path,
        metavar="PATH",
        help=(
            f"Read the Apple ID password from this file, or from standard input if it is '-'."
            f" The file must not be readable by other users. ${secrets.APPLE_PASSWORD_VAR} is"
            f" also read, and is second best. There is deliberately no --password flag: a"
            f" command line is visible to everyone on the machine."
        ),
    )
    scripted.add_argument(
        "--passcode-file",
        type=Path,
        metavar="PATH",
        help=(
            f"Read the device screen-lock passcode the same way."
            f" ${secrets.DEVICE_PASSCODE_VAR} is also read."
        ),
    )
    scripted.add_argument(
        "--device",
        metavar="SERIAL",
        help=(
            "Unlock using the escrow record of the device with this serial, instead of asking"
            " which one. Match is case-insensitive."
        ),
    )
    scripted.add_argument(
        "--all-tags",
        action="store_true",
        help=(
            "Export every accessory found, instead of asking which. Your own iPhones, iPads and"
            " Macs are left out unless --include-my-devices is given as well."
        ),
    )
    scripted.add_argument(
        "--include-my-devices",
        action="store_true",
        help=(
            "Let --all-tags include your own devices. A bundle holding your MacBook lets whoever"
            " receives it locate you, so this is never implied."
        ),
    )
    scripted.add_argument(
        "--non-interactive",
        action="store_true",
        help=(
            "Fail rather than ask anything. Without it, a missing answer is prompted for and a"
            " scripted run hangs waiting for a keystroke that never comes."
        ),
    )

    parser.add_argument(
        "--anisette-url",
        help=(
            "Use a remote Anisette server instead of running it locally. Local is the default and"
            " keeps the sign-in between this machine and Apple."
        ),
    )
    parser.add_argument(
        "--anisette-libs",
        type=Path,
        help="Where to cache Apple's ADI libraries, so a later run does not download them again.",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="count",
        default=0,
        help=(
            "Show what the library is doing. Twice (-vv) turns on the CloudKit and keychain"
            " protocol steps, which is what to send with a bug report."
        ),
    )

    return parser


def configure_logging(verbosity: int) -> None:
    """
    Turn logging up, in two steps rather than one.

    `-vv` puts the two chatty packages at DEBUG and leaves the root at INFO deliberately: at DEBUG
    the root logger also carries aiohttp and asyncio, which produce far more output than either
    and none of it about Apple. What is worth reading is `findmy.cloudkit` - the record fetch and
    the field decryption - and `findmy.keychain`, which is escrow recovery.
    """
    logging.basicConfig(
        level=logging.WARNING if verbosity == 0 else logging.INFO,
        format="%(levelname)-8s %(name)s: %(message)s",
    )

    # Same reason as the wizard's copy: a `-vv` log is what a report attaches, and it said
    # nothing about which build wrote it.
    logging.getLogger("exporter.version").info("OpenTagViewer exporter %s", describe_build())

    if verbosity >= 2:
        # **The package, not a list of its subpackages.** This used to name `findmy.cloudkit`,
        # `findmy.keychain` and `findmy.icloud`, which is every part anybody had needed so far and
        # therefore wrong the first time a new one mattered: `announce_device` logs under
        # `findmy.reports`, so the diagnostic written specifically to explain a failure was
        # discarded by the flag turned on to see it.
        #
        # The reason it was a list is still real - the *root* logger at DEBUG carries aiohttp and
        # asyncio, which drown everything about Apple in socket chatter. `findmy` is the level that
        # excludes those without having to predict which of its modules will matter next.
        for name in ("findmy", "exporter"):
            logging.getLogger(name).setLevel(logging.DEBUG)

    if verbosity:
        warn_about_sharing_logs()


def warn_about_sharing_logs() -> None:
    """
    Say what is about to be printed, before it is printed.

    **Said here rather than only in the docs**, because the person who turns this on is usually
    about to paste the result into an issue, and by then it has scrolled past. The docs are read
    before a first run; this is read at the moment it matters.

    The list is specific on purpose. "Contains personal information" is easy to skim past and
    tells nobody what to look for; a device serial and a keychain `acct` field are things somebody
    can actually find and delete.
    """
    lines = [
        "  Verbose output is for debugging, not for publishing. It names your devices by",
        "  name, model and serial, prints keychain item attributes as Apple stores them,",
        "  and identifies every device in your trust circle.",
        "",
        "  No key, password or passcode is logged. But this cannot promise a given",
        "  identifier never appears, because the text comes from a library reading",
        "  Apple's own structures.",
        "",
        "  Read it and strip anything that identifies you before pasting it anywhere.",
    ]

    print("", file=sys.stderr)
    for line in lines:
        print(_red(line), file=sys.stderr)
    print("", file=sys.stderr)


def _red(text: str) -> str:
    """
    Colour, when there is a terminal to colour and the reader has not asked otherwise.

    **Guarded rather than unconditional.** The likely next step after reading this is piping the
    run to a file to attach it, and escape codes written into that file are noise in the issue -
    which is the opposite of what the warning is for. `NO_COLOR` is honoured because it costs one
    lookup and somebody has already decided.
    """
    if not sys.stderr.isatty() or os.environ.get("NO_COLOR"):
        return text

    return f"\033[31m{text}\033[0m"


# ---------------------------------------------------------------------------------------------
# Asking
# ---------------------------------------------------------------------------------------------


async def ask_choice(question: str, options: Sequence[str]) -> int:
    """Pick one of several, by its position."""
    return await prompts.select(
        question,
        [prompts.Option(label=option, value=index) for index, option in enumerate(options)],
    )


async def _retry_credentials(error, attempt: int):
    """
    Offer the Apple ID and password again after Apple rejects them.

    Both are asked for again rather than only the password: the Apple ID may be the wrong one, and
    a screen that will not let you correct it is worse than one extra field to press enter on.
    """
    print(f"\nApple would not accept that sign-in:\n\n  {error}\n", file=sys.stderr)
    print(f"Attempt {attempt} of {icloud.MAX_LOGIN_ATTEMPTS}. Apple locks an account after enough",
          file=sys.stderr)
    print("failed sign-ins, so it is worth being sure rather than guessing.\n", file=sys.stderr)

    if not await prompts.confirm("Try again?"):
        return None

    email = await prompts.text("Apple ID")

    return email, await prompts.password("Password")


async def _retry_code(error, attempt: int):
    """
    Offer the verification code again, and only send a new one if asked.

    **Re-typing is the default and sending a new code is not**, because a resend invalidates the
    code Apple already sent - so somebody who simply mistyped the code in front of them would have
    it taken away by the recovery step.
    """
    print(f"\nApple would not accept that code:\n\n  {error}\n", file=sys.stderr)
    print(f"Attempt {attempt} of {icloud.MAX_CODE_ATTEMPTS}.", file=sys.stderr)

    choice = await ask_choice("What now?", [
        "Type the code again (the one Apple sent is still valid)",
        "Send a new code (this cancels the one already sent)",
        "Stop",
    ])

    return [icloud.CODE_AGAIN, icloud.CODE_RESEND, None][choice]


async def _ask_verification_code() -> str:
    """
    Ask for the code Apple sent, and keep asking until it could be one.

    The same check the window makes, for the same reason: a code Apple rejects costs another
    send and another wait, so "12345" is worth catching before it goes anywhere. What is
    returned is the digits alone, since people paste the hyphen the notification shows them.
    """
    while True:
        typed = await prompts.text(f"Verification code ({VERIFICATION_CODE_LENGTH} digits)")

        if is_verification_code(typed):
            return verification_code(typed)

        print(
            f"That is not a {VERIFICATION_CODE_LENGTH}-digit code. Spaces and hyphens are fine.",
            file=sys.stderr,
        )


# ---------------------------------------------------------------------------------------------
# The flow
# ---------------------------------------------------------------------------------------------


async def accept_terms(account, *, without_reading: bool) -> bool:
    """
    Show any pending iCloud terms and offer to accept them. Returns whether signing in can go on.

    **Only reached when signing in has already failed** on the delegate exchange, which is what an
    account with unaccepted terms does. Apple takes acceptance on one of its own devices or on
    iCloud.com and nowhere else, so somebody with neither is stuck without this.

    Agreeing is a deliberate act: nothing is sent unless the user types ACCEPT, and only for the
    document they were just shown.
    """
    documents = await account.fetch_terms()

    if not documents:
        # Signing in failed for some other reason, and accepting nothing would not fix it.
        return False

    print(f"\nApple wants agreement to {len(documents)} document(s) before this account can", file=sys.stderr)
    print("be used. They are shown in full - this is what you would be agreeing to.\n", file=sys.stderr)

    for document in documents:
        print(f"  {terms.summarise(document.page_id, document.html)}", file=sys.stderr)

    for document in documents:
        if not without_reading:
            # Through the system pager, so a long document scrolls rather than flying past. Falls
            # back to plain output when there is no pager or no terminal.
            pydoc.pager(_page(document))

            print(f"\nAgreeing records acceptance of the {document.page_id} terms on your Apple", file=sys.stderr)
            print("account. Nothing has been sent yet.\n", file=sys.stderr)

            if await prompts.text("Type ACCEPT to agree, or anything else to stop") != "ACCEPT":
                print("\nNot accepted. Nothing was sent, and your account is unchanged.", file=sys.stderr)
                return False
        else:
            print(f"\nAccepting {document.page_id} unread, because --accept-terms was given.", file=sys.stderr)

        await account.accept_terms(document)

    state = await account.complete_login()
    if state != LoginState.LOGGED_IN:
        raise ExportSourceError(f"The terms were accepted, but signing in ended at {state}.")

    return True


def _page(document) -> str:
    """One document, rendered with a heading that says what it is and where it ends."""
    width = terms.terminal_width()
    rule = "=" * width

    return "\n".join([
        rule,
        f"  {document.page_id} terms of service",
        rule,
        "",
        terms.render(document.html, width),
        "",
        rule,
        f"  end of the {document.page_id} terms",
        rule,
        "",
        "  Press q to leave this view, then type ACCEPT to agree.",
        "",
    ])


async def sign_in(arguments: argparse.Namespace):
    """Sign in, and hand back the logged-in account."""
    account = icloud.make_account(
        arguments.anisette_url,
        str(arguments.anisette_libs) if arguments.anisette_libs else None,
    )

    # Named as it will actually appear. The model and OS come from FindMy.py, which presents as a
    # MacBook Pro, so somebody who was only told the serial goes looking for a device they own.
    print("\nSigning in registers this exporter as a device on your Apple account. It appears in",
          file=sys.stderr)
    print("your device list as a MacBook Pro on macOS 13.4.1, serial 0PENTAGXPORT - that is this",
          file=sys.stderr)
    print("program, not a Mac you own. Remove it any time at account.apple.com > Devices.\n",
          file=sys.stderr)

    email = arguments.apple_id or await prompts.text("Apple ID")
    password = (
        secrets.read(arguments.password_file, secrets.APPLE_PASSWORD_VAR)
        or await prompts.password("Password")
    )

    try:
        # Nested rather than another `except` clause beside the one below, and that is not a style
        # choice: an exception raised inside an `except` block is not caught by a sibling `except`
        # on the same `try`. Written flat, every way out of the terms handler skipped the close -
        # which is exactly the case the close exists for.
        try:
            await icloud.log_in(
                account,
                email,
                password,
                choose_second_factor=lambda methods: ask_choice("How should Apple send the code?", methods),
                get_code=_ask_verification_code,
                retry_credentials=_retry_credentials,
                retry_code=_retry_code,
            )
        except MobileMeDelegateError as e:
            # Authentication itself worked; the exchange that follows it did not. Unaccepted terms
            # are the one cause of that with a remedy here, and which error value means "terms
            # pending" is not established - so this says what Apple said and then offers, rather
            # than assuming.
            print(f"\nSigning in got as far as your account and then stopped:\n\n  {e}\n", file=sys.stderr)
            print("If that is about terms of service, they can be shown and accepted here.", file=sys.stderr)
            print("If it is about something else, accepting terms will not fix it.\n", file=sys.stderr)

            if not arguments.accept_terms and not await prompts.confirm("Fetch the terms of service?"):
                raise ExportSourceError("Signing in stopped at the delegate exchange.") from None

            if not await accept_terms(account, without_reading=arguments.accept_terms):
                raise ExportSourceError("Signing in stopped at the delegate exchange.") from None

        # Signing in registered a device, whichever of the two ways it got here; remembering which
        # one is what stops the next export registering another. Out here rather than inside the
        # handler, where it only ran for an account that had terms pending - so the ordinary
        # sign-in, which is nearly every run, stored nothing and registered a fresh Mac each time.
        icloud.remember(account)
    except BaseException:
        # The account owns an HTTP session, and a sign-in that fails leaves it open - which
        # surfaces as "Unclosed client session" from asyncio, after the real error, where it reads
        # like a second fault. Only `run` closes it on the way out, and only once this returns.
        await account.close()
        raise
    finally:
        del password  # used inside the call above and wanted no longer

    return account


async def unlock(client, arguments: argparse.Namespace) -> bool:
    """
    Recover the keychain keys, which needs the passcode of one of the account's devices.

    Read-only, like everything else here: the shares are wrapped to a key escrow recovery yields,
    so nothing is created, signed or enrolled by asking.
    """
    options = await client.recovery_options()

    if not options.recoverable:
        print("\nNo record on this account can currently be recovered from.", file=sys.stderr)
        if not options.viability_is_trustworthy:
            print("Nothing was reported usable at all, which reads as a service having a bad day", file=sys.stderr)
            print("rather than an account with nothing to recover from. Worth trying again later.", file=sys.stderr)
        return False

    print("\nUnlocking needs the screen-lock passcode of one of your Apple devices -", file=sys.stderr)
    print("its PIN or login password, not your Apple ID password.\n", file=sys.stderr)

    chosen = _pick_device(options.recoverable, arguments.device) or options.recoverable[
        await ask_choice(
            "Which device's passcode do you have?",
            [record.describe() for record in options.recoverable],
        )
    ]

    # Read once, outside the loop: re-reading a file or the environment on every attempt would
    # retry the identical value three times and report it as three rejections.
    supplied = secrets.read(arguments.passcode_file, secrets.DEVICE_PASSCODE_VAR)

    async def ask(attempt: int) -> str:
        if attempt == 1 and supplied is not None:
            return supplied

        again = " (try again)" if attempt > 1 else ""
        return await prompts.password(f"Screen-lock passcode for {chosen.serial}{again}")

    async def rejected(error, attempt: int) -> bool:
        # The library's own text, printed whole. It says what was rejected and then three things
        # worth doing about it, in the order worth doing them - the first being to try the same
        # passcode again, because this call has been seen to fail intermittently.
        print(f"\nThat was not accepted:\n\n{error}\n", file=sys.stderr)
        print(f"Attempt {attempt} of {icloud.MAX_UNLOCK_ATTEMPTS}.", file=sys.stderr)

        return await prompts.confirm("Try again?")

    await icloud.unlock(client, chosen, ask, rejected)

    return True


def _pick_device(recoverable, serial: str | None):
    """
    Find the escrow record `--device` names, so a scripted run does not have to answer a menu.

    :raises ExportSourceError: If nothing matches. Naming what is available, because a serial is
        easy to mistype and the alternative is a run that silently unlocks with the wrong device.
    """
    if serial is None:
        return None

    wanted = serial.strip().casefold()
    for record in recoverable:
        if (record.serial or "").strip().casefold() == wanted:
            return record

    available = ", ".join(sorted(r.serial for r in recoverable if r.serial)) or "none"
    msg = f"No recoverable device on this account has the serial {serial!r}. Available: {available}"
    raise ExportSourceError(msg)


def _take_all(candidates: list[Candidate], *, include_my_devices: bool) -> list[Candidate]:
    """
    Everything, for `--all-tags`, with the account's own hardware left out by default.

    **"All my tags" and "all my tags plus the laptop I am sitting at" are different requests**, and
    only one of them is what somebody writing a cron job meant. The interactive path names each
    device and asks again before including one; a scripted run has nobody to ask, so the safe half
    is the default and the other half has its own flag.
    """
    if include_my_devices:
        return list(candidates)

    keeping = [c for c in candidates if not is_own_device(c.owned_beacon)]

    for left_out in [c for c in candidates if is_own_device(c.owned_beacon)]:
        print(f"Leaving out {left_out.label}: it is one of your own devices."
              " --include-my-devices exports it anyway.", file=sys.stderr)

    return keeping


async def choose(candidates: list[Candidate], *, or_nothing: bool = False) -> list[Candidate]:
    """
    Ask which accessories to export.

    **Nothing is ticked to begin with.** Handing over one tag and handing over a household's whole
    set are different acts, and what is handed over cannot be withdrawn afterwards - so a list that
    arrived pre-selected would make those the same keystroke.

    :param or_nothing: Whether taking none of them is a valid answer. True when the run already
        has key-file tags to export, where "none of these" is a choice rather than an empty run.
    """
    for candidate in candidates:
        # Only where nothing here recognised the maker, and only once per run: a real registry
        # value somebody can settle in a browser beats a name this program invented.
        advice = where_to_look_up(candidate.owned_beacon)
        if advice and not candidate.name:
            print(f"\n{advice}", file=sys.stderr)

    chosen = await prompts.checkbox(
        # Not "accessories": three of the six rows on a real account were the owner's own devices,
        # and this is the exact moment to be precise about what is leaving the account.
        "What should go in the bundle?",
        [
            prompts.Option(
                label=candidate.label,
                value=candidate,
                # What it is, its serial and when it was paired - because an accessory the owner
                # never named has this instead of a name. Plus the alignment warning, priced at
                # the decision rather than explained after it.
                note=" - ".join(
                    part for part in (
                        candidate.details,
                        "" if candidate.has_alignment else "no alignment record: slow first locate",
                    ) if part
                ),
            )
            for candidate in candidates
        ],
    )

    if not chosen:
        if or_nothing:
            return []
        raise ExportSourceError("Nothing was selected, so there is nothing to export.")

    return await _confirm_devices(chosen, or_nothing=or_nothing)


async def _confirm_devices(chosen: list[Candidate], *, or_nothing: bool = False) -> list[Candidate]:
    """
    Say what including one of the owner's own devices means, and only then.

    **The 'a for all' problem.** One keystroke takes the iPads and the Mac along with the AirTags,
    and a bundle holding a Mac lets whoever receives it locate that Mac - not a wallet, the person
    - for as long as the keys are valid, with no way to revoke short of unpairing. The list says
    what each row is, which is most of the job; what it does not do is state the consequence at
    the moment it is incurred.

    Deliberately not a prompt on every export. A warning that fires when nothing is wrong is one
    people learn to dismiss, and then it is not there when it matters.
    """
    devices = [candidate for candidate in chosen if is_own_device(candidate.owned_beacon)]

    if not devices:
        return chosen

    named = ", ".join(candidate.label for candidate in devices)
    print(f"\nThat selection includes {named}.", file=sys.stderr)
    print("Those are your own devices, not tags. Anyone you give this bundle to can locate", file=sys.stderr)
    print("them for as long as their keys are valid, and that cannot be undone.\n", file=sys.stderr)

    if await prompts.confirm("Include them anyway?"):
        return chosen

    # Dropped rather than starting the whole selection again: what they said about the tags was
    # not in doubt, and making somebody re-pick six things to change their mind about one is how
    # they end up pressing 'a' a second time.
    kept = [candidate for candidate in chosen if not is_own_device(candidate.owned_beacon)]

    if not kept and not or_nothing:
        raise ExportSourceError("Nothing left to export once your own devices were left out.")

    print(f"\nLeaving them out. Exporting {len(kept)}.", file=sys.stderr)

    return kept


async def name_the_nameless(chosen: list[Candidate]):
    """
    Ask for a name for any accessory that has none, and turn them all into exports.

    An accessory with key material and no naming record is normal; the importer still needs one,
    because it drops anything it cannot pair with a name. Asking is the only honest way to fill
    that in - inventing one would manufacture a tag the recipient has no reason to doubt.
    """
    exports = []
    for candidate in chosen:
        name = None
        if candidate.naming_record is None:
            print(f"\n{candidate.beacon_id} has no name on the account.", file=sys.stderr)
            while not name:
                name = await prompts.text("What should it be called?")

        exports.append(icloud.to_export(candidate, name=name))

    return exports


async def read_key_files(paths: list[Path]) -> list[PreparedTag]:
    """Read every `--add-keys` file, and ask what the files could not say."""
    prepared: list[PreparedTag] = []

    for path in paths:
        try:
            tags = parse_key_file(path.read_bytes(), filename=path.name)
        except OSError as e:
            raise ExportSourceError(f"Could not read {path}: {e}") from None

        print(f"\n{path.name}: {len(tags)} tag(s), read as {tags[0].source_format}.", file=sys.stderr)

        for tag in tags:
            check_advertisement_key(tag)

            suggestion = suggested_name(tag)
            typed = await prompts.text("Name for this tag", default=suggestion)

            prepared.append(PreparedTag(
                tag=tag,
                name=typed or suggestion,
                identifier=suggested_identifier(tag),
            ))

    return prepared


def write(bundle, output: Path, passcode: str | None) -> None:
    """Write the bundle, and say what it means."""
    write_zip(bundle, output, password=passcode)

    print(f"\nWritten to {output}", file=sys.stderr)

    if passcode is None:
        print("\nIt is not locked. Anyone who gets the file can locate those accessories,", file=sys.stderr)
        print("and that cannot be revoked without unpairing them.", file=sys.stderr)
        return

    print("\n  The code for this bundle is:  " + format_passcode(passcode), file=sys.stderr)
    print("\n  It is not stored anywhere and cannot be recovered - write it down now.", file=sys.stderr)
    print("  Send it separately from the file itself: a code sent in the same message", file=sys.stderr)
    print("  as the bundle is in the same backup as the bundle.", file=sys.stderr)


# ---------------------------------------------------------------------------------------------


async def read_local():
    """
    Read this Mac's own Find My files.

    No account, no sign-in, no device registered on it - macOS did all of that already, and this
    reads what it left behind. It does ask for the login password, twice, in a dialog this program
    neither draws nor controls, which is worth saying before it appears.
    """
    print("\nmacOS will ask for your login password, twice, so this can read the key that",
          file=sys.stderr)
    print("decrypts its Find My files. That prompt is macOS's own.\n", file=sys.stderr)

    return localsource.fetch(localsource.read_key())


async def read_icloud(arguments: argparse.Namespace):
    """Sign in and read the account. None if the keychain could not be unlocked."""
    account = await sign_in(arguments)

    try:
        async with await icloud.open_client(account) as client:
            if not await unlock(client, arguments):
                return None

            return await icloud.fetch(client)
    finally:
        await account.close()


async def run(arguments: argparse.Namespace) -> int:
    """The whole flow, from a source to a written bundle."""
    prepared = await read_key_files(arguments.add_keys)

    # Decided rather than asked. Signing in is the bigger ask of the two routes, and it should not
    # be made of somebody whose Mac still keeps the records on disk.
    route = source.resolve(arguments.source)
    where = "this Mac" if route.is_local else "iCloud"

    fetched = None

    if route.reads_nothing:
        if not prepared:
            raise ExportSourceError(
                "There is nothing to export: --source none reads no Apple account, so the bundle"
                " can only hold tags given with --add-keys, and none were.",
            )
        print(f"\nReading no account, because {route.reason}.", file=sys.stderr)
    else:
        print(f"\nReading from {where}, because {route.reason}.", file=sys.stderr)
        fetched = await read_local() if route.is_local else await read_icloud(arguments)

    # A tag from `--add-keys` was read from a file and named by hand, and it is in no Apple
    # account - so an empty account, or one that could not be reached, is a reason to export less
    # rather than to throw away what the user already provided. Returning here dropped it silently
    # and asked for the names again on the next run.
    exports = []
    missed_the_source = fetched is None and not route.reads_nothing

    if fetched is not None:
        for skipped in fetched.skipped:
            print(f"Not exportable: {skipped.beacon_id} {skipped.reason}", file=sys.stderr)

        if fetched.candidates:
            picked = (
                _take_all(fetched.candidates, include_my_devices=arguments.include_my_devices)
                if arguments.all_tags
                else await choose(fetched.candidates, or_nothing=bool(prepared))
            )
            exports = await name_the_nameless(picked)
        else:
            print(f"\nNothing on {where} can be exported.", file=sys.stderr)

    exports.extend(tag.to_export() for tag in prepared)

    if not exports:
        return 1

    if missed_the_source:
        # Both halves of what happened, because either alone misleads: a bundle was written, and
        # it holds none of the accessories on the account. The exit code reports the failure; this
        # says what the file beside it is.
        print("\nOnly the tags from your key file(s) are in this bundle - nothing was read", file=sys.stderr)
        print(f"from {where}.", file=sys.stderr)

    bundle = build_export(
        exports,
        via=EXPORT_VIA_CLI,
        source_user=arguments.source_user or getpass.getuser(),
        exported_at_ms=int(time.time() * 1000),
    )

    passcode = None if arguments.no_password else generate_passcode()
    write(bundle, arguments.output or _default_output(), passcode)

    # Not 0 when the account could not be read: the bundle is real and worth keeping, but part of
    # what was asked for did not happen, and a script reading the exit code should hear that.
    # Reading nothing on purpose is not that case - there, nothing was missed.
    return 1 if missed_the_source else 0


def _default_output() -> Path:
    """A filename nobody has to think about, with the date in a form that sorts."""
    return Path(f"OpenTagViewer_export_{time.strftime('%Y%m%d_%H%M%S')}.zip")


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)

    configure_logging(arguments.verbose)

    if arguments.non_interactive:
        prompts.forbid_prompting()

    try:
        return _run_and_return(arguments)
    finally:
        # **Again, at the end.** The copy printed when logging was turned on is thousands of
        # lines up by now, and the end of the run is both where somebody is looking and where
        # they start selecting from. A warning that has scrolled away is one that was not given.
        if arguments.verbose:
            warn_about_sharing_logs()


def _run_and_return(arguments: argparse.Namespace) -> int:
    try:
        return asyncio.run(run(arguments))
    except prompts.Abandoned:
        print("\nStopped. Nothing was written.", file=sys.stderr)
        return 130
    except InvalidCredentialsError as e:
        # Apple rejected the credentials. Reported on its own because its message names the
        # password even when it arrives at the verification-code step, which reads as though the
        # wrong thing was wrong - so the message is printed verbatim and then placed.
        print(f"\nApple would not accept that sign-in:\n\n  {e}\n", file=sys.stderr)
        print("If that appeared after you entered a verification code, the code and the", file=sys.stderr)
        print("password are both worth checking - a code expires quickly, and Apple reports", file=sys.stderr)
        print("either as a credentials failure at this point. Nothing was written.", file=sys.stderr)
        return 1
    except (
        ExportError, ExportSourceError, KeyFileError, CustomTagError, TermsError,
        secrets.SecretError, prompts.PromptForbidden,
    ) as e:
        print(f"\n{e}", file=sys.stderr)
        return 1
    except RecoveryError as e:
        # **Before the handler below, and deliberately not through it.** RecoveryError is an
        # UnhandledProtocolError by inheritance, but it is not an unmodelled shape: it is a
        # documented rejection that carries its own advice, and "Apple returned something
        # unexpected" over the top of that is both wrong and in the way. A stack says nothing here
        # either, so it does not print one.
        print(f"\nThe keychain could not be unlocked:\n\n{e}\n", file=sys.stderr)
        print("Nothing was written.", file=sys.stderr)
        return 1
    except UnhandledProtocolError as e:
        # Apple said something this library does not model. Worth its own message: it is not the
        # user's mistake and there is nothing for them to correct.
        print(f"\nApple returned something unexpected: {e}", file=sys.stderr)

        # **And the traceback, because this is the one error where it is the whole diagnosis.**
        # The message names a symptom - a length that disagrees with the bytes around it - and
        # says nothing about which structure was being read. There are a dozen places that parse
        # DER here and the message is identical from all of them, so without the stack a report
        # of this cannot be acted on at all. That is not hypothetical: issue #89 arrived with
        # exactly this line and nothing else, and it could not be placed.
        if arguments.verbose:
            traceback.print_exc()
        else:
            print("\nRe-run with -vv and include the output if you report this. Without the",
                  file=sys.stderr)
            print("stack this message names a symptom and not a place. That run will say what",
                  file=sys.stderr)
            print("it prints about you, and it is worth reading before pasting it anywhere.",
                  file=sys.stderr)

        # **And where.** Asking somebody to report something without saying where to put it leaves
        # them holding output and no destination - which is not a hint they should have to take.
        # The window has said this for as long as it has had an error dialog; this one asked for a
        # report and named nowhere.
        print(f"\nReport it at: {GITHUB_ISSUES_LINK}", file=sys.stderr)

        return 1
    except KeyboardInterrupt:
        print("\nStopped. Nothing was written.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
