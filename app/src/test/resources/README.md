# Test resources

## `19032025/`

A redacted copy of a real OpenTagViewer export (`OPENTAGVIEWER.yml` reports
`version: 0.0.1`, `via: OpenTagViewer.app:1.0.0`, exported 2025-03-20). The
directory layout mirrors a genuine export so tests can exercise the same paths
the importer does:

```
19032025/
  OPENTAGVIEWER.yml
  OwnedBeacons/<beacon-uuid>.plist
  BeaconNamingRecord/<beacon-uuid>/<record-uuid>.plist
```

Keeping a real export's shape is the point. A hand-written fixture only contains
the fields we already knew to write, so it cannot catch a parse regression caused
by a field Apple writes that we did not account for.

### What was redacted

- every key blob (`privateKey`, `sharedSecret`, `secondarySharedSecret`) was
  replaced with characters from the base64 alphabet, preserving the original
  lengths — `FindMyAccessory.from_plist` only reads byte lengths, so the fixture
  parses identically while carrying no real key material
- UUIDs were replaced
- `cloudKitMetadata` was stubbed rather than redacted. It is an NSKeyedArchiver
  blob whose `ModifiedByDevice` field is a human device name, so it is not
  something to publish. Consequence: `decodeBeaconNamingRecordCloudKitMetadata`
  returns null for this fixture, and the record ctime/mtime extraction path is
  not covered.

`pairingDate` is genuine, which matters: with no key alignment record in the
export, FindMy.py falls back to `alignment_date = paired_at, alignment_index = 0`,
so tag age alone determines how wide the first key search is. This fixture is
~526 days old (as of time of writing at 8 august 2026), which works out at roughly 
50,000 key indices — about 175 requests to Apple at the 290-keys-per-request limit. 
That is what `_narrowAlignmentIfNeeded` in `main.py` exists to avoid.

### Nothing here is sensitive

These files are committed to a public repo. The filler bytes are not derived from
the originals; only lengths carried over.

## `beacons/`

Reserved for additional fixtures of differing tag ages, for testing how the
alignment probe behaves across the range. See `scripts/make_test_beacon_plist.py`
to generate synthetic ones.