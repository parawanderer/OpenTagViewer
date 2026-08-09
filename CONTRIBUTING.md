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

### Install the git hook

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

### If you use a coding agent, install `gh`

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
| Chaquopy bridge tests | `app/src/test/python/` | pytest | no |
| Desktop wizard tests | `python/test/` | pytest | no |
| String tooling tests | `scripts/test/` | pytest | no |

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

To run against a device you already have, `scripts/run_instrumented_tests.sh` pins
`ANDROID_SERIAL` so a run cannot install to a physical phone, and restarts the emulator and
the Gradle daemon if a run wedges.

Report: `app/build/reports/androidTests/managedDevice/debug/allDevices/index.html`

Targeting a subset:

```bash
./gradlew :app:testEmulatorDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=dev.wander.android.opentagviewer.db
```

### Android unit tests

Plain JVM, no Android framework. Be aware there is currently almost nothing here — the
meaningful coverage is instrumented, so a green `test` run says very little.

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

### String tooling tests

```bash
python -m pytest scripts/test -v
```

`scripts/add_strings.py` gates CI's translation check, so a bug in it would report green
while protecting nothing.

### Type checking

**pyright, not mypy.** The editors used here run Pylance, which is pyright; mypy's defaults
are lenient enough to pass code Pylance flags.

```bash
python -m pip install pyright pillow -r app/src/test/python/requirements.txt
python -m pyright app/src/main/python scripts
```

The installs matter: pyright reports an unresolved import as an error, so without `findmy`,
`NSKeyedUnArchiver` and `pillow` present it fails while telling you nothing about your code.

`python/` is not clean yet and is excluded from CI's pyright step.

---

## Adding user-facing strings

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
| `macos-exporter-python.yml` | on release | Wizard tests plus the PyInstaller build |
| `update-contributors.yml` | weekly | Regenerates the contributor list on the Information page, opens a PR if it changed |

The instrumented job needs KVM on the runner; the workflow enables it before starting the
emulator.

## Opening a pull request

- Run `./gradlew testAll` and `python scripts/add_strings.py --check` first
- If you changed the database schema, include the migration, a migration test, and the
  exported schema JSON — see rule 1 in [AGENTS.md](./AGENTS.md)
- Say what you verified and what you could not. A change needing a real Apple account, a
  Mac, or hardware you do not have is fine; claiming coverage you do not have is not
