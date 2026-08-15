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
import pydoc
import sys
import time
from pathlib import Path
from typing import Sequence

from findmy import InvalidCredentialsError, LoginState, MobileMeDelegateError, TermsError
from findmy.errors import UnhandledProtocolError

from exporter import icloud, localsource, prompts, source, terms
from exporter.custom_tags import (
    CustomTagError,
    PreparedTag,
    check_advertisement_key,
    suggested_identifier,
    suggested_name,
)
from exporter.icloud import Candidate, ExportSourceError
from exporter.version import EXPORT_VIA_CLI, VERSION
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
    )

    parser.add_argument("--version", action="version", version=VERSION)
    parser.add_argument(
        "-o",
        "--output",
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
            " holds keys that cannot be revoked - but no OpenTagViewer release can open a locked"
            " one yet."
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
            " otherwise, which is every other machine and every macOS 15 or newer."
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

    if verbosity >= 2:
        for name in ("findmy.cloudkit", "findmy.keychain", "findmy.icloud", "exporter"):
            logging.getLogger(name).setLevel(logging.DEBUG)


# ---------------------------------------------------------------------------------------------
# Asking
# ---------------------------------------------------------------------------------------------


async def ask_choice(question: str, options: Sequence[str]) -> int:
    """Pick one of several, by its position."""
    return await prompts.select(
        question,
        [prompts.Option(label=option, value=index) for index, option in enumerate(options)],
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

    print("\nSigning in registers this exporter as a device on your Apple account.", file=sys.stderr)
    print("It appears in your device list as '0PENTAGXPORT', and can be removed there.\n", file=sys.stderr)

    email = await prompts.text("Apple ID")
    password = await prompts.password("Password")

    try:
        await icloud.log_in(
            account,
            email,
            password,
            choose_second_factor=lambda methods: ask_choice("How should Apple send the code?", methods),
            get_code=lambda: prompts.text("Verification code"),
        )
    except MobileMeDelegateError as e:
        # Authentication itself worked; the exchange that follows it did not. Unaccepted terms are
        # the one cause of that with a remedy here, and which error value means "terms pending" is
        # not established - so this says what Apple said and then offers, rather than assuming.
        print(f"\nSigning in got as far as your account and then stopped:\n\n  {e}\n", file=sys.stderr)
        print("If that is about terms of service, they can be shown and accepted here.", file=sys.stderr)
        print("If it is about something else, accepting terms will not fix it.\n", file=sys.stderr)

        if not arguments.accept_terms and not await prompts.confirm("Fetch the terms of service?"):
            raise ExportSourceError("Signing in stopped at the delegate exchange.") from None

        if not await accept_terms(account, without_reading=arguments.accept_terms):
            raise ExportSourceError("Signing in stopped at the delegate exchange.") from None
    except BaseException:
        # The account owns an HTTP session, and a sign-in that fails leaves it open - which
        # surfaces as "Unclosed client session" from asyncio, after the real error, where it reads
        # like a second fault. Only `run` closes it on the way out, and only once this returns.
        await account.close()
        raise
    finally:
        del password  # used inside the call above and wanted no longer

    return account


async def unlock(client) -> bool:
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

    chosen = options.recoverable[await ask_choice(
        "Which device's passcode do you have?",
        [record.describe() for record in options.recoverable],
    )]

    passcode = await prompts.password(f"Screen-lock passcode for {chosen.serial}")
    try:
        await client.unlock(chosen, passcode)
    finally:
        del passcode

    return True


async def choose(candidates: list[Candidate]) -> list[Candidate]:
    """
    Ask which accessories to export.

    **Nothing is ticked to begin with.** Handing over one tag and handing over a household's whole
    set are different acts, and what is handed over cannot be withdrawn afterwards - so a list that
    arrived pre-selected would make those the same keystroke.
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
        raise ExportSourceError("Nothing was selected, so there is nothing to export.")

    return await _confirm_devices(chosen)


async def _confirm_devices(chosen: list[Candidate]) -> list[Candidate]:
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

    if not kept:
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
            if not await unlock(client):
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
    print(f"\nReading from {where}, because {route.reason}.", file=sys.stderr)

    fetched = await read_local() if route.is_local else await read_icloud(arguments)
    if fetched is None:
        return 1

    for skipped in fetched.skipped:
        print(f"Not exportable: {skipped.beacon_id} {skipped.reason}", file=sys.stderr)

    if not fetched.candidates:
        print(f"\nNothing on {where} can be exported.", file=sys.stderr)
        return 1

    exports = await name_the_nameless(await choose(fetched.candidates))

    exports.extend(tag.to_export() for tag in prepared)

    bundle = build_export(
        exports,
        via=EXPORT_VIA_CLI,
        source_user=arguments.source_user or getpass.getuser(),
        exported_at_ms=int(time.time() * 1000),
    )

    passcode = None if arguments.no_password else generate_passcode()
    write(bundle, arguments.output or _default_output(), passcode)

    return 0


def _default_output() -> Path:
    """A filename nobody has to think about, with the date in a form that sorts."""
    return Path(f"OpenTagViewer_export_{time.strftime('%Y%m%d_%H%M%S')}.zip")


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)

    configure_logging(arguments.verbose)

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
    except (ExportError, ExportSourceError, KeyFileError, CustomTagError, TermsError) as e:
        print(f"\n{e}", file=sys.stderr)
        return 1
    except UnhandledProtocolError as e:
        # Apple said something this library does not model. Worth its own message: it is not the
        # user's mistake and there is nothing for them to correct.
        print(f"\nApple returned something unexpected: {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nStopped. Nothing was written.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
