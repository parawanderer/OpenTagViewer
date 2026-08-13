# Stage 3 — iCloud Keychain trust circle

Specification of how a client joins the user's iCloud Keychain trust circle, which is what makes
the PCS keys of Stage 5 reachable and therefore what makes the accessory records of Stage 4
readable.

Read [README.md](./README.md) first. In particular: **implement from this document alone.**

> **The read-only half is verified against a live account on 2026-08-13** — host derivation,
> transport, error envelope, and the record schema of §5, all by a probe written from this
> document. Values from that run are marked **[observed]**, and several corrected what reading
> the reference had suggested.
>
> **Everything from §6 onward is unverified**: recovery, joining the circle, enrolling and
> deleting. The sequence, cryptography and message layouts are all specified; none has been run. Those are derived by reading implementations whose authors report them working.
>
> It is also the stage that **needs the user's device passcode** and **writes to their account**.
> Everything before it was read-only; this is not. Treat the residue rules of §7 as part of the
> specification rather than as advice.

---

## 1. What this stage is for

Find My accessory records are encrypted. Their keys live in the user's iCloud Keychain, which is
shared between the devices the user has explicitly trusted. To read the keys, this client has to
become one of those trusted devices.

Apple's mechanism for admitting a device that cannot be approved by tapping "Allow" on an
existing one is **escrow recovery**: the user's keychain material is escrowed in a record
protected by a device's screen-lock passcode, and anyone who knows that passcode can recover it.

That is why this feature needs a passcode it never uses for anything else, and it is the honest
answer to "does this remove the need for an Apple device": it removes the need to *have* one, not
the need for one to have *existed*. An Apple ID whose keychain circle has never contained a
device has nothing to recover from.

## 2. Two services, joined by an identifier

This stage talks to two unrelated-looking services, and understanding the split makes everything
else legible:

| Service | Transport | Holds |
| --- | --- | --- |
| **Escrow proxy** | plain HTTPS, plist bodies | the escrow *records* — the passcode-protected material and its metadata |
| **Cuttlefish** | CloudKit function invocation | the trust *circle* — peers, bottles, keys, who trusts whom |

"Cuttlefish" is the server side of Octagon, Apple's trust-circle protocol. "Bottle" is its name
for a sealed piece of key material a peer can be reconstituted from.

Listing what the account has requires **both**: the escrow proxy knows the human-readable
metadata, Cuttlefish knows which bottles are actually usable, and they are joined on the bottle
identifier. Neither alone answers "what can I recover from".

### 2.1 Cuttlefish rides on CloudKit

Cuttlefish is not a REST service. It is invoked as a **server-side function through a CloudKit
container**:

| Property | Value |
| --- | --- |
| Container id | `com.apple.security.keychain` |
| Bundle id | `com.apple.security.cuttlefish` |
| Database | private |
| Environment | `Production` |

Calls are made with the function-invoke operation — field `1101` of `RequestOperation`, per
[Stage 4 §3.2](./04-cloudkit.md) — naming the target `Cuttlefish`, the method, and a protobuf
body. The response body is protobuf too.

### 2.2 The invoke message

Field `1101` of `RequestOperation` carries a `FunctionInvokeRequest`:

| # | Field | Type | Value |
| --- | --- | --- | --- |
| 1 | `service` | string | `Cuttlefish` |
| 2 | `name` | string | the method, e.g. `fetchViableBottles` |
| 3 | `parameters` | bytes | **the method's own request message, serialised** |

The response arrives at field `1101` of `ResponseOperation` as a `FunctionInvokeResponse`:

| # | Field | Type |
| --- | --- | --- |
| 1 | `serializedResult` | bytes — **the method's own response message, serialised** |

So there are **two layers of protobuf**: the CloudKit envelope, and inside `parameters` and
`serializedResult` an entirely separate message that CloudKit neither parses nor validates. A
malformed inner message therefore produces a successful CloudKit operation carrying a payload that
fails to decode, not a CloudKit error.

### 2.3 `fetchViableBottles`

The method [§5](#5-listing-what-the-account-has--read-only) step 2 needs.

**Request:**

| # | Field | Type | Value |
| --- | --- | --- | --- |
| 1 | `filter` | uint32 | `1` |
| 2 | `metrics` | bytes | empty |

**Response:**

| # | Field | Type | Meaning |
| --- | --- | --- | --- |
| 1 | `valid` | repeated `EscrowData` | **the usable bottles** |
| 2 | — | — | a legacy repeat of the same type; ignore |
| 3 | `partial` | repeated `EscrowMeta` | bottles that are not fully usable |

**`EscrowData`:**

| # | Field | Type | Meaning |
| --- | --- | --- | --- |
| 1 | `id` | string | **the join key** — matches an escrow record's `label` (§5 step 3) |
| 2 | `bottle` | message | the sealed material |
| 4 | `meta` | message | metadata |

> Note field 3 is skipped, and that `EscrowMeta` is an **empty message** in every schema examined
> — it carries nothing, so a `partial` entry conveys only that a bottle exists in that state.

**The endpoint is a different service from the record database**:

```
POST https://gateway.icloud.com/ckcoderouter/api/client/code/invoke
```

derived from `cloudKitCodeGatewayUrl` rather than `cloudKitDatabaseGatewayUrl`, both of which
[Stage 4 §2.3](./04-cloudkit.md) returns. The CloudKit headers, envelope and authentication are
otherwise identical to any other operation.

> **So Stage 4 is a hard prerequisite for Stage 3, not merely a convenient one.** The CloudKit
> transport, its container-open step, its headers and its envelope are all needed here. The
> ordering in this document set — specifying and verifying Stage 4 before writing Stage 3 — was
> the correct dependency order and not just risk management.

A second container, `com.apple.securityd` over the same `com.apple.security.keychain` container
id, exists for keychain item operations proper.

## 3. Prerequisites

| Value | From |
| --- | --- |
| **PET** | Stage 1. Used as the escrow proxy's HTTP Basic password — see the warning below. |
| The account's email | Stage 1 `acname`, the Basic username |
| A working CloudKit container | Stage 4 §2, for every Cuttlefish call |
| `com.apple.gs.icloud.escrow.auth` | Stage 1 issues this token; whether this stage needs it, or only the PET, is unestablished |
| **The user's device passcode** | the user, at the moment of recovery. Never stored. |
| Anisette headers | the same identity as every other stage |

> **The PET is spent by Stage 2, and this is an ordering constraint on the whole flow.** Stage 2
> uses it as its HTTP Basic password and then replaces it with the service tokens it returns, so
> an implementation that follows Stage 2 as written has **no PET left** by the time this stage
> begins — and it expires in about five minutes regardless.
>
> So Stage 3 needs either a **second, fresh authentication** whose PET is retained rather than
> spent, or Stage 2 amended to keep a copy. This is not a detail of Stage 3; it changes how
> Stages 1 to 3 fit together, and an implementation that discovers it late will have to restructure.

## 4. The escrow proxy

```
POST <escrow-host>/escrowproxy/api/<command>
```

> **[observed] The derivation below is confirmed.** `p<N>-escrowproxy.icloud.com` answered a
> request from an account on partition 24 with a well-formed service response. The escrow proxy
> is partitioned like every other per-account host, and deriving the host from the CloudKit
> partition works.

**Deriving the host is the first problem, and the protocol does not hand it over.**

The intended source appears to be a key named `escrowProxyUrl`, nested somewhere inside the
MobileMe delegate's configuration — the reference searches its whole configuration tree
recursively for it, which is itself a sign that its position is not fixed.

> **[observed] It is not there.** The delegate response for this client contains no configuration
> at all beyond a `protocolVersion` — see [Stage 2 §6](./02-mobileme-delegate.md). Independently,
> the reference exporter reports the same thing and falls back to a **hardcoded**
> `p97-escrowproxy.icloud.com`.

That hardcoded value is a bug waiting to happen, and the reason is visible in
[Stage 4 §2.3](./04-cloudkit.md): `p97` is a **partition prefix**. iCloud shards accounts across
numbered partitions, every per-account service host is named `p<N>-<service>.icloud.com`, and the
account observed for these documents is on a different partition entirely.

**So derive it.** Opening the CloudKit container returns URLs such as
`p<N>-ckdatabase.icloud.com`; take the partition number from any of them and construct:

```
https://p<N>-escrowproxy.icloud.com:443
```

**[observed]** This works. A client should still fail legibly rather than mysteriously if the
host does not answer, since the derivation is a pattern rather than a documented rule. Whether
another partition's proxy would also serve the account is untested and does not need to be.

### 4.1 Errors

A failure returns a plist with `success`, `errorCode` and `errorMessage`. **[observed]** The
messages are specific and worth surfacing verbatim — `Wrong command sent: get_records` named the
exact mistake. Check `success` before reading anything else.

### 4.2 Transport

| Aspect | Value |
| --- | --- |
| Authentication | HTTP Basic: the account **email**, and the **PET** as password |
| `Content-Type` | `application/x-apple-plst` |
| Body | an XML property list |
| `User-Agent` | `com.apple.sbd/638.100.48 CFNetwork/<version> Darwin/<version>` |
| `X-Mme-Client-Info` | the client-info string of Stage 1 §2.2, bundle `com.apple.AuthKit/1 (com.apple.sbd/638.100.48)` |
| `Accept` | `*/*` |
| `Accept-Language` | `en-US,en;q=0.9` |
| `X-Apple-I-Locale` | `en_US` |
| `x-apple-i-device-type` | `1` |

Plus all the Anisette headers.

`sbd` is *secure backup daemon* — Apple's internal name for this mechanism, and the reason
escrow records are called secure-backup records throughout. Note the unusual content type:
`application/x-apple-plst`, not the `text/x-xml-plist` used by Grand Slam.

### 4.3 Commands

> **Each command is named twice, in two different spellings, and both must be right.** The URL's
> last path segment is lower_snake_case; the body's `command` field is SCREAMING_SNAKE_CASE, and
> it is *not* simply the uppercase of the path. Sending the path spelling in the body is rejected
> with `Wrong command sent: <value>` — **[observed]**, and it is the failure this document
> originally caused by describing the two as one thing.

| Command | URL path segment | Body `command` value | Purpose |
| --- | --- | --- | --- |
| Get records | `get_records` | `GETRECORDS` | **list escrow records** — read-only |
| Get club certificate | `get_club_cert` | `GETCLUB` | fetch the certificate chain used when enrolling |
| SRP init | `srp_init` | `SRP_INIT` | begin passcode-authenticated recovery |
| Recover | `recover` | `RECOVER` | complete it and obtain the escrowed material |
| Enroll | `enroll` | `ENROLL` | **create** an escrow record |
| Delete | `delete` | `DELETE` | **destroy** an escrow record |

Note the irregularity: `get_club_cert` becomes `GETCLUB`, dropping a word, while `srp_init`
becomes `SRP_INIT`, keeping its separator, and `get_records` becomes `GETRECORDS`, losing its.
There is no rule to derive one from the other — the pairs must be tabulated.

Only `get_records` is free of consequences. `enroll` and `delete` both modify the account, and
`delete` is destructive to something a real device may depend on.

### 4.4 Request body

A dictionary. Fields vary by command; the ones common to all:

| Key | Type | Meaning |
| --- | --- | --- |
| `command` | string | the command's **body spelling** — see the table above, not the URL segment |
| `label` | string | which record class is being addressed — see below |
| `transactionUUID` | string | a fresh uppercase v4 UUID per request |
| `userActionLabel` | string | a free-text description of *why*, for Apple's logs |
| `version` | integer | `1` |

and, per command: `blob`, `blobDigest`, `metadata`, `dsid`, `silentAttempt`,
`baseRootCertVersions`, `trustedRootCertVersions`.

**`label` is overloaded**, and which meaning applies depends on the command:

| Command | `label` means |
| --- | --- |
| `get_records` | a **record class** — `com.apple.securebackup.record` |
| `get_club_cert` | a record class — `com.apple.icdp.record` |
| `srp_init`, `recover`, `enroll`, `delete` | **one specific record**, identified individually |

A specific record's label has the form `com.apple.icdp.record.<peerId>` — the class, then the
identifier of the peer the record belongs to.

> **A record's label is not its `bottleID`, and the two must not be substituted for each other.**
> The label identifies the *escrow record*; `bottleID`, found inside the record's metadata, is a
> separate **UUID** identifying the bottle. They are different strings with different shapes.
> Everything addressed to the escrow proxy takes the **label**.

> **`userActionLabel` is a free-text string that Apple keeps.** The reference sends descriptions
> of the operation being performed. It is not authentication and nothing checks it, but it is
> written into someone else's audit trail, so it should describe what is actually happening and
> should not impersonate a first-party Apple process.

## 5. Listing what the account has — read-only

This is the part worth building first: it shows the user what exists, creates nothing, and needs
no passcode.

**Step 1 — escrow metadata.** `get_records` with label `com.apple.securebackup.record`.

**[observed]** The response envelope carries `status`, `message`, `version`, `dsid` and
`metadataList`. Check `status` and surface `message`; the list is the payload. `metadataList` is
an array of:

| Key | Meaning |
| --- | --- |
| `label` | **the record's identifier, and the join key** — see below |
| `metadata` | base64 of an XML plist: the record's descriptive fields |

> **Join on `label`, not on `bottleID`.** The escrow proxy's `label` is the same value as the
> Cuttlefish bottle's `id` (§2.3), and that pairing is what step 3 matches. The `bottleID` inside
> the decoded metadata is a **different** value — a UUID for the bottle itself — and nothing
> requires it to equal the label. Reporting a mismatch between them is reasonable
> diagnostics; treating either as a substitute for the other is not.

**Step 2 — viable bottles.** Cuttlefish `fetchViableBottles`, with a filter value of `1` and an
empty metrics list. The response carries a list of valid bottles, each with an id.

**Step 3 — join them.** Match each bottle's id against a metadata entry's `label`. A bottle
without metadata cannot be described to the user; metadata without a viable bottle cannot be
recovered from. **Report both mismatches rather than silently dropping them** — they are the
signal that this specification has drifted from what Apple returns.

### 5.1 What the metadata contains

Decoding the base64 plist gives:

| Key | Meaning |
| --- | --- |
| `ClientMetadata` | a dictionary holding `device_name`, `device_model`, `device_model_class` |
| `serial` | the device's serial number |
| `build` | its OS build |
| `com.apple.securebackup.timestamp` | when the record was escrowed |
| `bottleID` | the bottle's own identifier |
| `escrowedSPKI` | the escrowed public key information |
| `passcodeGeneration` | which generation of the device's passcode protects it. **[observed]** camelCase, and present on only the newest records — 2 of 12 on the observed account. |
| `SecureBackupUsesMultipleiCSCs` | a legacy flag, from the iCloud Security Code era |

**This is a copy of a device's identity**, which is why there is no separate escrow UI in any
Apple interface — see the [README](./README.md). Present it to the user as device name, model,
serial and escrow date, because those are the fields they can match against their own device
list.

> ### [observed] Escrow records outlive the devices that created them
>
> On the account these documents were written against, the device list showed **one** iMac Pro
> while the escrow proxy held **eight** `iMacPro1,1` records — phantoms left by an older
> macOS-VM export route whose device entries had since been removed.
>
> **Removing a device from the account does not remove its escrow record.** The device entry is
> the visible artefact; the escrow record is the durable one, and it accumulates in the one place
> Apple provides no interface to inspect.
>
> Two consequences, and they point in opposite directions:
>
> - **The residue rules matter more here than for the device registration**, not less. An
>   uncleaned escrow record is permanent as far as the user is concerned, because they cannot
>   see it to clean it up.
> - **The list a user is shown before any deletion will contain records they no longer recognise**
>   — devices they have sold, wiped or removed years ago. That makes "delete the one you don't
>   recognise" actively dangerous advice, and is why deletion must confirm against the serial of
>   a specific record rather than inviting a judgement call.

**Treat the schema as unstable — [observed], it genuinely is.** Of twelve records on one
account, eleven had the device shape above and one had a different shape entirely:
`BackupKeybagDigest`, `ClientMetadata` and a timestamp, with **no** serial, build, `bottleID` or
`escrowedSPKI`. `passcodeGeneration` appeared on only two.

So there is more than one kind of record under this label, and a client that assumes the device
shape will fail on the others. Decode defensively, report what could not be understood, and never
discard a record silently. A record without a `bottleID` cannot be a recovery candidate and
should be filtered out rather than treated as broken.

## 6. Recovering — the passcode step

Recovery is an **SRP exchange in which the device's passcode is the password**. That is why a
passcode is required: it is not a credential Apple checks, it is the secret that decrypts the
escrowed material, and Apple cannot recover it either.

**The passcode is used twice** — once as the SRP password, and again as a PBKDF2 input to unwrap
the innermost blob (§6.5). Both uses are essential; neither substitutes for the other.

### 6.1 The KeyVault message framing

Both escrow blobs use a framing that appears nowhere else in this protocol. Given a header length
*H* and a section count *S*:

| Offset | Content |
| --- | --- |
| 0 | 4 bytes, skipped |
| 4 | the header, *H* bytes |
| 4 + *H* | *S* offsets, each a 4-byte big-endian integer |

Each section then lives at `(H + 4 + (S + 1) × 4) + offset`, and begins with its own 4-byte
big-endian length followed by that many bytes.

> Note the `S + 1` in the base calculation: there is **one more offset than there are sections**,
> the last marking end-of-data. Computing the base from *S* rather than *S* + 1 puts every
> section four bytes out and produces garbage that looks like a decryption failure.

### 6.2 Begin the exchange

Send `srp_init` (§4.3) with `label` set to the chosen record's **label** — its own identifier from
the `metadataList` entry, *not* its `bottleID` — and `blob` set to the base64 of the SRP client's
public value **A**.

SRP parameters match [Stage 1 §4.1](./01-authentication.md): the RFC 5054 2048-bit group,
SHA-256, and a 32-byte client secret.

The response carries `respBlob`, `dsid`, and an optional `clubTypeID`. Parse `respBlob` with
*H* = 24 and *S* = 3:

| Section | Contents |
| --- | --- |
| 0 | a request identifier, echoed back in §6.3 |
| 1 | the SRP **salt** |
| 2 | the server public value **B** |

The 24-byte header is two 32-bit values followed by a 16-byte request id.

### 6.3 Compute the proof

> **This is standard SRP-6a, unlike Stage 1.** Stage 1 §4.6 documents three deviations for Grand
> Slam; **none of them apply here.** The identity *is* included in the private-key hash, and the
> password is the passcode **directly** — not stretched through PBKDF2 first as `s2k` requires.
> The same SRP code serves both only if it is parameterised, and getting this backwards produces
> an authentication failure indistinguishable from a wrong passcode.

The identity is the **`dsid` string from the `srp_init` response**, not the Apple ID and not the
`adsid` from Stage 1.

Then build the response blob: take the 24-byte header from §6.2 and change two fields — set the
first 32-bit value to **165**, and the second to **2** if `clubTypeID` is 1, otherwise **0**.
Frame it as a KeyVault message with two sections: the request identifier from section 0, **sized
to 20 bytes**, followed by the SRP proof **M1**.

Send that as `blob` with the `recover` command, same `label` and same transaction id.

### 6.4 Unwrap the outer blob

Parse the response `respBlob` with *S* = 3 and *H* = **40 if `clubTypeID` is 1, otherwise 24**.

Verify the server's proof **M2** against section 0 before going further.

The version is a 4-byte big-endian integer at **offset 4 within the header**, and it selects the
cipher:

| Version | Cipher | Key | IV / nonce | Ciphertext |
| --- | --- | --- | --- | --- |
| 0 | AES-256-CBC | the SRP session key | section 1 | section 2 |
| 2 | AES-256-GCM, **16-byte nonce** | the SRP session key | section 1 | section 2 |
| 1 | **unknown** — refuse rather than guess | | | |

### 6.5 Unwrap the inner blob

The plaintext from §6.4 is itself a KeyVault message, with *H* = 16 and *S* = 6. Its header is
four 32-bit values, of which **the third is a PBKDF2 iteration count**.

```
derived = PBKDF2-HMAC-SHA256(passcode, salt = section 1, iterations = header[2], length = 16)
material = AES-128-CBC-decrypt(derived, iv = first 16 bytes of section 1, data = section 3)
```

Note that section 1 serves as **both** the PBKDF2 salt and, in its first 16 bytes, the CBC
initialisation vector. The result is the escrowed keychain material.

> **Sections 0, 2, 4 and 5 are unaccounted for.** Six sections are framed; three are used. What
> the others carry is unestablished.

### 6.6 Certificate pinning

The escrow service is authenticated against a **pinned set of certificates**, referenced by the
`baseRootCertVersions` and `trustedRootCertVersions` fields of §4.4 — the reference offers
versions 101, 102, 103 and 500. The certificates themselves must be obtained; this document does
not carry them.

**This matters more here than elsewhere.** The material crossing this exchange is the user's
entire keychain, and the passcode protecting it is short enough to brute-force offline if an
attacker obtains the blob. Do not disable verification, and do not fall back to the system trust
store because pinning was inconvenient.

### 6.7 From recovered material to a trusted peer

The §6.5 plaintext is not the keychain. It is a **bottled peer** — the sealed identity of a
device that was already in the circle. Recovering it lets this client impersonate that device
just long enough for it to vouch for a *new* identity of our own, which is what actually joins.

That indirection is the whole design, and it is why the sequence has five steps rather than one.

**Step 1 — parse the recovered blob.** It is a property list with one field that matters: a
**bottled peer entropy** blob. Everything below derives from it plus the account's `adsid`.

**Step 2 — derive the bottle's three keys.** All by **HKDF-SHA384**, with the **`adsid` as salt**
and the entropy as the input keying material:

| Key | HKDF info string | Output |
| --- | --- | --- |
| Symmetric | `Escrow Symmetric Key` | 32 bytes |
| Signing | `Escrow Signing Private Key` | 56 bytes → an EC scalar |
| Encryption | `Escrow Encryption Private Key` | 56 bytes → an EC scalar |

The two EC keys are on **P-384**, and the 56-byte output is converted to a scalar by **FIPS 186-4
B.5.1**, the extra-random-bits method — generate more bits than the order needs and reduce, rather
than retrying.

> Note the salt: the account identifier, not a random value. Two accounts recovering the same
> entropy would derive different keys, which is the point.

**Step 3 — verify before trusting.** The bottle carries public keys and signatures, and all four
checks should pass before anything is decrypted:

- the derived encryption key's public part equals the bottle's escrowed encryption key
- the derived signing key's public part equals the bottle's escrowed signing key
- the bottle's own signature verifies under the escrowed signing key, **SHA-384**
- the sponsoring peer's signature over the bottle verifies under that peer's known key

A mismatch on either of the first two means the passcode produced the wrong entropy. The third and
fourth mean the bottle is not what it claims.

**Step 4 — open the bottle.** **AES-256-GCM**, keyed by the symmetric key from step 2, with the
IV and authentication tag carried alongside the ciphertext in the bottle structure. The plaintext
holds the sponsoring peer's **signing and encryption private keys**, again P-384.

At this point the client holds a second peer's identity. It has not joined anything.

**Step 5 — vouch, then join.**

1. Generate a **new identity of our own** — a fresh peer, with its own keys.
2. Using the recovered peer, **sign a voucher** for the new identity. This is the recovered
   device saying "I trust this one".
3. **Fetch the recovered peer's key shares** — the TLK shares that carry the actual keychain view
   keys. **If it has none, stop**: joining would succeed and yield no keys, which is a worse
   outcome than failing.
4. **Create a bottle for the new identity**, sealed under the *device passcode*, so that this
   client is itself recoverable later. This is the escrow record §7 discusses.
5. Call Cuttlefish **`joinWithVoucher`** with the new peer, its bottle, the voucher's sponsor, and
   the re-shared keys.

The response carries trust changes to apply to local state. A client with no voucher would call
**`establish`** instead, creating a new circle rather than joining one — **not what this project
wants**, and calling it by mistake resets the account's trust rather than joining it.

> **`establish` and `joinWithVoucher` differ by one branch and are catastrophically different.**
> The first is for a device forming a circle where none exists; the second for joining one that
> does. An implementation that falls back from the second to the first on error will silently
> destroy the user's existing trust circle. There is no reason this project should ever call
> `establish`.

**Step 6 — sync the views.** With trust established, sync the `Manatee` and `ProtectedCloudStorage`
views (§6.8). Only then does [Stage 5](./05-pcs-decryption.md) have keys.

> **Passcode handling.** Read it, use it twice, discard it. Never written to disk, never logged,
> never in diagnostics, and not retained in memory past the exchange. It is the most sensitive
> value this project touches: it is the key to the user's whole keychain, and unlike an Apple ID
> password it cannot be rotated without physical access to the device.

### 6.9 The messages `joinWithVoucher` carries

All are protobuf, and all travel inside the invoke envelope of §2.2 — so they are serialised into
a `bytes` field that CloudKit does not inspect.

**`CuttlefishJoinWithVoucherRequest`** — the request itself:

| # | Field | Type |
| --- | --- | --- |
| 1 | `restorePoint` | string — the client's sync token, if it has one |
| 2 | `peer` | `CuttlefishPeer` |
| 3 | `bottle` | `Bottle` — the escrow record being created for the new identity |
| 4 | `shares` | repeated `TlkShare` |
| 5 | `keys` | repeated `ViewKeys` |

`CuttlefishEstablishRequest` is the same message with the fields in a **different order** — peer 1,
bottle 2, keys 3, shares 4 — and no restore point. Another reason not to confuse the two (§6.7).

**`CuttlefishPeer`** — everything except `hash` is a signed blob:

| # | Field | Type |
| --- | --- | --- |
| 1 | `hash` | string — the peer's identifier |
| 2 | `permanentInfo` | `SignedInfo` |
| 3 | `stableInfo` | `SignedInfo` |
| 4 | `dynamicInfo` | `SignedInfo` |
| 5 | `voucher` | `SignedInfo` |

**`SignedInfo`** is the wrapper that makes this work — field 1 `info` (bytes, a serialised message)
and field 2 `signature` (bytes). **The signature covers the serialised bytes, not the parsed
message**, so a client must keep the exact bytes it signed and send those; re-encoding before
sending invalidates the signature even if the content is identical.

**`PeerPermanentInfo`** — inside `permanentInfo.info`:

| # | Field |
| --- | --- |
| 1 | `epoch` (uint64) |
| 2 | `signingKey` (bytes) |
| 3 | `encryptionKey` (bytes) |
| 4 | `machineId` (string) |
| 5 | `modelId` (string) |
| 6 | `creationTime` (uint64) |

**`PeerStableInfo`** — inside `stableInfo.info`. Larger, and mostly optional:

| # | Field | | # | Field |
| --- | --- | --- | --- | --- |
| 1 | `clock` | | 10 | `flexiblePolicyVersion` |
| 2 | `frozenPolicyVersion` | | 11 | `flexiblePolicyHash` |
| 3 | `frozenPolicyHash` | | 12 | `userControllableViewStatus` |
| 4 | `secrets` (repeated bytes) | | 13 | `custodianRecoveryKeys` (repeated) |
| 5 | `osVersion` | | 14 | `secureElementIdentity` |
| 6 | `deviceName` | | 15 | `walrus` |
| 7 | `recoverySigningPublicKey` | | 16 | `webAccess` |
| 8 | `recoveryEncryptionPublicKey` | | 18 | `isInheritedAccount` (bool) |
| 9 | `serialNumber` | | | |

Note field 17 is absent. `deviceName` and `serialNumber` are how this peer appears to the user, so
the labelling rules of the [README](./README.md) apply here as they do to the device registration.

**`PeerDynamicInfo`** — inside `dynamicInfo.info`, and the actual statement of who trusts whom:

| # | Field | Type |
| --- | --- | --- |
| 1 | `clock` | uint64 |
| 2 | `includeds` | repeated string — peer ids this peer trusts |
| 3 | `excludeds` | repeated string — peer ids it has removed |
| 4 | `dispositions` | repeated `PeerDisposition` |
| 5 | `preapprovals` | repeated string |

**`Voucher`** — inside `voucher.info`, and remarkably small for what it does:

| # | Field | Type |
| --- | --- | --- |
| 1 | `reason` | uint32 |
| 2 | `beneficiary` | string — the peer being vouched **for** |
| 3 | `sponsor` | string — the peer vouching |

Three fields. The signature on the enclosing `SignedInfo`, made with the recovered peer's signing
key, is the entire weight of the claim.

**`Bottle`** — the escrow record created for the new identity:

| # | Field | Type |
| --- | --- | --- |
| 2 | `bottle` | bytes — a serialised `OTBottle` |
| 3 | `escrowedSigningKey` | bytes |
| 4 | `escrowedKeySignature` | bytes |
| 5 | `peerKeySignature` | bytes |
| 6 | `peerID` | string |
| 7 | `bottleID` | string |

Field 1 is absent. `escrowedKeySignature` and `peerKeySignature` are the two signatures §6.7 step 3
verifies when recovering.

**`OTBottle`** — inside `bottle.bottle`:

| # | Field | Type |
| --- | --- | --- |
| 1 | `peerID` | string |
| 2 | `bottleID` | string — a UUID |
| 8 | `escrowedSigningKey` | bytes |
| 9 | `escrowedEncryptionKey` | bytes |
| 10 | `peerSigningKey` | bytes |
| 11 | `peerEncryptionKey` | bytes |
| 12 | `ciphertext` | message: 1 `ciphertext`, 2 `authenticationCode`, 3 `initializationVector` |

**Fields 3 to 7 are reserved and must not be used.** The AES-256-GCM unsealing of §6.7 step 4
takes its three inputs from field 12; note the tag is carried separately from the ciphertext and
must be appended before decryption, or supplied to a detached-tag interface.

The plaintext is an **`OTInternalBottle`**, which is two fields and both are at surprising numbers:
`signingKey` at **3** and `encryptionKey` at **4**, each an `OTPrivateKey` of `keyType` (1) and
`keyData` (2). There is no field 1 or 2.

**`TlkShare`** — one per view key being handed to the new peer. Note that almost everything is a
**string**, including things that look like binary:

| # | Field | Type | | # | Field | Type |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `service` | string | | 7 | `receiverPublicEncryptionKey` | **string** |
| 2 | `curve` | uint64 | | 8 | `sender` | string |
| 3 | `epoch` | uint64 | | 9 | `signature` | **string** |
| 4 | `keyId` | string | | 10 | `version` | uint32 |
| 5 | `poisoned` | uint32 | | 11 | `wrappedKey` | **string** |
| 6 | `receiver` | string | | | | |

**`ViewKeys`** — sent when *establishing* keys rather than receiving them, so this project sends an
empty list: field 1 `service`, then `topLevelKey` (2), `classA` (3), `classC` (4) and
`oldTopLevelKey` (5), each a `ViewKey` of `keyId` (1), `topLevelKeyId` (2), `keyNumber` (3),
`key` (4) and a fifth field whose name is misspelled in every schema examined.

### 6.8 What this stage must actually deliver

[Stage 5 §2](./05-pcs-decryption.md) makes the output contract concrete, and it is narrower than
"join the circle":

| Required | Detail |
| --- | --- |
| The **`Manatee`** keychain view, synced | the view holding Find My's Protected Cloud Storage keys |
| The **`ProtectedCloudStorage`** view, synced | synced alongside it |
| The service key within `Manatee` | the item labelled `com.apple.ProtectedCloudStorage-com.apple.icloud.searchparty` |
| Items readable as dictionaries | key lookup matches on the `acct` attribute, which holds base64 of a compressed EC public key |

So success for this stage is not "we are in the circle" but "we can enumerate `Manatee` and find
an item whose `acct` matches a key referenced by a record's protection structure". That is the
thing to test against.

> **Passcode handling.** It must be read, used, and discarded. Never written to disk, never
> logged, never included in diagnostics, and not retained in memory past the exchange. It is the
> single most sensitive value this project ever touches: it is the key to the user's entire
> keychain, and unlike an Apple ID password it cannot be rotated without physical access to the
> device.

## 7. What this stage creates, and the rules for it

Joining the circle enrolls **an escrow record of this client's own**, via `enroll`. That is a
second artefact on the user's account, after the device registration of Stage 1 §13.

The [README](./README.md) sets out the standing rules. Applied here:

- **One record, created once.** Never a new one per run.
- **It inherits the labelling for free.** A record created with the identity of Stage 1 §2.2
  carries the same recognisable serial, so it identifies itself by the same means the device
  entry does — provided the identity is consistent, which it must be anyway.
- **It is insurance, not access.** Its purpose is to let the client re-establish trust if it
  loses local state. Once trust is established and the peer identity is stored locally, the
  record can be deleted — at the cost of needing the user's passcode again should that local
  state ever be lost. This is the opposite lifetime to the device registration, which is kept
  precisely because it is what keeps ordinary access working.
- **Deletion must confirm against the serial.** The list contains the user's *real* devices, and
  every entry looks structurally alike. Destroying the wrong record destroys that device's
  ability to recover its keychain. Require the user to re-enter the serial of the record being
  deleted; never accept a list position.
- **Never delete without listing first**, and never offer a "clean up all" action.

## 7.1 Deleting an escrow record

Deletion is **two calls**, both `delete`, in this order:

| Order | `label` |
| --- | --- |
| 1 | `<label>.double` — a companion record |
| 2 | `<label>` |

> **Correction: an earlier version of this section said `<bottleID>`. That was wrong** — these
> take the record's **label**, the same value used for `srp_init` and `recover` and the same value
> §5 joins on. Deleting by `bottleID` addresses nothing.

Both carry only `command`, `label`, `transactionUUID`, `userActionLabel` and `version`. The
`.double` suffix names a paired record that exists alongside the main one; deleting only the main
record appears to leave it behind.

> **The protocol requires nothing but the label.** No password, no blob, no proof that the caller
> could have recovered the record. Any client holding a valid PET can delete **any** escrow record
> on the account, including one belonging to a real device that the user still depends on.

Two things follow, and they are the whole design of this operation:

**The client-side confirmation is the only protection that exists.** There is no server-side
check to fall back on. So: list first, show the full identifying detail — device name, model,
serial, escrow date — require the user to re-enter the **serial** of the record being deleted,
and never accept a list position. Never offer a bulk or "clean up all" action.

**Requiring recovery before deletion is a choice, not a constraint.** The reference exporter
demands a successful recovery with the record's password before it will delete, which is a sound
guard against deleting the wrong thing — but it also makes it *impossible* to remove records
whose passcode is no longer known. That matters: [§5](#5-listing-what-the-account-has--read-only)
observes that escrow records outlive their devices, so an account can accumulate records for
hardware that is long gone and whose passwords nobody remembers. An implementation that copies
the guard uncritically cannot clean those up.

**The recommendation** is to separate the two cases:

- **Deleting this application's own record** — identified by the serial it was created with, per
  Stage 1 §2.2 — needs no recovery guard. The client knows it made it, and it knows its own
  escrow password.
- **Deleting anything else** is a maintenance action, should be presented as one, and should
  require the serial typed out in full. Whether to additionally require recovery is a judgement
  about whether the user is more likely to be cleaning up known debris or about to destroy a real
  device's recoverability.

## 8. Open questions

This stage has more of them than the rest of the set combined, and they are load-bearing.

1. ~~Is the escrow host really `p<N>-escrowproxy.icloud.com`?~~ **[observed] Yes.** Derived from
   the account's CloudKit partition, and the service answered. Ruled out as sources along the
   way: the MobileMe delegate config (carries no configuration at all), and `ckAppInit`'s
   `values` array — that is a per-environment endpoint table for the container being opened, one
   row each for `PRODUCTION` and `SANDBOX`.
2. **What is the exact SRP exchange for recovery?** §6 is a sketch. The parameters, the
   derivation from the passcode, and the response format all need specifying before anything can
   be implemented.
3. **How are the peer's signed blobs constructed, exactly?** §6.9 enumerates every message, but
   two things it does not settle: which fields of `PeerStableInfo` a client is *required* to
   populate, and what the policy version and hash fields must contain. A peer that signs an
   incomplete stable info may be admitted and then behave oddly rather than being rejected.
4. **Which token authenticates what?** Stage 1 issues `com.apple.gs.icloud.escrow.auth`, but the
   escrow proxy is authenticated with the PET. Whether that token is needed at all is unknown.
5. **Can bottles be listed without any prior trust state?** §5 is presented as read-only and
   safe, and the reference performs it before joining — but it also constructs a keychain client
   first, and what that construction requires is not established. If listing turns out to need
   local trust state that only exists after joining, the safe read-only step is not available.
6. **Does an account with Advanced Data Protection enabled behave differently?** ADP changes how
   keychain material is protected, and Stage 1 §7.3 already notes an unverified claim that it
   breaks the two-factor configuration endpoint. It is likely to matter more here.
7. **What happens on an account whose keychain circle is empty?** Presumably no viable bottles
   and no way in. That case needs detecting and explaining to the user, not failing obscurely.
