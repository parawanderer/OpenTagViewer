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

## 5. What not to export

[Stage 4 §3.5.1](./04-cloudkit.md) records that the zone contains more than accessories. Of the
nine record types returned, most have no place in an export:

| Type | Export? |
| --- | --- |
| `MasterBeaconRecord`, `BeaconNamingRecord` | **yes** — this is the payload |
| `KeyAlignmentRecord` | **yes** — see §6 |
| `SafeLocation` | **no.** Holds the user's home and work coordinates. |
| `OwnedDeviceKeyRecord` | no — the user's devices' keys, not accessories' |
| `OwnerPeerTrust`, `OwnerSharingCircle`, `SharingCircleSecret` | no — trust and sharing state |
| `LeashRecord` | no |

**`SafeLocation` deserves an explicit decision rather than a default.** It arrives whether wanted
or not, it decrypts with the same keys as everything else, and it contains named geofences around
the user's home. Discard it at the point of decryption. Do not log it, do not persist it, and do
not let it reach an export file because nobody wrote a filter.

## 6. Key alignment

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

## 7. Open questions

1. **Does anything read `cloudKitMetadata`?** §2.2. If nothing does, the key can hold a placeholder
   indefinitely; if something does, this stage is not as thin as it looks.
2. **What is `stableIdentifier` a list of** in the plist, given CloudKit returns a single string? A
   one-element list is the obvious guess and is unconfirmed.
3. **What does `isZeus` mean?** Carried faithfully by both formats and understood by neither.
4. **Is `secureLocationsSharedSecret` needed for anything?** Absent on the account examined, and no
   consumer of it has been identified.

**Unexercised:** this stage has never run, because it needs decrypted records. Its *mapping* is
confirmed against real CloudKit records and the committed macOS fixtures; its output is not.

