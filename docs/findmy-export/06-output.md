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
| `stableIdentifier` | `stableIdentifier` | string in CloudKit, **a list** in the plist |
| — | `identifier` | the record's own identifier, from `recordIdentifier` |
| — | `cloudKitMetadata` | CloudKit system fields; see §2.2 |
| `secureLocationsSharedSecret` | — | **[observed] absent** on the account examined |
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

The plists carry CloudKit's own record metadata as an opaque blob. **Whether anything reads it is
unestablished**; the fixtures in OpenTagViewer's test resources contain a 3-byte stub, which
suggests it is at least tolerated as a placeholder. Prefer emitting a placeholder over omitting
the key, and do not attempt to synthesise real CloudKit metadata.

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

**So emit FindMy.py's format as the primary output**, and treat the plist layout as a
compatibility path for existing tooling rather than the target. A generator that writes only the
plist form is discarding data it successfully decrypted.

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
- **`cloudKitMetadata` must not be passed through.** CloudKit's system fields identify the record's
  creator and last modifier, so real metadata carries the **owner's** identity into a file given to
  someone else. §2.2 already prefers a placeholder for a different reason; this is the stronger one.
- **Nothing identifying the owner's account.** The accessory records themselves do not carry it —
  keep it that way rather than adding a provenance field that does.

### 5.4 A bundle must say when it was made

An export that cannot say how old it is produces the §7 problem months later with nobody able to
explain it. **Stamp the format, the time and the producer**, the way the macOS exporter's `via:`
line does — that line is how anyone looking at a zip afterwards works out what built it, and an
export made this way needs the same.

That is a statement about the bundle, not about the person: a version and a timestamp, not an
account.

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

1. **Does anything read `cloudKitMetadata`?** §2.2. If nothing does, the key can hold a placeholder
   indefinitely; if something does, this stage is not as thin as it looks.
2. **What is `stableIdentifier` a list of** in the plist, given CloudKit returns a single string? A
   one-element list is the obvious guess and is unconfirmed.
3. **What does `isZeus` mean?** Carried faithfully by both formats and understood by neither.
4. **Is `secureLocationsSharedSecret` needed for anything?** Absent on the account examined, and no
   consumer of it has been identified.

**Unexercised:** this stage has never run, because it needs decrypted records. Its *mapping* is
confirmed against real CloudKit records and the committed macOS fixtures; its output is not.

