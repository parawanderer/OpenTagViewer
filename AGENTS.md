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
exporter stamps `OpenTagViewer.wizard:<version>`, its CLI stamps `OpenTagViewer.cli:<version>`, and
the Android app will stamp its own. They share `VERSION` because they ship together; the name in
front of it is what makes a bug report answerable. The shared writer takes `via` as a parameter
and never invents one — see `python/opentagviewer_export/bundle.py`.

Nothing patches it at build time, and nothing should: the wizard also runs from source (the
VM bootstrap, `python -m exporter.wizard`), and those exports stamp `via:` too, so a build-time
patch would make two artifacts from one commit disagree.

So releasing is two steps, in this order:

1. Commit the `VERSION` bump to `main`
2. Tag that commit `exporter-v<the same version>` and publish the release

**And the app's release goes out before the exporter's, whenever the exporter's changes what a
bundle is.** They are separate releases with separate tags, which makes them look independent;
they are not. Exporter 1.4.0 locks bundles by default, and an app older than 1.1.0 cannot decrypt
one at all — it fails with a message about the zip rather than about a code. Publish the exporter
first and every bundle written that day is unopenable by whoever receives it, and the recipient is
the one person in that transaction who chose none of it and can fix none of it.

Nothing enforces this — `release_version.py` checks a tag against a version, not one release
against another — so it is a thing to remember, which is why it is written here.

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
| A skill in `.claude/skills/` | the skills table below |
| A constraint that would take someone an afternoon to rediscover | a rule here |

Skills carry the longer version of a workflow, so this file can stay short:

| Skill | For |
| --- | --- |
| `.claude/skills/add-strings/` | any user-facing string — adding, rewording, removing, checking |
| `.claude/skills/device-screenshots/` | rendering the UI on the managed device and reading it cheaply |
| `.claude/skills/watch-pr/` | watching a pushed PR's checks through to a verdict, and acting on it |
| `.claude/skills/watch-gradle-tests/` | watching a backgrounded emulator suite to a verdict, and telling the silent hangs apart |

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

Four things follow:

- **One source of truth.** A path that needs the identity reads it; it does not compose its own.
  `AdiDeviceIdentity.Hardware` is it — Python reads the six values across the bridge rather than
  keeping a copy, because there is no single right answer to keep: an install from before the
  choice existed must present the Mac its ADI was provisioned with, while a fresh one presents
  the iPhone.
- **The parts must agree with each other.** Model, OS version, build, CFNetwork and Darwin
  describe one real release ([findmy-export §2.2](./docs/findmy-export/01-authentication.md)).
  Claiming a Mac in one string and an iPhone in another is a contradiction Apple's own clients
  never produce.
- **The serial is a label, and the only field here the user actually sees.** `0PENTAGVIEWR` is
  confirmed accepted and displayed. Without one, Apple omits the row entirely, leaving an entry
  with nothing to tell it apart from real hardware.
- **Sending the same value is not the same as sending the same bytes.** Two of these fields are
  transformed on the way out, and only by one side. FindMy.py sends `X-Apple-I-MD-LU` as
  `base64(uid)` and uppercases `X-Mme-Device-Id`; the Java ADI path sends what it is given. So
  handing Python the string Java sends aligns one field and silently leaves the other as two
  different values — **alignment that reads as alignment and is not**, which is worse than none,
  because nothing ever complains. Java renders the header per hardware profile for exactly this
  reason, and `IdentityBridgeTest` pins both directions. Check what actually goes on the wire
  before believing two paths agree.

**Changing it later adds an entry rather than renaming one**, and may require signing in again,
so it is not a thing to adjust casually once shipped. Document what the app registers as, so a
user reading their device list can recognise it — see the wiki.

### 12. A UI change gets a test that inflates it, and one that drives it

UI is where this repo's silent failures live. Nothing throws, the build is green, and the
screen is simply wrong — blank, unreadable, or quietly dropping what the user typed. Every
example below shipped or nearly shipped here.

**Inflate it. Almost always, and it is cheap.** No activity, no account, no network: build a
themed context, inflate, measure, draw to a bitmap. `SystemColorsLayoutTest` is the pattern.
What to actually assert:

| Check | The failure it catches |
| --- | --- |
| It inflates at all | a missing `?attr/`, a style that does not exist in this theme, a bad `tools:` leak — throws at runtime only, on the one screen nobody opened |
| Every id the code looks up resolves | a renamed id still compiles; `findViewById` returns null and the screen half-works |
| Drawables load **the way the app loads them** | `ResourcesCompat.getDrawable(..., null)` draws a theme-attribute vector as *nothing*. The history timeline went blank this way while its screenshot test stayed green, because the test passed a theme and the app did not |
| Resolved colours, not named ones | contrast is a number: 3:1 non-text, 4.5:1 AA. A selected pin here once landed at 1.23:1 — present, correct, invisible |
| Dark mode, as a second render | `createConfigurationContext` with `UI_MODE_NIGHT_YES`. Half of what breaks only breaks in one mode |
| Measured size is not zero, and does not clip | `wrap_content` on something that measures to nothing |
| A screenshot, alongside the assertions | it explains *why* a failure looks wrong. **It is not itself an assertion** — see below |

**Then drive it with Espresso and fakes, for the error paths as much as the success one.**
Success gets exercised by hand constantly; failures do not, and failures are what users meet.
`AppDependencies.replaceAnisette` and the fake auth service exist for exactly this — states
that would otherwise need Apple to have shipped a new build or the network to be down.
`TestHostActivity` hosts a dialog without starting an activity that wants an Apple session.

Assert what the user is told, per failure, not merely that something failed. Every import
error used to arrive at one toast telling people to restart the app, and no test noticed
because no test asked what it said.

**Two habits that make the difference between a test and a decoration:**

- **Check the test can fail.** Break the thing on purpose and confirm it goes red. Ten
  green tests first try is a reason for suspicion, not confidence — the paste handling in
  `BundlePasscodeDialogTest` was verified this way, by reinstating the truncation bug it
  exists to catch and watching exactly two of the ten turn red.
- **A screenshot is not an assertion.** It shows what one configuration looked like once.
  Assert the resolved colour, the ratio, the measured height — and be careful what the
  assertion is *about*.

The mechanics of all of this — `Eventually`, the managed device, rendering to a bitmap and
compacting the results — are under [Building and testing](#building-and-testing) below. Do not
restate them here; this rule is about *what* to cover, that section is about *how*.

### 13. If it does not need a device, test it on the JVM

`./gradlew testDebugUnitTest` runs the JVM suite in about fifteen seconds. The emulator suite is
**580 tests and twelve minutes**, and it has to boot a device first. So a test that could have
been a JVM test and was written as an instrumented one costs that difference on every run, for
every person, forever — and it is the reason a suite stops being run while working.

**Ask what the class actually touches, not which package it lives in.** Parsing, arithmetic,
policy, protocol shapes, anything taking bytes and returning bytes: JVM. A `dev.wander.…android…`
package name is not an answer, and neither is "it feels like Android code".

**A stray `Log` line is not a reason to go to the emulator.** This is the trap, because it looks
like one: `android.util.Log` throws *"not mocked"* in a JVM test, so a class that is otherwise
pure Java appears to require a device. It does not — `testOptions.unitTests.isReturnDefaultValues`
is already on, and Log returns 0 instead of throwing.

The worked example is `AdiLibraryFetcher`, which reads Apple's ADI libraries out of a 142 MB APK
over HTTP range requests. Zip parsing, offset arithmetic, an inflate and an ELF check — and one
`Log.i` in the middle of it, which is why it was first written as an instrumented test:

| | |
| --- | --- |
| As an instrumented test | ~17s, after provisioning an emulator |
| As a JVM test | **0.145s**, inside a suite you can run on every edit |

Both were the *same six tests*, against the same code, serving a fixture zip off a socket on
`127.0.0.1`. Nothing was given up by moving it.

Two things follow:

- **"It reaches the network" is rarely a reason either.** A `ServerSocket` and a byte array cover
  an HTTP client completely, and far better than the real endpoint does — see `FakeApkServer`,
  which can also refuse to honour range requests, something Apple's CDN will not do on request.
- **The JVM suite is still not the one to report "tests pass" from.** Every screen, every
  repository and the whole Python bridge are instrumented. Rule 12 is about what genuinely needs
  a device; this rule is about not sending it things that do not.

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
./gradlew :app:testEmulatorDebugAndroidTest    # 580 tests, about twelve minutes
```

Use this rather than `connectedDebugAndroidTest`. The Android Gradle Plugin holds its ADB
connection inside the Gradle daemon and reuses it between invocations, so once a hand-started
emulator's adb daemon goes stale, the next run fails to install or hangs — with an error that
has nothing to do with the code. Recovering from that needs *both* an emulator restart and
`./gradlew --stop`, because restarting only the emulator leaves the daemon holding the dead
bridge. A managed device is created fresh per run, so nothing survives to go stale. It is
also the only form of this that CI can run unattended.

#### The run sits in `testEmulatorDebugAndroidTest` and never starts a test

**Check the lock count first.** It is a one-line fix and it looks like four other things:

```bash
cat ~/.android/avd/gradle-managed/active_gradle_devices    # e.g. "MDLockCount 4"
adb devices                                                # no managed AVD listed
```

If the count is above zero with no run in flight, that is the fault. Stop the daemons, delete
the file, run again — AGP recreates it and there is nothing in it worth keeping:

```bash
./gradlew --stop
rm ~/.android/avd/gradle-managed/active_gradle_devices
```

**Why it happens:** AGP counts the managed devices it has in flight in that file, and a run that
is interrupted rather than finished never gives its slot back. Each `Ctrl-C`, each killed
background job, each IDE stop button adds one. Once the count reaches the concurrency limit the
next run waits for a slot that nothing will ever release.

**Why it is worth a heading:** it boots *no emulator at all* and prints nothing while waiting, so
for the first ten minutes it is indistinguishable from a device starting slowly, and after that
from every other reason a run can hang. Three runs were abandoned here diagnosing it as port
contention with a hand-started emulator — which it was not, and `adb devices` said so the whole
time by listing no managed AVD.

**So prefer letting a run finish over killing it**, and when you do kill one, clear the count in
the same breath rather than meeting it on the next run.

Two other hangs with the same symptom, so rule them in or out by what they leave behind:

| What you see | What it is |
| --- | --- |
| No managed AVD in `adb devices`, no test output | the leaked lock above |
| A managed AVD is up, tests ran and then stopped mid-suite | the device died — check `adb logcat -b crash`; the emulator's Bluetooth stack aborting has done this here |
| `connectedDebugAndroidTest` fails to install, or hangs immediately | stale ADB bridge in the daemon — needs *both* an emulator restart and `./gradlew --stop` |

Three consequences worth knowing:

- The `aosp-atd` image carries no Play Services, so **a test that touches Maps will not run
  on it** — that device would need a `google` image. Nothing today does.
- **Espresso only works on the managed device.** A guest window has focus only while the
  emulator's own window has focus on the host desktop, so against a hand-started emulator any
  UI test fails with `RootViewWithoutFocusException` the moment you alt-tab away. There is
  nothing to fix in the test when that happens — run it on the managed device.
- `./gradlew testDebugUnitTest` runs **181 JVM tests in about fifteen seconds**, and is worth
  running constantly while working on anything it covers: the pure logic. Refresh policy, scan
  order, marker focus, the long-fetch banner, passcode parsing, the CSV and zip writers,
  coordinate conversion. Anything that needs neither Android nor a device belongs there, not on
  the emulator — it is two orders of magnitude faster to run.

  It is still **not** the suite to report "tests pass" from. Every screen, every repository and
  the whole Python bridge are instrumented, so a green JVM run says nothing about them.

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

- **`perform`'s predicate runs *before* the first attempt, so it must be cheap when the answer
  is "not yet".** It is asked once up front, then twice per retry. A predicate phrased as "is
  the dialog up?" runs `inRoot(isDialog())` against a screen with no dialog, and Espresso's
  root picker retries internally for seconds before admitting there isn't one — so the cheap
  case is the slow one, fifty times over. Seven tests in `UnlinkTheAccountSettingTest` took
  **6m 33s**; asking a repository instead took **19s**. Prefer a fake's call count or a stored
  value. And do not reach for `perform` at all unless the action might tear the screen down —
  a click that opens a dialog cannot, so `Eventually.check` then a plain `perform(click())` is
  both correct and instant.
- **One `ViewAction` per `perform` when the action might finish the flow.**
  `perform(replaceText(code), closeSoftKeyboard())` fails on the *second* action, because the
  first one completed the sign-in and there is no activity left to close a keyboard on.
- **A `GONE` view still matches `withId`.** "Not on screen" is `matches(not(isDisplayed()))`,
  never an expected `NoMatchingViewException`.
- **`isEnabled()` on a popup menu item asks the wrong view.** `withText` finds the `TextView`
  inside the row, and that reports itself enabled whatever the `MenuItem` says — so
  `not(isEnabled())` passes for an item that is fully enabled, and the assertion proves nothing.
  The state is on the containing `ListMenuItemView`:
  `allOf(withClassName(endsWith("ListMenuItemView")), hasDescendant(withText(…)))`. Pair it with
  a positive case using the same matcher, or you cannot tell a correct disabled item from a
  matcher aimed at nothing. `ArrangingTheTagListTest` does both.
- **`replaceText`, not `typeText`, for any field that moves focus as it fills.** The 2FA code
  boxes and the bundle passcode groups both advance when full, and Espresso's per-character
  typing fails the moment the field it started on stops being focused. It is also the case
  people actually hit: a code is pasted far more often than typed.

#### `animationsDisabled` does not disable animations

It reads like it settles the question. It does not: AGP passes `--no-window-animation` to
`am instrument`, which zeroes the *window* and *transition* scales and leaves
`animator_duration_scale` — the one `ViewPropertyAnimator` and `ObjectAnimator` obey — exactly
as the device had it. Animators in the app can and do run at full speed under test.

So **no view's final state may be set in an animation callback.** `StepTransition`, which
animates the sign-in steps, is the worked example: every visibility, offset and alpha it
touches is at its final value before the call returns, and the animation only decorates the
journey there. A screen whose state is readable only once an animation has ended cannot be
asserted on without sleeping, and `Eventually` is a retry loop, not a fix for that.

Two things fall out of it that are easy to get wrong:

- **Animating a step out is not free.** These containers are siblings in a vertical
  `LinearLayout`, so one still fading occupies its height and the arriving one is laid out
  *below* it, then jumps when the old one finally goes. It reads as a snapping bug, not as a
  slow fade. Only the arriving step animates; cross-fading them needs a `FrameLayout`.
- **Do not read the animator scale to decide what a test expects.** It is a global, and by the
  time this suite reaches any one class something earlier has already turned animators off.
  `StepTransitionTest` sets the scale it needs per test, through reflection, and puts it back.

That test also pins down that Settings → Accessibility → **Remove animations** is honoured —
the flow stops moving entirely rather than moving quickly, which is the point of the setting.

### Looking at the UI yourself

A visual change should be looked at, not reasoned about. `SystemColorsLayoutTest` shows the
pattern: inflate a layout, or load a drawable, against a themed context, draw it to a bitmap,
and write it to the directory AGP passes as `additionalTestOutputDir`. It comes back to the
host in `app/build/outputs/managed_device_android_test_additional_output/`, and the managed
device runs headless, so no window and no emulator of your own is needed.

Then compact them before reading — a raw 1080px screenshot is mostly whitespace:

```bash
python .claude/skills/device-screenshots/sheet.py <output-dir> <somewhere-temporary>
```

**A screenshot is not an assertion.** It shows what one configuration looked like once. Assert
the thing that matters as well — a resolved colour, a contrast ratio, a measured height — and
be careful what the assertion is about: the screenshot test kept passing while the history
timeline was invisible, because it loaded the drawables with a theme and the app did not.

Full details, including forcing dark mode without touching the device: `.claude/skills/device-screenshots/`.

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
python scripts/add_strings.py --remove <name>… # delete strings from every locale
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

**Deleting goes through `--remove`, and renaming is `--remove` followed by an ordinary add.**

```bash
python scripts/add_strings.py --remove export_tag
```

The same argument applies: a string missed in one locale is still valid XML and still passes
`--check`, so an orphan sits there indefinitely. `--remove` refuses names that exist nowhere,
so a typo fails loudly. It does **not** update references — if a layout or menu still points
at a removed string, aapt fails the build, which is the intended way to find out.

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
