# Gaps found while implementing

Written by the implementation side of the clean-room split, addressed to the specification.

> **Answered 2026-08-13.** Every gap below has been resolved in the specification documents, by
> the specification side reading the references — which is that side's job and is why the split
> exists. Nothing was told to the implementer directly; the amendments are in the stage documents
> and should be read from there.
>
> **Read Stage 5 §6 first.** Answering B2 turned up a defect in the specification serious enough
> that it would have failed every decryption: the AAD was documented as the header alone, and it
> is the header **concatenated with a per-field context string**. That was the specification's
> error, not the implementation's, and it is exactly the class of thing this document existed to
> surface.

## Disposition

| Gap | Outcome |
| --- | --- |
| **A1** Cuttlefish invoke message | **Closed.** Stage 3 §2.2 and §2.3 now specify the invoke envelope and `fetchViableBottles` with field numbers. |
| **A2** joining after recovery | **Closed.** Stage 3 §6.7 specifies the sequence and the cryptography, §6.9 the messages. See [E1](#e1-closed-by-69) below for what was built from them. |
| **B1** the KDF's fixed input | **Closed.** Stage 5 §5. The default reading — `be32(i) ‖ label ‖ 0x00 ‖ context ‖ be32(bits)`, context empty, counter from 1 — was correct. |
| **B2** RFC 6637 parameters | **Closed.** Stage 5 §4 step 2, in full: MPI ciphertext layout, the KDF input, and the framed plaintext. **The ciphertext-layout assumption was wrong** — it is a 2-byte big-endian *bit* count then a 32-byte compact point, not an X9.62 point with a self-describing length. |
| **B3** a decrypted field's form | **Partly closed.** The AAD correction above is the load-bearing part. Date and integer encodings remain unstated; the runtime search is still the right approach. |
| **B4** which bits the EC scalar keeps | **Closed.** Stage 5 §5: the **low** bits. It is a bit mask, not `bits2int`. |
| **B5** what the HMAC covers | **Closed.** Stage 5 §4 step 5: DER of `keyset` ‖ raw `meta` ‖ DER of `signatureData`. |
| **C** assumed field numbers | **Closed.** Stage 4 §3.2.2, §3.2.3, §3.5, §3.6. Every assumption listed was correct. The three name-wrappers are now called out explicitly, since that was the one that throws rather than degrades. |
| **C1** `recordRetrieveRequest` | **Closed.** The bold was wrong; it is not required. Stage 4 §3.2 now says so. |
| **D1** `dsid` from Stage 2 | **Accepted.** Stage 2 §4 and Stage 4 §2.1 now prefer it, for the reason given. |
| **D2** the PET is spent by Stage 2 | **Accepted.** Stage 3 §3 now states it as an ordering constraint on the whole flow. |
| **D3** the v2 application tag | **Closed.** It is explicit, like the others. Stage 5 §3.2. |
| **D4** UDID from the device UUID | **Accepted.** Stage 1 §2.2 now permits it. |
| **D5** the client need not be an iPhone | **Accepted.** Stage 1 §2.2 now frames the iPhone as a worked example and says an existing identity should be kept. |

**On the TLS note.** Agreed, and it is not merely a Stage 3 problem — a library-wide `ssl=False`
undermines the Apple Root CA pinning of Stage 1 §2.3 as well. Fixing it upstream is the right
move; until then no stage of this should be considered to have the transport security its
documents describe.

---


Per the [README](./README.md): *"If something here is ambiguous, incomplete or wrong, fix this
document — raise the gap, get the specification amended, and implement from the amendment."*
This is that raise. Nothing here was resolved by reading `rustpush`, `apple-private-apis` or
`export-findmy`; the questions below are exactly the ones that reading them would have answered,
and they are open because it was not done.

**What was built:** Stages 4, 5 and 6 in [FindMy.py](https://github.com/malmeloo/FindMy.py), plus
the read-only half of Stage 3.

> **Stage 4 §3 is now verified against a live account, on 2026-08-13**, by an implementation
> written from this document alone. Container opened, both zones listed, **35 records fetched
> across the same nine types §3.5.1 records, in the same counts.** Most of §C below is therefore
> answered; what remains open is marked. Stage 5 has still never touched a real record.

Gaps are graded by what they cost:

| Grade | Meaning |
| --- | --- |
| **A** | Blocks the stage. Cannot be implemented from the document as written. |
| **B** | Implemented by searching a small space of readings at runtime. Works, but the answer should be written down. |
| **C** | Assumed. A wrong assumption fails loudly and cheaply. |
| **D** | A correction or clarification, not a gap. |

---

## A — blocking

### A1. Stage 3 §2.1: the Cuttlefish function-invoke message is not specified

§2.1 says Cuttlefish is called "with the function-invoke operation — field `1101` of
`RequestOperation` — naming the target `Cuttlefish`, the method, and a protobuf body", and §5 step
2 requires `fetchViableBottles`.

**The message shape is not given.** Field 1101's request message, its field numbers, how the
target and method names are carried, and the shape of `fetchViableBottles`'s own request and
response are all absent. Stage 4 §3.2 tabulates every other operation's message in this much
detail; 1101 is the one that only gets a number.

**Consequence:** Stage 3 §5 step 2 and step 3 are unimplementable, so the read-only listing cannot
tell viable bottles from stale ones. Only step 1 — the escrow proxy's own metadata — was built. It
also blocks §6.7 entirely, and with it every route to the `Manatee` keys that Stage 5 needs.

**What would close it:** the same treatment §3.2 gives operations 201 and 213 — the request and
response message layouts with field numbers, for the invoke envelope and for `fetchViableBottles`.

### A2. Stage 3 §6.7: joining the circle after recovery is named, not specified

The document says so itself, and open question 3 records it. Worth restating here only because it
is what makes A1 load-bearing rather than merely annoying: even given A1, recovery ends with
"the escrowed keychain material" in a format nothing describes, and no route from there to an item
in the `Manatee` view whose `acct` matches a key in a record's protection structure.

**Consequence:** the output contract Stage 3 sets itself — "we can enumerate `Manatee` and find an
item whose `acct` matches" — cannot be reached. Stage 5 is implemented against keys supplied by the
caller instead.

---

## B — ambiguous, resolved by searching at runtime

Each of these has a small space of plausible readings and a cheap way to tell which is right,
because Stage 5 §4 step 5 and §6 provide integrity checks. The implementation tries the candidates
and reports which one worked, at INFO level. **The first successful decryption of a real record
answers all three at once**, and the answers belong in the document.

### B1. Stage 5 §5: the KDF's fixed input is not specified [closed]

> **Closed by amendment.** §5 now gives the PRF input exactly --
> `be32(i) ‖ label ‖ 0x00 ‖ context ‖ be32(L)`, context empty, counter from 1 -- so this is
> no longer a search and the implementation no longer contains one. What follows is the
> reasoning as it stood.

> "a **counter-mode KDF with HMAC** (the NIST SP 800-108 shape), with a fixed label and an output
> the same length as the input key"

The construction is named and the labels are exact, but not *how* counter, label and length are
laid out in the PRF's input. SP 800-108 counter mode is
`PRF(K, [i] || Label || 0x00 || Context || [L])`, and the document supplies a label but no context
and no encoding widths. Five readings are tried:

| Name | Fixed input |
| --- | --- |
| `sp800_108` (default) | `be32(counter) ‖ label ‖ 0x00 ‖ be32(bits)` |
| `sp800_108_no_separator` | `be32(counter) ‖ label ‖ be32(bits)` |
| `counter_label` | `be32(counter) ‖ label` |
| `label_counter` | `label ‖ be32(counter)` |
| `label_only` | `label` |

This one matters most: it is used four times over, including for the encryption key, so getting it
wrong fails everything downstream with no clue as to why.

### B2. Stage 5 §4 step 2: the RFC 6637 parameters are not specified [closed]

> **Closed by amendment.** §4 step 2 now gives the whole KEK construction: the
> length-prefixed P-256 OID, ECDH, SHA-256 as KDF hash, AES-128 as the wrap cipher, the
> twenty-character sender string, and the literal word `fingerprint` zero-padded to twenty
> bytes. One construction, no search. What follows is the reasoning as it stood.

> "The wrapping is **RFC 6637** … with the fixed parameter string `fingerprint`."

RFC 6637's `Param` is a structure, not a string: curve OID, algorithm id, KDF hash id, KEK
algorithm id, the constant `"Anonymous Sender    "`, and a 20-byte recipient fingerprint. Two
readings of the sentence are possible — that `fingerprint` fills the fingerprint slot of an
otherwise ordinary `Param`, or that `Param` is literally the eleven bytes `fingerprint` — and
neither the hash nor the KEK algorithm is stated. Six combinations are tried; AES key unwrap's own
integrity check says which fits.

Also unstated, and **not** guessed: the ciphertext's layout. It is read as an X9.62 point followed
by the wrapped key, with the point's length taken from its own leading byte, which is
self-describing and so needs no assumption. Whether the unwrapped material is the 16-byte master
key or carries a leading algorithm byte, as OpenPGP's use of this construction does, is tried both
ways.

### B3. Stage 5 §8 Q1: a decrypted field's form

Open question 1 asks whether a decrypted field is raw bytes or an `EncryptedValue` message, and
says the answer "decides whether decryption yields a value or another parse step". Both are
accepted: the plain reading is tried first, and the wrapped one when the plain reading does not
look like the declared type. A string is the awkward case — a small protobuf message holding a
short string decodes as valid UTF-8, its tag and length bytes arriving as control characters — so
"it decoded" is not sufficient and "it decoded and reads like text" is used instead.

Two further encodings are unstated and had to be chosen:

- **Dates.** Read as seconds from 2001-01-01 rather than from the Unix epoch, as an IEEE double,
  big- or little-endian. Told apart by plausibility: a pairing date after 2019 and not in the
  future. A wrong reading is rejected rather than exported.
- **Integers.** Read big-endian.

### B4. Stage 5 §5: which bits the master EC scalar keeps

> `n = mask_to_bit_length(out, order_bits(P-256))`

Masking 1024 bits down to 256 can keep the low bits or the high ones, and "mask" suggests the
former while the conventional `bits2int` keeps the latter. Both satisfy the conditional
subtraction that follows, so the arithmetic does not disambiguate it.

**This one is cheap**, because the master EC key is used only to verify a protection structure's
signature (§4 step 4) and not to decrypt anything. It is exposed as a parameter and signature
verification is off by default. Worth resolving before anyone turns verification on.

### B5. Stage 5 §4 step 5: what the structure's HMAC covers

> "the structure's **HMAC** must verify under the derived key"

The HMAC key's derivation is given (`hmackey-of-masterkey`) but not what it is computed over — the
DER of the whole structure, of the keyset, of the structure with the HMAC field itself absent, or
something else. Compare §3.2's `ShareProtectionKeySet`, where the document is careful to say the
hash is "computed with `hash` itself absent"; the equivalent sentence is missing here.

**Consequence:** only one of §4 step 5's two checks is implemented. The truncated key id is
sufficient to select a key, so nothing is blocked, but one of the two independent confirmations is
absent.

---

## C — assumed field numbers, now mostly confirmed

Stage 4 §3 gives field numbers for most messages and omits them for these. Each was assumed; a
wrong assumption means protobuf silently routes the data to the unknown-field set and the caller
sees an empty result rather than an error.

**The live run of 2026-08-13 confirmed every assumption it exercised**, so these are no longer
guesses and the document can simply state them:

| Message | Assumed, and **[confirmed]** | Evidence |
| --- | --- | --- |
| `RetrieveChangesResponse` | 1 = repeated `recordChange`, 2 = `syncContinuationToken` | 35 records decoded, and paging advanced |
| `Zone` | 1 = `zoneIdentifier`, 2 = `etag` | both zones named themselves correctly |
| the name wrapper | `name` at 1 | record types decoded without the fallback path firing |
| `ZoneSummary` | 1 = `targetZone`, 4 = `deviceCount` | `BeaconStore` reported 11 devices, `_defaultZone` none |

Still assumed, because nothing exercised them:

| Message | Assumed | Where the document stops |
| --- | --- | --- |
| `RetrieveZoneChangesResponse` | 1 = repeated `changedZone`, 2 = token, 3 = status | §3.5 names the members only |
| `ChangedZone` | 1 = `identifier`, 2 = `changeType`, 3 = `deleteType` | as above |
| `ExtensionError` | 1, 2, 3 | §3.2.2 names the three members |
| `Header.deviceLibraryVersion` | a string | §3.3 gives the value `1970`, which could be either |
| `ResponseOperation.operationCost` | int64 | §3.2.2 names it without a type |
| `RetrieveChangesRequest.requestedFields` | repeated string | §3.5 names it without a type |

**One was riskier than the rest, and the risk did not materialise.** §3.6 describes a record's
`type` and a field's `identifier` as "a message wrapping a `name` string", while §3.5 describes
`RecordChange.recordType` as "the record's type name" — which reads like a bare string. A bare
string and a wrapper message are both length-delimited on the wire, so declaring the wrong one does
not degrade, it throws and takes the whole response with it. All three were declared as `bytes` and
resolved at read time, and the wrapper reading turned out to be correct. **Worth stating explicitly
in the document that these are wrappers with the name at field 1**, since a reader who guesses the
other way loses the whole response rather than one field.

### C2. `RetrieveChangesResponse` carries a field 4 the document does not mention [observed]

The first page returned 35 records and a continuation token. A second call with that token returned
**no records, no token, and a field 4** the schema does not model — so the paging loop makes one
request whose only content is that field.

Two things follow, and the second matters more than the first:

- The document should record field 4's existence. By analogy with `RetrieveZoneChangesResponse`,
  which §3.5 does say carries a `status`, it is most likely a status or completion flag.
- **If it is a "no more changes" indicator, reading it saves a request per fetch.** The README's
  own rule 6 treats unnecessary requests against Apple as an account-flagging risk rather than mere
  slowness, and this feature is meant to poll periodically, so one wasted round trip per poll is
  worth removing. Right now the only way to know a fetch is complete is to ask again and be told
  nothing.

The implementation now logs the wire type and value of any unmodelled field, so the next run
identifies it. Its earlier diagnostic overstated this as "the schema's assumed field numbers are
likely wrong", which was misleading — the numbers were right; the field was simply extra. That has
been fixed to distinguish "decoded nothing" from "decoded fine, plus something unknown".

### C3. Not yet exercised: the parts Stage 5 actually needs

Fetching proved the envelope, but the run printed only record types and counts. **Whether
`Record.recordField` (7) and `Record.protectionInfo` (13) decoded is still unknown**, and those two
are precisely what Stage 5 consumes — a record with zero fields would look identical in that
output. The probe now reports both, so the next run settles it.

### C1. Stage 4 §3.2: `recordRetrieveRequest` is listed as needed but never specified

Field 211 is one of the four operations marked in bold as required to read accessory data, but
unlike 201, 203 and 213 its request and response messages are never given. It turns out not to be
needed — `record/sync` returns everything — so it is not implemented. Either specify it or drop the
bold.

---

## D — corrections and clarifications

### D1. Stage 4 §2.1: `DsPrsId` is also returned by Stage 2

§2.1 says the HTTP Basic username is "the **numeric** account identifier from the Stage 1 session
payload — not `adsid`". True, but it implies the Stage 1 payload is the only source, and Stage 1
§6's own note that the payload's keys vary makes depending on it awkward.

**The MobileMe delegate response of Stage 2 carries the same numeric identifier as a top-level
`dsid`.** FindMy.py already persists it and already uses it as the Basic username against the
search-party service with `searchPartyToken`, which works — so the two are the same value. The
implementation uses that, which means Stage 4 needs nothing retained from Stage 1 at all.

Suggested amendment to Stage 2 §5: state that the response's top-level `dsid` is `DsPrsId`.

### D2. Stage 3 §3: the PET is gone by the time Stage 3 runs

§3 lists the PET as a prerequisite and notes the five-minute expiry. What it does not say is that
the PET is *consumed* by Stage 2 — it is the Basic password of the delegate exchange — and any
implementation that follows Stage 2 as written will have thrown it away by the time Stage 3 starts.
FindMy.py does exactly this: `idms_pet` is replaced by the service tokens at the end of login.

So Stage 3 does not merely need a PET "within five minutes"; it needs a **second, fresh** login
whose PET is retained rather than spent, or Stage 2 must be told to keep it. Worth a sentence,
because it is an ordering constraint on the whole flow rather than a detail of Stage 3.

### D3. Stage 5 §3.2: `PrivateKey` v2 is the only application tag written without `EXPLICIT`

Every other application tag in the document is written `[APPLICATION n] EXPLICIT SEQUENCE`.
`PrivateKey`'s v2 arm is written `[APPLICATION 5] SEQUENCE`. If that is deliberate the tag replaces
the SEQUENCE's own tag and the scalar is one level shallower; if it is a typo it does not. Both
nestings are accepted; the document should say which is meant.

### D4. Stage 1 §2.2: the UDID can be derived from the device UUID

§2.2 asks for both a "Device UUID" (v4, uppercase, persisted) and a "UDID" (32 uppercase hex,
persisted) as separate values. Stage 4 §3.3 then requires the UDID as `deviceHardwareID`.

Two independently persisted identity values are two things that can drift apart, and the README's
standing rule is that identity state must never be silently regenerated. A UUID's hex form is
exactly 32 uppercase hex characters, so **one persisted value can serve both**, which is what the
implementation does. Worth noting as permitted, unless something requires them to differ.

### D5. Stage 4 §2.2 and §3.3: this client is a Mac, not an iPhone

The specification's identity is an iPhone throughout, and Stage 2 §3 notes the `iosbuddy` endpoint
matches it. FindMy.py has long presented as a `MacBookPro18,3` on `Mac OS X 13.4.1` — including to
that same `iosbuddy` endpoint — and it works.

The implementation keeps FindMy.py's existing identity rather than introducing a second one,
swapping only the bundle for CloudKit's. Consistency within one client matters more than which
platform it claims, and changing it would invalidate every existing user's session. Not a defect in
the document, but its worked example should not be read as a requirement.

---

## Not a specification gap, but worth knowing

**FindMy.py disables TLS certificate verification.** `findmy/util/http.py` passes `ssl=False` to
every request, which turns verification off for the whole library. Stage 3 §6.6 requires pinned
certificates specifically because the material crossing that exchange is the user's entire
keychain, protected by a passcode short enough to brute-force offline. Those two facts cannot both
stand. Fixing it is out of scope for a protocol implementation and is upstream's call, but Stage 3
must not be built on that session as it is.

---

# Second round — gaps found implementing the amendments

Written after implementing every closed gap above. Stage 5 is now exact throughout, Stage 4's
schema states rather than assumes, and Stage 3's Cuttlefish invoke path is built.

Same grading. Nothing here was resolved by reading the references.

## E — blocking

### E1 — closed by §6.9

*Raised when §6.7 landed without message layouts; closed by §6.9, which supplies them.*

§6.9 enumerates every message the join carries, and all of it is now modelled in
`findmy/cloudkit/proto/cuttlefish.proto`. The four members that fail silently rather than loudly
each have a test rather than a comment:

| Trap | How it is held |
| --- | --- |
| `SignedInfo`'s signature covers the **serialised bytes**, not the parsed message | `SignedBlob` in `findmy/keychain/join.py` holds bytes and a signature, never a message. There is nothing in it to re-encode, so a signature cannot be invalidated by a re-serialisation that changes bytes without changing content. |
| `OTBottle` reserves 3–7 | `reserved 3, 4, 5, 6, 7;` in the schema, which makes reuse a compile error rather than a convention |
| `OTInternalBottle` starts at field 3 | modelled as-is, with a test asserting its field set is exactly `{3, 4}` |
| `TlkShare` declares binary-looking members as `string` | modelled as `string`, with a test asserting the wire type of all three |

**`CuttlefishEstablishRequest` is deliberately not defined at all.** The document's warning is
sound, but a comment saying "never call this" is weaker than not being able to. With the message
undefined, no code in the package can construct one, and a test asserts the name appears nowhere.
That the two requests carry the same four members in a *different order* is the argument for
structural prevention rather than discipline: a confusion between them would serialise perfectly
cleanly and mean something else entirely.

**Step 5.3's check is implemented and is a refusal, not a warning.** `require_key_shares` raises
rather than logging, because the failure it prevents is the expensive kind: joining without shares
*succeeds*, and leaves a peer in the user's trust circle and an escrow record on their account —
both permanent, both invisible in Apple's own interfaces — in exchange for no keys at all.

**What is still not built, and why.** The join is assembled but never sent. Sending it needs the
passcode-authenticated recovery of §6.2–§6.5, which is specified and not yet implemented, and it
writes two permanent artefacts to a real account. Those are worth building deliberately rather than
as the tail of a schema change. The message layer is the part that can be written and tested
without an account, and that is what exists.

## F — smaller, from the same pass

### F1 — closed by the §3.1 and §4 step 4 amendments

*Raised because the signature's coverage was unstated, leaving a correctly-specified key
derivation with nothing to verify. Closed.*

`SignatureData.data` decoding to an `ObjectSignature`, and the nine-part concatenation, are both
implemented. The asymmetry the amendment calls out has its own test, because it is the one that
fails with no symptom: an absent `symmKeyCount` contributes four zero bytes, absent `attributes`
and `ecKeyList` contribute nothing, and a test asserts each half separately rather than asserting
one signature verifies and calling it proof.

`Signature.keyid` is checked before verifying rather than after. The reasoning in the amendment is
right and worth restating: a mismatch names the wrong key, where a failed verification only says
something is wrong.

**B4 is no longer dead code.** The master EC key derivation now has the use it was always specified
for, and a test signs a structure with the derived key and verifies it back — so the low-bit
masking is exercised end to end rather than merely asserted against a recomputation. The fallback
to `signature2` is implemented and tested as rotation rather than failure.

Verification is skipped, not failed, when the read-only flag is set. It is off by default in
`unwrap_protection` for the same reason the HMAC check is: neither has met a real record.

### F2 — closed, and the answer was not the forgiving one

*Raised as "the document does not say whether a record's `label` and its `bottleID` are the same
value, so an implementation has to pick". They are never the same value.*

The join is now on the **label** alone — `com.apple.icdp.record.<peerId>`, which is what the
trust-circle service reports as a bottle's id. `bottleID` is a UUID naming the bottle inside the
record, and matching on it as well was forgiving of a confusion that should be caught. A test now
asserts that a viable-bottle list containing a `bottleID` produces **no** match and reports both
mismatch directions, rather than quietly working.

**The §7.1 correction did not reach this implementation, because deletion is not implemented.**
Worth recording why that was the right call independent of the bug: a delete path written against
the old text would have addressed `<bottleID>.double` and `<bottleID>`, which name nothing. The
document's own note is that this would "fail, or worse, silently succeed against nothing" — and it
is the second that matters, because a user who has been shown a list, typed a serial to confirm,
and watched a delete return success would reasonably believe a record was gone. The reason for
leaving deletion out was that the confirmation interface is the only protection the protocol has;
it turns out the addressing was wrong too.

### F3 — closed, and it was a correctness bug rather than an efficiency one

*Raised as "field 4 might save a request". The answer was worth more than that.*

`status == 3` means the zone is fully synced, and the paging loop now reads it.

The important part is not the saved request. **A response can carry `status = 3` and still contain
changes**, which means the previous condition — stop when a page comes back empty — was not merely
wasteful but wrong: it relied on emptiness coinciding with completion, and nothing guarantees that.
It happened to work on the observed account because the last page *was* empty.

So this is a case where a diagnostic that said "here is a field I do not understand" was worth more
than the guess that followed it. The loop now stops on the status, warns if it can neither finish
nor continue, and a test covers a synced page that still carries records.

## G — confirmed by implementing

Recorded so the next reader knows these were exercised rather than assumed:

- **The KDF, verbatim.** `be32(i) ‖ label ‖ 0x00 ‖ be32(bits)` with an empty context reproduces
  independently. The counter loop is implemented even though 16 bytes from SHA-256 never advances it.
- **The RFC 6637 rewrite.** MPI bit count, 32-byte compact point, AES-128 KEK from the first half of
  the digest, and the framed plaintext with its checksum and self-describing padding all round-trip.
  The compact point needs the y coordinate recovered by solving the curve equation; either root
  works, since a point and its negation share an x and ECDH takes only x.
- **The AAD correction.** Implemented as `header ‖ "<zone>-<record>-<field>"`. The consequence the
  document predicts is real: decryption now takes a field's identity, and the plumbing to carry a
  zone name from the fetch down to a field decrypt was the largest single change of this round.
- **EncryptedValue's field numbers.** Round-tripping the wrapper reproduces the exact plaintext
  sizes the amendment derived from ciphertext sizes — 8 bytes for `AirTag`, 11 for a date, 2 for a
  small integer, 38 for a UUID string. Independent confirmation that 3, 5 and 6 are right.

---

## H — observed, running the read-only half of Stage 3

The escrow proxy and the trust-circle service were asked together against a live account for the
first time on 2026-08-13. Both answered, the join produced matches, and nothing was written.

**The label join is confirmed live.** Three records matched viable bottles. Had `bottleID` been the
join key, the answer would have been three undescribed bottles and nothing recoverable — so F2's
correction was load-bearing rather than cosmetic, and the previous match-on-either would have
hidden it by succeeding for the wrong reason.

**§5.1's "treat the schema as unstable" is confirmed, exactly.** Twelve escrow records, of which
eleven carry the device shape and one does not — the same eleven-and-one split §5.1 records, on a
different account and two years later. The odd one is filtered out as not a recovery candidate
rather than reported as broken, which is what §5 asks for.

**`partial` accounts for the gap precisely.** Cuttlefish returned 3 valid bottles and 8 partial;
the join produced 3 recoverable records and 8 described-but-not-viable. §2.3 says an `EscrowMeta`
is empty and so "conveys only that a bottle exists in that state" — but the *count* is evidently
not noise. It corresponds one-to-one with the records that have metadata and are not recoverable,
which makes `partial` a usable cross-check on the join rather than something to ignore.

**The phantom iMacs are visible, and they are still accumulating.** Of the eight not-viable
records, six are iMac Pros and they come in duplicate pairs — two records with one serial, two
with another — from the macOS-VM export route the README describes. Two of them are dated within
the last week. So this is not only historical debris: the route that produces it is still in use,
and the records it leaves are exactly the ones no Apple interface will ever show the user.

That is the strongest available argument for the residue rules, and for building the deletion path
eventually: this account is carrying eight unrecoverable escrow records, and until now there was no
way for anyone to know.

### H1. The companion hypothesis does not hold, and the labels say what does

*Tested as suggested, from the listing already in hand. It is falsified.*

**No label on the account ends in `.double`.** Not one of the twelve. Every label has the shape

```
com.apple.icdp.record.SHA256:<base64>
```

so `peerId` in §4.3's `com.apple.icdp.record.<peerId>` is itself a `SHA256:`-prefixed base64
digest — worth recording, since it is neither a UUID nor a serial and nothing said so.

The apparent pairs have **entirely different digests**, not one differing by a suffix. So they are
distinct peers, not a record and its companion, and the note should come out as offered.

**What the data shows instead is worse than pairs, in one case:**

| Serial | Records | Escrowed |
| --- | --- | --- |
| `C02X70…` | **three** | all the same day |
| `C02Y40…` | two | both the same day |
| two further iMac Pro serials | one each | |

Three records for one serial is not a pair by any reading. What it is consistent with is the
README's own account of the macOS-VM route: **a fresh peer identity minted per run against a
reused serial**. The serial is the part the client controls and kept constant; the peer identity
is the part it regenerated. So the count of records is a count of *runs*, and the count of serials
is the count of claimed devices — four iMac Pros claimed, seven records left behind.

That sharpens the residue rule rather than softening it. "Never regenerate a persisted identity"
is the rule that was broken, and the escrow list is where the evidence of breaking it accumulates,
because unlike the device list nothing ever removes it.

**Consequences taken:**

- `device_count` groups by **serial**, not by record or by companion. Eight devices across twelve
  records on this account.
- Companion support is kept — `is_companion`, `companion_of`, and the requirement that a deletion
  issue both calls — because §7.1 describes them and a first-party record may well have one. It is
  simply not what explains these.
- The `partial` cross-check is implemented as specified: `join_recovery_options` takes the count
  and logs a warning if it disagrees with the number of described-but-unviable records.

### H2. Deletion works, and the corrected addressing is confirmed [observed]

Escrow deletion was run against a live account on 2026-08-13. **Records were removed, and a
re-listing confirmed the count dropped.** So §7.1's two-call flow is verified end to end, and with
it the correction that deletion addresses `<label>` rather than `<bottleID>` — the earlier text
would have addressed nothing.

That the confirmation came from a *re-listing* rather than from the calls returning success is the
part worth keeping. The protocol reports success for a deletion that addressed nothing, so a client
that trusts its own return values cannot tell a removal from a no-op. The only proof is asking
again, and any interface offering deletion should do that rather than reporting what it attempted.

The viability guard did the work it was designed for without the user having to be careful: every
record from the retired VM route sorted into the non-viable list and was offered, and the three
live recovery paths were withheld. Debris and danger separated on their own, exactly as §7.1 now
predicts — which is the argument for making viability the guard rather than the warning.

**What this does not establish:** that new records stop appearing. Two of those removed were from
the week before, so the route producing them is still running and the count will climb again. The
cleanup is a cleanup, not a fix.

### H3. Transient non-viability, answered structurally rather than left open

*Raised on the specification side as newly load-bearing once viability became the deletion guard:
could a service outage make a live bottle look dead, and invite deleting something real?*

A client cannot distinguish a transient outage from a bottle that is genuinely gone — that much is
true and does not look solvable. What it *can* distinguish is when the answer is not worth trusting
at all, and the clearest such case is **no viable bottle reported whatsoever**.

An account holding escrow records but no usable bottle is possible. An account where every record
became unusable at the same moment is a far better description of a service having a bad day. Since
being wrong destroys a real device's recovery path, that reads as "ask again later".

So `RecoveryOptions.viability_is_trustworthy` is false when nothing was reported viable, and in
that state `safe_to_delete` is **empty** and `delete_record` refuses outright. The dangerous case
is not warned about; it is removed, in the same spirit as offering only non-viable records.

This does not cover a partial outage — one bottle unreachable while others answer — and nothing
here could. It covers the total failure, which is both the most likely shape of an outage and the
one that would otherwise offer the user every record on the account at once.

Worth noting the shape of the fix, since it is the third time it has applied: where a guard cannot
be made correct, it can often still be made *refuse*. Deletion refuses without a listing, refuses a
viable record, and now refuses when viability is unavailable. Each is a case that could have been a
warning and is instead a closed door.

---

# Third round — from running recovery against a live account

Stage 3 §6.2 is confirmed and §6.3 is not working. `srp_init` succeeds and parses; `recover` is
rejected with an internal error. Below is everything the account said, so the answer can be worked
out rather than guessed at.

## I1. §6.1's four "skipped" bytes are the message's total length [observed]

> "| 0 | 4 bytes, skipped |"

True for a reader — every section is addressed by offset, so the four bytes can be ignored and
everything still parses. **False for a writer**, and the asymmetry is not visible from the table.

An `srp_init` reply with a 24-byte header and sections of 8, 64 and 256 bytes declared
`0x00000180`. Its actual total length is `4 + 24 + (3+1)×4 + (4+8) + (4+64) + (4+256)` = **384**.
Exactly.

This cost two wrong attempts. First zeros, on the reading that "skipped" meant "ignored". Then —
worse, because it felt principled — echoing the server's own four bytes back, which put `384` on a
100-byte proof. A message declaring four times its own length is a good way to make a parser fail
somewhere internal.

**Suggested amendment:** state that the field is the total length in bytes, big-endian, and that a
reply computes its own rather than echoing the one it answers.

## I2. §6.2 is confirmed exactly [observed]

The reply parsed with `H = 24, S = 3` and the sections came out as the table says: a request
identifier, a 64-byte salt, and a 256-byte server public value — the last being the right size for
the RFC 5054 2048-bit group.

The header's two 32-bit values were **164** and **0**, followed by a 16-byte request id. Since §6.3
says to reply with **165**, the pair reads as request-type/response-type and confirms that constant
from the other direction.

`clubTypeID` was **absent**, so the zero branch of §6.3 and the 24-byte branch of §6.4 are the ones
in play on this account. The 40-byte and club-type-1 paths remain unexercised.

A framing built from the specification reproduces the server's 384-byte message byte-for-byte at
those sizes, so the offsets, the `S + 1` rule and the length prefix are all confirmed correct
against real output.

## I3. §6.3: "sized to 20 bytes" is ambiguous, and it is where this now stops

> "the request identifier from section 0, **sized to 20 bytes**, followed by the SRP proof M1"

**[observed] Section 0 is 8 bytes.** So "sized to 20" means padding it by 12, and the document does
not say with what, on which side, or why 20.

It is also not the only candidate. §6.2 states the header carries **a 16-byte request id**, so
there are *two* request identifiers in the reply and §6.3 names one of them without distinguishing
it from the other. Padding an 8-byte value to 20 is an odd operation; padding a 16-byte value to 20
is a more natural one, which makes the header's id the likelier reading — but that is a guess about
plausibility, not something the document supports.

Current behaviour: section 0, right-padded with zeros to 20 bytes. The result is rejected with

```
status -6138
CLUBH ERROR: An internal error occurred.
```

**What is ruled out:** the framing (I2 confirms it byte-for-byte), the header rewrite (165 and 0
are both what §6.3 asks for and 164 corroborates the first), the label (`srp_init` accepted it),
the transaction id (shared across both calls), and the encoding (both blobs are base64 in plist
strings). What remains is the *content* of the two sections.

**Also tried, without effect:** declaring `baseRootCertVersions` and `trustedRootCertVersions` as
`[101, 102, 103, 500]` per §6.6 on both commands, and echoing `dsid` back on `recover`. Both are
listed in §4.4 as per-command fields without it being said which commands require them; both are
harmless and have been left in.

**What would close it:** which of the two request identifiers is meant, and how it reaches 20
bytes. Failing that, whether §6.3's two sections are the whole body — a third section would
explain an internal error better than a mis-sized first one.

> **A note on method.** Further variants could be tried against the account, and deliberately have
> not been. Each attempt spends a real passcode against a real service, the failure is
> indistinguishable from a wrong passcode by design, and the search space is large enough that
> guessing would more likely produce a false positive than an answer. This is the situation the
> clean-room split exists for: one reading of the reference settles it, where a dozen live attempts
> might not.

## I4. Recovery works end to end [observed]

Stage 3 §6.1–§6.5 and §6.7 steps 1–4 all verified against a live account on 2026-08-13, with the
§6.3 correction applied. The passcode exchange completed, the bottle opened, and this client now
holds the recovered peer's own private keys.

Four things the run settled that the documents do not state:

**The recovered plist's fields.** §6.7 step 1 says the blob holds "one field that matters" without
naming it. Seven fields, 713 bytes:

| Field | Type |
| --- | --- |
| `BottledPeerEntropy` | 72 bytes — the one step 2 needs |
| `SecureBackupIDMSData` | 286 bytes |
| `DoubleEnrollmentPassword` | 36-character string |
| `DoubleEnrollmentVersion` | integer |
| `BackupBagPassword` | 29 bytes |
| `BackupVersion` | 1-character string |
| `com.apple.securebackup.timestamp` | string |

**`DoubleEnrollmentPassword` explains `.double`.** A companion record is a *double enrollment*, and
the password for it travels inside the parent's recovered material. That is the first concrete
connection between §7.1's companion records and anything else in the protocol, and it implies a
client creating its own record has to decide whether to create one — which §7 does not discuss.

**Escrowed public keys are DER SubjectPublicKeyInfo**, 120 bytes for P-384. Not the X9.62 point a
reader might assume. §6.7 step 3 says to compare derived public keys against escrowed ones without
saying in what form either is.

**A peer's private key is 145 bytes: the 97-byte uncompressed public point, then the 48-byte
scalar.** Not DER, not PKCS#8, not a bare scalar. Both keys reported `keyType` 1, so that field
does not distinguish signing from encryption.

The last one is worth a note on method. 145 is exactly 97 + 48, which is a guess — but a
self-checking one: deriving a public key from the trailing 48 bytes must reproduce the leading 97,
and it does, for both keys. That is a free offline check that either passes or does not, so it
needed no live attempt. Where such a check exists, guessing is cheap; where it does not, it costs a
credential and fails ambiguously.

### The one that cost a run: the salt is `adsid`, and I used `dsid`

§6.7 step 2 says the HKDF salt is the account's `adsid`. The implementation passed `DsPrsId`,
which Stage 4 §2.1 states plainly is "not `adsid`, which is a different identifier in the same
dictionary" — so the document was right, unambiguous, and ignored.

Two things made that easy to do, and both are worth guarding against rather than blaming:

- The library exposed `dsid` and not `adsid`, because nothing had needed `adsid` before. The
  available value won.
- The failure said **"the passcode produced the wrong entropy"**, which was confidently wrong. A
  wrong salt and a wrong passcode are indistinguishable at that check, and the error named only
  one of them.

Both are fixed: `adsid` is carried forward through login rather than discarded, and the salt is
now *found* by trying candidates against the bottle's own escrowed keys rather than assumed — the
same free-offline-check reasoning as above. The error now says the salt may be at fault too.

---

## J — the key-share round

Three corrections landed and the trust circle now reads 13 peers. What follows is what
implementing them found, one new specification question, and one note on method.

### J1. §5.3's response is nested one level deeper than a flat reading gives [closed]

The amended table gives the response as field 1 `changes`, holding `syncToken` (1) and repeated
`change` (2). I had it flat — `repeated change = 1` and `syncToken = 2` directly on the response —
and that is the shape that reported 0 peers.

**It parses.** A flat reading of the nested message yields exactly one change per response whose
own fields are 1 and 2, so `add` at field 3 is absent and it is skipped as an unrecognised kind.
Nothing errors, the directory is empty, and the symptom surfaces one layer away in §6.7.0 as shares
whose senders cannot be identified.

That is now the third instance of the same shape — the `tlkshare` record, the share entry, and this
— so it may deserve a general note: **in this protocol a repeated member is usually inside a
wrapper, not on the response.** Protobuf's tolerance of unknown fields turns each such mistake into
a quiet empty result rather than a parse error.

### J2. Implementing the loop, and a trap that is Python's rather than the protocol's [closed]

The paging correction is implemented as specified: the only termination is an empty `changes`.

One thing bit me between writing it and testing it. The break read `if not response.changes`, and
with `changes` now a **message** rather than a repeated field that is always false — a protobuf
message is truthy however empty it is. The loop ran to the page backstop rather than terminating,
which against a real account is fifty calls per run. It must test the repeated field inside it. A
test for a feed that never empties caught this; reading the code would not have.

Not a specification defect, but recorded because the amendment's shape is what introduced it, so
anyone else implementing this amendment in Python will meet it too.

### J3. `changeTokenExpired` is handled, and restarts exactly once [closed]

As specified: discard the token *and* the accumulated directory, then restart from no token. The
restart is attempted once — a second expiry raises rather than looping, since a circle that expires
the token on every call is not a state that retrying escapes.

### J4. §6.7.0's signature integers can be negative, and the document implies otherwise [new]

§6.7.0 step 1 gives `version`, `curve`, `epoch` and `poisoned` as 32- or 64-bit little-endian, and
§6.7.0's record table declares them `int64`. Real records carry **negative** values — this account
has shares with `epoch = -1`.

"64-bit little-endian" alone does not say signed, and an unsigned rendering does not merely
mis-encode: in Python it *raises*, and the raise propagates out of the per-share loop and takes
down the entire listing. Twenty-one readable shares became one traceback.

Worth one word in the table — **signed** — because the failure is disproportionate to the mistake
and does not point at itself.

Fixed here by rendering two's complement into the fixed width, and by making verification report an
unreadable share as unverified rather than raising. The second is the more important half: one
malformed share must not be able to hide the others.

### J5. §6.7.0 steps 2–4 — closed, and the search was never going to find it [closed]

**21 of 21 shares unwrap, across every view including `Manatee`, and each yields three 64-byte
keys.** The amended §6.7.0 is correct as written and is implemented exactly; the 1,280-combination
search is deleted.

The part worth recording is why two rounds of searching failed while the answer was *inside* the
space being searched. `SFCiphertext` overruns the ciphertext by exactly the size of the other two
members, and the excess is uninitialised heap. A GCM tag rejects trailing rubbish precisely as it
rejects a wrong key or a wrong parameter — so a correct implementation of the correct construction
produced the identical failure to every incorrect one.

That is the general lesson, and it is worth more than the parameters: **a search over parameters
cannot distinguish a wrong parameter from a right parameter applied to the wrong bytes.** Widening
the space was the wrong instinct at every point, because the space was never the problem. Two
things would have caught it earlier — noticing that the ciphertext member's length had no
relationship to any plausible plaintext, and treating "the key is confirmed" as evidence that the
fault was structural rather than as licence to search harder.

Three implementation notes, none of which need amendment but all of which cost time:

- **Apple's archived key is misspelled.** The member reads
  `SFEphemeralSenderPublicKeyExternaRepresentation` — no final `l`. §6.7.0 spells it correctly, so
  a reader matching the documented name exactly finds nothing on real data. Matching the fragment
  `EphemeralSenderPublicKey` works against both.
- **The trim must be derived, not written as 113.** It is `len(point) + len(code)`, which is a
  P-384 number; a different curve makes it a different one. There is a P-256 test that fails
  against a hardcoded 113.
- **The top-level key is 64 bytes**, which settles that "AES-256-CMAC-SIV" means two 32-byte
  halves rather than a 32-byte key.

### J5.1 What this closes, which is larger than one cipher

The keys arrive **with nothing written to the account**: no peer created, no voucher signed, no
escrow record enrolled, nothing sent to Cuttlefish. §6.7.0's own note — that this "may remove every
write from the flow" — is now observed rather than hypothesised, for the read path at least.

§6.9's join messages remain built and untested, and should stay that way. They are what a client
would need to *stay* in the circle across key rotations, which is a real cost deferred rather than
avoided. But nothing in the read path needs them.

### J6. A diagnostic the record already supported and I was not using

`tlkshare` carries `receiverPublicEncryptionKey` — the key the share was wrapped to. I was
decrypting without consulting it, so **"the recovered key is wrong" and "the ECIES parameters are
wrong" arrived as the same failure**, and they lead in opposite directions.

Comparing it against the recovered key's public point costs nothing and separates them before any
decryption. Now done, comparing by meaning rather than bytes, since the same key written as a
SubjectPublicKeyInfo and as a bare point is the same key.

No amendment needed — the field was already documented. Recorded because the general form recurs:
**where two failures are indistinguishable and lead different ways, look for something already in
the data that tells them apart before widening the search.**

### J7. On method: I implemented an amendment from its summary rather than from the file

The §5.3 correction reached me as prose alongside the note that the file had been updated. I
implemented from the prose. It was accurate as far as it went, but it did not carry the response's
nesting — which was in the table in the file, and which was the actual cause of the empty directory.

So the paging loop I built from the summary was correct and would still have returned nothing.

**The specification is the file.** A summary is a pointer to a change, not the change. This is the
same discipline as not reading the reference implementation: the value of a written specification is
that it is the single artefact both halves work from, and substituting a description of it
reintroduces exactly the drift it exists to prevent.

---

## K — the one remaining gap, now narrowed to a single step

A2 said there was "no route from recovery to an item in the `Manatee` view whose `acct` matches".
That gap has closed at both ends and now consists of exactly one step in the middle.

**What is now held.** For every keychain view including `Manatee`: a 64-byte top-level key and two
64-byte class keys, obtained read-only and confirmed against a real account.

**What Stage 5 §2 asks for.** Not those keys — a **keychain item**. Specifically the item labelled
`com.apple.ProtectedCloudStorage-com.apple.icloud.searchparty`, read as a dictionary, matched on
its `acct` attribute, which holds base64 of a compressed EC public key.

**What is unspecified: how a view key becomes an item.** The two are different kinds of thing and
the type signature makes it concrete — this library's PCS layer takes
`Sequence[EllipticCurvePrivateKey]`, because a protection structure is unwrapped by RFC 6637 ECDH
against an EC private key. A 64-byte AES-SIV key is not one and cannot be coerced into one. So
something must sit between them: the view keys decrypt keychain *items*, and one of those items
contains the EC private key that PCS actually wants.

Nothing in Stages 3, 4 or 5 describes that step. Concretely, what is missing is:

| Unknown | Why it cannot be guessed |
| --- | --- |
| Which zone or container holds a view's items, and how they are enumerated | Stage 4 §3 specifies the `searchparty` container's zones; a keychain view is elsewhere and no document says where |
| The item record's shape — which fields hold the wrapped item and which the class reference | A wrong field yields empty rather than an error, as `tlkshare` and `fetchChanges` both did |
| Which of the three keys unwraps an item, and how the item names it | `classA` and `classB` exist precisely because items differ in this, and picking wrong is an authentication failure with nothing to say why |
| The item encryption itself | AES-SIV as the class keys use, or something else; and the "empty vector of headers" distinction of §6.7.0 step 4 suggests headers may carry meaning here |
| How a decrypted item becomes a dictionary with an `acct` attribute | §6.8 says "items readable as dictionaries" without saying in what encoding |

**This is not a search.** The J5 lesson applies directly: a parameter search cannot distinguish a
wrong parameter from a right one applied to the wrong bytes, and here the *bytes* — which records,
which fields — are exactly what is unknown. Searching would produce another round of uniform
authentication failures that say nothing.

So this one is asked rather than attempted. Everything either side of it is built and verified.

---

## K closed — Stage 3 reaches Stage 5's input

**[observed]** `session.service_keys(peer)` returns both P-256 keys, and both are
*verified* rather than guessed: the key blob carries a public half that the recovered
scalar reproduces exactly. The chain runs end to end — passcode, escrow recovery, bottle,
shares, view keys, keychain item, EC key — with **nothing written to the account**.

§6.8.1 is correct as written and needed no amendment. Everything below is detail the
document could carry, found by implementing it.

### K1. The v2 tag is implicit, which answers D3 [observed]

D3 asked whether `[APPLICATION 5]` being written without `EXPLICIT` was deliberate. It is:
the tag **replaces** the SEQUENCE's own tag, so the octet string sits directly inside the
wrapper rather than one level further in. Reading it as explicit fails with "cannot read
children of a primitive element", which names neither the structure nor the choice.

Both nestings are now read. Worth noting the failure I made getting there: D3 already said
"both nestings are accepted", and I had written that in this file while implementing only
one. A claim in a gap report about what the code does is worth nothing unless the same edit
makes it true.

### K2. A key blob's public half is a bare x coordinate [observed]

The payload's shape, from a real account:

```
1: { key: 64 bytes, public_structure: 210 bytes }   the encryption key
2: { key: 64 bytes }                                the signing key
```

**64 bytes for a P-256 key**, which is the bare **x coordinate** followed by the 32-byte
scalar — no `0x04` marker and no compressed-point sign byte. So "compressed private key
bytes" is neither X9.62 form, and a reader that knows only those two checks 33 and 65 bytes
against a 32-byte prefix and rejects a good key with nothing to say why.

Worth one line in §6.8.1, because the failure is silent in the way this protocol keeps
being silent: nothing is malformed, the lengths simply do not match anything expected.

The optional DER public structure is present on the encryption key and absent on the
signing key, which is why the two members differ in size.

### K3. Two things that cost a round each, and the rule they share

Both were mine rather than the document's, and they are the same mistake:

- I had **P-521 in the scalar-length table** speculatively. A 66-byte member of the payload
  then matched by length alone and was taken as a key it is not. Nothing in this protocol
  uses P-521.
- The reader took the **first** plausible scalar and raised when it would not derive, so an
  unchecked length match outranked a blob whose halves actually agreed.

The rule: **a length match is a guess and a self-checking match is proof, and code must not
treat them alike.** Widening the set of *public forms* is safe, because each is compared
against bytes the key itself produces and a wrong one cannot match. Widening the set of
*scalar lengths* is not, because nothing checks it. The two look like the same kind of
change and are opposites — which is the same distinction that made the SFIES parameter
sweep worthless and the bottle-key search sound.

### K4. Not exercised: the additional data's date rendering

No field on any item observed is a date, so the RFC 3339 rendering of §6.8.1 has never run
against real data and the epoch it assumes is Apple's. Stage 5 §8 Q2 records that the epoch
is unsettled, and the two questions are the same question. The implementation warns when a
date participates in the additional data rather than guessing quietly, so if an item ever
fails to authenticate with that warning present, the epoch is the first thing to doubt.

### K5. [observed] The pointer indirection is the norm, not a special case

The `Manatee` view holds **67 items and 66 pointers** — very nearly one pointer per item.
So `currentitem` is not a mechanism that exists for this one service key; it is how items
are addressed generally. That makes the tag lookup of §6.8.1 the ordinary path rather than
a shortcut, and the `acct` scan the exception.

---

## L. "Compressed public key" means a bare x coordinate, everywhere [observed]

Stage 5 §2 says `acct` "holds the base64 of a compressed elliptic-curve public key", and §4
step 1 says to "compress our private key's public part and scan the keyset for an entry
whose public key matches those bytes". Both readings say *compressed*, and X9.62's
compressed point is 33 bytes for P-256.

**It is 32.** The service key item's `acct` decodes to 32 bytes beginning `0xb5` — no
`0x02`/`0x03` sign byte and no `0x04` marker. It is the bare x coordinate. That is the same
form K2 found in the key blobs, so this is not a quirk of one field: **in this protocol, a
"compressed public key" is the x coordinate alone.**

The consequence is not cosmetic. §4 step 1's match compares our compressed public key
against each keyset entry's, and 33 bytes never equals 32, so **every record fails to match
and reports as protected for someone else**. On this account that was 15 of 15 wanted
records skipped as "no key held" — a message which asserts something much stronger and more
discouraging than "the encoding is wrong", and which is what a reader would believe.

Worth stating in §2 and §4 step 1, because the failure is indistinguishable from the
legitimate case it is supposed to describe.

Two notes on the fix. Matching on a bare x is slightly weaker than matching on a full
point, since x alone does not fix the sign of y — but that ambiguity is the protocol's own,
introduced by storing x alone, not by the reader. And the failure now names the entries'
public-key sizes beside the forms compared against, which distinguishes "really not for us"
from "compared the wrong bytes" without another run.

### L1. B1 and B2 are closed, and the note pointing at them was stale

The probe ended by asking the operator to write down "the INFO line naming the key wrap and
KDF", which was the output of the searches those sections describe. §5 and §4 step 2 now
specify both exactly, the implementation has one construction rather than a search, and no
such line is emitted. The instruction has been removed and both sections marked closed.

---

## M. The key protecting a beacon record is in neither keychain view [open]

Everything up to Stage 5's first step now works, and this is where it stops. Asking rather
than searching, on the J5 principle: the unknown here is *which key*, and no parameter
sweep addresses that.

**What is established, on a live account:**

| | |
| --- | --- |
| `Manatee` view | 67 items, **67 of 67 decrypt**, yielding **68 elliptic-curve keys** |
| Item self-consistency | every item's key matches the `acct` that indexes it — no warnings |
| Records wanted | 15 (`MasterBeaconRecord`, `BeaconNamingRecord`, `KeyAlignmentRecord`) |
| Entries per record | **exactly one** |
| That entry's public key | **32 bytes** — a bare x coordinate, as §4 step 1 compares against |
| Matches among our 68 | **none** |

So this is not an encoding problem — 32-byte forms are compared and the sizes agree — and
not a coverage problem, since every item in the view was read rather than only the one the
`currentitem` pointer names. **The key those records are protected under is not in
`Manatee`.** `ProtectedCloudStorage` is now read as well, per §2's "two views must be
synced"; if that also finds nothing, the questions below are what remain.

### M1. Should the record's key be in a keychain view at all?

§2 says "`Manatee` is the keychain view holding these keys", and §4 step 1 says to scan the
keyset for an entry whose public key matches ours. Both read as though the service key
found in `Manatee` is the one a record's keyset names. On this account it is not, and
neither is any of the other 67.

Three readings, and the document does not distinguish them:

1. **The record's key is elsewhere** — another view, another container, or not in the
   keychain at all.
2. **The record's key is *derived* from the service key** rather than being it, so
   comparing public halves was never going to match.
3. **The keyset is not matched against our key directly** — §8 Q3 already asks whether a
   record's structure is unwrapped under a *zone* key rather than the service key, and
   that question turns out to be load-bearing rather than incidental. Every zone in the
   container reported `protected=True`.

### M2. What are the parallel key records for?

The zone holds records this flow ignores, and two look like a key hierarchy rather than
data: **`OwnedDeviceKeyRecord` ×8** and **`SharingCircleSecret` ×5**, plus
`OwnerSharingCircle` and `OwnerPeerTrust` ×1 each. §8 Q4 already asks about the sharing
circle ones and offers "a parallel key hierarchy for accessories shared with others" as the
plausible reading.

If a beacon record is protected under something reached through those rather than through
the keychain directly, that would explain this exactly — and it would mean §4 step 1 needs
a preceding step that the document does not currently have.

### M3. What this side can supply

The next run names the public key each entry asks for, in hex, now that *which key* is the
only thing left to learn. It is a public key, so it identifies the holder without
disclosing anything. If that value is recognisable — a device key, a zone key, a sharing
circle key — it answers M1 outright.

Nothing here is blocked on the answer for the read-only flow's other half: Stage 3 is
complete and verified end to end, and every part of Stage 5 before the keyset match is
implemented as specified and untested only because no record has reached it.

---

## N. The zone yields one key and the record asks for another [open]

§4 step 0 works. The zone's structure unwraps under a keychain service key, its key id
matches, its `meta` decrypts under the empty-context AAD, and the identity inside yields an
elliptic-curve key. Every level the amendment added is confirmed.

**The zone yields exactly one key, and a record's keyset names a different one** —
`20fb99a6 4463a920 …`, 32 bytes, one entry per record. So there is either a source of zone
keys this is not reading, or a step between the zone key and the record's.

**Where the numbers stand**, after correcting the v1 reader (N1 below):

| | |
| --- | --- |
| `Manatee` | 67 of 67 items read, **101** elliptic-curve keys |
| `ProtectedCloudStorage` | 34 of 35 items read, **32** keys |
| Zone structure | unwraps; `meta` holds **0 symmetric** and **1 private** key |
| Record structures | one entry each, 32-byte public key, none matching |

### N1. My own bug, recorded because it changed the numbers

§4 step 6 says the nested keyset's keys are "the same private-key CHOICE as a keychain
item's `v_Data`". This implementation had taught the **v2** arm of that CHOICE that a key
blob may be a bare x coordinate beside its scalar (K2), and had never taught the **v1** arm,
which passed its octets straight to a bare-scalar reader.

That rejected every v1 key as "matches no curve" — and the identity search above it caught
the exception and moved on, so a correct structure produced nothing at all. Fixing it took
`Manatee` from 68 keys to 101, `ProtectedCloudStorage` from 0 to 32, and the zone from 0 to
1. Not a specification defect; recorded because the counts in M were measured with it
present and are therefore wrong.

### N2. What could be missing

Three readings, and nothing here distinguishes them:

1. **More zone keys exist than one.** §4 step 0 says a record's keyset names "one of the
   zone keys", plural. This account's zone `meta` holds one identity with one key. If the
   plural is meaningful, something else supplies the rest.
2. **A step between the zone key and the record's.** The record's key may be derived from
   the zone key, or wrapped under it somewhere this is not looking, rather than being it.
3. **`recordProtectionInfo` is the source, not the exception.** §4 step 0 gives it as the
   fallback for records that carry no `protectionInfo` of their own. Every record here
   carries one, so it is not being read — but if a record's own structure names a key that
   comes *from* the zone's `recordProtectionInfo`, the two are not alternatives and the
   order matters.

### N3. Two checks that have never verified, now on the path

Neither is fatal today and both may matter once the key is found:

- **The protection structure's HMAC fails on every structure**, including the zone's, while
  the key id matches. §4 step 5 says it covers the DER of `keyset`, the raw `meta` and the
  DER of `signatureData`, **re-encoded** rather than sliced from the input. That
  re-encoding is the most likely divergence, and a wrong reading of what the HMAC covers
  looks exactly like this.
- **The nested keyset's own `hash`** — SHA-256 over its DER with `hash` removed — is not
  implemented. Same re-encoding shape.

If either is meant to gate what follows rather than merely confirm it, that would matter
here.

---

## N continued — the HMAC is fixed, and the zone still yields one key

### N4. §4 step 5's third part was the whole of that bug [closed]

**The HMAC now verifies**, on the zone's structure and on every record's. Signing the
`ObjectSignature` — the contents of the `data` OCTET STRING, taken as bytes with no
re-encoding — was exactly it. The amendment's note about the asymmetry is worth keeping:
key-id-matches-but-HMAC-fails really is diagnostic, and a wrong key would have failed both.

Worth recording that my own test fixture had been building its HMAC the same wrong way. It
agreed with the implementation and therefore proved only that the two agreed, which is the
failure mode of a fixture written from the same misreading as the code.

### N5. The SET OF warning found a real bug, but not this one [closed]

`_identity_keys` was handing whole `SET`s to the private-key reader. That does not fail —
it returns a **pair**, because the structure it returns holds an encryption key and a
signing key — so a set of five keys would have yielded two and looked complete. Exactly the
shape described. Fixed, with tests for a five-entry set and for one unreadable entry not
ending the loop.

**But it did not change this account's numbers**, because the zone's `meta` genuinely holds
one identity whose keyset is 112 bytes — about what a single v1 key, a name, a set and a
32-byte hash come to. The prediction was falsifiable and it falsified: the HMAC cleared and
the count stayed at one.

### N6. So the zone's only key is not the one its records name [open]

| | |
| --- | --- |
| Zone `meta` | `[2]{SET{SEQUENCE{INTEGER, OCTET STRING(112B)}}}` — one identity |
| Zone yields | **one** key, `1df257ad ee267516 …` |
| Every record names | **one** key, `20fb99a6 4463a920 …` |
| HMAC | verifies at both levels now |

Given N2's answer — the zone's `meta` is the only source, a record's keyset names a zone
key directly, and there is no derivation between them — one of these must be true, and
nothing here distinguishes them:

1. **The zone's `meta` should hold more than one identity on this account and does not**,
   which would make this a property of the account rather than of the reader.
2. **`1df257ad` is the wrong key to have taken** — the identity's keyset holds one key and
   this took it, but perhaps the keyset is not where a *zone* key lives.
3. **The structure being unwrapped is not the one whose keys the records name.** It is the
   `BeaconStore` zone's `protectionInfo` from a zone retrieve, which is what step 0 says.
   If `recordProtectionInfo` is the one that yields keys records name — rather than the
   alternative-for-structureless-records the amendment describes — that would fit.

The next run reports, for a real record, whether the key it names is among the 133 keychain
keys, among the zone keys, or in neither. **If it is in neither**, no better reading of
either source finds it and the key comes from somewhere not yet described. That is a cheap
check and it should settle which of the three above applies.

### N7. The derived key is not it either, and the record's key is in neither set [open]

Both checks ran. Both say no.

| | |
| --- | --- |
| Zone keys | **2** — `1df257ad ee267516` from the identity, `50545af9 cd0513b0` derived per §5 |
| Record names | `20fb99a6 4463a920` |
| Among the 2 zone keys | **no** |
| Among the 133 keychain keys | **no** |

So §5's master EC key is not the bridge — thank you for it anyway, since ruling it out cost
one run and it was the only construction in the document that could have been. And the
fourth outcome you proposed does not apply either: the key is not a keychain key, so
records are not naming one directly and step 0 is not a red herring.

**The key a record names is in neither set.** Every source this document describes has now
been read and none holds it, so it comes from somewhere not yet described rather than from
somewhere read wrongly. That is a different kind of gap from the previous four, all of
which were "read it better".

What is confirmed working, so the search space is genuinely only this last step: keychain
items, both views, the service key, the zone's structure, its HMAC at both levels, its
`meta` under the empty-context AAD, the identity inside it, and §5's derivation.

**What this side can still supply cheaply.** The next run reports every *distinct* key
named across all 15 records, and which set each is in. If all fifteen name **one** key, the
thing being looked for is a single zone-wide key that no described source holds. If they
name fifteen, it is per-record and the question changes shape. That distinction seems worth
having before anything else is proposed, and it costs nothing.

**The unexplained records are the obvious suspects**, and M2 is still open: the zone holds
`OwnedDeviceKeyRecord` ×8, `SharingCircleSecret` ×5, `OwnerSharingCircle` and
`OwnerPeerTrust`. §8 Q4's plausible reading — a parallel key hierarchy — would fit a key
that no keychain view and no zone structure holds. Nothing here establishes it.

### N8. One key protects the whole zone, and no described source holds it [open]

The distinct-key count came back as **one**. Every record in the zone — all 35, of nine
different types — names the same 32-byte key, `20fb99a6 4463a920`:

```
BeaconNamingRecord, KeyAlignmentRecord, LeashRecord, MasterBeaconRecord,
OwnedDeviceKeyRecord, OwnerPeerTrust, OwnerSharingCircle, SafeLocation, SharingCircleSecret
```

Two things follow, and the second was not expected.

**It is a single zone-wide key, not a key per record.** So whatever is missing is one thing
obtained once, not a lookup performed per record. That is a smaller gap than it could have
been.

**M2's parallel-hierarchy reading is dead**, at least as an explanation for this.
`SharingCircleSecret` and `OwnedDeviceKeyRecord` name the *same key* as `MasterBeaconRecord`
— they are not a separate key hierarchy, they are more records under the same one. §8 Q4 may
still be right about what those records are *for*, but they are not the route to this key,
and neither is anything else in the zone.

**Everything described has now been read, and the key is in none of it:** 133 keychain keys
across both views, the zone's identity key, and §5's derivation from the zone's master key.
The zone carries no `recordProtectionInfo` (Stage 4 §3.7 records it as absent), so that is
not an unread source either.

So the remaining question is narrow and, I think, well posed: **what does a client hold that
lets it unwrap `20fb99a6…`, given it is not in the keychain and not in the zone's own
protection structure?**

Two shapes that would fit, offered as shapes rather than as candidates to try:

- Something obtained from a service not yet in this document, at fetch time rather than from
  stored state.
- A keychain item this client cannot read. `Manatee` yielded 67 of 67 items, but
  `ProtectedCloudStorage` yielded **34 of 35** — one item did not decrypt under the keys
  held. That is expected for items of another class, and it is also the only place on the
  read path where something exists and was not opened. Worth mentioning only because it is
  the sole remaining "read something better" possibility, and everything else is exhausted.

### N9. The blob layout was already being read correctly — the prediction falsifies [observed]

§6.8.1's amendment is right and is now implemented as stated, with the check at the point of
parse. **It changed nothing**, and that is the informative part.

The prediction was that `20fb99a6…` would be the leading 32 bytes of the zone identity's
blob and `1df257ad…` the public x of reading those bytes as a scalar. Neither holds:

- The zone still yields **`1df257ad…`**, byte-identical to before.
- **No blob raised** the new halves-disagree error, at any of the 133 keychain keys or the
  zone's identity.

The reason is that the previous reader was not doing what it looked like. It searched both
ends, but it tried the **trailing** scalar *first* and compared the remainder against a set
of public forms that included the bare x — which is exactly `x ‖ scalar`. So it matched the
stated layout on its first attempt, and the two readers are equivalent for 64-byte blobs.
The search was ugly and its being a search was a real problem, but it was not producing the
wrong half.

So `1df257ad…` is a correctly-read zone key, and `20fb99a6…` is genuinely not it. N6, N7 and
N8 are not one bug.

**What the amendment did buy**, and worth keeping: the layout is now stated rather than
searched for, a 32-byte blob is dispatched on rather than assumed, and a disagreeing pair
raises where it is parsed instead of travelling. It removes the class of error even though
this account did not have an instance of it.

The next run also reports, per blob, whether it was 64 bytes with agreeing halves or 32 with
no public half. That distinguishes the two remaining readings of the zone identity's key,
though neither changes the conclusion: whatever shape it is, it is not `20fb99a6…`.

**N8's question stands unchanged**: one key protects all 35 records, and it is in neither
the 133 keychain keys nor the zone's structure.

### N10. The zone identity's blob is 32 bytes — the only one on the account [observed]

With the blob shapes reported, one line stands out of 134:

| | |
| --- | --- |
| All 133 keychain key blobs | **64 bytes**, halves agree, every one |
| The zone identity's blob | **32 bytes**, no public half |

§6.8.1 says a 32-byte blob is "the scalar alone with no public half", and this reads it that
way — deriving `1df257ad…`. But **nothing checks that reading**, by definition: a blob with
no public half carries nothing to verify against, which is exactly the case the amendment
notes has nothing to check.

So there is a reading of this that no evidence here excludes: **those 32 bytes may be a
public x rather than a private scalar.** An identity that names a key rather than carrying
one would look precisely like this, and would explain N8 exactly — the zone tells a client
*which* key protects its records, and the private half comes from somewhere else.

It is testable in one line and the next run does it: the log now reports the blob's own
leading bytes beside what they derive to. **If those 32 bytes are `20fb99a6…`, the question
is answered** — the identity carries the public half of the very key all 35 records name,
this has been deriving a key from a public coordinate, and what is missing is wherever the
private half lives. If they are something else, the reading is wrong and N8 stands as it is.

Worth asking regardless of that result: **is a 32-byte blob in an identity's keyset really
a private scalar?** Every actual private key on this account is 64 bytes and carries its
public half. A lone 32-byte value in a structure whose purpose is to identify parties reads
more naturally as an identifier than as a secret — and the amendment's "at 32 bytes there is
nothing to check against" is a statement about verification, not evidence that what is there
is private.

Two unrelated observations from the same run, neither on the path:

- Two `ProtectedCloudStorage` items are named `default` and `PCSBound` rather than by a key,
  and hold no readable `v_Data`. The base64 fallback handled them correctly.
- 34 of 35 items read there; the one that does not is `PCSBound`, whose `v_Data` is not DER.
  So the earlier note that an unread item might hold the missing key is resolved: it is that
  one, and it holds no key structure at all.

### N11. The 32 bytes are not the record's key either — but they may not be a key at all

The blob is `97eb8f96 85a72734…`, and read as a scalar it derives to `1df257ad…`. So it is
**neither** the key the records name nor its public half, and N10's hypothesis is dead:
the identity is not naming `20fb99a6…`.

What replaces it is arithmetic rather than a hypothesis. The nested keyset is **112 bytes**,
and its members per §4 step 6 are a string, `keys`, a set, and a 32-byte `hash`:

| Contents | DER cost |
| --- | --- |
| outer SEQUENCE header | 3 |
| a short name | ~4 |
| `hash`, SHA-256 | 34 |
| `SET { SEQUENCE { OCTET STRING(64) } }` | ~72 → **113 total** |
| `SET { SEQUENCE { OCTET STRING(32) } }` | ~40 → **81 total** |

**112 fits a 64-byte key and leaves 31 bytes unexplained for a 32-byte one.** Every real
private key on this account is 64 bytes. And a 32-byte value is exactly the size of both a
P-256 scalar and a SHA-256 digest — so a reader that accepts any 32-byte primitive as a bare
scalar will happily turn a structure's **checksum** into a key, and nothing about the result
looks wrong: it is a valid key on the curve with a public x that matches nothing.

That is the same failure the §6.8.1 amendment describes, one level up: not "the halves were
read the wrong way round" but "something that was never a key was read as one".

**This is a question for the reference rather than a guess to act on**, and it is precise:
in the zone's identity keyset, is the private key 64 bytes or 32? If 64, this implementation
is picking up the `hash` and missing the key, and the 112 bytes say where to look. If 32,
then `97eb8f96…` really is the key material and the record's key comes from somewhere else
entirely — and N8 stands untouched.

The next run logs the identity's full shape to six levels, so the member sizes will be
visible directly rather than inferred from a total. I have not changed the reader on the
strength of the arithmetic.

**A note on my own reasoning here**, since it has now cost two rounds: `scalar_in` accepts
any 32-byte value as an unverified scalar. That is what the amendment says a 32-byte blob
is — but the amendment describes a *key blob*, and this reader applies it to any primitive
it meets while walking a structure. Those are not the same thing, and the check I added at
the point of parse does not help, because a 32-byte blob is precisely the case it cannot
check.

### N12. Closed — it was reading the keyset's checksum as its key [closed]

The identity's shape answered it:

```
16{2(1B), 4(112B -> APP 2{16{12(0B), 17{16{4(64B)}}, 17{}, 4(32B)}})}
```

That is §3.2's `ShareProtectionKeySet` — `[APPLICATION 2] { name, keys, set, hash }` — with a
**64-byte key blob inside `keys`** and a 32-byte `hash` beside it. This implementation was
returning the hash.

Nothing in the document was wrong. The mechanism was that reading the keyset *as* a key
**succeeds**: its `hash` is exactly a P-256 scalar's length, and an unverifiable 32-byte blob
carries nothing that distinguishes a checksum from a secret. The walk then stopped at the
first member to yield something and never opened `keys`.

**Records decrypt.** Two levels, both halves taken at the right one.

Worth recording as a pattern rather than an incident, because it is the third instance in
this stage alone:

| Where | What stopped early |
| --- | --- |
| §6.7.0 identities | a `SET` handed to a reader that returns a *pair*, so five keys yielded two |
| §6.8.1 key blobs | a search that took the first plausible scalar |
| §4 step 6 keysets | a walk that halted at the first member to yield anything |

Each looks like efficiency, each produces a **short list that looks complete**, and none
reports anything. The general rule the last of these earned: where a structure may hold keys
in more than one place, collect rather than settle — a spurious key costs one failed
comparison, a missed one costs everything below it.

**Still open and now worth doing**, since the structure is finally known:

- The nested keyset's own `hash` — SHA-256 over its DER with `hash` removed — is still not
  verified. It is the checksum this was mistaking for a key, and checking it would have
  named the mistake immediately.
- Stage 5 §8 Q2, the date epoch, is now answerable from a real accessory's pairing date
  rather than by argument.
- M2 and §8 Q4 remain open: `OwnedDeviceKeyRecord` and `SharingCircleSecret` are still
  unexplained, though N8 established they are protected by the same key as everything else.

## Round O — terms of service

### O1. Closed — renew before each terms request

§5.2 and §5.3 are consistent and complete, and implementing them turned up one thing the
document does not mention, which is a property of the two sections combined rather than of
either.

The setup headers of §5.3 authenticate with the **PET**, which §5.1 puts at about five
minutes. The flow of §5.2 is fetch, *render, let the user read, let them decide*, accept.
Reading a terms of service takes longer than five minutes if anyone actually does it — so
the acceptance in step 3 is posted with a credential that expired while it was being read.

It fails **after** the user has agreed, which is the worst available moment: to them, they
read a contract, accepted it, and were told to sign in again. An app that then loops them
back through login has asked for agreement twice for one acceptance.

This implementation re-authenticates immediately before each terms request rather than
reusing the token the failed delegate request carried. That works, and it is cheap — one
extra GSA exchange in a flow that already involves a person reading — but it is a decision
made in the implementation about a property the specification states in two separate
places without connecting them.

**Answered, and §5.2 now specifies it**: renew immediately before each terms request. The
assumption was right, for a better reason than the one it was made on. Renewing a PET
repeats the SRP exchange, which needs the password — and terms can only block a login that
is *in progress*, so the password is necessarily in hand. A session restored from storage
is past this stage entirely. That is why the renewal can be unconditional here when it
could not be elsewhere.

The one exception is §5.1's weekly token refresh, which repeats this stage unattended. With
new terms published and no password retained, renewal is impossible and a fresh sign-in is
the only answer — so falling back to the token already held rather than failing at the
renewal is correct, and §5.2 now says so.

### O2. Open by design — what `localizedError` says for pending terms

Implemented as §5.2 directs: `localizedError` and `description` are surfaced verbatim,
nothing branches on a guessed value, and the terms flow is offered as a remedy the caller
chooses rather than one triggered by a string match. The value is also logged at WARNING so
that the first account to hit it records the answer rather than merely working.

Nothing further is needed from the spec. This is only noted so the round closes with it
still open: it can be answered by one affected account and by nothing else.

## Round P — what an unnamed master beacon is

### P1. The check is one run away, not free — and one existing note is unreliable

The hypothesis is worth taking seriously and I cannot settle it from anything already in
hand. Two corrections to what "data you already hold" covers:

**The committed macOS fixture cannot answer it.** `app/src/test/resources/19032025/` holds
exactly one beacon and one naming record, and they match. A bundle with no unmatched record
says nothing about what an unmatched record contains.

**Nothing recorded the secret, because the discard happens first.** `accessories_from_records`
drops an unnamed beacon before `accessory_from_record` reads any secret from it, so no run so
far has reported which of the two it carried. The model was recorded — `iPad13,18` — and that
is the whole of the evidence to date.

So the discard now logs the discriminator: model, plus which of `sharedSecret2` and
`secureLocationsSharedSecret` is present, for each record dropped. It is one INFO line on the
next ordinary run, no probe and no extra request. Nothing branches on it.

**A note in my own docstrings was wrong and is corrected.** Two of them said the `iPad13,18`
entry came from "an account with no iPad". That was my inference, not an observation, and the
account owner's remark at the time — that they do not have *two* iPads — indicates they have
one. If so it strengthens the hypothesis rather than weakening it: the record is their real
iPad, findable, in the zone, and unnamed because a device takes its name from the device. But
it is now stated as what was seen rather than as what it implied.

### P2. If it holds, §5.5's synthesis is the dangerous half

Worth separating, because the two consequences are not equally reversible.
`accessories_from_records` discarding an unnamed record is at worst an omission the user can
notice. §5.5's advice to **synthesise** a naming record for a beacon that has none would put
a tag in the export that the user does not own — an iPad rendered as an AirTag, in a bundle
whose whole purpose is to be imported somewhere and trusted.

An omission is visible and recoverable. A fabricated accessory is neither, and it would be
produced by the path that looks like the more helpful one.

### P3. The model claim checks out, and the fixture answers more than it was asked

Verified against `app/src/test/resources/19032025/`, which is free and offline in a way P1
was not. The accessory there carries:

| Field | Value |
| --- | --- |
| `model` | `''` — **empty** |
| `productId` / `vendorId` | `21760` / `76` |
| secondary secret | `secondarySharedSecret`, and no `secureLocationsSharedSecret` |

So both halves hold: an accessory's model is empty and its identity is the product ids,
while `iPad13,18` is the `<family><major>,<minor>` form devices use for themselves. Agreed
that this makes the model close to decisive and the secret a confirmation. Both are now
logged; neither is branched on.

Two things the same fixture says that the specification does not:

**§2.4's `2006` is not universal.** The table gives an AirTag's `stableIdentifier` as
`2006~#<hardware-id>~#<serial>`. This one is `2001~#<hardware-id>~#<serial>`. Nothing breaks
— the serial is taken after the *last* `~#`, so the prefix is never parsed — but a reader
that matched on `2006` to identify an AirTag would reject a real one. Worth softening the
table to say the leading number varies, or establishing what it means.

**§3's `emoji` absence is an account property, not a format one.** The table marks it
`[observed] absent`; this record carries `🐈`. Both are tolerated, and the writer treats every
field as optional, so this changes nothing in the implementation. It is worth noting only
because two `[observed]` notes from two accounts disagreeing is exactly the case where one of
them gets read as "the format does not have this".

The fixture's key sets are now the oracle for both plist writers, replacing fixtures that
only agreed with the implementation that produced them: 14 keys for the beacon, 6 for the
naming record, read off files Apple's own framework wrote.

## Round Q — the write path is verified

### Q1. Stage 4 §4 and Stage 5 §6.1 both work, confirmed the only way they could be

A name written by this implementation displayed correctly in **Apple's own Find My on a
Mac**. §4.3 and §6.1 both said a success response and a clean read-back prove nothing, and
they were right to: an untouched Apple device was the only thing that could settle it.

**§4.3 and §6.1's "unverified" can be dropped.** What that one screenshot establishes:

| | Why it follows |
| --- | --- |
| §6.1's layout — **tag before ciphertext** | the trap of the whole section. A writer and reader that both appended it would agree perfectly and produce something Apple rejects |
| The AAD, for a *write* | the same construction as §6's read, but nothing had exercised it in that direction |
| §4's `record/save`, op **210**, `saveSemantics` **3** | the operation was accepted and took effect |
| §4.2's `recordProtectionInfoTag` handling | a wrong tag would have been a silently lost update |
| **§4.1's `merge`, and sending the whole record** | the accessory kept its **emoji** and stayed joined to its beacon. A dropped `associatedBeacon` would have unlinked it; a dropped `emoji` would have shown a default |

That last row was not something I set out to test and is the part I would have been least
sure of, since §4.1 describes exactly the failure it rules out.

**One thing that reads as a bug and is not.** The name written was `Shane's Backpackk`,
with a doubled final letter — deliberate on the owner's part, and it round-tripped
character for character through Find My. A doubled trailing character is also what a
length or offset error looks like, so it is worth recording that this one was typed rather
than produced.

**Scope, honestly.** What ran was one `name` field on one naming record. `emoji` was
preserved but not *written*, `saveSemantics` 2 has never created anything, and no write has
been attempted against a stale tag to confirm §4.2's lost-update behaviour presents as a
failure rather than as silence. Those remain untested; the layout underneath them does not.

### Q2. Stage 5 §4 step 0's fallback is built and still unexercised

`recordProtectionInfo` and the `pcsKey` prefix selection now ship, per the reconsidered
disposition. Nothing on the account examined reaches them: every record carried its own
structure and none carried a `pcsKey`.

It warns at WARNING twice — once when the zone's defaults are unwrapped, once per record
decrypted through them — each naming itself as the first thing to suspect. A test asserts
that line exists, because without it this is an untested branch rather than a labelled one.

An empty `pcsKey` selects the sole default when there is exactly one and nothing when there
are several: choosing would present as a wrong key rather than as a missing selector.

### Q3. The keyset checksum does not verify, and the keys are right anyway

Round N12 left this open: *"The nested keyset's own `hash` — SHA-256 over its DER with
`hash` removed — is still not verified. It is the checksum this was mistaking for a key,
and checking it would have named the mistake immediately."*

It is now implemented, and **[observed] it fails on every real keyset.** The shape is
exactly §3.2's `ShareProtectionKeySet`:

```
16{12(0B), 17{16{4(64B)}}, 17{}, 4(32B)}
```

— `name` empty, `keys` holding one 64-byte blob, an empty `set`, and a 32-byte `hash`.

**The keys out of it are correct.** The zone unwraps, all records decrypt, and a field
encrypted under this material displayed correctly on an Apple device. So this is not a
data problem — the digest's construction is what is unestablished. Two candidates, and I
have not searched between them, because a search over hash inputs cannot tell a wrong
construction from a right one applied to the wrong bytes:

1. **The re-encoding is not byte-identical** to what Apple hashed. Stripping a member and
   re-serialising reproduces the original only if every length and tag round-trips
   exactly, and the outer length changes when a member is removed.
2. **It covers something other than "the structure with `hash` removed"** — the `keys`
   member alone, say, or the structure with `hash` present but zeroed.

Nothing acts on the result and nothing will until a real keyset passes: the function's
docstring says so explicitly, so it cannot quietly become a check that rejects live data.

**Worth answering only if it is cheap on the reference side.** It buys a diagnostic rather
than a capability — the one that would have named N12's bug in a line instead of three
rounds — so it is not worth much effort now that the bug it would have caught is fixed.
Recording it because "implemented and it fails" is a more useful state to leave behind
than "still not verified", and because a later reader finding a False here should not
conclude the keys are bad.

### Q3 follow-up. Not the empty members — checked, and they survive

The named candidate does not apply to this implementation. `parse_all` keeps zero-length
elements, and `rebuild_without` re-emits each member's **raw bytes** rather than
re-encoding from parsed values, precisely so that a decoder which discards its input
cannot fail to reproduce it.

Demonstrated on a synthetic keyset of exactly the reported shape:

```
16{12(0B), 17{16{4(64B)}}, 17{}, 4(32B)}
```

The rebuild is byte-identical to the hashed input and the rule verifies. A test now pins
the empty `name` and the empty `set` surviving the rebuild, so the failure mode you named
cannot appear here later without something going red.

**So the remaining difference is which element "the structure" means**, and it is the
level-confusion shape this protocol has produced at every other layer. `ShareProtectionKeySet`
arrives **explicitly** application-tagged — `APP 2` wrapping a `SEQUENCE` — and §4 step 6
says the digest covers "the structure with `hash` omitted". Two readings:

| Framing | Bytes hashed |
| --- | --- |
| `sequence` | the inner `SEQUENCE`, rebuilt without `hash` — **what this implementation was doing** |
| `wrapper` | that, re-wrapped in its `APP 2` tag |

They differ by four bytes of tag and length. Both are now computed on every keyset and the
matching one is logged by name; neither gates anything.

**Trying both is sound here in a way trying key layouts was not**, and the distinction is
worth keeping: the oracle is an exact 32-byte digest match, so a wrong framing cannot
produce one without a preimage collision. A candidate search is only untrustworthy when
its oracle is "something plausible came out" — which is exactly what made the key-blob
search worthless and is why I refused that one.

**One ordinary run answers it**, on this account or the reference's, and needs nothing new:
if either framing matches, the run says which. If the reference implementation is to hand,
looking at whether it hashes the wrapper or its contents is cheaper still.

## Round R — what is missing before a join can be built

Taking stock of §6.7 step 5 against what exists. The **message layer of §6.9 is built** —
`SignedBlob` with the type-prefixed signing discipline, `make_voucher`, `make_peer`,
`make_join_request`, and `require_key_shares`. `CuttlefishEstablishRequest` remains
undefined in `cuttlefish.proto`, so no code here can build one, and that stays.

Two of step 5's five sub-steps are fully specified and I can build them now:

- **Signing a voucher** — done.
- **Re-sharing the view keys.** §6.7.0 gives the share signature's field order and its
  little-endian integers, and gives SFIES exactly. Creating a share is the inverse of the
  reader already written, and inverting a construction this precisely stated is not a guess.

Three are not, and each blocks the whole thing rather than degrading it.

### R1. How is a peer's `hash` computed?

`CuttlefishPeer.hash` is "the peer's identifier" and `Voucher.beneficiary` must equal it.
Nothing says how a client **derives** it for an identity it just generated. §5.1 observes
the related escrow label is `SHA256:` followed by base64 — a digest of *something*.

This is not searchable. A wrong peer id produces a voucher naming a peer that does not
exist, and the failure lands **after** `joinWithVoucher` has been sent, which is the one
call in this project that cannot be taken back.

What is needed: the exact input to that digest — which fields, in which encoding, in which
order — and whether the `SHA256:` prefix is part of the id or only of the escrow label.

### R2. What must `PeerStableInfo` assert?

Already §8 Q4, and it is now blocking rather than theoretical. Eighteen fields, all
optional on the wire, and a peer that signs an incomplete one **may be admitted and then
behave oddly** rather than be rejected — the worse failure, and one that would present
long after the irreversible step.

Specifically: which fields are required; what `frozenPolicyVersion`/`frozenPolicyHash` and
their flexible counterparts must contain; and whether `clock` starts at 0 or 1.

### R3. How is the new identity's escrow record enrolled?

Step 5.4 says to create a bottle for the new identity "sealed under the device passcode",
so the client is recoverable later. §6.9 gives `Bottle` and `OTBottle`, and §6.7 steps 2–4
give the key derivation and the seal — all invertible.

**What is missing is the enrolment**: §4 specifies the escrow proxy's *read* commands, and
writing a record is what makes the entropy recoverable by passcode at all. A bottle sent in
`joinWithVoucher` whose entropy was never escrowed produces a peer that is in the circle and
**cannot ever be recovered** — permanent, invisible, and exactly the residue the README's
rules exist to prevent.

### R4. What does a joining peer put in `PeerDynamicInfo`?

`includeds` is "peer ids this peer trusts". A peer joining a circle presumably includes its
sponsor and itself, but whether it must enumerate the whole circle, what `dispositions`
carries, and what `clock` starts at are unstated. Same failure shape as R2: plausibly
accepted, then wrong.

---

**What I am not doing.** Guessing any of these and sending it. §6.7's own warning is the
reason — this is the one call in the project that creates permanent account state, and
unlike the deletes there is no listing that would even show a mistake afterwards. The
account owner runs the first real join; I build and hand over, and I would rather hand over
four unanswered questions than a peer id I inferred.

**What I will build while these are open**: SFIES encryption and share creation (R's second
bullet), since they are specified and are reusable regardless of how R1–R4 resolve.

## Round S — enrolment is built, and three things about it are unsettled

§4.5 is implemented in `findmy/keychain/enrolment.py`: the pinned roots and their
fingerprint check, the club certificate's verification, both layers of the blob, the binary
metadata, and the two requests sharing one `transactionUUID`. `AsyncEscrowProxy` gained
`get_club_cert` and `enroll`. **Nothing has been sent** — as with the join, the account
owner runs the first real one.

Two things came out better than expected, and both are checks rather than assertions:

- **The inner message round-trips through the reader.** §4.5.1 describes it as a writer and
  §6.5 describes the same message as a reader, and the two were implemented from opposite
  directions without reference to each other. `unwrap_inner_blob(build_inner_message(…))`
  returns the record, which settles the section ordering, the iteration count's position in
  the header, the salt doubling as the CBC IV, and the PBKDF2 parameters all at once.
- **The verifier is checked by running the exchange it exists for**, against the same SRP
  library the recovery path uses, with the identity in the private-key hash as §6.3
  requires. A verifier computed with that flag the wrong way is accepted by the service and
  then fails every future recovery, indistinguishably from a wrong passcode.

The three digests are named per purpose (`_BLOB_DIGEST`, `_OAEP_DIGEST`,
`_INTEGRITY_DIGEST`) with the comment you asked for, and each is pinned by a test that
computes it independently rather than referring to the constant. Changing `_OAEP_DIGEST` to
SHA-256 — the plausible tidy-up — fails a test; I checked by making the change and watching
it go red rather than assuming the coverage was real.

**Enrolment refuses to run without the root certificates.** The fingerprints are carried;
the certificates are not, because this side has no way to obtain them that would not defeat
the point. `PinnedRoots.load` takes them from the caller, checks each against a fingerprint,
and cross-checks its serial number against the version — and `verify_club_certificate`
raises if none was supplied, rather than falling through to the system trust store. So the
last operational step before a first enrolment is obtaining four certificates.

### S1. Closed — §4.5.2 was wrong, and §5.1 had it right

The write side says `clientMetadata`, `timestamp` and `multipleICSC`. The read side, marked
**[observed]** on real records, says `ClientMetadata`, `com.apple.securebackup.timestamp`
and `SecureBackupUsesMultipleiCSCs`.

The service stores this plist verbatim and hands it back in `metadataList`, so these cannot
both be a description of the same client. Either Apple's own clients write the long
spellings and the reference implementation writes the short ones, or one of the two
sections has been normalised.

It matters concretely rather than cosmetically: a record enrolled by §4.5.2 would be one
that §5.1's own reader cannot describe — no device name, no serial, no date — and escrow
records are only ever seen through a listing like that one. A record nobody can identify is
a record nobody can safely delete.

**This is checkable offline, on data already held.** The account's twelve records include
some written by the export route the residue rules were written about. Decode each
`metadata` and report the key names: if any record carries `clientMetadata`, §4.5.2 is a
second real shape; if every one carries `ClientMetadata`, §4.5.2 is describing the
reference's spelling and a record written that way will be invisible in listings.

Meanwhile the writer follows §4.5.2 exactly and the reader accepts both, which is the right
way round — a tolerant reader costs one `or`, and a writer that hedges by sending both
spellings would put a guess into permanent account state.

### S2. Closed — padded, and for a better reason than the one I had

§4.5.1 says section 3 is "the SRP verifier" and does not say how the integer is rendered.
The natural big-endian encoding of `g^x mod N` is one byte shorter about **one time in
256**, and shorter still, more rarely.

Padded to the group's 256 bytes here, because that is what the service does in the other
direction — §6.2 observes `B` arriving as exactly 256 bytes.

Worth settling from the reference because of *when* it fails. A wrong choice enrols
successfully, and the record is only found to be unrecoverable when someone needs it, for
one user in 256, with a failure that reads as a wrong passcode. Nothing local catches it and
no amount of testing on one account would find it.

### S3. Closed — §4.5.3 now says, so the check is a refusal

§4.5.1 calls section 4 "the encrypted record" without saying what a record is. §6.5 unwraps
the same section, and §6.7 then reads `BottledPeerEntropy` out of the plist inside it — so
the shape is knowable from the read side, but only the one field is.

Implemented as: the caller supplies the bytes, and a plist that parses and carries no
`BottledPeerEntropy` logs a warning rather than being refused. Refusing would encode an
inference as a rule; saying nothing would let a record be enrolled that recovers
successfully and yields nothing usable — the same failure shape as joining with no key
shares, and permanent in the same way.

What would close it: whether the other fields observed in recovered material
(`SecureBackupIDMSData`, `DoubleEnrollmentPassword`, `BackupBagPassword`, the versions and
timestamp) are required by anything, or are what an Apple client happens to include.

### S4. Closed — `enroll` sends no certificate versions

§4.5's field table for `enroll` lists `blob`, `blobDigest`, `dsid` and `metadata`, and does
not mention `baseRootCertVersions`/`trustedRootCertVersions`. The club machinery is
plainly in play, since the blob is sealed to the club certificate, and §4.4 notes that
omitting them elsewhere leaves the club handler failing internally rather than saying what
is missing.

They are sent. Extra fields are ordinarily ignored and the failure mode of omitting them is
worse than of including them, but it is a choice made against the table rather than from it.

---

**Round S answered.** All four, and the code follows.

- **S1** — the three keys are now `com.apple.securebackup.timestamp`, `ClientMetadata` and
  `SecureBackupUsesMultipleiCSCs`, the last with a lower-case `i` in `iCSCs`. A test asserts
  the metadata's whole key set literally rather than checking a field survives a round trip:
  six keys in one dictionary spanning camelCase, reverse-DNS and PascalCase, none derivable
  from its neighbours, is not a shape any convention will reproduce. The reader still takes
  both spellings — records written under the earlier text exist, and a record this listing
  cannot describe is one nobody can safely delete — and the DEBUG counts stay.
- **S2** — padding kept, and the docstring now carries your argument rather than mine.
  "Right under both readings, wrong under one if omitted" is a reason that survives the
  observation being reinterpreted; §6.2's `B` was only ever evidence about what Apple emits.
- **S3** — `build_record` builds §4.5.3's three keys and generates the 72 bytes, and
  `require_usable` now refuses rather than warns. `enrol_record` takes the entropy and builds
  the record itself, which also removes the way a caller could have let the timestamp drift
  between the four places it appears.
- **S4** — dropped.

**Still open on my side**, unchanged: SFIES share creation, then assembling
`joinWithVoucher`. Enrolment cannot run at all until the four root certificates are obtained
and handed to `PinnedRoots.load` — the fingerprints alone verify nothing, which is deliberate.

## Round T — what a *writer* needs that the reading rules do not give

Taking stock again, now that enrolment is built. What remains before a join can be assembled
is two constructions, and both are inversions of readers already written — which is exactly
the situation where a specification written from the receiving side stops being enough.

Everything below is a question about the **write** direction. None of it is checkable
locally: each produces something Apple's side either accepts or silently rejects, and the
symptom of getting one wrong is a share that authenticates against nothing or a bottle that
cannot be opened — after `joinWithVoucher` has been sent.

### T1. Must a created share reproduce the `SFCiphertext` overrun?

§6.7.0 records that `SFCiphertext` carries the ciphertext followed by 113 bytes of
uninitialised heap — the producer sizes the buffer for all three parts and archives it
untrimmed. The reader here derives the trim from the other two members and discards it.

**A writer has to decide whether the overrun is part of the format.** Two readings, and
nothing on this side distinguishes them:

- Apple's reader trims the same way this one does, in which case a correctly-sized
  ciphertext is fine and the overrun is pure accident.
- Apple's reader subtracts `len(point) + len(code)` **unconditionally**, in which case a
  correctly-sized ciphertext arrives 113 bytes short, fails its tag check, and looks like a
  wrong key.

The second is not far-fetched. The overrun exists because *some* Apple code always writes
it, and a reader written against a writer that always overruns has no reason to check. If
it is the second, the fix is trivial — pad the member by that much with anything — but
padding on a guess is inventing a field, and not padding on a guess is a silent failure.

**This is the one that most needs an answer from the reference**, because it is the only
open question whose wrong answer is invisible in both directions.

### T2. What exactly does the archive have to be?

§6.7.0 already gives the three member names, the misspelled `Externa`, and that the point is
archived as `NSMutableData` while the other two are plain `NSData` — and it says outright
that a writer cannot be forgiving about the spelling. That is the hard half, and it is
answered.

What is not stated is the **envelope**: whether Apple's unarchiver requires the top-level
object to carry a `$class` naming `SFIESCiphertext` with its class hierarchy, or whether a
plain keyed archive holding three named members under any class is accepted. A reader that
looks up members by name — as this one does — never has to know.

### T3. Which values does a *created* `TlkShare` carry?

The reader takes `curve`, `epoch`, `version` and `poisoned` off the record and only uses
them to rebuild the signed data. A writer has to choose them. `poisoned` is presumably 0,
and `curve` presumably names P-384 — but the enumeration is not given, and §6.7.0 notes the
signed form of these integers is neither the message's declared width nor the record's.

Also unstated: the `TlkShare` message carries `service` and `keyId`, which the CloudKit
record does **not**. So both have to be sourced from somewhere else when creating one, and
§6.7.0's note that neither is on the record is a statement about reading.

### T4. What `keyType` goes in an `OTPrivateKey`?

`OTInternalBottle` holds `signingKey` and `encryptionKey`, each an `OTPrivateKey` of
`keyType` (1) and `keyData` (2). The reader tolerates whatever arrives and parses the key
material; a writer has to name the type. The enumeration is not in §6.9, and reading one
value off a real bottle would only prove that value is *a* valid one — the absence rule from
the README applies.

### T5. Who signs a bottle being created, and what does `escrowedSPKI` have to agree with?

§6.7 step 3 gives the verification: `escrowedKeySignature` under the escrowed signing key,
`peerKeySignature` under "the sponsoring peer's signing key", both over the raw serialised
`OTBottle`. Read in the create direction, the natural reading is that a bottle for a **new**
identity is signed by the escrowed key derived from its own entropy and by the **new** peer's
own signing key — the peer the bottle belongs to, not the sponsor that vouched for it.

That is an inference from a verification rule, which is the thing this round is about. Worth
one sentence either way, and the same sentence can settle whether §4.5.2's `escrowedSPKI` is
the public half of the HKDF-derived escrowed **signing** key — i.e. the same value as
`OTBottle.escrowedSigningKey` — which is what ties the escrow record to the bottle it
accompanies.

---

**What I will build while these are open**: nothing that depends on them. T1 and T2 gate
share creation and T4 and T5 gate the bottle, which between them are the whole of what is
left. The identity layer and enrolment are done, and the remaining blocker on enrolment is
not a specification question at all — it is the four root certificates, which have to be
obtained before `PinnedRoots.load` will accept anything.

---

**Round T answered.** All five, and one correction that matters more than any of them.

### Before T1 — §6.8.2's `PeerDynamicInfo` was wrong, and it is on the join path

**If anything here was built against "a joining peer sends `clock: 0` and nothing else",
rebuild it.** That was my answer to R4 and it describes the **`establish`** path — the one
this project must never take. I mapped the wrong branch onto the join, and the failure shape
is the one R2 and R4 were asked to avoid: a peer that is admitted while claiming to trust
nobody, discovered long after the irreversible call.

§6.8.2 now gives the join: sync, **copy the sponsor's `includeds`, `excludeds` and `clock`**,
fast-forward over any peer with a higher clock, add this peer's own id, then increment the
clock. The section also states the rule that governs step 3 — a peer not already trusted is
adopted only on a valid voucher from one that is — and keeps `clock: 0` where it does belong,
which is the reset a client applies when it finds itself outside the circle.

The related claim in the same section, that trust is asserted afterwards by `updateTrust`, is
also withdrawn. `updateTrust` carries a peer id and a set of shares; it is how an established
member hands view keys to *another* peer later, not the second half of a join.

### T1 — pad it, with zeros

The overrun is part of the format. Apple's reader subtracts `len(point) + len(code)`
unconditionally, so a correctly-sized ciphertext arrives 113 bytes short and fails its tag
check — your second reading, and it is the one that holds.

**Pad with zeros, not with anything.** The bytes Apple leaks there are uninitialised heap and
there is no reason to reproduce that half of the behaviour. §6.9.2 now says so, and says to
derive the pad length the same way the trim is derived rather than writing the constant.

### T2 — a plain dictionary, no `$class` for `SFIESCiphertext`

The top level is an ordinary keyed archive of a three-entry dictionary. Nothing declares the
class or its hierarchy. The only class that matters is the `NSMutableData` on the ephemeral
point, which is a property of the member rather than the envelope.

Which also explains the asymmetry you noticed: Apple's side finds the members **by name**, so
a missing class costs nothing and the misspelling costs everything.

### T3 — the values, and the two fields the record does not carry

`curve` is **4**, `epoch` is **1**, and `poisoned` and `version` are **omitted from the
message and signed as zero** — the signature input has seven parts and none may be skipped, so
building it by walking the populated fields produces a five-part digest that verifies nowhere.

`service` is the key's **zone name** and `keyId` is the key's **UUID**; both come from the
key message of §6.7.0 step 3, which is where the reader got them too.

`receiverPublicEncryptionKey` is base64 of the **uncompressed point**, not the SPKI — the one
place in this stage where a public key does not travel as DER.

And **a joining peer shares to itself**: `sender` and `receiver` are both the new peer's
identifier, wrapped to the new peer's own encryption key. It reads like a no-op and is not.
The keys were recovered from a different peer's shares, and Cuttlefish only counts a peer as
holding a view key when a share addressed to that peer says so.

### T4 — `keyType` is `1`

And the encoding `keyData` needs, which was equally unstated: the **uncompressed public point
followed by the private scalar**, 97 + 48 bytes on P-384. Note this is *not* the 64-byte
layout of §6.8.1 — that one is public-x-then-scalar with no point prefix and no y. Two
Apple key blobs, two layouts, in one stage.

### T5 — your inference is right, and so is the one you did not make

The bottle is signed by the peer it **belongs to**. §6.7 step 3's "sponsoring peer" is
accurate for a bottle you are reading, because the bottle you recover was created by the peer
that sponsors you; the general rule is the peer the bottle is for, which for every bottle this
project creates is this client.

`escrowedSPKI` is the same value as `OTBottle.escrowedSigningKey` — the DER SPKI of the
HKDF-derived escrow **signing** key. Which carries a constraint worth stating: the record and
the bottle have to come from **one** derivation, not from two calls that each generate
entropy.

### Three more things the write direction needs, none of which you asked

§6.9.1 and §6.9.3 now carry these; flagging them because a reader never had to know any of
them.

- **Every `SignedInfo` is ECDSA-SHA-384**, over the type-prefixed bytes. The share signature
  of §6.7.0 is **SHA-256**. Same stage, same message, different digest.
- **The bottle's seal uses a 32-byte IV.** Not 12, not 16. It is carried in
  `initializationVector` with the tag split off, as §6.9 already describes for reading.
- **`PeerPermanentInfo`'s values**: `epoch` 1, both keys DER SPKI, `machineId` is the Anisette
  `X-Apple-I-MD-M` header, `modelId` is the hardware model, and `creationTime` is in
  **milliseconds**. The blob is signed once at generation and those exact bytes are kept —
  the identifier is a digest of them, so re-encoding later is a different peer.

### And the order, which is new — §6.9.4

**Enrol the escrow record before calling `joinWithVoucher`.** The two failure modes are not
comparable: enrolment failing after a join leaves a peer nobody can ever recover, invisible to
every listing and out of reach of §7's deletion; joining failing after enrolment leaves a
record for a peer that does not exist, which is listed by §5 and deletable by §7.1. Permanent
and invisible against tidy-up.

§6.9.4 gives the full nine-step sequence, including the one recovery worth handling rather
than reporting — an enrolment that fails because a record already exists at that label is
resolved by deleting that label and enrolling again, **only** on an error the service itself
reports.

**Still open on your side after this**: nothing specification-shaped that I know of. The four
root certificates remain the blocker on enrolment, and they are the account owner's to obtain.

## Round U — the join is built, and one thing is still unnamed

Everything §6.9 describes now exists, and `AsyncKeychainSession.join` performs §6.9.4's
sequence. **Nothing has been sent** — the account owner runs the first real one.

Three things the answers changed rather than added, worth recording because each was live
code rather than a gap:

- **R4's dynamic info was implemented as written**, so this repo was carrying the
  `establish` shape on the join path. Replaced with §6.8.2's merge. Two of my first tests
  for it failed and the code was right: I had written scenarios where the peers offering
  trust updates were untrusted, and their updates were correctly ignored.
- **The share signature used 32-bit widths** for `version` and `poisoned`, from the earlier
  revision of §6.7.0's table. That is the failure the correction describes: both are zero on
  real shares, so the digest ran four bytes short and every share was skipped, which
  presents as a peer with nothing to give.
- **The voucher reason was 0**, the protobuf default, rather than §6.9.4's 1.

The round-trip tests are what I trust here, because in each case the reader was written
from the receiving side before the writer existed: a sealed bottle opens with nothing but
its entropy and the account id, and a created share's archive is one the §6.7.0 reader
trims correctly and decrypts. If the writer and reader disagreed about the overrun, or the
32-byte IV, or the point-then-scalar layout, neither would come back.

### U1. Closed — it returns the message I was already decoding

§6.9.4 step 9 says to apply the returned trust changes. No message is specified for the
response, and this is the one place where guessing has a known failure mode already
documented in this stage: decoding bytes as the wrong protobuf **does not error**, it
yields a message with every field empty, which reads as a circle with no changes rather
than as a parse that found nothing. §6.7.0's forty-one empty results are the same mistake.

So `JoinOutcome.response` hands the bytes back undecoded rather than pretending. What would
close it: whether the reply is a `FetchChangesResponse`, something join-specific, or an
envelope carrying one.

Not blocking — the join can be sent and its effect confirmed by re-syncing the directory,
which is a read the library already does.

### U2. Closed — it is that token, and it is a string at both ends

§6.9.4 step 8 says to set it "if one is held". The directory's sync token is **bytes** and
`restorePoint` is a **string**, so sending it means choosing an encoding that nothing
states. It is optional, so nothing is sent, and the cost of that is at most a fuller
response.

### U3. Closed — it returns the leaf only, so the roots stay bundled

Only `clubCert` is read. If the reply also carries the issuing chain, the four roots would
not need bundling at all: they could be fetched, fingerprint-checked and used, which
survives Apple issuing a 501. The response's shape is now logged at DEBUG — names and sizes
only, since the values are certificates — so one real call answers it.

The roots ship in the package meanwhile, defaulted rather than asked for, with the
fingerprint check on the bundled path exactly as on a caller's. Worth recording how they
reach a wheel: `setuptools-scm` packages what git tracks, so an untracked `.crt` is absent
from the built artefact while every test in a checkout passes. I found that by building one.

**What is left on my side**: nothing specification-shaped. The join is assembled, tested
offline, and unsent.

---

**U1 and U2 answered, and they were one question.** The reply is
`CuttlefishJoinWithVoucherResponse` carrying the same `CuttlefishChanges` as `fetchChanges`,
and its `syncToken` is the `restorePoint` a later call sends back.

Worth recording *how* I got this wrong, because the reasoning was sound and pointed the
wrong way. "Decoding as the wrong message yields every field empty rather than erroring" is
exactly §6.7.0's trap, and it is an argument for decoding as a message that **is**
specified — not for declining to decode. Applying it to a specified message meant
discarding both the trust changes the server reports and the token underneath them.

Three changes came out of it:

- `ChangeSet` is now `CuttlefishChanges`, and its `syncToken` is a **string** rather than
  bytes on the request and the response alike. The two share a protobuf wire type, so the
  mistake was invisible until the same value had to be sent as a string field — which is
  the only reason it ever surfaced.
- `PeerDirectory.updated` is the single place changes are folded in, so `fetchChanges`,
  `joinWithVoucher` and a future `updateTrust` cannot grow three readings of what a change
  is.
- A reply that does not decode now reports that the **join happened**: the peer and the
  record exist, only the changes and the token are lost, and re-reading the circle recovers
  both. The instinct on a decode failure is to retry the call, and retrying this one would
  join twice.

`updateTrust`'s messages are transcribed from §6.8.2's table and nothing calls them. That is
how an established member hands keys to another peer, which is a later job than joining.

**U3 is still the only open item**, and one real `get_club_cert` answers it.

## Round V — what one read-only run settled

`preflight_join.py` against a live account, writing nothing. Five things it answered, four
of which were open.

### V1. `get_club_cert` returns no chain — U3 closed, unhelpfully

    clubCert=1396B, dsid=11B, message=7B, status=1B, version=1

The leaf and four scalars. So fetching the roots and fingerprint-checking them at runtime is
not an option Apple offers, and this library carries them. The DEBUG line stays anyway,
because that is an answer with a shelf life rather than a fact.

### V2. Root 500 is the one in active use — so 2032 is a real deadline

The club certificate verified against **500 and none of the other three**. The issuer DN
shows the version in the X.520 `serialNumber` attribute (`2.5.4.5=500`), which is what makes
an issuer match select exactly one of four otherwise identical subjects.

That turns §4.5's note about 500's ten-year life into this library's own end date: 101 to
103 run to 2049 and are apparently not what is being issued against. Apple will move to a
root not in the bundled set before December 2032, and the failure that follows is enrolment
refusing with a message about certificates.

`verify_club_certificate` now warns a year out. Worth considering whether §4.5 should say
the same to other implementers — a hardcoded pinning set with a known expiry is a thing to
be told about in advance rather than discovered.

### V3. The signing construction verifies against Apple's own devices

**13/13 permanent infos, 12/12 vouchers, 13/13 signing keys parsing as DER SPKI.**

This is the first external check of §6.9.1's signing rule. Every other test of it signs and
verifies with the same code, where a wrong type prefix, a wrong digest or a wrong key
encoding agrees with itself perfectly. A blob Apple's device signed cannot, so this settles
the ASCII prefix with no separator, ECDSA over SHA-384, and DER SPKI as the key encoding —
by the same standard the identifier check meets.

Thirteen peers and twelve vouchers is not a gap. **The peer that established the circle
joined nothing and carries no voucher**, so exactly one unvouched peer is the expected
shape; its absence would have been the surprise. Counted rather than skipped now, so
"12/12" does not sit beside thirteen peers unexplained.

### V4. S1 confirmed on real data, 7/7

    ClientMetadata 7/7, clientMetadata 0/7,
    com.apple.securebackup.timestamp 7/7, timestamp 0/7

Every record uses §5.1's spellings and none uses the ones §4.5.2 originally carried. The
tolerant reader is now carrying a spelling that has never been observed anywhere — worth
keeping regardless, since a record written under the earlier text would be undescribable and
therefore undeletable, but it is unexercised code and should be understood as such.

### V5. The sync token really is a string

Two pages of change feed decoded with `syncToken` declared as `string` rather than `bytes`.
That is U2's correction meeting real data: had the token not been valid UTF-8, the parse
would have raised rather than quietly continuing.

---

**Nothing open on my side.** The join is built, checked as far as anything offline can check
it, and unsent. What remains untested is everything this client puts in its own blobs, which
by construction has no oracle short of sending one.

## Round W — the join went through

`join_circle.py` against the live account. **A peer, a bottle and an escrow record now
exist**, and the circle went from 13 peers to 14.

    club certificate verifies against pinned escrow root 500
    Enrolling escrow record com.apple.icdp.record.SHA256:… -- permanent
    Joined as SHA256:…; the reply carried 1 change(s)
      keys re-addressed to it: 21
      the circle now holds 14 peer(s)

What that settles, which is most of the write direction at once: §4.5's enrolment (the
blob's two layers, the club-certificate seal, the metadata, the record), §6.9.1's permanent
info, §6.8.2's stable and dynamic info, the voucher, §6.9.3's bottle, §6.9.2's twenty-one
shares, and §6.9.4's order. Apple accepted all of it in one call, which is the only oracle
any of it had.

It also confirms **U1 on real data**: the reply decoded as
`CuttlefishJoinWithVoucherResponse`, its single change was an `add`, applying it through the
same path `fetchChanges` uses produced 14 peers, and a sync token came back as a string.

### W1. Closed — the new record recovers, and its keys decrypt beacons

Acceptance is not usability, and two things remain unproven:

- **That the new peer holds the view keys.** Twenty-one shares were accepted; nothing has
  yet read a key back *as* the new peer. Cuttlefish counting a peer as holding a view key
  is the thing the re-sharing exists for, and the only proof is reading one.
- **That the bottle is recoverable.** This is the important one. An enrolled record whose
  bottle does not open is exactly the residue §4.5 warns about -- permanent, invisible in
  every Apple interface, and discovered whenever somebody actually needs it. Nothing in the
  join proves the record round-trips; only a recovery does.

Both are read-only checks and both should be run now rather than in a year:

1. `preflight_join.py` again -- it will report 14 peers, and the identifier and signature
   checks now include **this client's own peer**. If Cuttlefish reports back a hash equal to
   the one derived locally, that is Apple echoing this project's identifier derivation for
   an identity it did not produce.
2. `trace_key_recovery.py`, choosing the new record by its serial and giving the passcode
   chosen during the join. That exercises the bottle, the escrow blob and the shares from
   the reading side, which is the direction none of them has been tested in.

If the second fails, the peer is permanent residue and the record is deletable by §7.1 --
worth knowing immediately, while what created it is still in front of somebody.

---

**W1 answered: both checks passed against the record the join created.**
`trace_key_recovery.py` recovered from `FINDMYPY0001` and reached the service key, and
`fetch_beacons_from_icloud.py` then decrypted accessory records with it.

That closes the loop in the direction acceptance could not:

- **The bottle opens.** The record round-trips, so it is a live recovery path rather than
  the permanent invisible residue §4.5 warns about. It also proves the one-derivation
  constraint held end to end -- the entropy the record escrowed derived the keys that
  unsealed the bottle this project built.
- **The new peer holds the view keys.** Twenty-one shares were not merely accepted; they
  were read back *as* the new peer, which is what re-sharing exists to achieve and the only
  thing that demonstrates Cuttlefish counts the peer as holding them.
- **And the keys work.** Stage 5 decrypted with what came out, so the whole flow runs from
  an Apple ID to accessory locations through an identity this project created, with no Mac
  anywhere in it.

Every construction in the write direction has now been exercised from both sides by
something other than itself.

### W2. What remains untested, and why it cannot be tested yet

- **Staying current across key rotation**, which is the actual reason to be a member rather
  than a recoverer. Nothing demonstrates it until the keys rotate.
- **`updateTrust`**, transcribed from §6.8.2's table and unbuilt. It is how a member hands
  view keys to another peer, and the first thing to reach for when the above matters.
- **The content choices Apple tolerates but may not require** -- the policy hashes, `epoch`
  1, the machine id. They were accepted and the resulting peer functions, which is as strong
  as this gets short of long-term operation.

One consequence worth noting for anyone tidying up afterwards: the new record is **viable**,
so §7.1's deletion refuses it by default. That is the safeguard working as designed -- it is
now a live recovery path for a real client, and removing it costs exactly what removing any
other viable record costs.
