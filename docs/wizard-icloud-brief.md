# Task brief: point the desktop exporter at iCloud

> **This is a task brief, not documentation.** Delete it when the work lands. It exists so a
> fresh session can start without reading the history behind it.

## What you are building

The desktop exporter in [`python/`](../python/) currently reads AirTag data out of **local macOS
files**, which is why it only runs on a Mac and why the project ships a VM bootstrap. A Python
library, **FindMy.py**, can now read the same records out of **iCloud** instead.

**Repoint the exporter at that library.** The output is unchanged — the same zip, imported by the
same Android app. What changes is where the data comes from, and the consequence is that the
exporter stops needing macOS at all.

**And extract the zip writer into a shared package** — `python/opentagviewer_export/` — because
the Android app is about to need exactly the same thing. It runs Python too, through Chaquopy, so
both can call one implementation. This is the larger half of the task and the part with a second
consumer, so it is worth doing first and doing carefully.

**Stage it in three:**

1. **The shared sink.** Pure Python, no UI, no network: it takes accessory data plus a chosen list
   and writes a zip. Test it directly with pytest — no Apple account needed, so this stage is
   fully verifiable by you.
2. **A headless CLI** that logs in, recovers, fetches and calls the sink. Testable in one run by
   the account owner.
3. **The tkinter screens** on top of the CLI's logic. Fiddlier and partly blocked — see *Blocked*.

## What exists today

| File | Role |
| --- | --- |
| `python/main/wizard.py` | the tkinter app: screens, beacon selection, zip writing |
| `python/main/airtag_decryptor.py` | the macOS-local source — keychain key, decrypting local plists |
| `python/test/` | pytest suite |
| `python/requirements.txt`, `python/OpenTagViewer.spec` | deps and the PyInstaller build |

The flow is: get a key from the macOS keychain → decrypt plists under `INPUT_PATH` → build a
`dict[str, BeaconData]` → let the user pick beacons → write a zip.

**Keep:** the selection UI, and the idea of a `BeaconData` map keyed by beacon id.

**Replace:** everything that produces that map — `_retrieve_beacon_data`, `_read_all_plists`,
`_extract_plists`, and the `airtag_decryptor` path behind them.

**Extract and rewrite, do not reuse as-is:** `_create_zip` becomes the shared package. It cannot
move unchanged — it copies plist *files*, deriving each output path from the source path via
`make_output_path(...)`, and under the new route there are no source paths: records arrive from
iCloud as data. Build the layout directly from identifiers instead. Two other things in that
method must not survive the move: `os.system("open …")` at the end, which is macOS-only and is a
UI concern rather than the sink's, and `sourceUser: os.getenv("USER")`, which is `USERNAME` on
Windows and will be `None` as written.

## The shared package

`python/opentagviewer_export/`, consumed by two callers with nothing else in common.

**Why it is shared rather than written twice.** The format has traps — see *The zip format* — and
two implementations means two chances to get each one wrong. The failure is invisible on the
producing side: a malformed zip fails at **import**, in Java, on someone else's phone. One
implementation with one test suite is the only version of this that stays correct.

**It also makes the sink testable without a device.** The Android app's real tests are instrumented
and need an emulator; a Java sink would need one to test. Python does not, and the app's Python
layer is already forbidden from importing Android or Java types, so a shared package slots in as
another Chaquopy source directory rather than needing a bridge.

Rules for it:

- **Pure.** No UI, no network, no Apple ID, no platform assumptions. In and out: data and a path.
- **`via:` is a parameter, never invented inside.** Rule 9 makes `VERSION` in `wizard.py` the
  exporter's identity; the app has its own producer string. One module, two callers, two strings
  passed in. Same for anything else identifying the producer.
- **Its tests live with it** and run on plain CPython. Add them to the test table in
  `CONTRIBUTING.md` — rule 10.
- **Two packagings have to include it**: PyInstaller's `OpenTagViewer.spec`, and the APK via
  Chaquopy's source sets. Neither picks it up on its own.

The Android side of the wiring is **not** part of this task — that machine cannot build the app.
Leave the package importable and documented; someone else connects it.

## The new source

FindMy.py is checked out at **`/Users/sb/git/FindMy.py`** (an unreleased branch — see *Packaging*).
Read its `examples/` first: `fetch_beacons_from_icloud.py` is the flow you need almost end to end,
and `_login.py` shows the login and 2FA handling.

Roughly: authenticate with an Apple ID → accept terms if the account has not → recover the
keychain keys using the **screen-lock passcode of one of the user's Apple devices** → fetch the
accessories → get the fields the zip needs.

**Two passcodes exist in that library and only one applies here.** The device passcode above is a
*read*. There is also a flow that creates a record on the user's account, which asks for a second
passcode the user chooses. **The exporter must not use it.** Exporting is read-only and must write
nothing to anyone's Apple account.

## The zip format

Specified in [findmy-export/06-output.md](./findmy-export/06-output.md) §5.5. Read it; the current
code is the other reference. Traps that have already cost time:

- `KeyAlignmentRecords` is **plural**, the other two directories are singular
- every file is `.plist`, even though macOS names some of them `.record`
- **XML** plists, not binary
- an alignment record's accessory is its **parent directory**, not a field
- the key alignment record is optional; without it an import searches a tag's entire key history

`OPENTAGVIEWER.yml` carries `version`, `exportTimestamp`, `sourceUser`, `via`. See *Decisions*.

## Hard constraints

**Persist no credentials.** The exporter holds nothing today — every run starts from nothing — and
that matters more now, because the credential stops being a local keychain password and becomes
the user's **Apple ID password**, alongside a device passcode. FindMy.py's own default is the
opposite: `to_json()` writes the account, password included, to `account.json` in plaintext, and
every example script does exactly that. **Do not copy that from the examples.** Read, use, drop.

**Stamp `via:` and bump `VERSION`.** `VERSION` in `wizard.py` is the only place the exporter's
version is written, and it reaches every export as `via: OpenTagViewer.app:<version>`. See rule 9
in [AGENTS.md](../AGENTS.md) — releasing is a `VERSION` commit and then a matching tag, enforced by
`scripts/release_version.py`.

**One device identity, legible to the user** — rule 11 in `AGENTS.md`. Logging in registers a
device in the user's Apple account, shown with a *Remove from Account* button next to "If you do
not recognise this device". Send a serial that reads as a label rather than as hardware, and make
every path send the same one. Model, OS version, build, CFNetwork and Darwin strings must all
describe one real release — see [findmy-export/01-authentication.md](./findmy-export/01-authentication.md)
§2.2 and §13.

**Selection is explicit.** Export the beacons the user picked, never "everything" by default.
Sharing one tag with a friend is the common case.

**Cross-platform from the start.** No `open`, no macOS paths, no assumption of a POSIX username.
The whole point is that this runs on Windows and Linux.

## Packaging

`requirements.txt` is four pinned packages today and none of them is FindMy.py — adding it pulls
in `cryptography` and `protobuf`, which is a real size increase for the PyInstaller bundle in
`OpenTagViewer.spec`. Check that the bundle still builds and note the size change.

The library is currently an unreleased branch rather than a PyPI release. Pin it the way the
Android app does — see the Chaquopy `install(...)` line in `app/build.gradle.kts` — and leave a
comment saying it becomes a version pin once released.

## Decisions to raise rather than take

- **`sourceUser`** names a person and travels inside a bundle meant to be shared. Keep, drop, or
  make it optional? It is currently the POSIX username.
- **The exporter's serial.** The app has its own; the exporter is a different client and arguably
  wants a different legible serial so the two are distinguishable in a device list.
- **Whether to keep the macOS-local path.** It works today and costs nothing to keep as a fallback.
  Be accurate about what it is, though: **it is not an offline route.** It reads files macOS
  populated, and macOS populated them by signing into iCloud, joining the keychain trust circle as
  a peer, and syncing the records down. The VM route does the same thing with a Mac that does not
  exist. So it needs a signed-in Mac, and it leaves *more* on the account than the new route does —
  a full device peer and its escrow record, created by Apple's own software rather than by us.
  Its one genuine advantage is that the Apple ID password is typed into macOS rather than into
  this program.

## Testing, and what you cannot claim

`python/test/` is pytest. Type-check with **pyright, not mypy** — the editors here run Pylance, and
mypy's defaults are far more lenient:

```bash
python -m pyright python/main/wizard.py
```

There is a pre-commit hook (`git config core.hooksPath .githooks`) running flake8 and pyright over
staged Python.

**Nothing here can be verified against a real Apple account by you.** The account owner runs it.
Say plainly what was tested and what was not — a wrong "this works" is worse than an honest gap,
because the failure mode lands on someone else's data.

## Blocked / out of scope

- **Terms of service and 2FA in the GUI.** The library handles both; presenting them in tkinter is
  real UI work, and the terms path has not been exercised against an account that needs it. This
  is the main reason to build the CLI first.
- **Do not modify FindMy.py.** It is developed separately. If something is missing there, write
  down what and why rather than patching it.