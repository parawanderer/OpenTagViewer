# Handover: the Android side of the iCloud exporter

> **A task brief, not documentation.** Delete it when the work lands.
>
> Written by whoever built the exporter, on a machine that **cannot build the Android app**. Every
> Python change described here is tested and passing; nothing Java or Gradle has been compiled,
> which is why some of it is described rather than done.

The desktop exporter now reads accessories out of iCloud and writes bundles the app imports. Three
things it emits are ahead of what the app can read, and one thing it shares needs wiring.

---

## 1. Wire the shared package into Chaquopy

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

> [!WARNING]
> **Do not merge that line as written.** `../python` also contains `exporter/` (the desktop wizard,
> which imports tkinter) and `test/` - and a top-level package called `test` on the Python path
> **shadows the standard library's `test` module**. That is a real hazard in a phone app and the
> reason this was left undone rather than committed unverified.
>
> Two ways out, either fine:
>
> - **Move the shared package under a directory of its own** - `python/shared/opentagviewer_export/`
>   - and point `srcDir` at `python/shared`, which then contains exactly one thing. The exporter
>   reaches it by adding `shared` to its path; `python/pyproject.toml` and the PyInstaller spec both
>   need to know.
> - **Filter the source set**, if Chaquopy's `srcDir` honours AGP's `exclude` patterns. Cheaper if
>   it works; unverified here, which is the whole problem.
>
> Whichever, `app/src/test/python/conftest.py` puts `python/` on `sys.path` today and must be
> changed to match, or the tests will exercise a layout the app does not have.

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

## 5. Two smaller things

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
