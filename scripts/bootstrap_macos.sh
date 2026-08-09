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
# 3. Dependencies, kept out of the system Python.
# ---------------------------------------------------------------------------------------
if [ ! -d .venv ]; then
    say "Creating a virtualenv"
    python3 -m venv .venv || die "Could not create a virtualenv"
fi

# shellcheck disable=SC1091
. .venv/bin/activate || die "Could not activate the virtualenv"

say "Installing dependencies"
python3 -m pip install --quiet --upgrade pip
python3 -m pip install --quiet -r requirements.txt || die "Dependency install failed"

# The wizard is a tkinter app. The Command Line Tools python3 includes it; a Homebrew one
# does not without python-tk, and the failure otherwise is an ImportError at launch.
python3 -c "import tkinter" 2>/dev/null || die "This python3 has no tkinter. Use the system python3, or install python-tk."

# macOS 15 tightened keychain access, so the BeaconStore key cannot be read automatically.
# The wizard says so itself, but saying it up front saves a confusing detour.
macos_major="$(sw_vers -productVersion | cut -d. -f1)"
if [ "$macos_major" -ge 15 ] 2>/dev/null; then
    printf '\nNOTE: on macOS %s the key cannot be extracted automatically.\n' "$macos_major"
    printf '      You will need to pass --key. See:\n'
    printf '      https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Manually-Export-AirTags\n'
fi

# ---------------------------------------------------------------------------------------
# 4. Run it.
#
# PYTHONPATH=. is required: wizard.py does `from main.airtag_decryptor import ...`, and
# running the file directly puts python/main on sys.path rather than python/.
# ---------------------------------------------------------------------------------------
say "Starting the export wizard"
echo "    Checkout: $DEST ($BRANCH)"
echo "    Re-run later with: cd $DEST/python && . .venv/bin/activate && PYTHONPATH=. python3 main/wizard.py"
echo

PYTHONPATH=. python3 main/wizard.py
