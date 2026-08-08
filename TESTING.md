# Testing

Tests live in four places, because the code runs in three different environments:
the JVM, an Android runtime, and CPython (both inside the app via Chaquopy and on
the desktop for the export wizard). Each needs a different runner.

| Suite | Location | Runner | Needs a device? |
| --- | --- | --- | --- |
| Android unit tests | `app/src/test/java/` | Gradle / JUnit | no |
| Android instrumented tests | `app/src/androidTest/java/` | Gradle / JUnit + emulator | **yes** |
| Chaquopy bridge tests | `app/src/test/python/` | pytest | no |
| Desktop wizard tests | `python/test/` | pytest | no |

## Prerequisites

- **JDK 17+.** Android Studio's bundled runtime works:
  `export JAVA_HOME="/path/to/Android Studio/jbr"`
- **Android SDK**, with `local.properties` containing `sdk.dir=...`, or `ANDROID_HOME` set.
- **Python 3** on `PATH`. The build shells out to it at configuration time to
  generate the stub `unicorn` wheel (see `scripts/build_unicorn_stub_wheel.py`).
  If your interpreter is named something unusual, pass `-PpythonExecutable=...`.

## Run everything

```bash
export JAVA_HOME="/path/to/jbr"

./gradlew testDebugUnitTest                                  # JVM unit tests
./gradlew connectedDebugAndroidTest                          # needs a running emulator/device
python -m pytest app/src/test/python                         # Chaquopy bridge
(cd python && python -m pytest ./test)                       # desktop wizard
```

---

## Android unit tests

Plain JVM tests. No Android framework, no device.

```bash
./gradlew testDebugUnitTest
./gradlew test                    # all variants

# a single class
./gradlew testDebugUnitTest --tests 'dev.wander.android.opentagviewer.ExampleUnitTest'
```

Report: `app/build/reports/tests/testDebugUnitTest/index.html`

## Android instrumented tests

These need a real Android runtime — they cover the Room database, the v1→v2
migration, and the `accessory_json` backfill, none of which can run on the JVM.

Start an emulator first (or plug in a device with USB debugging on):

```bash
# one-time: create an AVD
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  -n otv_test -k "system-images;android-35;google_apis_playstore;x86_64" -d pixel_6

# boot it
"$ANDROID_HOME/emulator/emulator" -avd otv_test -no-window -gpu swiftshader_indirect &

adb devices    # confirm it appears
```

Then:

```bash
./gradlew connectedDebugAndroidTest

# example: one test
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.wander.android.opentagviewer.db.room.OpenTagViewerDatabaseMigrationTest

# example: everything under the db package
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=dev.wander.android.opentagviewer.db
```

Report: `app/build/reports/androidTests/connected/index.html`

> **With more than one device attached**, pin the target so a build never lands on
> your phone by accident:
> ```bash
> export ANDROID_SERIAL=emulator-5554
> ```
> Debug builds use the applicationId `dev.wander.android.opentagviewer.debug` and are
> labelled "OpenTagViewer (debug)", so they install alongside a real install rather
> than colliding with it. Never uninstall a production install to force an install —
> `allowBackup` is false, so the beacons and location history are gone for good.

## Chaquopy bridge tests

Cover `app/src/main/python/main.py`, the module Chaquopy packages into the APK.
Nothing in it imports Android or Java types, so it runs on plain CPython.

```bash
python -m venv .venv
.venv/bin/pip install -r app/src/test/python/requirements.txt     # Windows: .venv/Scripts/pip

python -m pytest app/src/test/python -v
python -m pytest app/src/test/python -k alignment                 # one area
```

The pins in `app/src/test/python/requirements.txt` must match the
`chaquopy { pip { ... } }` block in `app/build.gradle.kts`, or the tests exercise a
different FindMy version than the app ships. `test_pinned_versions_match_the_app_build`
enforces that.

Fixtures live in `app/src/test/resources/` — a redacted copy of a real export. See
the README there for what was redacted and why.

## Desktop wizard tests

For the macOS export wizard under `python/`.

```bash
cd python
python -m pip install -r requirements.txt
python -m pip install flake8 pytest

python -m pytest ./test
flake8 . --count --select=E9,F63,F7,F82 --show-source --statistics
```

---

## Offline diagnostic scripts

Not tests, but useful and requiring neither an Apple account nor network access.

```bash
# Would my existing export survive the FindMy 0.9.x upgrade, and how expensive
# is the first fetch? Prints derived numbers only - no identifiers, no contents.
python scripts/check_export_compatibility.py ~/path/to/OpenTagViewer_export_.../

# Same measurement across a directory of plists.
python scripts/measure_key_alignment_cost.py <dir> --hours-back 24

# Generate a synthetic beacon plist of a given age.
python scripts/make_test_beacon_plist.py out.plist --days-old 730
```

## What CI runs

- `build-debug.yml` — JVM unit tests, Chaquopy bridge tests, instrumented tests
  on an emulator, then the debug APK.
- `build-release.yml` — JVM unit tests, then the release APK.
- `macos-scripts-python.yml` — desktop wizard tests across Python 3.10–3.13.
- `macos-exporter-python.yml` — desktop wizard tests plus the PyInstaller build.

The instrumented job needs KVM on the runner; the workflow enables it before
starting the emulator.