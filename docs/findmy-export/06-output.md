# Stage 6 — Output

Specification of how decrypted accessory records become something FindMy.py can use.

Read [README.md](./README.md) first.

> **The mapping is confirmed.** A real `MasterBeaconRecord` fetched from CloudKit was compared
> field by field against the plists OpenTagViewer already imports, and they are the same data
> under nearly the same names. Values marked **[observed]** come from that comparison.
>
> This is the shortest stage in the set, and deliberately so: if it turns out to be long,
> something has gone wrong upstream.

---

## 1. Why this stage is nearly nothing

The macOS export route reads a cache directory. **[observed] That cache is a local copy of the
CloudKit records** — the plists even carry a `cloudKitMetadata` field holding CloudKit's own
system fields, which is the giveaway.

So there is no format to design. The records fetched in [Stage 4](./04-cloudkit.md) and decrypted
in [Stage 5](./05-pcs-decryption.md) *are* the export, modulo naming.

## 2. The accessory mapping

`MasterBeaconRecord` against the `OwnedBeacons/<UUID>.plist` layout:

| CloudKit field | Plist key | Note |
| --- | --- | --- |
| `privateKey` | `privateKey` | **wrapped** — see §2.1 |
| `publicKey` | `publicKey` | wrapped |
| `sharedSecret` | `sharedSecret` | wrapped |
| `sharedSecret2` | `secondarySharedSecret` | **the one rename**, and also wrapped |
| `productId` | `productId` | |
| `vendorId` | `vendorId` | |
| `model` | `model` | |
| `isZeus` | `isZeus` | integer in CloudKit, **boolean** in the plist |
| `systemVersion` | `systemVersion` | |
| `pairingDate` | `pairingDate` | |
| `batteryLevel` | `batteryLevel` | |
| `stableIdentifier` | `stableIdentifier` | string in CloudKit, **a list** in the plist; see §2.4 |
| — | `identifier` | the record's own identifier, from `recordIdentifier` |
| — | `cloudKitMetadata` | CloudKit system fields; see §2.2 |
| `secureLocationsSharedSecret` | — | an iDevice's secondary secret; see §2.3 |
| `groupIdentifier` | — | no home in this format; see §4 |

### 2.1 Key material is wrapped

Every key and secret in the plist is nested two levels deep rather than stored as bare bytes:

```
privateKey → { "key": { "data": <bytes> } }
```

CloudKit returns the bytes directly, so writing the plist means wrapping them and reading it
means unwrapping. It carries no information — it is a serialisation artefact of the macOS
framework that produced the cache — but omitting it produces a file that fails to import for a
reason nothing will explain.

### 2.2 `cloudKitMetadata`

The plists carry CloudKit's own record metadata as an opaque blob. **[observed] What OpenTagViewer
reads out of it is three fields** — when the accessory record was created, when it was last
modified, and which device registered the accessory.

That is worth exporting: it is real information about the accessory, and nothing else in the format
carries it.

It is also less than the name suggests. CloudKit's system fields can identify a record's creator
and last modifier as *user* records; these do not, so this blob is not a route by which an account
identity reaches a file. The fixtures in OpenTagViewer's test resources contain a 3-byte stub, so a
placeholder is tolerated where the real thing is unavailable — but prefer the real thing.

> **The device is named, so a person may be.** Devices are commonly called *someone's* laptop, and
> that is the one part of this blob worth a thought before it goes to another person in §5.3's
> case. It is a naming convention rather than an account identifier, and it is the owner's call.

### 2.3 `secureLocationsSharedSecret` is the iDevice's secondary secret

The two are alternatives, not unrelated fields. **An accessory carries `secondarySharedSecret`; an
iPhone, iPad or Mac carries `secureLocationsSharedSecret`**, and they occupy the same position in
key derivation — FindMy.py reads whichever is present as its secondary secret.

That it was **[observed] absent** on the account examined is therefore expected: that account's
records were accessories. A client that exports the owner's *devices* as well as their tags needs
it, and one that only reads `secondarySharedSecret` silently exports nothing usable for them.

### 2.4 `stableIdentifier` holds the hardware serial

The list's **first entry** is a structured string, and the serial is the part after the last `~#`:

| Kind | Shape |
| --- | --- |
| AirTag | `2006~#<hardware-id>~#<serial>` |
| Third-party accessory | `a:/<uuid>~#<serial>` |
| AirPods | `a:/<uuid>~#¶<model>§<hardware-id>§<serial as hex ASCII>§<position>` |

For the AirPods form the tail begins `¶` (U+00B6), sections are separated by `§` (U+00A7), and the
third section is the serial **hex-encoded rather than plain** — decode it to ASCII. The fourth is
which unit: `0` and `1` are the left and right buds, `2` is the case.

> **This is why `groupIdentifier` matters** and why §4 treats it as the field with nowhere to go: a
> set of AirPods is three of these sharing one group.

## 3. The naming mapping

`BeaconNamingRecord` maps **exactly** — same field names, same camelCase:

| CloudKit field | Plist key |
| --- | --- |
| `name` | `name` |
| `associatedBeacon` | `associatedBeacon` |
| `roleId` | `roleId` |
| `emoji` | `emoji` — **[observed] absent** on the account examined |

Plus `identifier` and `cloudKitMetadata` as above.

The directory layout OpenTagViewer imports mirrors the association:

```
OwnedBeacons/<beacon-UUID>.plist
BeaconNamingRecord/<beacon-UUID>/<naming-record-UUID>.plist
```

> **[observed] These are not one-to-one.** Six accessories, five naming records and four key
> alignment records were returned for one account. Join on `associatedBeacon` and tolerate
> absence — an accessory with no naming record is normal, not an error, and must still be
> exported.

## 4. Prefer FindMy.py's own format

The plist layout above exists because it is what a Mac happened to write. It predates fields the
protocol now carries, and `groupIdentifier` is the clearest case: it identifies accessories that
belong to a set, such as the two halves of a pair of AirPods, and there is **nowhere to put it**.

FindMy.py gained support for exactly that in 0.10.0, along with serial-number extraction and
group naming. Its native JSON has somewhere for these to live.

### 4.1 Neither format contains the other

**The JSON is not a re-encoding of the plists — it is a lossy derived view.** It holds what key
derivation consumes, not what Apple's record held:

| JSON key | Derived from |
| --- | --- |
| `master_key` | `privateKey`, **its last 28 bytes only** |
| `skn` | `sharedSecret` |
| `sks` | `secondarySharedSecret` — or **`secureLocationsSharedSecret`**, see §2.3 |
| `paired_at` | `pairingDate` |
| `model`, `identifier`, `group_identifier` | the same fields |
| `serial_number` | **parsed out of** `stableIdentifier`, see §2.4 |
| `name` | the naming record |
| `alignment_date`, `alignment_index` | the alignment record, flattened to two scalars |

**Only the plists carry:** `publicKey` (which nothing reads), `batteryLevel`, `isZeus`, `productId`,
`vendorId`, `systemVersion`, `roleId`, `emoji`, `cloudKitMetadata`, and `stableIdentifier` itself
once its serial has been taken.

**Only the JSON carries:** `group_identifier` and `serial_number` — and the second of those is
recoverable from the first format, since it is derived rather than fetched.

> **So "prefer FindMy.py's format" is about where new fields have somewhere to live, not about one
> format superseding the other.** Emitting only the plists loses `groupIdentifier`. Emitting only
> the JSON loses nine fields to gain one, including everything descriptive: the emoji a user chose,
> the battery level, and every hardware identifier.
>
> **Emit both.** They are cheap, they come from one decryption, and a consumer of either is not
> served by the other.

### 4.2 What that means for a live view

The dropped fields are exactly the ones that make a connected client better than an import.
`batteryLevel` changes, `emoji` and the naming record change when a user renames a tag, and
`KeyAlignmentRecord` moves every time Apple observes the accessory.

A client re-syncing an account has all of them and should keep them. A bundle built only from the
JSON has thrown them away before the recipient ever sees one.

## 5. Two sinks, one pipeline

There are two things a caller wants from this, and they share everything expensive — the passcode,
escrow recovery, the view keys, the PCS key, the fetch and the decryption. Both end at the same
list of accessories. **So this is one pipeline with two sinks, not two implementations.**

| | **Connected** | **Export** |
| --- | --- | --- |
| What it is | a live account, re-synced | a bundle handed to someone else |
| Notices new accessories | yes | **no — a snapshot** |
| Key alignment | refreshed each sync | **ages**, see §7 |
| Revocable | yes — sign out, change the password | **no** |
| Needs the owner's account afterwards | yes | **no** |

**Connected is the default**, because [Stage 3 §6.7.1](./03-keychain-trust.md) establishes the
passcode is spent once: after that, syncing costs nothing extra and is what makes this a connection
rather than an import. Export is a deliberate act taken from that state, not a mode to be in.

### 5.1 What export is actually for

**An Apple account is free; an Apple device is not.** That is the barrier this removes, and being
exact about it matters because it is easy to overstate:

- The recipient **still needs an Apple account** — locating an accessory means querying the Find My
  network, and that is authenticated.
- The recipient **does not need an Apple device**, or a Mac, or the owner's account.
- The account they use **need not be the owner's**, because a fetch is keyed on hashed
  advertisement keys rather than on ownership.

So this is "share a tag with a friend who has no Apple hardware", which is the same shape as
OpenTagViewer's existing macOS export and the reason that route exists at all — with the Mac taken
out of it.

### 5.2 Export cannot be undone

The last row of the table is the one to design around. **Exported accessory keys are revocable only
by unpairing the accessory** — the owner cannot withdraw them, and the recipient cannot be made to
stop. Anyone holding them can locate that accessory indefinitely, from any Apple account.

> Apple's own item sharing is revocable. This is not that, and should not be presented as though it
> were. Handing over key material is a different act from granting access, and the difference only
> becomes visible later, when someone wants it back.

Three consequences for the interface:

- **Export takes an explicit list of accessories.** Not a default of everything. Sharing one tag
  with a friend and handing over a household's entire set are different acts, and a default that
  makes them the same keystroke is the wrong default.
- **The export sink must have nowhere to put account material.** Not "it happens not to write the
  session" — a type that structurally cannot carry a token, a `dsid`, an `adsid` or an Apple ID.
- **Say what it means at the point of export**, once, plainly: this cannot be taken back.

### 5.3 What must not travel

[§5](#5-two-sinks-one-pipeline) above is about intent; this is about leakage. The pipeline decrypts
more than it exports, and an export destined for another person is where that becomes a problem
rather than an untidiness.

- **`SafeLocation` must be discarded at decryption**, per §6 — before anything is serialised, not
  filtered out afterwards.
- **Nothing identifying the owner's account.** Neither output format has anywhere to put a token, a
  `dsid`, an `adsid` or an Apple ID, and the accessory records do not carry them either. **That is
  what makes §5.2's requirement hold** — not a filter that has to be right, but a format with no
  field to be wrong about. Adding a provenance field that names the owner is the way to break it.
- **`cloudKitMetadata` is fine to export.** §2.2 records what it actually contains, which is three
  facts about the accessory and no user identity. The device name inside it is the only part worth
  a moment's thought.

### 5.4 What an accessory *is* decides its format

The list a user picks from is **not homogeneous**, and export is where that stops being an internal
detail. Two kinds of thing can sit in it:

| Kind | Where its keys came from | Formats it can be written in |
| --- | --- | --- |
| Apple-paired — an AirTag, or a Find My-certified third-party accessory | this pipeline, out of `BeaconStore` | the plists **and** FindMy.py's `accessory` JSON |
| Self-generated — an OpenHaystack-style tag | the user made the keypair; **it was never in any Apple account** | FindMy.py's `custom_rolling_key_accessory` JSON **only** |

**The plist layout cannot represent a self-generated tag at all.** It is built around
`privateKey`, `sharedSecret` and `secondarySharedSecret` — the inputs to Apple's rolling-key
derivation. A tag whose keys are a plain list has none of them, and no field to put them in.

So **the format is chosen per accessory, not per bundle**. A bundle holding one of each is normal
and must remain readable.

> **The failure mode to design against is silence.** A writer that only knows the plist layout,
> handed a mixed selection, produces a bundle missing exactly the tags it could not represent — no
> error, a plausible-looking zip, and a recipient who finds out when a tag never appears. If a
> selected accessory cannot be written in a chosen format, **say so at export time**, do not skip
> it.

Two smaller consequences:

- **A self-generated tag's name is local.** There is no `BeaconNamingRecord` for it, because there
  is no CloudKit record at all — so its name comes from the app's own state and travels in the
  JSON's `name` field or not at all.
- **Nor is there a `KeyAlignmentRecord`, and none is needed.** Its keys are a fixed list rather than
  a rolling derivation, so [rule 6](../../AGENTS.md)'s index search does not arise. The ageing
  described in §5 applies only to the paired kind.

> **An OpenHaystack tag missing from an export is not a bug.** It was never in the account, so
> Stages 3 to 5 have nothing to find for it — it reaches the list by a different route entirely. A
> null result here is the correct one, and it is not guessable from the absence.

### 5.5 A bundle must say when it was made

An export that cannot say how old it is produces the §8 problem months later with nobody able to
explain it. **Stamp the format, the time and the producer**, the way the macOS exporter's `via:`
line does — that line is how anyone looking at a zip afterwards works out what built it, and an
export made this way needs the same.

That is a statement about the bundle, not about the person: a version and a timestamp, not an
account.

> **There are now two producers, and they must not claim to be the same one.** `via:` currently
> reads `OpenTagViewer.app:<version>`, and [rule 9](../../AGENTS.md) exists so that a zip can be
> traced to the macOS exporter that built it. An export made in the app is a **different producer
> with a different version number**, so it needs its own identifier — otherwise the field that was
> added to answer "what built this" starts answering it wrongly, which is worse than not having it.
>
> The same rule then applies to the app's own identifier: whatever it stamps must be the version
> that shipped, and nothing may patch it at build time.

## 6. What not to export

[Stage 4 §3.5.1](./04-cloudkit.md) records that the zone contains more than accessories. Of the
nine record types returned, most have no place in an export:

| Type | Export? |
| --- | --- |
| `MasterBeaconRecord`, `BeaconNamingRecord` | **yes** — this is the payload |
| `KeyAlignmentRecord` | **yes** — see §7 |
| `SafeLocation` | **no.** Holds the user's home and work coordinates. |
| `OwnedDeviceKeyRecord` | no — the user's devices' keys, not accessories' |
| `OwnerPeerTrust`, `OwnerSharingCircle`, `SharingCircleSecret` | no — trust and sharing state |
| `LeashRecord` | no |

**`SafeLocation` deserves an explicit decision rather than a default.** It arrives whether wanted
or not, it decrypts with the same keys as everything else, and it contains named geofences around
the user's home. Discard it at the point of decryption. Do not log it, do not persist it, and do
not let it reach an export file because nobody wrote a filter.

## 7. Key alignment

`KeyAlignmentRecord` carries `beaconIdentifier`, `lastIndexObserved` and
`lastIndexObservationDate`.

This is not incidental. OpenTagViewer's rule 6 explains why: without an alignment record, a
freshly imported accessory starts its key search at index 0 from its pairing date, which for an
eighteen-month-old tag means deriving on the order of fifty thousand keys and issuing hundreds of
requests against Apple. That is an account-flagging risk, not merely slow.

**So export the alignment record whenever one exists.** The format OpenTagViewer imports has
carried it since export format `0.0.2`, and the importer falls back to a probe when it is absent
— but the fallback is the expensive path, and this route can avoid it.

**[observed] Not every accessory has one** — four records for six accessories. Absence is normal
and must not be treated as an error.

## 8. Open questions

1. **What does `isZeus` mean?** Carried by the plist and understood by nobody. The JSON drops it,
   which is a decision rather than an answer.
2. **Why is `master_key` the last 28 bytes of `privateKey`?** The truncation is what FindMy.py
   does and it works, so the leading bytes are structure rather than key — but what structure is
   unestablished.
3. **What is `roleId`?** `999` on the record examined. Carried by the plist, dropped by the JSON.

**Unexercised:** this stage has never run, because it needs decrypted records. Its *mapping* is
confirmed against real CloudKit records and the committed macOS fixtures; its output is not.

