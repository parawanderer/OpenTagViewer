# Contributing

Thanks for looking. This covers getting the project running, and how to test what you
change. For the rules a change has to satisfy — migrations, Anisette, API keys, attribution
— see **[AGENTS.md](./AGENTS.md)**, which applies to people as much as to automated
contributors.

---

## Setting up

### What you need

| | Why |
| --- | --- |
| **JDK 17+** | The Android Gradle Plugin refuses to run on anything older, with an error that reads like a plugin bug. Android Studio's bundled runtime works: `export JAVA_HOME="/path/to/Android Studio/jbr"` |
| **Android SDK** | Either `local.properties` with `sdk.dir=...`, or `ANDROID_HOME` set |
| **Python 3** on `PATH` | The build shells out to it to generate the stub `unicorn` wheel (`scripts/build_unicorn_stub_wheel.py`). If your interpreter has an unusual name, pass `-PpythonExecutable=...` |
| **Android Studio** (optional) | Everything works from the CLI, but the IDE is easier for running the app on a device |
| **[`gh`](https://cli.github.com/)** (optional) | Strongly recommended if you work with a coding agent — see below |

Easiest route to the first three is Android Studio, which ships a JDK and can install the
SDK for you. Otherwise: [Temurin 17](https://adoptium.net/temurin/releases/?version=17),
the [command line tools](https://developer.android.com/studio#command-tools), and
[python.org](https://www.python.org/downloads/).

### Clone and configure

<details>
<summary>How to clone and initially configure the project...</summary>

```bash
git clone https://github.com/parawanderer/OpenTagViewer.git
cd OpenTagViewer
```

**A Google Maps API key.** The app will build without one, but the map will not load.
Create `secrets.properties` in the repository root:

```properties
MAPS_API_KEY=your_key_here
```

Get one from the [Google Maps Platform console](https://console.cloud.google.com/google/maps-apis/),
enabling **Maps SDK for Android**. `secrets.properties` is gitignored and must stay that
way — the pre-commit hook refuses a commit that stages it. `local.defaults.properties`
supplies a placeholder so a fresh clone still compiles.

If you would rather not deal with a key at all, AMap works without one at build time — users
supply their own in Settings — though it only covers mainland China.

</details>

### Install the git hook

<details>
<summary>How to setup the git hook...</summary>

Hooks are not versioned, so this is once per clone:

```bash
git config core.hooksPath .githooks
```

`.githooks/pre-commit` runs the translation check, plus flake8 and pyright over whatever
Python you staged, and refuses to commit `secrets.properties`. It deliberately does not
build or run tests — a hook that takes a minute gets bypassed with `--no-verify` within a
day, and a routinely-bypassed hook is worse than none. Use `--no-verify` when you genuinely
need to.

Optional but recommended for Python work:

```bash
python -m pip install flake8 pyright
```

</details>

### If you use a coding agent, install `gh`


<details>
<summary>Notes on setting up <code>gh</code> CLI, especially for coding agent users...</summary>


Ensure you are logged in to `gh` when working on the project:

```bash
gh auth login
```

Worth doing before anything else. Without it, a red check on your PR is a coloured square on
a web page your agent cannot see, so it can only guess. With it, the agent reads the failing
step and its log, fixes the cause, and tells you what it was.

`gh` also lets an agent check whether something is already reported before changing anything. AGENTS.md has the specific
commands, including how to read a failure while the rest of the run is still going.

Note that `gh` authenticates separately from git: `gh` can be logged in while `git push`
still fails, if your remote is HTTPS with no cached credential, or SSH with a
passphrase-protected key and no agent running.

</details>

### Build it

```bash
export JAVA_HOME="/path/to/jbr"
./gradlew assembleDebug          # ./gradlew.bat on Windows
```

Debug builds install as `dev.wander.android.opentagviewer.debug`, labelled
"OpenTagViewer (debug)" with an inverted icon, so they sit alongside a real install rather
than replacing it. **Never uninstall a production install to force an install** —
`allowBackup` is false, so the beacons and location history are gone for good, and getting
them back means redoing the macOS export.

---

## Testing

Tests live in five places, because the code runs in three environments: the JVM, an Android
runtime, and CPython (both inside the app via Chaquopy, and on the desktop for the export
wizard).

| Suite | Location | Runner | Needs a device? |
| --- | --- | --- | --- |
| Android unit tests | `app/src/test/java/` | Gradle / JUnit | no |
| Android instrumented tests | `app/src/androidTest/java/` | Gradle / JUnit + emulator | provisioned for you |
| Anisette tests | `app/src/androidTest/java/.../anisette/` | as above, **opt-in** | yes, and network |
| UI tests | `AppleLoginFlowTest`, `app/src/androidTest/java/.../ui/` | Espresso, on the managed device | provisioned for you |
| Chaquopy bridge tests | `app/src/test/python/` | pytest | no |
| Desktop wizard tests | `python/test/` | pytest | no |
| Tooling tests | `scripts/test/` | pytest | no |

### Run everything

```bash
export JAVA_HOME="/path/to/jbr"

./gradlew testAll           # everything that needs no device
./gradlew testAllOnDevice   # the above, plus instrumented tests
```

`testAll` is deliberately separate so you don't get a failure just for not having an
emulator. It says the instrumented tests were skipped.

Both create a virtualenv under `app/build/test-venv` on first run and install the Python
dependencies into it, so a fresh clone works with no manual setup. The first run is slow;
afterwards it is reused. If your Python is not discovered automatically:

```bash
./gradlew testAll -PpythonExecutable=/path/to/python
```

> On Windows, `python3.exe` in `%LOCALAPPDATA%\Microsoft\WindowsApps\` is a zero-byte
> Microsoft Store alias that **hangs** rather than failing when run non-interactively. The
> build skips those deliberately. If any Python-invoking tooling hangs mysteriously on
> Windows, that alias is a good first suspect.

### Android instrumented tests

Gradle provisions the emulator, runs the tests and tears it down — nothing needs to be
booted first:

```bash
./gradlew :app:testEmulatorDebugAndroidTest
```

Use this rather than `connectedDebugAndroidTest`. The Android Gradle Plugin holds its ADB
connection inside the Gradle daemon and reuses it between invocations, so once a
hand-started emulator's adb daemon goes stale, the next run fails to install or hangs with
an error that has nothing to do with your code. Recovering needs *both* an emulator restart
and `./gradlew --stop`. A managed device is created fresh per run, so nothing survives to go
stale.

The device is defined in `testOptions { managedDevices { ... } }` in `app/build.gradle.kts`
and uses an `aosp-atd` image, which has **no Play Services** — a test that needs Maps would
need a `google` image.

**Watching a UI flow happen.** These run too fast to follow. `slowMotion` pauses between
steps — off by default, so CI is unaffected — and needs an emulator with a window, whose
window keeps focus:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.slowMotion=1500 \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.wander.android.opentagviewer.AppleLoginFlowTest#signingInWithATextedCodeReachesTheMap
```

**UI tests otherwise need the managed device specifically.** Espresso refuses to touch a window that lacks
focus, and a window inside a hand-started emulator only has focus while the emulator's window
has focus on your desktop — so alt-tabbing away from a `connectedDebugAndroidTest` run makes
every UI test fail with `RootViewWithoutFocusException`. The managed device is headless and
has no such notion.

`scripts/run_instrumented_tests.sh` runs the same task with retries, and retries only when a
run failed *before* any test reported — a suite that ran and failed is reported as-is instead
of being run a second time. Set `GRADLE_TASK=:app:connectedDebugAndroidTest` to point it at an
emulator you already have open; it then pins `ANDROID_SERIAL` so a run cannot install to a
physical phone, and restarts the emulator and the Gradle daemon if a run wedges.

Report: `app/build/reports/androidTests/managedDevice/debug/allDevices/index.html`

Targeting a subset:

```bash
./gradlew :app:testEmulatorDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=dev.wander.android.opentagviewer.db
```

### Anisette tests (skipped by default)

The tests under `dev.wander.android.opentagviewer.anisette` cover running Apple's ADI
libraries in-process, so that logins do not have to go through a public Anisette server.
They are skipped unless you ask for them:

```bash
./gradlew :app:testEmulatorDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.anisetteLiveTests=true \
  -Pandroid.testInstrumentationRunnerArguments.package=dev.wander.android.opentagviewer.anisette
```

From Android Studio, add `-e anisetteLiveTests true` to **Instrumentation extra params** in
the run configuration.

They are gated because they talk to third parties, not because they are unfinished:

- they download about 2.9 MB of Apple's libraries from Apple's CDN, so they need network
- `AdiProvisioningTest` **provisions a new machine identity with Apple on every run**, which
  is not something to do on every build. No Apple account is involved — it provisions
  anonymously, with `dsId` of -2 and a randomly generated identity.
- `AdiProvisioningTest` also spends about 40 seconds sampling how often the one-time
  password rotates

What to look at afterwards, since passing is not the whole story — filter logcat on
`adi-stub`. The app ships generated stand-ins for two of Apple's libraries
(`app/src/main/cpp/stubs/`), and each one reports itself when called:

- **INFO** means a symbol we already know is called harmlessly, listed with its evidence in
  `libmediaplatform.expected`
- **ERROR** means Apple's code now depends on something we only pretend to implement. The
  result may be silently wrong rather than obviously broken, so it needs investigating
  before trusting anything the run produced.

If the tests fail after Apple ships a new Apple Music build, regenerate the symbol lists and
the verification manifest, then run them again:

```bash
python scripts/update_adi_stub_symbols.py           # regenerate
python scripts/update_adi_stub_symbols.py --check   # what CI runs weekly
```

### Android unit tests

Plain JVM, no Android framework, so these are the fastest tests in the project — seconds, no
emulator.

Most of what lives here is in `util/rx/`: the stream compositions and decision logic behind
the map, extracted out of `MapsActivity` precisely so it could be tested. They assert that a
call *happens* rather than that a value looks right, because the failures in this area are
silent — a stream disposed early, a marker that stops being raised, a fetch that returns
nothing being indistinguishable from a fetch that failed. None of those throw, and none show
up in logcat.

```bash
./gradlew testDebugUnitTest
```

Report: `app/build/reports/tests/testDebugUnitTest/index.html`

### Chaquopy bridge tests

Cover `app/src/main/python/main.py`, the module Chaquopy packages into the APK. It imports
no Android or Java types, so it runs on plain CPython.

```bash
python -m venv .venv
.venv/bin/pip install -r app/src/test/python/requirements.txt   # Windows: .venv/Scripts/pip

python -m pytest app/src/test/python -v
python -m pytest app/src/test/python -k alignment
```

The pins in `app/src/test/python/requirements.txt` must match the
`chaquopy { pip { ... } }` block in `app/build.gradle.kts`, or the tests exercise a
different FindMy version than the app ships. `test_pinned_versions_match_the_app_build`
enforces that.

Fixtures live in `app/src/test/resources/` — a redacted copy of a real export. See the
README there for what was redacted and why.

### Desktop wizard tests

For the macOS export wizard under `python/`.

```bash
cd python
python -m pip install -r requirements.txt flake8 pytest

python -m pytest ./test
flake8 . --count --select=E9,F63,F7,F82 --show-source --statistics
```

### Tooling tests

```bash
python -m pytest scripts/test -v
```

Most of `scripts/` is one-shot utilities where a failure is loud and immediate to whoever ran
them, and tests would not earn their keep. These three are different, because each one guards
something else and a bug in a guard reports green while protecting nothing:

| Script | What it gates |
| --- | --- |
| `add_strings.py` | CI's translation check |
| `exporter_version.py` | whether a release tag may disagree with the version in the source |
| `release_notes.py` | the notes of a *published* release, so a parsing mistake is visible to everyone |

### Which Python each tree targets

There are three, and they are not the same:

| Tree | Version | Why |
| --- | --- | --- |
| `python/` (export wizard) | **3.9+** | 3.9 is what the Xcode Command Line Tools install, so it is what someone running the wizard from source on a stock Mac gets. Tested across 3.9–3.13 in CI |
| `app/src/main/python/` | **3.12** | Pinned by the `chaquopy { }` block; it only ever runs inside the app |
| `scripts/` | **3.12** | Developer tooling, run on a dev machine. Not tested below 3.12 — do not assume it works on 3.9 |

Only `python/` has a floor worth respecting, and it is a real constraint rather than a
preference: a module-level `tuple[int, int] | None` annotation is evaluated at import, so
3.10-only syntax there means the wizard dies before it starts on a stock Mac. Hence
`from __future__ import annotations` in those files, and 3.9 in the CI matrix.

### Type checking

Type checking in this project uses **pyright.** The editors used here run Pylance, which is pyright; mypy's defaults
are lenient enough to pass code Pylance flags.

```bash
python -m pip install pyright pillow -r app/src/test/python/requirements.txt
python -m pyright app/src/main/python scripts
```

The installs matter: pyright reports an unresolved import as an error, so without `findmy`,
`NSKeyedUnArchiver` and `pillow` present it fails while telling you nothing about your code.

`python/` is not clean yet and is excluded from CI's pyright step.

---

## Adding user-facing strings (Coding Agents)

For manual changes made by a human, you can make changes in the Android Studio UI (easiest)
or make changes in the `strings.xml` file yourself.

For agent based changes: never edit the ten `strings.xml` files by terminal, and never pipe
translations through a shell loop — that has corrupted non-ASCII text here twice.
See the section in [AGENTS.md](./AGENTS.md#adding-user-facing-strings).

```bash
python scripts/add_strings.py                 # usage and input format
python scripts/add_strings.py new.json        # add to every locale
python scripts/add_strings.py --check         # fail if any locale is missing one
```

---

## Running the export wizard on a Mac

Testing an export means a real Mac signed into iCloud, usually a VM. A fresh macOS install
has **neither git nor python3** — both arrive with the Xcode Command Line Tools — so
`scripts/bootstrap_macos.sh` does the lot: installs the tools, clones, creates a virtualenv,
installs the dependencies and launches the wizard. It is safe to re-run.

It cannot live in the clone you do not have yet, so fetch it with `curl`, which macOS does
ship:

```bash
curl -fsSLO https://raw.githubusercontent.com/parawanderer/OpenTagViewer/main/scripts/bootstrap_macos.sh
less bootstrap_macos.sh          # read it before running it
bash bootstrap_macos.sh          # or: bash bootstrap_macos.sh some-branch
```

Read it first rather than piping `curl` straight into `bash`. It is a hundred lines, and the
people using this app are exactly the people who should not run unread code off the internet
as a habit.

<details>
<summary>What it does, if you would rather do it by hand</summary>

```bash
# 1. Command Line Tools. Opens a GUI installer and returns immediately, so wait for it.
#    Tested with --version rather than `command -v`: a bare macOS ships stubs at
#    /usr/bin/git and /usr/bin/python3 that exist only to trigger this installer, so
#    "is it on PATH" answers yes long before either one can actually run.
until git --version >/dev/null 2>&1 && python3 --version >/dev/null 2>&1; do
  xcode-select --install 2>/dev/null
  echo "Click Install in the dialog that opened, then leave this running..."
  sleep 20
done

# 2. The code. Swap the branch for whatever you are testing.
git clone -b main --depth 1 https://github.com/parawanderer/OpenTagViewer.git
cd OpenTagViewer/python

# 3. Dependencies, isolated from the system Python.
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# 4. Run it.
PYTHONPATH=. python3 main/wizard.py
```

</details>

`PYTHONPATH=.` is not optional: `wizard.py` does `from main.airtag_decryptor import ...`, and
running the file directly puts `python/main` on `sys.path` rather than `python/`, so the
import fails. It is the same thing the CI workflow sets.

Then check the export actually contains what it should:

```bash
unzip -l ~/Desktop/OpenTagViewer_export_*.zip | grep -iE "OPENTAGVIEWER.yml|KeyAlignmentRecords"
```

You want `version: 0.0.2` in the yml and `KeyAlignmentRecords/<uuid>/<uuid>.plist` entries.
Without those the app has no key alignment and every tag's first fetch searches its entire
history — see rule 6 in [AGENTS.md](./AGENTS.md).

### If the GUI will not start?

<details>
<summary>Self-help steps for if the GUI fails to start...</summary>

The wizard is tkinter, and Tk can fail to initialise even when `import tkinter` works — the
process then dies with `Abort trap: 6` and no traceback. A Docker-OSX VM will do this if its
`SystemVersion.plist` disagrees with the version the OS reports through the API Tk queries,
so Tk concludes the OS is too old for itself.

**The export does not need the GUI.** `airtag_decryptor.py` has its own CLI and imports no
Tk, and it owns `WHITELISTED_DIRS` — the part that actually decides what gets exported:

```bash
cd ~/OpenTagViewer/python && . .venv/bin/activate
PYTHONPATH=. python3 main/airtag_decryptor.py -o ~/Desktop/otv_decrypted --rename-legacy
```

That writes decrypted `OwnedBeacons/`, `BeaconNamingRecord/` and `KeyAlignmentRecords/`
folders, prompting for your login password through the system keychain dialog. What it does
not do is package them, since that is wizard code. To build a zip the app can import:

```bash
cd ~/Desktop/otv_decrypted

cat > OPENTAGVIEWER.yml <<EOF
exportTimestamp: $(python3 -c 'import time; print(int(time.time()*1000))')
sourceUser: $USER
version: 0.0.2
via: manual-cli-export
EOF

zip -r ~/Desktop/OpenTagViewer_export_manual.zip \
  OwnedBeacons BeaconNamingRecord KeyAlignmentRecords OPENTAGVIEWER.yml
```

The folders have to sit at the **root** of the zip — the importer matches `^OwnedBeacons/…`,
so zipping the parent directory instead produces an archive where nothing matches and every
file is skipped as unexpected.

Unlike the wizard, this includes every decrypted beacon rather than only those with a
matching naming record. The importer does its own inner join, so the import is equivalent,
but it is the importer's join being tested rather than the wizard's.

</details>

### Things that catch people out

- **macOS 15+ cannot extract the key automatically.** Keychain access was tightened; the
  wizard exits with instructions and you have to pass `--key`. macOS 14 and below are fine,
  which is why the VM guides target Sonoma.
- **Use the system `python3`.** The wizard is tkinter, which the Command Line Tools build
  includes. A Homebrew `python3` does not, unless you also install `python-tk`.
- **The bundled 3.9 is enough to run the wizard**, but the test suite needs 3.10+.

You do not need to build the `.app` to test a change — running from source exercises the
same `airtag_decryptor.py`. The PyInstaller build only runs on release.

## Offline diagnostic scripts

Not tests, but useful, and needing neither an Apple account nor network access.

```bash
# Would my existing export survive the FindMy 0.9.x upgrade, and how expensive is the
# first fetch? Prints derived numbers only - no identifiers, no contents.
python scripts/check_export_compatibility.py ~/path/to/OpenTagViewer_export_.../

# Same measurement across a directory of plists.
python scripts/measure_key_alignment_cost.py <dir> --hours-back 24

# Generate a synthetic beacon plist of a given age.
python scripts/make_test_beacon_plist.py out.plist --days-old 730
```

---

## What CI runs

| Workflow | When | What |
| --- | --- | --- |
| `build-debug.yml` | push/PR to `main` | Translation check, pyright, string tooling tests, instrumented tests on an emulator, JVM tests, Chaquopy bridge tests, debug APK |
| `build-release.yml` | on release | Translation check, JVM tests, Chaquopy bridge tests, release APK |
| `macos-scripts-python.yml` | `python/**` changes | Wizard tests across Python 3.10–3.13 on macOS 14 |
| `macos-exporter-python.yml` | on release | Tag/version check, wizard tests, the PyInstaller build for both architectures |
| `update-contributors.yml` | weekly | Regenerates the contributor list on the Information page, opens a PR if it changed |
| `check-adi-libraries.yml` | weekly | Checks Apple's ADI libraries still match what is checked in, opens an issue if they drifted |

The instrumented job needs KVM on the runner; the workflow enables it first. It runs the same
`:app:testEmulatorDebugAndroidTest` managed device you run locally, so there is no second
emulator definition in CI to keep in step with `app/build.gradle.kts`.

---

## Releasing the macOS exporter

The exporter's version lives in exactly one place — `VERSION` in `python/main/wizard.py`:

```python
VERSION = "1.0.5"
```

It shows in the window title, and it is stamped into every export as
`via: OpenTagViewer.app:1.0.5` inside `OPENTAGVIEWER.yml`. That field is how a maintainer
reading a bug report works out which exporter produced the zip in front of them, so it has to
be true.

**Nothing rewrites it at build time.** The release tag names the zip and the GitHub release;
the app keeps whatever was committed. That is deliberate rather than an oversight — the wizard
also runs straight from source (the VM bootstrap below, `python main/wizard.py`), and those
runs stamp `via:` as well. If CI patched the tag into the source, a binary and a from-source
export built from the same commit would claim different versions, which is the same drift in a
place nobody would think to look.

So a release is two steps, in this order:

```bash
# 1. Bump it, commit it, push it
#    (edit python/main/wizard.py -> VERSION = "1.0.6")
git commit -am "Bump the macOS exporter to 1.0.6"
git push origin main

# 2. Write the changes for this version, then create the release as a draft
python scripts/release_notes.py draft --kind exporter --changes-file notes.md --dry-run
python scripts/release_notes.py draft --kind exporter --changes-file notes.md

# 3. Publish it from the GitHub UI, then collapse the release it replaced
python scripts/release_notes.py demote --kind exporter
```

`release_notes.py` exists because the release pages follow a convention that is easy to get
half-right: only the newest release carries the full wrapper — description, screenshot,
feature list, wiki link — and the one it replaces gets stripped back to its first line with
its changes folded into a collapsed `<details>` block. The half that gets forgotten is the
demotion, and nobody notices until two releases both look current.

The wrapper is never stored in the script. It is read from the release being superseded and
carried forward, which is what copying the previous body does by hand — so editing the wording
on the latest release is enough to change it for the next one. The version comes from
`VERSION`, and the tag from that, so neither can be typed wrong. `--kind android` does the
same for the app releases.

The draft is deliberate: the release workflow triggers on `published`, so nothing builds or
ships until someone clicks the button.

The tag must be `macos-exporter-v` followed by exactly what `VERSION` says. Before either
build job starts, `test-release-version` runs:

```bash
python scripts/release_version.py --kind exporter --tag macos-exporter-v1.0.6
```

which fails the release, with instructions, if the two disagree — so the mistake costs a
minute rather than an incorrectly labelled build on the releases page. Both build jobs then
take the version from that job's output instead of parsing the tag themselves, so the zip
name, the release title, and the version the app reports cannot come apart.

You can run the same check locally before tagging:

```bash
python scripts/release_version.py --kind exporter --print               # what the source declares
python scripts/release_version.py --kind exporter --tag macos-exporter-v1.0.6   # would this tag be accepted?
```

If you tagged before bumping, the fix is to push the bump, delete the release and its tag, and
re-tag the new commit. Releasing the Android app is unrelated and unaffected — its version
lives in `app/build.gradle.kts`.

## Releasing the Android app

Same shape as the exporter, with the version in a different file — `versionName` in
`app/build.gradle.kts`:

```kotlin
versionCode = 3
versionName = "1.0.5"
```

**Bump `versionCode` too.** Android refuses to install an APK whose code is not higher than
the installed one, so a `versionName`-only bump leaves existing users unable to update — and
it fails on their phone, never in any build.

```bash
# 1. Bump both, commit, push
git commit -am "Bump the Android app to 1.0.6"
git push origin main

# 2. Draft the release, then publish it from the GitHub UI
python scripts/release_notes.py draft --kind android --changes-file notes.md

# 3. Collapse the release it replaced
python scripts/release_notes.py demote --kind android
```

Publishing runs `build-release.yml`, which checks the tag against the source before building
anything:

```bash
python scripts/release_version.py --kind android --tag android-app-v1.0.6
```

then runs the tests, builds and signs the APK, and attaches it to the release. The tag must be
`android-app-v` followed by exactly what `versionName` says; the check fails the release, with
instructions, if they disagree.

Signing needs `SIGNING_KEY`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD` and the `ALIAS` in the
**Android Build Release** environment. Those are separate from the exporter's token, and a
release is a bad time to discover one has expired.

## Opening a pull request

- Run `./gradlew testAll` and `python scripts/add_strings.py --check` first
- If you changed the database schema, include the migration, a migration test, and the
  exported schema JSON — see rule 1 in [AGENTS.md](./AGENTS.md)
- Say what you verified and what you could not. A change needing a real Apple account, a
  Mac, or hardware you do not have is fine; claiming coverage you do not have is not
