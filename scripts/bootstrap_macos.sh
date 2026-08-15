#!/usr/bin/env bash
#
# Set up a Mac from scratch to run the OpenTagViewer export wizard.
#
# Intended for a freshly installed macOS, usually a VM, which has no git and no python3 -
# both arrive with the Xcode Command Line Tools. Installs those, clones the repo, creates a
# virtualenv, installs the dependencies and launches the wizard.
#
# Safe to re-run: an existing clone is updated rather than replaced, and each step is skipped
# if it is already done.
#
# Usage:
#     bash bootstrap_macos.sh                      # main branch
#     bash bootstrap_macos.sh feat/some-branch     # a branch you want to test
#     BRANCH=main DEST=~/otv bash bootstrap_macos.sh
#
# Written for bash 3.2, which is what macOS ships - no associative arrays, no ${var,,}.
#
set -uo pipefail

REPO_URL="${REPO_URL:-https://github.com/parawanderer/OpenTagViewer.git}"
BRANCH="${1:-${BRANCH:-main}}"
DEST="${DEST:-$HOME/OpenTagViewer}"

say() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = "Darwin" ] || die "This script is for macOS. The export has to run on a Mac."

# ---------------------------------------------------------------------------------------
# 1. Xcode Command Line Tools.
#
# Tested by running the tools rather than by looking for them: a bare macOS ships stubs at
# /usr/bin/git and /usr/bin/python3 whose only purpose is to trigger this installer, so any
# "is it on PATH" check answers yes long before either can actually run.
# ---------------------------------------------------------------------------------------
if git --version >/dev/null 2>&1 && python3 --version >/dev/null 2>&1; then
    say "Command Line Tools already present"
else
    say "Installing the Xcode Command Line Tools"
    echo "    A dialog will open. Click Install, accept the licence, and leave this running."
    echo "    It is a few hundred MB, so it can take a while."

    xcode-select --install 2>/dev/null

    waited=0
    until git --version >/dev/null 2>&1 && python3 --version >/dev/null 2>&1; do
        sleep 15
        waited=$((waited + 15))
        # 30 minutes is generous even for a slow VM on a slow connection.
        [ "$waited" -lt 1800 ] || die "Command Line Tools did not finish installing. Run 'xcode-select --install' by hand."
        [ $((waited % 120)) -eq 0 ] && echo "    still waiting (${waited}s)..."
    done

    say "Command Line Tools installed"
fi

# ---------------------------------------------------------------------------------------
# 2. The code.
# ---------------------------------------------------------------------------------------
if [ -d "$DEST/.git" ]; then
    say "Updating the existing clone at $DEST"
    git -C "$DEST" fetch origin "$BRANCH" || die "Could not fetch $BRANCH"
    git -C "$DEST" checkout "$BRANCH" || die "Could not check out $BRANCH"
    git -C "$DEST" pull --ff-only origin "$BRANCH" || die "Could not fast-forward; the clone has local changes"
else
    say "Cloning $BRANCH into $DEST"
    git clone --branch "$BRANCH" "$REPO_URL" "$DEST" || die "Clone failed"
fi

cd "$DEST/python" || die "No python/ directory - is $DEST the right checkout?"

# ---------------------------------------------------------------------------------------
# 3. Pick an interpreter whose Tk actually works.
#
# The wizard is a tkinter app, and "has tkinter" is not the same as "can open a window".
# Importing tkinter succeeds in cases where creating one does not: the Command Line Tools
# python3 links the system Tk, which refuses to start if it decides the OS is too old, and
# the process then dies during Tk_Init with "Abort trap: 6" and no traceback. Seen on a
# Docker-OSX VM whose SystemVersion.plist claimed 14.8.9 while the API Tk queries reported
# 14.6.
#
# So candidates are tried newest-first and each is judged by whether it can construct a Tk
# window, not by version. python.org and Homebrew builds bring their own Tcl/Tk and target
# macOS 11+, so they generally work where the Command Line Tools one does not.
# ---------------------------------------------------------------------------------------
tk_works() {
    "$1" -c 'import tkinter; tkinter.Tk().destroy()' >/dev/null 2>&1
}

PY=""
for candidate in \
    /Library/Frameworks/Python.framework/Versions/3.13/bin/python3 \
    /Library/Frameworks/Python.framework/Versions/3.12/bin/python3 \
    /Library/Frameworks/Python.framework/Versions/3.11/bin/python3 \
    /opt/homebrew/bin/python3 \
    /usr/local/bin/python3 \
    "$(command -v python3 2>/dev/null)"
do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    if tk_works "$candidate"; then
        PY="$candidate"
        break
    fi
done

if [ -n "$PY" ]; then
    say "Using $PY ($("$PY" -V 2>&1))"
else
    system_python="$(command -v python3 2>/dev/null)"
    tk_error="$("${system_python:-python3}" -c 'import tkinter; tkinter.Tk().destroy()' 2>&1 | tail -2)"

    printf '\nNo Python on this machine can open a Tk window, so the GUI wizard cannot run:\n\n'
    printf '%s\n\n' "$tk_error"
    printf 'Most likely fix - python.org builds bundle their own Tcl/Tk:\n\n'
    printf '    curl -LO https://www.python.org/ftp/python/3.12.7/python-3.12.7-macos11.pkg\n'
    printf '    sudo installer -pkg python-3.12.7-macos11.pkg -target /\n'
    # Carries the branch through. Without it the re-run silently lands on main, and an
    # export from the wrong branch looks completely normal until you inspect the zip.
    printf '    rm -rf %s/python/.venv && bash %s %s\n\n' "$DEST" "$0" "$BRANCH"
    printf 'Or skip the GUI entirely - the export does not need it:\n\n'
    printf '    cd %s/python\n' "$DEST"
    printf '    python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt\n'
    printf '    PYTHONPATH=. python3 main/airtag_decryptor.py -o ~/Desktop/otv_decrypted --rename-legacy\n\n'
    printf 'That writes the decrypted OwnedBeacons, BeaconNamingRecord and KeyAlignmentRecords\n'
    printf 'folders. CONTRIBUTING.md covers turning them into a zip the app can import.\n\n'
    exit 1
fi

# ---------------------------------------------------------------------------------------
# 4. Dependencies, kept out of the system Python.
#
# The venv is rebuilt if it was made from a different interpreter, which happens whenever
# someone installs a newer Python after a first run that fell back to the system one.
# ---------------------------------------------------------------------------------------
if [ -d .venv ] && ! tk_works .venv/bin/python3; then
    say "Existing virtualenv cannot open a Tk window; rebuilding it from $PY"
    rm -rf .venv
fi

if [ ! -d .venv ]; then
    say "Creating a virtualenv"
    "$PY" -m venv .venv || die "Could not create a virtualenv"
fi

# shellcheck disable=SC1091
. .venv/bin/activate || die "Could not activate the virtualenv"

say "Installing dependencies"
python3 -m pip install --quiet --upgrade pip
python3 -m pip install --quiet -r requirements.txt || die "Dependency install failed"

# macOS 15 tightened keychain access, so the BeaconStore key cannot be read automatically.
# The wizard says so itself, but saying it up front saves a confusing detour.
macos_major="$(sw_vers -productVersion | cut -d. -f1)"
if [ "$macos_major" -ge 15 ] 2>/dev/null; then
    printf '\nNOTE: on macOS %s the key cannot be extracted automatically.\n' "$macos_major"
    printf '      You will need to pass --key. See:\n'
    printf '      https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Manually-Export-AirTags\n'
fi

# ---------------------------------------------------------------------------------------
# 5. Run it.
#
# PYTHONPATH=. is required: wizard.py does `from exporter.airtag_decryptor import ...`, and
# running the file directly puts python/exporter on sys.path rather than python/.
# ---------------------------------------------------------------------------------------
say "Starting the export wizard"
echo "    Checkout: $DEST ($(git -C "$DEST" rev-parse --abbrev-ref HEAD))"
echo "    Re-run later with: cd $DEST/python && . .venv/bin/activate && PYTHONPATH=. python3 exporter/wizard.py"
echo

PYTHONPATH=. python3 exporter/wizard.py
