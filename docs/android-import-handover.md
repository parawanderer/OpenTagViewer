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

## 3. Read a locked bundle

**The exporter locks bundles by default** (AES-256, WinZip scheme, a generated 12-character code).
`java.util.zip.ZipInputStream` in `AppleZipImporterUtil` cannot decrypt anything at all - not AES,
not the legacy ZipCrypto - so a locked bundle currently fails to import with a message about the
zip rather than about a missing code.

What this needs:

- **zip4j**, or another reader that does AES. `net.lingala.zip4j:zip4j`.
- **A prompt.** `ZipFile.isEncrypted()` answers before anything is read, so the app can ask for the
  code rather than failing.
- **The same normalisation the exporter uses**, or the code a user types will not match the bytes
  the zip was locked with. It is in `opentagviewer_export/passcode.py`: strip spaces and hyphens,
  uppercase, then fold `O` to `0` and `I`/`L` to `1`. Those letters are excluded from the alphabet
  *because* people write them for the digits, so a code read off paper depends on this.

Until then, exports for released app versions need `--no-password`. The
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
- Storage for a key-list accessory. `OwnedBeacon.content` assumes a plist; `accessoryJson` is
  already FindMy.py's own JSON and is the natural home, but the fetch path calls
  `FindMyAccessory.from_json` specifically and a custom tag is a `FixedRollingKeyPairAccessory`.
- **A Room migration** if the schema moves - rule 1, and there is no going back from getting that
  wrong.

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

### Settings: one switch

**Read my tags from my Apple account — on or off.** On joins. There is no separate "stay
connected" question, because users do not distinguish it from being connected at all, and no
third state on the screen.

Turning it off should say what it does not undo: it stops reading the tag list, and it does not
remove the entry from the user's Apple device list. That removal is theirs to do, in Apple's
interface.

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

## 6. Two smaller things

**The FindMy pin is out of step with itself.** `app/build.gradle.kts` installs the
`feat/icloud-keychain-export` branch, while `app/src/test/python/requirements.txt` pins
`FindMy==0.9.8`. Whatever `test_pinned_versions_match_the_app_build` is checking, it is not those
two agreeing - so the bridge tests exercise a different library than the app ships. The exporter
solved the same problem with a lockfile that records the resolved commit; the app still needs
`@<sha>` appended before any build that leaves the machine, which its own comment already says.

**The app registers as `0FINDMYPY001`.** Nothing passes a serial to FindMy.py, so the device the
user sees in their Apple account list is named after the library rather than after this app -
which is what [rule 11](../AGENTS.md) exists to prevent, and not what the docs describe. FindMy.py
now takes `serial=` on the Anisette provider and defaults CloudKit to it, so this is a one-line
fix at the point the provider is built, plus a decision that `0PENTAGVIEWR` is the app's. The
exporter presents `0PENTAGXPORT`, deliberately different: two installs, two entries, each
removable without breaking the other.

**And the entry has no name, which is the field a person reads first.** Nothing calls the
`postdata` announce of
[Stage 2 §7](./findmy-export/02-mobileme-delegate.md#7-naming-the-registered-device), so Apple
names the row after whatever model the client claims - `MacBookPro18,3` shows up as a bare
**"MacBookPro"**, sitting in a list of the user's real hardware with nothing to tell it apart.
FindMy.py cannot do this at all today; the ask is written up in
[findmy-py-device-name-request.md](./findmy-py-device-name-request.md). Target is
**`OpenTagViewer App`**, with the exporter as `OpenTagViewer Exporter`.

**Once name and serial are set, the model is only choosing an icon** - so the app should claim an
iPhone and the desktop tool a Mac, which makes a device list readable at a glance. Two conditions
on that:

- **Do not switch the model on its own.** Today the app sends serial `"0"` and no name, so an
  iPhone claim would produce an entry called "iPhone" with no serial, among the user's real
  iPhones. Strictly worse than the Mac claim it has now. Model, name and serial land together or
  not at all.
- **All five strings move together.** Model, OS version, build, CFNetwork and Darwin describe one
  real release - see [rule 11](../AGENTS.md) and
  [Stage 1 §2.2](./findmy-export/01-authentication.md), which carries a worked set:
  `iPhone15,2`, iOS `17.4`, build `21E219`, CFNetwork `1494.0.7`, Darwin `23.4.0`.

> **[observed] Claiming to be a phone does not make it a second factor.** A registered entry
> presenting as an iPhone still reports *"This device cannot be used to receive Apple Account
> verification codes"*. What decides that is omitting `ptkn`, exactly as
> [Stage 1 §13](./findmy-export/01-authentication.md) argued - so the icon change carries no risk
> of turning this app into a second factor for someone's Apple ID.

`AdiDeviceIdentity.CLIENT_INFO`'s comment used to justify its value as the least remarkable string
Apple sees. That reasoning is gone: an entry built to be recognised is not blending in.

**Build the disclosure text from the account, not from constants.** §7.1 of Stage 2 requires that
the identifiers shown to the user are the ones actually sent - the whole point being that somebody
reading their Apple device list can match it to what the app told them. FindMy.py now exposes
`account.device_name` and `account.serial`, so the screen that announces what will be registered
should read them rather than restating string literals that can drift out of step with what goes
on the wire. A disclosure that is merely *usually* right is worse than none, because it teaches the
user to trust a name that might not be theirs.
