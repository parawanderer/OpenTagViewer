# Handover: the Android side of the iCloud exporter

> **A task brief, not documentation.** Delete it when the work lands.
>
> Written by whoever built the exporter, on a machine that **cannot build the Android app**. Every
> Python change described here is tested and passing; nothing Java or Gradle has been compiled,
> which is why some of it is described rather than done.

The desktop exporter now reads accessories out of iCloud and writes bundles the app imports. Three
things it emits are ahead of what the app can read, and one thing it shares needs wiring.

---

## 1. Wire the shared package into Chaquopy — **done**

`python/opentagviewer_export/` is the one implementation of the bundle format and of the
accessory-identification heuristic. The app's Python layer already calls into it -
`identifyHardware` and `whereToLookUpHardware` in `app/src/main/python/main.py` - and those work,
with tests in `app/src/test/python/test_hardware_bridge.py` that pass on desktop CPython.

**What is missing is the Chaquopy source directory**, so the import resolves in the APK too:

```kotlin
chaquopy {
    sourceSets {
        getByName("main") {
            srcDir("../python")
        }
    }
}
```

**Filtering won, and the open question is answered: Chaquopy does honour `include` and
`exclude`.** So the package stays where it is, nothing moves, and `pyproject.toml`, the
PyInstaller spec and `conftest.py` are all untouched. What shipped:

```kotlin
srcDir("../python")
include("*.py", "opentagviewer_export/**")
exclude("opentagviewer_export/tests/**")
```

> [!WARNING]
> **The filter applies to the whole source set, not to the `srcDir` it follows.** So
> `include("opentagviewer_export/**")` on its own also filters `app/src/main/python/`, and
> **drops `main.py` out of the APK** — with a green build, passing unit tests, and an app that
> cannot sign in. That is what the first pattern is for. `../python` has no top-level `.py`
> files for it to catch by accident today.

Both hazards — that one, and `test/` shadowing the standard library — are now asserted rather
than reasoned about. `PythonPackagingTest` imports each module on a device: `main` and
`opentagviewer_export` must resolve, `test.test_airtag_decryptor` and `exporter.asyncui` must
not. It also asks the heuristic for an answer it knows the shape of, because `identifyHardware`
swallows every exception by design and a failed import there is otherwise invisible.

The APK now contains exactly `main.pyc` and six `opentagviewer_export` modules.

`main.py`'s two bridge functions never raise: an accessory is not worth losing over a label, so a
failed import there costs the label and logs.

## 2. Let Java use the heuristic instead of its own

`BeaconInformation.isAirTag()` and `isIpad()` are the older, narrower version of what
`opentagviewer_export/hardware.py` now does. They are not wrong; they are a subset:

| | Java today | The shared heuristic |
| --- | --- | --- |
| AirTag | `productId == 21760` | the same test |
| iPad | `model.contains("iPad")` | every Apple model family, with the identifier kept alongside |
| AirPods | — | recognised, **including which unit** - left, right or case |
| Third-party tags | — | named by maker where the Bluetooth SIG registry knows them |
| Anything else | — | the vendor and product ids, plus where to look them up |

**Prefer calling the bridge over porting the table.** The vendor list came out of the SIG's
registry and will need adding to as accessories turn up; two copies of it means two things to
update and one of them will be forgotten. The costs of a wrong answer are asymmetric and worth
remembering: a wrong name is believed, a hex number gets looked up.

Keep `isAirTag()` if something depends on the boolean - it agrees with the shared version by
construction.

## 3. Read a locked bundle — **done**

**The exporter locks bundles by default** (AES-256, WinZip scheme, a generated 12-character code).
`java.util.zip.ZipInputStream` in `AppleZipImporterUtil` cannot decrypt anything at all - not AES,
not the legacy ZipCrypto - so a locked bundle used to fail to import with a message about the zip
rather than about a missing code.

What it took, and what to know if you touch it:

- **zip4j** (`net.lingala.zip4j:zip4j`) replaced `java.util.zip` for reading. A plain bundle goes
  through the same path, so there is one reader rather than one per kind of bundle.
- **The prompt is driven by a `Reason`, not by `isEncrypted()`.** That method never gets a chance:
  for AES, zip4j raises from `getNextEntry()` before handing back a header. It raises
  `WRONG_PASSWORD` for a *missing* password as readily as an incorrect one, so which of the two it
  is comes from whether the app had a code to try - `LOCKED` if not, `WRONG_PASSCODE` if so.
- **The normalisation is duplicated, and that is the risk.** `BundlePasscode.normalise` has to
  agree with `opentagviewer_export/passcode.py` exactly, because a zip password is compared as
  bytes and the symptom of drift is a user being told their correct code is wrong. Note the doc
  summary previously given here - "strip spaces and hyphens" - was incomplete: it also drops
  underscores, tabs and newlines, which is what makes a code pasted out of an email work.
- **The fixture is written by the real exporter** (`scripts/make_locked_bundle_fixture.py`) rather
  than by the test, so `LockedBundleTest` is checking pyzipper-against-zip4j and not zip4j against
  itself. See [CONTRIBUTING](../CONTRIBUTING.md#the-locked-bundle-fixture).

**When the exporter is embedded in the app**, `passcode.py` arrives with it through Chaquopy and
there will be two implementations of one contract in the same APK. At that point the Java copy
should defer to the Python one, or a test should assert the two agree over the same inputs.

Note that *released* app versions still cannot open a locked bundle, so exports for them continue
to need `--no-password` until this ships. The
[CLI guide](./how-to-export-with-the-cli.md) says so prominently.

## 4. Import a self-generated tag

An OpenHaystack-style tag has no `privateKey`, `sharedSecret` or `secondarySharedSecret` - its keys
are a plain list - so the plist layout cannot hold one. The exporter writes them as:

```
CustomAccessories/<identifier>.json
```

carrying FindMy.py's own `custom_rolling_key_accessory` mapping, and `OPENTAGVIEWER.yml` then
declares `version: 0.0.3` **only when the bundle contains one** - so a plist-only export is
byte-identical to what the previous version wrote, and a reader meeting `0.0.3` knows there is
something in it that `0.0.2` could not express.

Today's importer skips the unknown directory silently. What it needs:

- The new directory in `AppleZipImporterUtil.MATCHERS`. Note the identifier is **not** a UUID -
  these come from files other tools wrote - so the existing v4-UUID regex does not apply.
- ~~Storage for a key-list accessory... the fetch path calls `FindMyAccessory.from_json`
  specifically.~~ **Done.** `main.accessoryFromJson` dispatches on FindMy.py's own `type` tag, and
  everything else the fetch path touches - `keys_between`, `get_min_index`, `get_max_index`,
  `update_alignment`, `to_json` - is on both classes already, so nothing downstream learns which
  kind it has.
- ~~**A Room migration** if the schema moves.~~ **It does not move.** `OwnedBeacons.content` is
  already nullable in schema 3, so a key-list tag is a row with `accessory_json` set and `content`
  NULL. No new column, no migration, and rule 1 does not come into it.

  The risk moved rather than vanished: the read paths must tolerate a NULL plist.
  `BeaconRepository` already does, but `BeaconDataParser` reads
  `getOwnedBeaconInfo().content` straight into an XML parse, and a custom tag has no naming record
  either - its display name is the `name` in its own mapping. That is where the work is.

## 5. The app becomes an exporter too — and then most people stop needing an export

Everything above is about the app *importing* better. This is the other direction, and it changes
what the zip is for.

**The app can run this whole pipeline itself.** It already embeds FindMy.py through Chaquopy, and
the iCloud route is pure Python with no desktop in it: sign in, recover the keychain from an escrow
record, fetch, decrypt. The desktop exporter is a UI over `exporter/icloud.py`, and that module
imports nothing the app cannot have.

Two consequences, and the second is the bigger one.

**A third producer.** The app exports bundles of its own, for sharing a tag with somebody else. It
stamps its own `via:` — `OpenTagViewer.android:<versionName>`, beside the desktop's
`OpenTagViewer.wizard` and `OpenTagViewer.cli` — and it writes them through
`opentagviewer_export`, the same shared package, so there is still one implementation of the
format. That is most of why the package exists.

**And an owner does not need an export at all.** Today every user imports a zip, including people
exporting their own tags to their own phone — a round trip through a desktop for data the phone
could fetch directly. Once the app can read the account, that step disappears for anyone who owns
the tags:

| Who | Needs an export today | Needs one after |
| --- | --- | --- |
| **Owner, own phone** | yes — desktop, zip, transfer, import | **no.** Sign in and the tags are there |
| **Owner, sharing with somebody** | yes | yes — the zip *is* the sharing mechanism |
| **Recipient** | yes | yes — they have no account with those tags on it |

So the zip stops being a prerequisite and becomes what it always should have been: **how you give
a tag to another person.** [export-modes.md](./export-modes.md) calls these the *connected* and
*export* roles and says why connected is the default — the device passcode is spent once, so after
it syncing costs nothing and is what makes this a connection rather than an import.

**Do not document any of this until it works.** The wiki currently tells every user to export, and
that is correct until the app can do without one. The paragraph to change is the prerequisite on
the wiki's Home page, and it should change when the feature ships and not before.

Worth knowing while building it: the app must present `0PENTAGVIEWR` and not the exporter's
`0PENTAGXPORT` (§2 above, and rule 11), and a connected app writes to the account in a way the
exporter never does — it may enrol as a peer rather than only recovering. That is the one place
this stops being read-only, and it deserves its own decision rather than arriving as a side effect.

## 7. The connection model, and the screens that express it

Refinement to everything above, settled after the exporter shipped. **The app has two states and
no third.**

| | What it means |
| --- | --- |
| **Not connected** | Signed in to Apple, because fetching location reports needs an Apple ID whatever the tags are. Tags come from files the user imports. Nothing is read from their iCloud account and nothing is written to it. |
| **Connected** | Also reads the tags in the user's own Apple account, which means it has joined the keychain trust circle. |

**There is no export-only mode in the app.** A one-off export that touches nothing is what the
desktop tool is for. In the app, "read my tags from my Apple account" and "join" are the same act
— joining is what stops it quietly ceasing to work — so they are not two questions.

The app can still *write* a bundle for someone else, but that is a thing a connected app can do,
not a mode it runs in.

### First run: two buttons, not four

- **Fetch my tags from my Apple account**
- **Import tags from a file**

The obvious four collapse. **A bundle and a key file are the same button**: the app can tell them
apart by looking, and `opentagviewer_export/keyfiles.py` already parses `.keys`, bare key lists,
`findmy-custom-accessory.json` and `macless-haystack-devices.json`. A user with one AirTag and one
self-generated tag is both cases at once anyway, so neither is a mode to be in.

**Say what was found, not just that it worked.** "Imported 3 AirTags" against "imported 2
self-generated tags" is how somebody who picked the wrong file finds out, and it is the natural
place to say that a bundle is a snapshot rather than a live link.

**Every path signs in.** Copy that implies importing a file avoids needing an Apple account will
produce a fresh crop of issues shaped exactly like #19.

### "Shared with me in Find My" is not a fourth option — it is the first one failing

A tag can only be registered by an iPhone or iPad, so an account with no device on it owns no
tags. Somebody choosing *fetch my tags* and finding nothing is almost always a person whose friend
shared tags with them in Apple's own Find My. That sharing does not carry key material — see
[export-modes.md](./export-modes.md) — so there is nothing to fetch and never will be.

Catch it where it happens rather than asking users to classify themselves up front:

> **No tags owned by this account**
>
> Tags are registered by an iPhone or iPad, and only that account can unlock them. This account
> has no device that can.
>
> **If a friend shared their tags with you in Find My**, that sharing does not include the keys
> this app needs. Ask them to export a bundle for you, and import it here.

Then drop them into the import path. The sold-or-wiped-device case reaches the same screen and
needs no branch of its own — that user still has Apple's own routes to restore keychain access.

**It is reached earlier than this section implies, and one thing that looks like it must not
reach it at all.** Written above as though the app fetches and finds nothing. It does not get
that far: an account with no Apple device has no escrow record either, so the flow stops one
step sooner, at unlocking the keychain — before the user is ever asked for a passcode they do
not have. Better, in fact. The screen is right; the trigger is `recoveryOptions` returning
nothing, not `fetch` returning nothing.

Which makes the distinction the bridge already draws load-bearing:

| `icloud_bridge` reason | What to show |
| --- | --- |
| `nothing_to_recover_from` | the screen above — final, and the import path is the answer |
| `service_unsure` | **not that screen.** Nothing was reported usable *at all*, which reads as a service having a bad day rather than every record on an account going bad at once. Say so, and offer to try again later |

Collapsing the two would tell somebody with a perfectly good account that they own no tags,
permanently, because Apple had a bad afternoon — and send them off to find a friend with a Mac.
FindMy.py draws the same line for the same reason, in `viability_is_trustworthy`.

### Settings: one switch

**Read my tags from my Apple account — on or off.** On joins. There is no separate "stay
connected" question, because users do not distinguish it from being connected at all, and no
third state on the screen.

Turning it off should say what it does not undo: it stops reading the tag list, and it does not
remove the entry from the user's Apple device list. That removal is theirs to do, in Apple's
interface.

### A tag should say where it came from

Once both routes exist, a tag in the list can have arrived three ways, and the device details
screen should say which — it already carries this kind of line for a self-generated tag:

| | What it means to the user |
| --- | --- |
| **From your Apple account** | live; it updates because the app can read the account |
| **Imported** | from a zip; it updates only as far as the keys in that zip reach |
| **Self-generated** | never in an Apple account at all |

Worth having because the three behave differently and nothing else on the screen distinguishes
them. A recipient wondering why their shared tag stopped updating is looking at an *imported*
one, and the answer is on the screen the moment the screen says so.

Not a schema question: `OwnedBeacon` already records enough to tell them apart — a self-generated
tag has no `content`, and an account-read one can be marked when it is written.

### The one prompt that interrupts a connected app

Once joined, key rotation is handled — a member is given the new keys. So the only thing that
breaks a working connection is the user changing something in Apple's own interface: **removing
OpenTagViewer from their device list**, or **resetting iCloud Keychain**. Section 6.7.1 of
[the Stage 3 spec](./findmy-export/03-keychain-trust.md) says the same.

Both are deliberate acts elsewhere, so the copy can be specific:

> **Your Apple account no longer recognises OpenTagViewer.** This usually means it was removed
> from your device list. Enter the passcode of one of your Apple devices to reconnect.

Which is the argument for naming the entry — `0PENTAGVIEWR` — on the connect screen in the first
place. It gets removed because the row looked unfamiliar next to *"If you do not recognise this
device"*.

### Two passcodes, and only one of them is new

The **device passcode** is an existing iPhone PIN or Mac login password, used to recover the
keychain. The passcode the user **chooses** seals this client's own recovery record, and exists
only on the connected path. Neither is the Apple ID password, and saying so on the screen saves a
support thread.

---

## 6. Two smaller things — **mostly done**

**~~The FindMy pin is out of step with itself.~~** Done, and then done again more widely. The app
pins a commit in both `app/build.gradle.kts` and `app/src/test/python/requirements.txt`; the
exporter, which tracked the branch and so drifted every time anybody pushed, now pins the same
commit in `python/pyproject.toml`. `test_main.py::test_the_whole_repository_pins_one_findmy`
asserts all four agree and names the file that does not.

The reason it is one pin and not two: **`opentagviewer_export` is shared code with two runtimes.**
Chaquopy packages it into the APK, the exporter runs the same files from source, and one package
behind two library versions breaks exactly one of its consumers on the first API difference.

**~~The app registers as `0FINDMYPY001`.~~** Done. A new sign-in presents `0PENTAGVIEWR`, beside
the exporter's `0PENTAGXPORT`. **New sessions only** - an account established before this keeps
what it was bound to, because re-identifying an existing session costs that user a sign-in and
leaves a second entry they never asked for. See `app/src/main/python/identity.py`.

**The entry has no name, and it is not going to get one.** Naming the row needs the `postdata`
announce of [Stage 2 §7](./findmy-export/02-mobileme-delegate.md#7-naming-the-registered-device),
which authenticates with the `com.apple.gs.idms.hb` heartbeat token. That token arrives once, in
the same set as the PET, and an account serialised before FindMy.py started keeping it has nothing
to announce with - so for the installed base it does not work, and making it work means those
users signing in again. **Not worth a re-login for a label**, and
[findmy-py-device-name-request.md](./findmy-py-device-name-request.md) is therefore not being
pursued for the app. The serial carries the recognisability instead.

**~~Once name and serial are set, the model is only choosing an icon.~~** Done, with the icon
being the whole point: `iPhone15,2` renders as **"iPhone 14 Pro"** rather than as a bare
"MacBookPro" among the user's real Macs. Both conditions were met -

- **It did not switch on its own.** The serial landed in the same release, so the entry is an
  iPhone *with* `0PENTAGVIEWR` on it rather than an unnamed, unserialled phone among real ones.
- **All five strings moved together**, from the worked set in
  [Stage 1 §2.2](./findmy-export/01-authentication.md): `iPhone15,2`, iOS `17.4`, build `21E219`,
  CFNetwork `1494.0.7`, Darwin `23.4.0`.

**And only for fresh installs.** An install that already has an ADI identity keeps the Mac it was
provisioned as, forever - the two are one identity and Apple binds a session to it. That is
`AdiDeviceIdentity.Hardware`, whose two profiles Python reads across the bridge rather than
copying. [Rule 11](../AGENTS.md) has the longer version, including the trap that cost most of the
time: FindMy.py transforms `X-Apple-I-MD-LU` and `X-Mme-Device-Id` on the way out and the Java ADI
path does not, so passing "the same string" aligns one field and silently leaves the other as two.

> **[observed] Claiming to be a phone does not make it a second factor.** A registered entry
> presenting as an iPhone still reports *"This device cannot be used to receive Apple Account
> verification codes"*. What decides that is omitting `ptkn`, exactly as
> [Stage 1 §13](./findmy-export/01-authentication.md) argued - so the icon change carries no risk
> of turning this app into a second factor for someone's Apple ID.

`AdiDeviceIdentity.CLIENT_INFO` no longer exists; a compile-time constant could only ever be one
thing for everyone, which is precisely what a per-install profile cannot be.

**Build the disclosure text from the account, not from constants.** §7.1 of Stage 2 requires that
the identifiers shown to the user are the ones actually sent - the whole point being that somebody
reading their Apple device list can match it to what the app told them. FindMy.py now exposes
`account.device_name` and `account.serial`, so the screen that announces what will be registered
should read them rather than restating string literals that can drift out of step with what goes
on the wire. A disclosure that is merely *usually* right is worse than none, because it teaches the
user to trust a name that might not be theirs.
