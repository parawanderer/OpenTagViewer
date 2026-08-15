# AGENTS.md

Rules for agents working on OpenTagViewer. Written for automated contributors, but the
constraints apply to anyone.

## What this project is

An Android app that shows Apple AirTag locations without an Apple device. Tags are exported
from a Mac once (see the wiki), imported as a zip, and located via Apple's Find My network
through [FindMy.py](https://github.com/malmeloo/FindMy.py).

| Layer | Technology |
| --- | --- |
| App | Android, **Java** (not Kotlin), Gradle Kotlin DSL, `compileSdk`/`targetSdk` 35, `minSdk` 24 |
| Apple protocol | **Chaquopy** — CPython 3.12 embedded, bridged in `app/src/main/python/main.py` |
| Persistence | **Room** (`opentagviewer-db`) |
| Async | **RxJava3** through repositories and services |
| Boilerplate | **Lombok** (`@Builder`, `@Getter` on entities) |
| Desktop exporter | `python/` — tkinter wizard, PyInstaller |

`app/src/main/python/` is packaged into the APK by Chaquopy. Nothing in it may import
Android or Java types — that is what makes it testable on plain CPython.

---

## Rules

### 1. Never ship a Room schema change without a migration

Bumping `version` without a `Migration` throws at first database access **on upgrade only**.
A fresh install works fine, so it passes casual testing and then destroys every existing
user's imported beacons and location history.

- Add a `Migration` and register it in `addMigrations(...)`
- **Never** use `fallbackToDestructiveMigration()` — recovery means redoing the macOS
  export, and `allowBackup` is false so there is no backup
- Add a migration test, including the direct path from the oldest version (users skip
  releases)
- Schemas are exported to `app/schemas/` and committed; include the new JSON in your change

### 2. Do not claim something works if you have not run it

Say plainly what was verified, what was assumed, and what could not be checked. If a change
needs a real Apple account, a real Mac, or hardware you do not have, state that rather than
implying coverage. A wrong "this works" is worse here than an honest gap, because the
failure mode is silent data loss on other people's devices.

### 3. Verify library behaviour against the installed version

FindMy.py's API changes significantly between minor versions. Methods and properties that
existed in 0.7.x are gone in 0.9.x. Before "fixing" code that looks wrong, check the pinned
version's actual source — several apparent bugs here are forced adaptations.

```bash
python -m venv .venv && .venv/bin/pip install "FindMy==<pinned version>"
```

### 4. Respect the Anisette constraints

- **FindMy's own local provider still does not work.** `anisette` needs `unicorn`, a CPU
  emulator Chaquopy cannot build for Android. `app/stubs/unicorn/` is a stand-in that makes
  the dependency tree resolve; every method raises. The stub is not a step toward enabling
  `aniLocal`, and never will be.
- **The app does run ADI locally, by a different route.** `app/src/main/java/.../anisette/`
  loads Apple's real Android ADI libraries in-process and produces Anisette here, falling
  back to a remote server when it cannot. That is unrelated to `aniLocal` and needs no
  emulator — the libraries are native Android code. See `CONTRIBUTING.md` for how to run its
  tests.
- **Sessions are bound to one machine identity.** Local and remote Anisette present different
  ones, as do two different servers. Changing it requires a re-login. That is inherent to how
  Apple binds the session, not a bug to work around.
- **So a fallback must say so.** Accounts are deliberately serialized as `aniRemote` even
  when established locally, because the ADI state lives in app storage and not in the account
  — this keeps exported logins restorable anywhere. The cost is that a locally-established
  session can end up continuing against a server, and Apple will see a different machine.
  `LocalAnisette.recordSessionProvenance` records which kind established the session so that
  this is reported rather than presenting as auth that silently stops working. Never remove
  that warning to make a log quieter.

### 5. Never bundle an AMap API key

AMap issues keys per developer account, bound to a package name and signing fingerprint, and
expects the key holder to be the app's operator. Users supply their own in Settings, applied
at runtime via `MapsInitializer.setApiKey`.

Selecting AMap without a key must not save — both in Settings and on the first-run screen.

### 6. Keep both key-alignment paths working

Without a `KeyAlignmentRecord`, `FindMyAccessory` starts at index 0 from its pairing date, so
the first fetch searches the tag's entire history — ~50,000 keys for an 18-month-old tag, at
Apple's ~290-keys-per-request limit. That is an account-flagging risk, not just slowness.

- Exports from format `0.0.2` onward carry the record; it is passed to
  `FindMyAccessory.from_plist(plist, key_alignment_plist)`
- Older exports have none and fall back to the probe in `main.py`
- Changes must not break either path

### 7. Add map providers behind `IMapProvider`

Do not branch inside `MapsActivity`. The abstraction exists precisely so a new provider is a
new implementation — a third party added a MapLibre provider in ~80 lines because of it.

### 8. Attribute other people's work

When merging a contributor's changes, preserve authorship — cherry-pick, or set `--author`,
rather than copying file contents into your own commit. Credit them in the PR description.

### 9. Bump the exporter's `VERSION` before tagging a release

`VERSION` in `python/exporter/version.py` is the only place the desktop exporter's version is
written. It reaches the window title and, more importantly, every export it produces, as
`via: <producer>:<version>` in `OPENTAGVIEWER.yml` — which is how anyone looking at a zip later
works out what built it.

**There is more than one producer, and they must not claim to be each other.** The windowed
exporter stamps `OpenTagViewer.app:<version>`, its CLI stamps `OpenTagViewer.cli:<version>`, and
the Android app will stamp its own. They share `VERSION` because they ship together; the name in
front of it is what makes a bug report answerable. The shared writer takes `via` as a parameter
and never invents one — see `python/opentagviewer_export/bundle.py`.

Nothing patches it at build time, and nothing should: the wizard also runs from source (the
VM bootstrap, `python -m exporter.wizard`), and those exports stamp `via:` too, so a build-time
patch would make two artifacts from one commit disagree.

So releasing is two steps, in this order:

1. Commit the `VERSION` bump to `main`
2. Tag that commit `exporter-v<the same version>` and publish the release

`scripts/release_version.py --kind exporter --tag <tag>` enforces it, and runs in `test-release-version`
before any build job. A tag that disagrees fails the release rather than shipping a build that
lies about itself. `macos-exporter-v` is the old spelling and still resolves, because tags already
published keep their name — but it stopped being true when the exporter started building for
Windows and Linux. Full procedure: [CONTRIBUTING.md](./CONTRIBUTING.md#releasing-the-exporter).

### 10. Update the docs that index what you added

Some files list things rather than describe them, so they go stale silently — nothing fails,
the list is just quietly wrong, and the next person trusts it.

If you add or change one of these, update its index in the same commit:

| You added | Update |
| --- | --- |
| A workflow in `.github/workflows/` | the CI table in [CONTRIBUTING.md](./CONTRIBUTING.md#continuous-integration) |
| A test suite, or one that needs opting into | the test table and its section in `CONTRIBUTING.md` |
| A script in `scripts/` that people run by hand | the section of `CONTRIBUTING.md` covering that workflow |
| A constraint that would take someone an afternoon to rediscover | a rule here |

The test for whether it belongs here rather than in a comment: would somebody hit it *before*
reading the code that explains it? Anisette's machine-identity binding is the example — it
presents as auth failing for no reason, hours away from the code responsible.

### 11. The app is one device, and every path must say the same thing

Everything this app does against Apple is attributed to a device identity it invents, and the
user sees the result: an entry in their Apple account's device list, with a *Remove from Account*
button next to the words "If you do not recognise this device".

**So the identity has to be one identity.** It is assembled in more than one place — the client
info string in `anisette/AdiDeviceIdentity.java`, the hardware headers, the serial the FindMy.py
providers take as a parameter — and paths that disagree do not fail. They register **separate
devices**, and the user is invited to remove things they cannot identify, which breaks the
session that was working.

Under the iCloud export route the same identity reaches two further places a person reads: the
escrow record's metadata, which is the only way to recognise a record in a listing, and the
peer's `serialNumber` in the keychain trust circle. A serial that differs between them makes one
client look like several — and by
[findmy-export §5.2](./docs/findmy-export/03-keychain-trust.md), serials are exactly what
distinguishes devices there.

Three things follow:

- **One source of truth.** A path that needs the identity reads it; it does not compose its own.
- **The parts must agree with each other.** Model, OS version, build, CFNetwork and Darwin
  describe one real release ([findmy-export §2.2](./docs/findmy-export/01-authentication.md)).
  Claiming a Mac in one string and an iPhone in another is a contradiction Apple's own clients
  never produce.
- **The serial is a label, and the only field here the user actually sees.** `0PENTAGVIEWR` is
  confirmed accepted and displayed. Without one, Apple omits the row entirely, leaving an entry
  with nothing to tell it apart from real hardware.

**Changing it later adds an entry rather than renaming one**, and may require signing in again,
so it is not a thing to adjust casually once shipped. Document what the app registers as, so a
user reading their device list can recognise it — see the wiki.

---

## Building and testing

See **[CONTRIBUTING.md](./CONTRIBUTING.md)** for setup and every test suite. Short version:

```bash
./gradlew testAll           # everything that needs no device
./gradlew testAllOnDevice   # the above plus instrumented tests
```

**Instrumented tests run on a Gradle managed device.** Nothing needs to be booted first —
Gradle provisions the emulator, runs the tests, and destroys it:

```bash
./gradlew :app:testEmulatorDebugAndroidTest    # 72 tests, about 20 seconds
```

Use this rather than `connectedDebugAndroidTest`. The Android Gradle Plugin holds its ADB
connection inside the Gradle daemon and reuses it between invocations, so once a hand-started
emulator's adb daemon goes stale, the next run fails to install or hangs — with an error that
has nothing to do with the code. Recovering from that needs *both* an emulator restart and
`./gradlew --stop`, because restarting only the emulator leaves the daemon holding the dead
bridge. A managed device is created fresh per run, so nothing survives to go stale. It is
also the only form of this that CI can run unattended.

Three consequences worth knowing:

- The `aosp-atd` image carries no Play Services, so **a test that touches Maps will not run
  on it** — that device would need a `google` image. Nothing today does.
- **Espresso only works on the managed device.** A guest window has focus only while the
  emulator's own window has focus on the host desktop, so against a hand-started emulator any
  UI test fails with `RootViewWithoutFocusException` the moment you alt-tab away. There is
  nothing to fix in the test when that happens — run it on the managed device.
- `./gradlew testDebugUnitTest` is close to meaningless: there is exactly one JVM test and it
  asserts `2 + 2 == 4`. Everything real is instrumented. Do not report "tests pass" off it.

### Writing an Espresso test that is not flaky

**Never call `onView(...)` bare when the screen may still be settling, and never retry a click
until it stops throwing.** Both mistakes pass on a fast machine and fail in CI, which is how
this repo went red three runs in a row.

Use `Eventually` (in `app/src/androidTest/.../Eventually.java`). It has exactly two methods,
and picking the wrong one is the bug:

| | Use for | Why |
| --- | --- | --- |
| `Eventually.check(() -> …)` | assertions, and waiting for a view to appear | Espresso waits for the main thread to go idle and **nothing else**; every step in this app runs on an Rx scheduler and hops back, so a check made the instant a click returns asks about work that has not started |
| `Eventually.perform(what, tookEffect, () -> …)` | **anything that changes the screen** | see below |

`perform` exists because retrying-until-no-exception cannot tell two opposite things apart:

- the tap **missed** — the soft keyboard was still over the button — and must be retried
- the tap **worked**, and what it started has already torn the screen down, so Espresso throws
  `NoActivityResumedException` from the very same call

Retrying the second turns one success into fifty failures. Only the test knows what "it
worked" means, so it says — usually by asking a fake what it was called with:

```java
Eventually.perform("the sign in button", () -> apple.timesCalled("login") > 0,
        () -> onView(withId(R.id.login_button_main)).perform(click()));
```

Two more rules that came out of the same failures:

- **One `ViewAction` per `perform` when the action might finish the flow.**
  `perform(replaceText(code), closeSoftKeyboard())` fails on the *second* action, because the
  first one completed the sign-in and there is no activity left to close a keyboard on.
- **A `GONE` view still matches `withId`.** "Not on screen" is `matches(not(isDisplayed()))`,
  never an expected `NoMatchingViewException`.

### Showing a UI test to a person

A UI test runs at machine speed — a whole sign-in is over in about three seconds and looks
like a flicker. **When someone asks to watch a flow, do not just run it: it will be over
before they look up.** Run it in slow motion, on a device with a window:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.slowMotion=1500 \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.wander.android.opentagviewer.AppleLoginFlowTest#signingInWithATextedCodeReachesTheMap
```

> [!WARNING]
> **`connectedAndroidTest` uninstalls the app when it finishes.** Both APKs are removed, so
> everything the app stored on that device is gone: the signed-in session, all settings
> including a hand-registered AMap key, and every imported beacon and its location history.
> `allowBackup` is false, so on a real phone that is unrecoverable and means redoing the macOS
> export. **Only ever point this at a throwaway emulator**, tell the user it will wipe that
> device, and never at a phone with real data — `ANDROID_SERIAL` pins the target, so set it.
> The tests also overwrite settings while they run; `DeviceStateGuard` puts those back, but
> nothing puts back an uninstall.

Four prerequisites, and it silently does nothing useful without them:

1. **`connectedDebugAndroidTest`, not the managed device.** The managed device is headless —
   there is nothing to see. This is the one case where it is the wrong task.
2. **An emulator with a window must already be running**, and `ANDROID_SERIAL` pinned to it so
   the run cannot install onto a physical phone. Ask the user to start it rather than starting
   one yourself; they may already have one, and two emulators fight over `emulator-5554`.
3. **Its window must keep focus on the host desktop.** Espresso will not touch an unfocused
   window, so clicking away mid-run fails the test with `RootViewWithoutFocusException`. Say
   so before starting.
4. **`slowMotion` is a number of milliseconds** and defaults to off, so a normal run and CI are
   unaffected. 1000–1500 is comfortable to follow. See `TestPace`, and call
   `TestPace.afterAStep()` after each visible step in any new UI test.

Pick a single `#method` rather than a class. A whole class replays the same screens over and
over, which is longer without showing more.

`scripts/run_instrumented_tests.sh` wraps the same task with retries, and only retries when a
run failed *before* any test reported — a suite that ran and failed is reported as-is rather
than repeated. `GRADLE_TASK=:app:connectedDebugAndroidTest` switches it to a hand-started
emulator, where it pins `ANDROID_SERIAL` so a run cannot install to a physical phone.

Python must be on `PATH` — the build shells out to it to generate the unicorn stub wheel.

**Type-check Python with pyright, not mypy.** The editors used here run Pylance, which is
pyright; default mypy is far more lenient and will pass code your editor flags.

```bash
python -m pyright app/src/main/python/main.py
```

## Adding user-facing strings

The app ships ten locales. A string missing from one of them silently falls back to English
for those users — the build succeeds, and it looks fine in whichever language you speak. Use
the helper rather than editing ten files:

```bash
python scripts/add_strings.py                 # prints full usage and the input format
python scripts/add_strings.py --locales       # which locales exist, discovered from the tree
python scripts/add_strings.py new.json        # add strings to every locale
python scripts/add_strings.py --fill new.json # add only where missing, for back-filling
python scripts/add_strings.py --show <name>…  # print current text, in the input format
python scripts/add_strings.py --replace r.json # reword strings that already exist
python scripts/add_strings.py --check         # fail if any locale is missing a string
```

**Rewording an existing string goes through `--show` and `--replace`, never ten hand edits.**

```bash
python scripts/add_strings.py --show anisette_upgrade_message > reword.json
# edit reword.json
python scripts/add_strings.py --replace reword.json
```

Two reasons this is the rule. A missed locale keeps the old wording and still passes
`--check`, so nothing complains and the app says two different things in two languages. And
the intermediate JSON puts all ten translations in one reviewable diff, instead of ten
separate edits nobody reads to the end.

Translations are read **from a JSON file, never from a command-line argument**. That is not
a style preference: passing non-ASCII text through shell quoting has twice corrupted it here,
once putting a literal `\&#8217;` on screen where a French apostrophe belonged.

**Write the JSON exactly as the text should appear on screen, and do not escape anything.**
The tool escapes apostrophes, quotes and ampersands for you, and preserves the inline tags
`<u>`, `<b>` and `<i>`. A `'` you escape yourself arrives on screen as `\'`. This is settled —
do not go and re-read the script to check it before every batch of strings.

It also refuses to write unless every locale is supplied, and re-parses each file afterwards
so a malformed write fails immediately rather than at aapt time. Locales are discovered from
`app/src/main/res/values-*/strings.xml`, so adding a locale directory makes it required with
no change to the script.

`--check` is worth running before opening a PR; it found eight strings missing across seven
locales the first time it was run. It also runs in CI, on every PR and every release.

### Pre-commit hook

`.githooks/pre-commit` runs the translation check, plus flake8 and pyright over whichever
Python files are staged, and refuses a commit that stages `secrets.properties`. Hooks are not
versioned, so each clone has to opt in once:

```bash
git config core.hooksPath .githooks
```

It deliberately does not build or run tests. A hook that takes a minute gets bypassed with
`--no-verify` within a day, and a hook people routinely bypass is worse than none, because it
looks like a safety net that is not there. Use `--no-verify` when you genuinely need to.

## Install the GitHub CLI

If you are working with an agent, install and authenticate [`gh`](https://cli.github.com/):

```bash
gh auth login
gh auth status
```

It is the difference between an agent that can only guess at a red build and one that can
read it. Without `gh`, a failing check is a coloured square on a web page the agent cannot
see; with it, the agent can find the failing step, read the log, fix the cause and say what
it was. The same applies to triaging issues before changing anything.

```bash
gh pr checks <pr>                       # which checks passed, failed, are pending
gh run list --limit 5                   # recent runs
gh run view --job <id> --log-failed     # the failing output, once the run has finished
gh issue list --state all --limit 100   # is this already reported?
gh issue view <n> --comments            # the discussion, which often has the diagnosis
```

**While a run is still in progress, `--log-failed` refuses**, reporting only that logs will
be available when the run completes — unhelpful when one job failed in seconds and another
has half an hour of emulator left. The step-level API answers immediately and tells you
exactly which step died:

```bash
gh api repos/<owner>/<repo>/actions/jobs/<id> \
  --jq '.steps[] | "\(.conclusion // .status)\t\(.name)"'
```

Note that `gh`'s authentication is separate from git's. `gh` can be logged in while
`git push` still fails, if the remote is HTTPS with no cached credential or SSH with a
passphrase-protected key and no agent running. `gh` will still work for everything above.

## Windows traps

Each of these has already produced a hang that looked like something else:

- **`python3` is usually a trap.** It resolves to a zero-byte Microsoft Store alias in
  `%LOCALAPPDATA%\Microsoft\WindowsApps\` which hangs forever when run non-interactively
  rather than failing. Prefer `python`; reject candidates whose file length is zero.
- **Git Bash rewrites POSIX paths.** `adb push ... /sdcard/Download/` becomes
  `C:/Program Files/Git/sdcard/...`. `MSYS_NO_PATHCONV=1` fixes that but *also* ~~stops~~
  `JAVA_HOME` being translated, so `gradlew.bat` gets a POSIX path and dies. Never set it
  globally.
- **adb appears to hang from Git Bash** on `start-server`, because MSYS waits on the
  daemon's inherited handles. Use PowerShell, or have Android Studio open — it runs its own
  adb server.
- **A gradle command that seems to hang is usually the environment, not the build.** Never
  wrap one in a `grep` or a `timeout` that can swallow the error message — that turns a fast,
  legible failure into an apparent hang. `JAVA_HOME` pointing below 17 is the usual culprit,
  and it fails deep inside the Android Gradle Plugin with a message that reads like a plugin
  bug.

## Conventions

- Java, not Kotlin, in `app/`. Match the surrounding style.
- Entities use Lombok `@Builder`; construct them that way.
- Comments explain *why*, especially where behaviour looks wrong but is forced by Apple's or
  FindMy.py's API.
- User-facing strings go in `values/strings.xml` with translations in every supported locale.
  **Use `scripts/add_strings.py` rather than editing the files by hand** — see below.
- Prefer fixing the root cause to adding a workaround.
