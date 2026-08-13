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
| **A2** joining after recovery | **Still open.** Stage 3 §6.7 remains unwritten; it is the last unspecified section in the set. |
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

### B1. Stage 5 §5: the KDF's fixed input is not specified

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

### B2. Stage 5 §4 step 2: the RFC 6637 parameters are not specified

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

## C — assumed field numbers

Stage 4 §3 gives field numbers for most messages and omits them for these. Each was assumed; a
wrong assumption means protobuf silently routes the data to the unknown-field set and the caller
sees an empty result, so the implementation logs which field numbers actually arrived whenever a
response decodes as empty. **One live `record/sync` settles all of them.**

| Message | Assumed | Where the document stops |
| --- | --- | --- |
| `RetrieveChangesResponse` | 1 = repeated `recordChange`, 2 = `syncContinuationToken` | §3.5 gives `RecordChange`'s own layout but not its envelope's |
| `RetrieveZoneChangesResponse` | 1 = repeated `changedZone`, 2 = token, 3 = status | §3.5 names the members only |
| `ChangedZone` | 1 = `identifier`, 2 = `changeType`, 3 = `deleteType` | as above |
| `Zone` | 1 = `zoneIdentifier`, 2 = `etag` | §3.2.3 pins only `protectionInfo` (3) and `recordProtectionInfo` (6) |
| `ExtensionError` | 1, 2, 3 | §3.2.2 names the three members |
| the name wrapper | `name` at 1 | §3.6 says "a message wrapping a `name` string" |
| `Header.deviceLibraryVersion` | a string | §3.3 gives the value `1970`, which could be either |
| `ResponseOperation.operationCost` | int64 | §3.2.2 names it without a type |
| `RetrieveChangesRequest.requestedFields` | repeated string | §3.5 names it without a type |

**One of these is riskier than the rest.** §3.6 describes a record's `type` and a field's
`identifier` as "a message wrapping a `name` string", while §3.5 describes `RecordChange.recordType`
as "the record's type name" — which reads like a bare string. A bare string and a wrapper message
are both length-delimited on the wire, so declaring the wrong one does not degrade, it throws and
takes the whole response with it. All three are declared as `bytes` and resolved at read time
instead. **Worth stating explicitly in the document which they are**, because a reader will
otherwise reasonably declare a message and lose an afternoon.

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
