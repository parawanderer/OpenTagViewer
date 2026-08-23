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

Tests live in a lot of places, because the code runs in three environments: the JVM, an Android
runtime, and CPython (both inside the app via Chaquopy, and on the desktop for the export
wizard).

| Suite | Location | Runner | Needs a device? |
| --- | --- | --- | --- |
| Android unit tests | `app/src/test/java/` | Gradle / JUnit | no |
| Android instrumented tests | `app/src/androidTest/java/` | Gradle / JUnit + emulator | provisioned for you |
| Anisette tests | `app/src/androidTest/java/.../anisette/` | as above, **opt-in** | yes, and network |
| UI tests | `AppleLoginFlowTest`, `app/src/androidTest/java/.../ui/` | Espresso, on the managed device | provisioned for you |
| Wiki screenshots | `app/src/androidTest/java/.../ui/WikiScreenshots*Test` | as above, **opt-in** | a windowed emulator |
| Chaquopy bridge tests | `app/src/test/python/` | pytest | no |
| Desktop exporter tests | `python/test/` | pytest | no |
| Shared export package | `python/opentagviewer_export/tests/` | pytest | no |
| Tooling tests | `scripts/test/` | pytest | no |
| Test doubles for the bridge | `app/src/debug/python/` | installed from an instrumented test | provisioned for you |
| Fakes for the screens | `app/src/androidTest/java/.../ui/maps/` | used directly by a test | provisioned for you |

### Faking the world the screens sit in

The Python doubles below get a session to restore. Three Java-side fakes get a *screen* to
render, and they exist because the managed device cannot provide what the real ones need.

| Fake | Stands in for | Why the real one cannot run here |
| --- | --- | --- |
| `FakeMapProvider` | `IMapProvider` | `aosp-atd` has no Play Services, so no real map initialises. It records what was drawn rather than drawing it, which is the more useful half: "there is a pin for this tag, at these coordinates" is a claim about the app's decision |
| `FakeMapView` | the map surface | Only so a screenshot shows something. It draws a flat ground, a graticule and the pins, captioned as a fake — **nothing asserts on what it paints** |
| `FakeGeocoder` | `AddressLookup` | The image has no geocoding backend, so `getFromLocation` returns empty for every point on earth — and the app's fallback for "no address" is to print the coordinates, so a missing geocoder is indistinguishable from an honest answer |

`AddressLookup` is an interface rather than a `Geocoder` subclass because `Geocoder` is final;
`AppDependencies.replaceGeocoder` is the seam.

**`AMapWithTagsOnIt` is the fixture that arranges all of it.** Reaching a drawn map takes eight
steps — a stored session, the Python double, a substituted provider, a geocoder, beacons with
usable `accessory_json`, locations to draw them at, and `RefreshPolicy.resetShared()` so the
startup fetch is not skipped. Seven tests need exactly that, and a copy of it that forgets the
last step passes for a year because the fetch it meant to observe never ran.

### Faking Apple, on the Python side of the bridge

**Everything this app does against Apple happens behind Python, so a fake on the Java side of
the bridge skips the bridge.** That is not a hypothetical: two bugs shipped through exactly that
gap while the whole suite stayed green.

- `PythonICloudService.openFor` checked its result with `made.toJava(Object.class)`, which
  throws for any Python object. The entire iCloud flow was dead on every device, and the screen
  blamed a missing account — a cause it had invented.
- `getLastReports` never emitted `wideSearch` or `exhaustedWideSearch`. Java reads both, a
  missing key reads as `false`, and the silent-tag backoff quietly did nothing at all.

Both were found by using the app. Every test of those paths replaced the Java service with a
Java fake, which is right for testing screens and means the bridge code itself — the JSON it
builds, the objects it converts, the reason strings it maps — had never run.

So there are two doubles, and they sit **below** the code under test rather than in front of it:

| Module | Replaces | So a test can |
| --- | --- | --- |
| `icloud_test_double` | the two functions in `exporter.icloud` that talk to Apple | drive sign-in, unlock, join, fetch, rename and close for real |
| `apple_test_double` | `main.getAccount` and `main.accessoryFromJson` | have a stored session restore, so screens that wait on one will draw — and answer both fetch paths, so the map and the history screen both work |

They live in the **debug source set**. Chaquopy compiles `src/<variant>/python` alongside
`src/main/python`, so they are in the debug APK the instrumented tests run against and in no
release build. Nothing in `main` imports them; a test installs them at runtime:

```java
final PyObject double_ = Python.getInstance().getModule("apple_test_double");
double_.callAttr("install");        // or installWithNothingToReport()
// ... drive the app ...
double_.callAttr("uninstall");      // in @After, always
```

Both are idempotent on install and safe to uninstall without a matching install, but **an
uninstall that never runs leaves the fake in place for every test after it** — so it belongs in
`@After`, not at the end of the test body.

Two things worth knowing before reaching for these:

- **Restoring a session needs no network.** `getAccount` is `AppleAccount.from_json` and nothing
  else; the sockets only appear at fetch time. That is why `apple_test_double` is small.
- **Neither of these tests Apple.** They prove this app's code is correct about a protocol it
  cannot check, so they say nothing about whether Apple still accepts what is being sent.
  Nothing in this repository has run against a real account in CI, and nothing can — which is
  why rule 2 in [AGENTS.md](./AGENTS.md) asks you to say what you actually verified.

`TheWholeICloudFlowAcrossTheBridgeTest` and `TheMapDrawsWhatIsStoredTest` are the worked
examples.

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

### Building a smaller APK for a local install

The debug APK is about **105 MB**, and 65 MB of that is native libraries: Chaquopy's CPython,
`cryptography`'s OpenSSL and Apple's ADI libraries, built for both `arm64-v8a` and `x86_64`.
Whatever you install to only uses one of them.

```bash
./gradlew :app:assembleDebug -PotvAbi=x86_64    # an emulator
./gradlew :app:assembleDebug -PotvAbi=arm64-v8a # a phone
```

That takes it to **68.6 MB**, and the saving is roughly double that in practice — an upgrade
needs room for the new APK while the old one is still installed.

Worth knowing when you hit `INSTALL_FAILED_INSUFFICIENT_STORAGE`, whose message says nothing
about ABIs. The other half of that fix is the emulator itself: a Pixel AVD defaults to a 6 GB
data partition, and Device Manager → Edit → Advanced → Internal Storage raises it. Changing it
wipes the device, so export anything you care about first — an account-linked install can be
re-read, but zip-imported tags and any location history older than about seven days cannot.

**It is for local debug installs only.** A release must carry both ABIs, so `assembleRelease`
refuses to run while `otvAbi` is set rather than quietly ignoring it. That matters because the
property is also read from `gradle.properties`, including `~/.gradle/gradle.properties` — so
setting it there to save typing would otherwise produce a release that installs on no phone
anybody owns, with a green build log. An unrecognised ABI fails the build too, rather than
producing an APK with no native libraries that installs fine and dies at the first Chaquopy
call.

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

**If a run sits in `:app:testEmulatorDebugAndroidTest` and never starts a test**, check the lock
count before anything else:

```bash
cat ~/.android/avd/gradle-managed/active_gradle_devices    # "MDLockCount 4" with nothing running
```

AGP counts its in-flight managed devices there, and a run that is killed rather than finished
never gives its slot back — so the count creeps up and eventually every run waits for a slot
nothing will release. It boots no emulator and says nothing, which is why it reads as a slow
start. Stop the daemons, delete the file, run again:

```bash
./gradlew --stop
rm ~/.android/avd/gradle-managed/active_gradle_devices
```

> [!WARNING]
> `connectedDebugAndroidTest` **uninstalls the app afterwards**, taking its session, settings
> and every imported beacon with it. `allowBackup` is false, so on a real device that is gone
> for good. Use a throwaway emulator, and keep `ANDROID_SERIAL` pinned to it.

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

### Wiki screenshots (skipped by default)

`WikiScreenshotsTest`, `WikiScreenshotsFromTheMapTest` and `WikiScreenshotsOfSigningInTest`
photograph the app's screens for the wiki. They are a documentation tool rather than tests —
each one ends in a `Shot` and asserts almost nothing — so they are skipped unless asked for:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.captureScreenshots=true \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.wander.android.opentagviewer.ui.WikiScreenshotsTest
```

Images land in `app/build/outputs/connected_android_test_additional_output/…`. **Copy them out
before the next run** — AGP wipes that directory when a run starts, which has already lost a
full set.

Three things they need, and one that will surprise you:

- **A windowed emulator with Play Services**, not the managed device: two of them photograph a
  real Google map, and the managed `aosp-atd` image cannot draw one. Keep its window focused or
  Espresso fails with `RootViewWithoutFocusException`.
- **`connectedDebugAndroidTest` uninstalls the app afterwards**, wiping that device's session,
  settings and imported beacons. Only ever aim it at a throwaway emulator, and set
  `ANDROID_SERIAL` so it cannot reach a phone.
- **The map class is slow, and it is the teardown.** About 50s per test, of which ~45s is
  `AMapWithTagsOnIt.putItBack` — measured; setup is around two seconds. Worth knowing before
  concluding that the map, the dialogs or the tile wait are responsible, all of which look
  guilty and are not.

Before publishing one, look at what is actually on it — a signed-in address, a real street name
from the geocoder, a bundle passcode, a signing fingerprint.
`.claude/skills/device-screenshots/blur.py` takes bands out by height fraction.

### Android unit tests

Plain JVM, no Android framework, so these are the fastest tests in the project — **118 of them
in about ten seconds**, no emulator. Worth running constantly while working on anything they
cover; anything needing neither Android nor a device belongs here rather than on the managed
device, which is two orders of magnitude slower.

Most of what lives here is in `util/rx/`: the stream compositions and decision logic behind
the map, extracted out of `MapsActivity` precisely so it could be tested. They assert that a
call *happens* rather than that a value looks right, because the failures in this area are
silent — a stream disposed early, a marker that stops being raised, a fetch that returns
nothing being indistinguishable from a fetch that failed. None of those throw, and none show
up in logcat.

`ui/maps/CoordinateConverterTest` is here for a different reason, and is worth knowing about:
it is the **only** coverage of the AMap path that exists. Both real map providers need a device
this project cannot provision — `aosp-atd` has no Play Services, and the AMap SDK is optional at
compile time — so everything else runs against `FakeMapProvider`. The GCJ-02 conversion is the
one piece that is pure arithmetic, and a wrong one puts every pin a few hundred metres out for
every user in mainland China, silently.

```bash
./gradlew testDebugUnitTest
```

Report: `app/build/reports/tests/testDebugUnitTest/index.html`

### Chaquopy bridge tests

Cover everything under `app/src/main/python/`, which Chaquopy packages into the APK: `main.py`,
`identity.py` and `icloud_bridge.py`. None of them import Android or Java types, so they run on
plain CPython — that constraint is what makes them testable at all, and it is worth keeping.

They also reach the shared `python/` tree, because `conftest.py` puts it on the path exactly as
the build packages it. What that cannot tell you is whether a module survives *being* packaged —
`icloud_bridge` reaches `findmy.cloudkit` and therefore protobuf, which resolves on a laptop and
is the shape of thing that goes missing on a phone. `PythonPackagingTest`, on the managed device,
is what answers that half.

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

### Desktop exporter tests

For the exporter under `python/` — the CLI, the windowed wizard, and the shared export package
they both write bundles with. Dependencies are managed with [uv](https://docs.astral.sh/uv/),
which installs a suitable Python itself:

```bash
cd python
uv sync

uv run pytest ./test ./opentagviewer_export
uv run flake8 . --count --select=E9,F63,F7,F82 --show-source --statistics
```

**Both paths, always.** `python/test/` covers the exporter — the iCloud mapping, the key-file
readers, the CLI's own decisions — and `python/opentagviewer_export/tests/` covers the shared
package that writes the bundle. The second is the one the Android app also depends on, and its
strongest test rebuilds the committed export fixtures in `app/src/test/resources/`
**byte for byte**, so a change in how a record is serialised fails here rather than on a phone.

Two things it deliberately does not cover, because they need an Apple account: signing in, and
everything the account then yields. What happens to the records afterwards is covered in full.

**Four files need Tk and skip without it.** All of them drive real Tk widgets, because what they
cover *is* the widget: a cancelled progress window used to leave the wizard hung for ever, and
that is not reproducible against a stand-in.

| File | What it drives |
| --- | --- |
| `test_asyncui.py` | closing the progress window mid-sign-in |
| `test_wizard_list.py` | the list of accessories |
| `test_save_logs_button.py` | the Save logs button, and that no identifier survives into the file |
| `test_terms_dialog.py` | Apple's terms of service, and that refusing them sends nothing |

**The skip has to be `pytest.importorskip`, at the top, before importing `exporter.wizard`.** A
`pytest.skip` inside a fixture is too late: the module is imported during collection, so a Python
built without Tk fails the whole run before any fixture can decline.

**Do not test a modal by driving it.** `grab_set` plus `wait_window` hand control to a nested
event loop and a window manager, and a test that schedules a click into that deadlocks rather than
failing. Build the window and show it in separate functions, as `_build_terms_window` and
`_show_terms` do, and drive the built one. For the same reason, keyboard bindings are asserted
with `widget.bind("<Key>")` rather than `event_generate`: a Toplevel under a withdrawn root holds
focus from nobody, so a generated keypress is dropped in silence and the test passes without
having pressed anything.

**CI runs them on one job in four.** The matrix is 3.10 to 3.13 on `macos-14`, and only its 3.13
has `_tkinter` — the other three skip both files at collection, so a green square there says
nothing about the window. `-rs` is what tells you which you got:

```bash
uv run pytest ./test -rs
```

`361 passed, 4 skipped` means Tk was missing and neither file ran. `378 passed, 2 skipped` means
they did, and the two remaining skips are the macOS-14-only decryptor tests.

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

**This covers the macOS-local route only.** Exporting through iCloud needs no Mac and no VM —
see [Running the exporter from the CLI](./docs/how-to-export-with-the-cli.md). What follows is
for testing the path that reads Find My's own files, which still runs on macOS and nowhere else.

Testing that export means a real Mac signed into iCloud, usually a VM. A fresh macOS install
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
PYTHONPATH=. python3 exporter/wizard.py
```

</details>

`PYTHONPATH=.` is not optional: `wizard.py` does `from exporter.airtag_decryptor import ...`, and
running the file directly puts `python/exporter` on `sys.path` rather than `python/`, so the
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

# Rewrite the locked bundle the Android importer's tests read. Needs the exporter's own
# dependencies (`pip install -e python/`), because it writes the fixture with the real
# exporter rather than an imitation of it — see below.
python scripts/make_locked_bundle_fixture.py
```

### The locked-bundle fixture

`app/src/androidTest/assets/locked_bundle_fixture.zip` is committed rather than built by the
test that reads it, and that is deliberate. The exporter writes AES-256 in the WinZip scheme
through `pyzipper`; the app reads it with zip4j. A test that wrote *and* read with zip4j would
prove zip4j agrees with itself, which is not the thing that can break. So the fixture is the
bytes the real exporter produces, and `LockedBundleTest` opens them the way a user would.

The unlock code it is built with, `H4K2-9WMR-7TQX`, is in both the generator and the test —
typed there in the grouped form so that the normalisation in `BundlePasscode` is exercised
too. Regenerate after any change to `zipsink.py` or `passcode.py`. The bytes differ on every
run regardless: each AES entry carries a fresh random salt.

---

## What CI runs

| Workflow | When | What |
| --- | --- | --- |
| `static-checks.yml` | `app/**`, `scripts/**` changes | Translation check, pyright, string tooling tests. Seconds, no Android |
| `build-debug.yml` | Android build inputs change | Instrumented tests on an emulator, JVM tests, Chaquopy bridge tests, debug APK |
| `build-release.yml` | on release | Translation check, JVM tests, Chaquopy bridge tests, release APK |
| `macos-scripts-python.yml` | `python/**` changes | Exporter and shared-package tests across Python 3.10–3.13 on macOS 14 |
| `macos-exporter-python.yml` | on release | Tag/version check, exporter tests, and the PyInstaller build for macOS (both architectures), Windows and Linux |
| `exporter-build-check.yml` | PR and push to `main`, `python/**` | Builds the Windows binary and starts it. The release workflow above only runs on `release: published`, so without this a broken bundle is first run by whoever downloads it |
| `update-contributors.yml` | weekly | Regenerates the contributor list on the Information page, opens a PR if it changed |
| `check-adi-libraries.yml` | weekly | Checks Apple's ADI libraries still match what is checked in, opens an issue if they drifted |

The instrumented job needs KVM on the runner; the workflow enables it first. It runs the same
`:app:testEmulatorDebugAndroidTest` managed device you run locally, so there is no second
emulator definition in CI to keep in step with `app/build.gradle.kts`.

### A PR stacked on another PR gets no checks at all

Every workflow above filters on `pull_request: branches: ["main"]`, and that filter matches the
**base** of the PR, not the branch the changes are on. So a PR opened against another PR's branch
runs nothing — no translation check, no tests, no build.

It does not look like a problem. The checks section is simply absent rather than red or pending,
which reads as "nothing to run here" and not as "nothing ran". Merge the base, retarget the child
at `main`, and the whole suite appears.

Stacking is still often the right shape for a chain of dependent work. Just know that the second
PR is unverified until it points at `main`, and do not read its empty checks list as a pass.

---

## Releasing the exporter

The exporter's version lives in exactly one place — `VERSION` in `python/exporter/version.py`:

```python
VERSION = "1.0.5"
```

It shows in the window title, and it is stamped into every export as
`via: OpenTagViewer.app:1.0.5` inside `OPENTAGVIEWER.yml`. That field is how a maintainer
reading a bug report works out which exporter produced the zip in front of them, so it has to
be true.

**Nothing rewrites it at build time.** The release tag names the zip and the GitHub release;
the app keeps whatever was committed. That is deliberate rather than an oversight — the wizard
also runs straight from source (the VM bootstrap below, `python -m exporter.wizard`), and those
runs stamp `via:` as well. If CI patched the tag into the source, a binary and a from-source
export built from the same commit would claim different versions, which is the same drift in a
place nobody would think to look.

So a release is two steps, in this order:

```bash
# 1. Bump it, commit it, push it
#    (edit python/exporter/version.py -> VERSION = "1.1.1")
git commit -am "Bump the exporter to 1.1.1"
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

The tag must be `exporter-v` followed by exactly what `VERSION` says. Before any build job
starts, `test-release-version` runs:

```bash
python scripts/release_version.py --kind exporter --tag exporter-v1.1.0
```

which fails the release, with instructions, if the two disagree — so the mistake costs a
minute rather than an incorrectly labelled build on the releases page. Every build job then
takes the version from that job's output instead of parsing the tag themselves, so the zip
name, the release title, and the version the app reports cannot come apart.

> **The tag used to be `macos-exporter-v`, and still resolves.** The exporter reads accessories
> out of iCloud now and builds for Windows and Linux as well, so the name stopped being true —
> but tags already published keep theirs forever, and nobody's habits update with a rename. Use
> `exporter-v` for anything new.

You can run the same check locally before tagging:

```bash
python scripts/release_version.py --kind exporter --print              # what the source declares
python scripts/release_version.py --kind exporter --tag exporter-v1.1.0   # would this tag be accepted?
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
