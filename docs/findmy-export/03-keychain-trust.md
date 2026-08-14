# Stage 3 — iCloud Keychain trust circle

Specification of how a client joins the user's iCloud Keychain trust circle, which is what makes
the PCS keys of Stage 5 reachable and therefore what makes the accessory records of Stage 4
readable.

Read [README.md](./README.md) first. In particular: **implement from this document alone.**

> **The read-only half is verified against a live account on 2026-08-13** — host derivation,
> transport, error envelope, the record schema of §5, and the escrow-to-Cuttlefish join, by two
> independent implementations written from this document. Values from that run are marked **[observed]**, and several corrected what reading
> the reference had suggested.
>
> **[observed] Deletion (§7.1) is verified** against a live account, including the corrected
> label addressing.
>
> **Recovery, joining the circle and enrolling remain unverified** — §6 onward. The sequence,
> cryptography and message layouts are specified; none has been run. Those are derived by reading implementations whose authors report them working.
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
> — an individual `partial` entry carries nothing at all.
>
> **But the count is meaningful.** [observed] A live call returned 3 `valid` and 8 `partial`, and
> the join of §5 produced exactly 3 recoverable records and 8 that had metadata but no viable
> bottle. So `partial` is not noise to discard: its length should equal the number of described-
> but-unrecoverable records, and a discrepancy means the join has gone wrong. **Use it as a
> cross-check**, and log both counts.

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

A specific record's label has the form:

```
com.apple.icdp.record.SHA256:<base64>
```

the class, then the identifier of the peer the record belongs to. **[observed] That peer
identifier is a `SHA256:`-prefixed base64 digest** — not a UUID, not a serial number, and not
anything a client chooses. It is the same value `CuttlefishPeer.hash` carries (§6.9), which is why
a peer is addressed by a hash rather than a name.

Two consequences: a label cannot be constructed from anything the user can see, so a record must
always be located by listing rather than by building its label; and **two records for the same
physical device will have entirely different labels** if the client regenerated its peer identity
between them.

> **A record's label is not its `bottleID`, and the two must not be substituted for each other.**
> The label identifies the *escrow record*; `bottleID`, found inside the record's metadata, is a
> separate **UUID** identifying the bottle. They are different strings with different shapes.
> Everything addressed to the escrow proxy takes the **label**.

### 4.5 Enrolling a record

**This is the write, and it is what makes an escrowed bottle recoverable at all.** A bottle sent in
`joinWithVoucher` whose entropy was never enrolled produces a peer that is in the circle and can
**never** be recovered — permanent, invisible in every Apple interface, and precisely the residue
§7's rules exist to prevent. Enrol before joining, not after.

It is two requests sharing one `transactionUUID`.

**Step 1 — `get_club_cert`.** Label `com.apple.icdp.record` — the *class*, not a specific record.
Send `baseRootCertVersions` and `trustedRootCertVersions` as the same list of acceptable versions,
**[observed]** `[101, 500, 103, 102]`. The response carries `clubCert`, base64 of a DER
certificate.

> **Verify that certificate against a pinned set of roots before using it.** It is the key the
> user's escrow blob is encrypted to, so accepting an unverified one hands the material to
> whoever supplied it. A chain that does not verify is a refusal, not a warning — and the
> verification belongs **before** anything is encrypted to that key, not after.

**The four roots**, which a client carries rather than fetches. All are self-signed, all share the
subject `CN=Escrow Service Root CA, OU=Apple Certification Authority, O=Apple Inc., C=US`, and the
version number is the certificate's **serial number**:

| Version | Not before | SHA-256 fingerprint |
| --- | --- | --- |
| 101 | 2015-05-16 | `5644C142208DD4BF7AD770902F70D6730B8571164FD874D9AE5168807B32F766` |
| 102 | 2016-05-13 | `D4EAA88170B8AEE18FCEFF68698310D4C2AB4CEB48179D99206A53AC73E8A4EB` |
| 103 | 2021-05-13 | `FDBA6F3365D617B9EB799D2F48851B1A85C62CF366C36797E539B3C4C2C3BDC6` |
| 500 | 2022-12-09 | `654BFB656D80A715559034C7FE08E637B335E770C84EC05CD90475E8BBC59EB0` |

> **The four are carried alongside this document**, in [`escrow-roots/`](./escrow-roots/), as DER
> (`.crt`, what a loader wants) and PEM (`.pem`, for reading). Each has been checked against the
> fingerprint above.
>
> **The fingerprints are the useful half, and that is what makes the delivery route irrelevant.** A
> root is trustworthy once it matches one of these and not before, so it needs no trusted channel —
> and equally, a pinning set assembled without checking is pinning to whatever arrived. Keep the
> check on the bundled copies too: a bundled certificate that fails its own fingerprint is a
> corrupted install, and should refuse exactly as loudly as a wrong one from a caller.
>
> **This is library data, not user input.** A client that asks whoever installs it to go and find
> four certificates gets them pasted in unverified, which is worse than shipping them, because it
> looks like someone made a security decision when nobody did.
>
> Note 500 expires in **2032**, while the three older roots run to 2049. Whichever set is carried,
> a client should say clearly when a chain fails to verify rather than falling back to the system
> trust store — the system store holds Apple's public roots, and these are not those.

**Step 2 — `enroll`.** Label is the **specific record's** label, per §4.4. Fields:

| Key | Content |
| --- | --- |
| `blob` | base64 of the escrow blob, §4.5.1 |
| `blobDigest` | base64 of the blob's **SHA-1** — not SHA-256 |
| `dsid` | the numeric account id |
| `metadata` | base64 of a **binary** property list, §4.5.2 |

> **The certificate versions go on `get_club_cert` only.** `enroll` does not carry
> `baseRootCertVersions` or `trustedRootCertVersions`; they asked which roots the *client* would
> accept, and that question was answered by the first request. Sending them again is sending a
> field to a write that a working client does not send.

#### 4.5.1 The escrow blob

Two nested layers, both in the KeyVault framing of §6.1.1.

**The inner message** — header `unk1=160`, `unk2=0`, `rounds=10000`, `unk3=10`, then six sections
in this order:

| # | Section | Note |
| --- | --- | --- |
| 1 | the **dsid**, as ASCII | padded to a 16-byte footprint |
| 2 | a random **64-byte salt** | |
| 3 | the **SRP verifier** | computed over the dsid as identity, the password, and that salt, using the 2048-bit group and SHA-256. **Zero-pad it to 256 bytes** |
| 4 | the **encrypted record** | §4.5.3, AES-128-CBC under PBKDF2-HMAC-SHA256(password, salt, **10000** iterations, 16 bytes), IV = the salt's **first 16 bytes** |
| 5 | the **label**, as ASCII | padded to an 80-byte footprint |
| 6 | the **timestamp**, as ASCII | `YYYY-MM-DD HH:MM:SS` — a space rather than a `T`, and no zone. Padded to a 24-byte footprint |

> The verifier is what later makes `srp_init` and `recover` (§6) work with this password. The
> encrypted record and the verifier are derived from the **same** password and the **same** salt,
> by two different constructions — do not reuse one derivation for the other.

**The outer message** — header `unk1=161`, `unk2=1`, `unk3=0`, `unk4=0`, `unk5=10`, then:

| # | Section |
| --- | --- |
| 1 | HMAC-SHA-256 of section 2, under a fresh random 32-byte key |
| 2 | a random 16-byte IV, followed by the inner message encrypted with **AES-256-CBC** under a fresh random 32-byte key |
| 3 | **RSA-OAEP** of those two random keys concatenated — the AES key then the HMAC key — to the club certificate's public key, with **SHA-1** as both the OAEP and MGF1 digest |
| 4 | SHA-256 of the certificate's public key, as **PKCS#1** DER |
| 5 | SHA-256 of the **inner message** |

> Note the digest asymmetry, all three of which are load-bearing and none derivable: the blob is
> announced with **SHA-1**, the RSA padding uses **SHA-1**, and the two integrity hashes inside are
> **SHA-256**.

#### 4.5.2 The metadata

A binary property list, base64-encoded:

> **Correction. These key names disagreed with §5.1, and §5.1 was right.** Three of them were
> written here as an implementation's internal field names rather than as what goes on the wire.
> The two sections describe one plist, the service stores it verbatim, and a record written under
> the wrong spellings is one §5.1's listing cannot describe — no name, no serial, no date — which
> is the only place these records are ever seen.

| Key | Content |
| --- | --- |
| `serial`, `build` | the client's own, as in [Stage 1 §2.2](./01-authentication.md) |
| `passcodeGeneration` | **[observed]** `13` |
| `bottleID` | the bottle's **UUID** — see §4.4 on why this is not the label |
| `escrowedSPKI` | the escrowed **signing** public key |
| **`com.apple.securebackup.timestamp`** | the same string as the blob's section 6, in the same `YYYY-MM-DD HH:MM:SS` form |
| **`ClientMetadata`** | a nested dictionary, below. **Capital C** |
| **`SecureBackupUsesMultipleiCSCs`** | `true`. Note the lower-case `i` in `iCSCs` |

> Only three keys are ordinary camelCase. The rest are a reverse-DNS key, a PascalCase one and a
> `SecureBackup`-prefixed one with irregular capitalisation — the same
> [strata](./README.md) pattern as everywhere else here. Copy them exactly; none is derivable.

`clientMetadata` describes the device to a human reading the record listing of §5 — which is the
only place any of it is ever seen:

| Key | |
| --- | --- |
| `device_name`, `device_model`, `device_model_version`, `device_model_class`, `device_platform` | what §5.1's listing shows the user |
| `device_mid` | the machine identifier |
| `SecureBackupMetadataTimestamp` | the timestamp again |
| `SecureBackupUsesNumericPassphrase` | whether the password is all digits |
| `SecureBackupNumericPassphraseLength` | its length if so, otherwise `0` |
| `SecureBackupUsesComplexPassphrase` | `1` |

> **This metadata is how a user recognises their own record**, so it is worth filling honestly.
> §5.1 records that escrow records outlive the devices that made them and appear in no Apple
> interface; a record labelled with an empty device name is one the user cannot identify when
> deciding what to delete.

#### 4.5.3 What the encrypted record holds

Section 4 of the inner message is a **binary property list** before encryption, with three keys:

| Key | Content |
| --- | --- |
| `BottledPeerEntropy` | **72 random bytes**. Everything the bottle yields derives from this |
| `com.apple.securebackup.timestamp` | the same timestamp again |
| `BackupVersion` | the string `1` |

> **`BottledPeerEntropy` is the whole point of the record**, and §6.7's recovery reads exactly this
> key back out. A record enrolled without it recovers successfully and yields nothing — which is
> worse than failing, because the escrow proxy will report a usable record for as long as the
> account exists.
>
> The entropy is **generated, not derived**: it is fresh randomness this client keeps nowhere else,
> and enrolling is what makes it recoverable at all.

> ### Generate it outside enrolment, not inside
>
> **The same bytes must seal the bottle that `joinWithVoucher` carries.** Enrolment makes the
> entropy recoverable by passcode; the bottle is what that entropy opens. A record escrowing
> different entropy from the bottle sent alongside it enrols cleanly, joins cleanly, and recovers
> months later to **a peer that does not exist** — with no step in between that could notice.
>
> So the entropy is an input to enrolment, never something enrolment invents. A function that
> generates its own is one a caller cannot make agree with the bottle, and the failure is
> unobservable until someone needs the recovery.

> **[observed] The other fields recovered material carries are not required.** §6.7 reports
> `SecureBackupIDMSData`, a `DoubleEnrollmentPassword` and version, a `BackupBagPassword`, a backup
> version and a timestamp coming back from real records. A record enrolled with the three keys
> above is recoverable and yields its entropy — so those are what an Apple client happens to
> include, not what the service or the recovery requires. Do not synthesise them.

> **PascalCase and a reverse-DNS key in the same three-key dictionary**, and the timestamp key is
> the same reverse-DNS spelling as §4.5.2's. Two of the three do not follow the convention of
> either neighbouring structure.

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

### 5.2 Records count runs; serials count devices [observed]

A live listing showed twelve records whose serials were **not** distinct: one serial appeared three
times, another twice, and two more once each.

Three records for one serial is not a pair under any reading. What it fits is a client that
**regenerated its peer identity on every run while reusing a fixed serial** — the serial being the
thing the client declares about itself, the peer identity being the thing it was supposed to
persist. Each run minted a new peer, each new peer escrowed a new record, and the serial stayed
put.

So when counting:

| To count | Group by |
| --- | --- |
| escrow **runs** | the record, or its peer digest |
| claimed **devices** | the **serial** |

Reporting a record count as a device count overstates the position, and grouping by peer identity
does not fix it — only the serial does.

> **This is the residue rule failing, observed in the wild.** The
> [README](./README.md) says identity state must never be silently regenerated; these records are
> what happens when it is. And the escrow list is where the evidence accumulates rather than the
> device list, because **nothing ever removes an escrow record** — so it preserves a run-by-run
> history of a mistake that the device list would have partly hidden.

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

## 5.3 The peer directory

Two verifications later in this stage need to look up a peer's public keys by its identifier, and
nothing described so far provides that. The directory comes from Cuttlefish:

```
Cuttlefish  fetchChanges
```

| Message | # | Field |
| --- | --- | --- |
| Request | 1 | `syncToken` — omit on a first call |
| Response | 1 | `changes`: `syncToken` (1) and repeated `change` (2) |
| `change` | 3 | `add` — a `CuttlefishPeer` |

A `change` may carry other kinds at other field numbers; **only `add` at field 3 builds the
directory**, and anything else is skipped. Each added peer carries its `hash` — the identifier used
everywhere else — and a `permanentInfo` whose `info` decodes to a `PeerPermanentInfo` (§6.9)
holding **`signingKey` at field 2** and `encryptionKey` at field 3. That signing key is what the
checks below verify against.

> **This is a paging loop, not a single call.** One request does not return the circle. Loop:
>
> 1. Call `fetchChanges` with the current token — **absent** on the first call.
> 2. If `changes` is **empty**, store the returned token and **stop**. That is the only
>    termination condition.
> 3. Otherwise add every peer found, take the returned `syncToken`, and go again.
>
> A client that issues one request and reads its result **will often see nothing**, because the
> first page need not carry peers. An empty directory after a single call is not evidence that the
> circle is empty — it is the expected outcome of not looping.

**Keep the final `syncToken`** and pass it next time; the feed is incremental, and a client that
always starts from nothing re-reads the whole circle on every run.

**Handle a rejected token.** If a call fails with a CloudKit error whose description is
`.changeTokenExpired`, the stored token is no longer valid — someone has reset the circle. Discard
the token **and the accumulated directory**, then start again from no token. Treating this as a
fatal error strands a client that could simply resynchronise.

> **This is a prerequisite, not an optimisation, and it is needed twice:**
>
> - [§6.7 step 3](#step-3--verify-before-trusting) verifies the **sponsoring peer's signature**
>   over the recovered bottle. That peer is looked up here.
> - [§6.7.0 step 1](#670-fetching-the-recovered-peers-key-shares--and-why-joining-may-be-unnecessary)
>   verifies each **key share's sender**. Likewise.
>
> A client without the directory cannot perform either check. Skipping them is not a small
> weakening: an unverified bottle is one an attacker who could serve a response may have chosen,
> and an unverified share is key material of unknown origin being trusted to decrypt the user's
> data. **Fetch the directory first**, and treat a peer that is genuinely absent from it as a
> reason to reject that share or bottle rather than to proceed.

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
| 0 | **the total message length**, 4 bytes big-endian |
| 4 | the header, *H* bytes |
| 4 + *H* | *S* + 1 offsets, each 4 bytes big-endian, **relative to the start of the body** |
| 4 + *H* + (*S*+1)×4 | the body |

Each section lives at `body_start + offset` and begins with its own 4-byte big-endian length,
followed by that many bytes.

> There is **one more offset than there are sections**, the last marking end-of-body. Computing
> the body's start from *S* rather than *S* + 1 puts every section four bytes out and produces
> garbage that looks like a decryption failure.

**[observed]** A real `srp_init` response: prefix `00000180` = 384 bytes, *H* = 24, three sections
of 8, 64 and 256 bytes. The arithmetic checks: body starts at 24 + 4 + 16 = 44, the sections with
their length prefixes occupy (4+8) + (4+64) + (4+256) = 340, and 44 + 340 = **384**. If a decoder's
computed total does not equal the prefix, it has the framing wrong and should say so rather than
proceeding.

### 6.1.1 Writing one

Reading and writing are not symmetrical, and this is the step where that bites.

1. Lay out the **body** first: each section is `be32(length) ‖ data`, concatenated. Record each
   section's offset from the body's start as you go.
2. Append **one more offset**, equal to the finished body's length.
3. Emit: a 4-byte placeholder, the header, the *S* + 1 offsets, then the body.
4. **Overwrite the placeholder with the total length** of everything written.

> **A section may be padded to a fixed footprint, and then its length prefix and its footprint
> disagree.** Where §6.3 asks for a section "sized to 20 bytes", it means: build
> `be32(length) ‖ data` as usual, then **zero-pad the result until it occupies exactly 20 bytes**.
> For an 8-byte value that is 4 + 8 = 12 bytes of content followed by 8 zero bytes. The padding is
> **appended**, never prepended, and the fixed footprint is a constant the service expects rather
> than anything derivable from the data.
>
> The declared length stays **8**, not 20 — a reader takes the length from the prefix and ignores
> the padding — but the **offsets must account for the full 20**, or every later section is
> misplaced. Sending the value unpadded, or padded without the inner length prefix, produces a
> message the server rejects without saying why.

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
| 0 | an **exchange token** — echoed back in §6.3. **[observed] 8 bytes.** |
| 1 | the SRP **salt**. **[observed] 64 bytes.** |
| 2 | the server public value **B**. **[observed] 256 bytes**, matching the 2048-bit group. |

The 24-byte header is two 32-bit values followed by a **16-byte transaction id**.

> **There are two identifier-like values here and they are not interchangeable.** The header's
> 16-byte transaction id is carried back **unchanged, inside the header**. The 8-byte exchange
> token in section 0 is what §6.3 re-sends **as a section**. Padding 16 bytes to 20 looks like the
> more natural operation and is the wrong one; the value that gets padded is the 8-byte token.

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

**[observed]** The value received in that first field is **164**, so the response is the request's
value plus one. Both readings — a literal 165, or an increment — fit what has been seen; the
reference sends a literal 165.
Frame it as a KeyVault message (§6.1.1) with **exactly two sections, and no others**:

| Section | Contents |
| --- | --- |
| 0 | the **8-byte exchange token** from §6.2 section 0, in a **20-byte footprint** |
| 1 | the SRP proof **M1**, an ordinary section |

The first is built as §6.1.1 describes: its own 4-byte length prefix holding **8**, then the eight
bytes, then **eight zero bytes appended** to bring the footprint to twenty. The declared length
stays 8. Why twenty is not established — it is simply the size the service expects.

The header is the same 24 bytes received in §6.2 with only its two 32-bit fields changed; **the
16-byte transaction id is carried back untouched.**

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
- `escrowedKeySignature` verifies under the **escrowed signing key**, **ECDSA over SHA-384**
- `peerKeySignature` verifies under the **sponsoring peer's signing key** from the directory of
  §5.3, also **SHA-384**

> **Both signatures cover the same data: the raw serialised `OTBottle` bytes** — the value of the
> `bottle` field of §6.9, exactly as received, with nothing prepended and no re-encoding. This is
> the simplest signed-data construction in the stage, and the only one that is just a field's
> bytes.

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

### 6.8.2 Building the identity a join sends

Three values a joining client must produce for itself, none of which is derivable from the message
layouts of §6.9. Each fails **after** `joinWithVoucher` has been sent.

#### The peer identifier

```
peerId = "SHA256:" ‖ base64( SHA-256( permanentInfo.info ‖ permanentInfo.signature ) )
```

where both parts come from the **signed `PeerPermanentInfo`** of §6.9 — its serialised payload
bytes followed by its signature bytes, concatenated, and nothing else.

> **The `SHA256:` prefix is part of the identifier**, not decoration on the escrow label. It is
> what `CuttlefishPeer.hash` carries and what `Voucher.beneficiary` must equal, and it is the same
> string that appears in §5.1's escrow labels.
>
> **This is checkable before anything is sent.** A client can recompute it for every peer already
> in the directory and confirm each matches the `hash` that peer reports. If they do, the
> derivation is right; if not, nothing has been written. Do that first — it converts the one
> irreversible call in this stage into an ordinary one.

#### `PeerStableInfo`

Eighteen fields, all optional on the wire, and an incomplete one is **admitted and then behaves
oddly** rather than refused. What a real client sends:

| Field | Value |
| --- | --- |
| `clock` | **the highest `clock` among all peers in the circle, plus one** — so a first peer sends 1, not 0 |
| `frozenPolicyVersion` | `5` |
| `frozenPolicyHash` | `SHA256:O/ECQlWhvNlLmlDNh2+nal/yekUC87bXpV3k+6kznSo=` |
| `flexiblePolicyVersion` | `20` |
| `flexiblePolicyHash` | `SHA256:OIzjC3WyLGrM8GAd/EyIfVzTJdYmcGoKPFdQeWeRZTY=` |
| `secrets` | empty |
| `osVersion` | this client's OS string |
| `deviceName` | may be empty |
| `serialNumber` | the client's serial, as in [Stage 1 §2.2](./01-authentication.md) |
| `userControllableViewStatus` | `1` |
| `isInheritedAccount` | `false` |

Everything else is omitted.

> **The two policy hashes are constants, not something to compute.** They are the digests of
> Apple's own trust policy documents, and a client asserts which policy version it is speaking
> rather than deriving anything. Send them verbatim; a wrong value here is not detectable locally.

#### `PeerDynamicInfo`

> **This section replaces an earlier answer that was wrong.** It previously said a joining peer
> sends `clock: 0` and nothing else, and that trust is asserted afterwards by `updateTrust`. That
> is the **`establish`** path — the one this project must never take — and sending it on a join
> produces a peer that is admitted while claiming to trust nobody, which is precisely the
> "accepted, then behaves oddly" failure this section exists to prevent. What follows is the join.

A joining peer **inherits its sponsor's trust and then adds itself**. It does not invent a trust
set, and it does not send an empty one.

1. **Sync first.** The peer directory of §5.3 must be current, because everything below reads it.
2. **Copy the sponsor's dynamic info** — the recovered peer that signed the voucher. Take its
   `includeds`, its `excludeds` and its `clock` verbatim as the starting point.
3. **Fast-forward over any peer with a higher `clock`.** Take them in ascending `clock` order, and
   for each one: adopt every id in its `includeds` not already held, then apply its `excludeds` —
   removing each from `includeds` if present before adding it — and take its `clock` as the
   current one.
4. **Add this peer's own id to `includeds`** if it is not already there.
5. **Increment `clock` by one**, so the info sent is newer than anything it was derived from.

> **A peer offering a trust update is not automatically believed.** In step 3, a peer that is not
> already in `includeds` is only adopted if it carries a voucher whose sponsor this peer already
> trusts, whose signature verifies under that sponsor, whose `beneficiary` is the peer presenting
> it, and whose beneficiary is not in `excludeds`. Otherwise its update is ignored. That check is
> what stops an untrusted peer writing itself into the circle by asserting it belongs.

> **`clock: 0` with everything cleared is still correct in one place**: a client that finds itself
> **not** in the circle resets to it, rather than keeping what it last asserted. It is the reset,
> not the join.

> **`updateTrust` is a different call for a different purpose.** It carries a peer id and a set of
> `TlkShare`s, and it is how an established member hands view keys to *another* peer later. It is
> not the second half of a join, and a join that relies on it to assert trust asserts none.

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
and field 2 `signature` (bytes).

**The signature does not cover `info` alone.** It covers a **type string prepended to it**:

```
signed data = <type string, ASCII> ‖ info
```

| Blob | Type string |
| --- | --- |
| `permanentInfo` | `TPPB.PeerPermanentInfo` |
| `stableInfo` | `TPPB.PeerStableInfo` |
| `dynamicInfo` | `TPPB.PeerDynamicInfo` |
| `voucher` | `TPPB.Voucher` |

No separator, no length, no terminator — the ASCII bytes immediately followed by the serialised
message. The prefix is what stops a blob of one kind being presented as another, so omitting it
does not merely fail verification, it removes a protection.

**And the signature covers the serialised bytes, not the parsed message**, so a client must keep
the exact bytes it signed and send those; re-encoding before sending invalidates the signature
even when the content is identical.

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

### 6.9.1 Signing, and what the new identity's `PeerPermanentInfo` says

**Every `SignedInfo` in §6.9 is ECDSA over SHA-384**, made with the signing key of the peer the
blob belongs to, over the type-prefixed bytes above. The one exception in this stage is the share
signature of §6.7.0, which is **SHA-256** — a different construction with a different digest, in
the same message.

Both of a peer's keys are **P-384**, generated fresh, and both public halves travel as **DER
SPKI**, not as raw points. That matters twice over: `PeerPermanentInfo` carries them, and the
identifier of §6.8.2 is a digest of the signed blob that contains them, so a different encoding is
a different peer.

| Field | Value |
| --- | --- |
| `epoch` | `1` |
| `signingKey`, `encryptionKey` | the two public keys, **DER SPKI** |
| `machineId` | the Anisette **`X-Apple-I-MD-M`** header — the same machine identity Stage 1 binds the session to |
| `modelId` | the client's hardware model string, as in [Stage 1 §2.2](./01-authentication.md) |
| `creationTime` | **milliseconds** since the Unix epoch, not seconds |

> **`machineId` ties the peer to the Anisette in use.** Rule 4 of the repo's `AGENTS.md` applies
> here with more force than anywhere else: a peer created under local Anisette and a peer created
> under a server carry different machine ids, permanently, because this blob is signed at creation
> and never rewritten.

**The permanent info is signed once, when the identity is generated**, and the signed bytes are
kept. Everything afterwards — the identifier, the peer message, the bottle — refers to those
bytes. Re-encoding the message later gives a different signature and a different identifier.

### 6.9.2 Creating a share

The inverse of §6.7.0, and the direction a joining peer actually needs. The plaintext is the same
four-field message §6.7.0 step 3 recovers — `uuid`, `zoneName`, `keyclass`, `key` — serialised.

**A joining peer shares to itself.** Both `sender` and `receiver` are the new peer's identifier,
and the wrapping key is the new peer's own encryption public key. That reads like a no-op and is
not: the keys were recovered from a *different* peer's shares, and Cuttlefish only recognises a
peer as holding a view key when a share addressed to that peer says so. The join is where the
keys are re-addressed from the recovered identity to this one.

**Wrapping** — SFIES, mirroring §6.7.0 step 3:

| Step | |
| --- | --- |
| Ephemeral key | a fresh P-384 key pair; its **uncompressed point** is what gets archived |
| Agreement | ECDH with the receiver's public key |
| Derivation | ANSI X9.63, SHA-256, shared info = that same 97-byte point, output 48 bytes |
| Key / nonce | first 32 bytes, then the next 16 — a **16-byte** GCM nonce |
| Cipher | AES-256-GCM, **no AAD** |

Then split the output: the last 16 bytes are the tag and go in `SFIESAuthenticationCode`; the rest
is the ciphertext.

> ### Reproduce the overrun, with zeros
>
> §6.7.0 describes `SFCiphertext` carrying 113 bytes past the end of the ciphertext, and a reader
> trimming them. **A writer must produce them**, padding the ciphertext by `len(point) + len(tag)`
> — the same quantity, derived the same way.
>
> This is not politeness towards a quirk. Apple's reader trims by that amount unconditionally,
> because Apple's writer always produces it; a tightly-sized ciphertext would have its last 113
> real bytes cut off and would fail authentication.
>
> **Pad with zeros.** The bytes Apple leaks there are uninitialised heap, and there is no reason
> to reproduce *that* part of the behaviour.

Archive the three members under the names of §6.7.0 — including the misspelled one — as a **binary
property list**, and base64 that for `wrappedKey`.

> **The archived object is a plain dictionary, not a class.** The top level is an ordinary keyed
> archive of a three-entry dictionary; nothing has to declare `$class` naming `SFIESCiphertext` or
> reconstruct its class hierarchy. The only class that matters is the `NSMutableData` on the
> ephemeral point, and that is a property of the member rather than of the envelope.
>
> Apple's side finds the members by name, which is why this works and why the misspelling is fatal
> while the missing class is not.

**The message's fields**, for a share this client creates:

| Field | Value |
| --- | --- |
| `service` | the key's zone name |
| `keyId` | the key's UUID — present on the message, absent from the record form |
| `curve` | `4` — P-384 |
| `epoch` | `1` |
| `receiver`, `sender` | the new peer's identifier, both |
| `receiverPublicEncryptionKey` | base64 of the receiver's **uncompressed point**, not its SPKI |
| `wrappedKey` | base64 of the archive |
| `poisoned`, `version` | **omitted** |
| `signature` | base64 of ECDSA-SHA-256 over §6.7.0's concatenation |

> **`poisoned` and `version` are omitted from the message and signed as zero.** The signature input
> of §6.7.0 has seven parts and none of them may be skipped, so both contribute eight zero bytes
> each to the digest of a message that does not carry them. Building the signed data by walking the
> populated fields produces a signature over five parts, which verifies nowhere.

### 6.9.3 Building the bottle for the new identity

Step 5.4's bottle, inverting §6.7 steps 1 to 4. **Fresh entropy** — the 72 bytes of §4.5.3 — with
the three keys derived from it exactly as §6.7 step 2 derives them, `adsid` as salt.

| Field | Value |
| --- | --- |
| `OTInternalBottle.signingKey`, `.encryptionKey` | the **new peer's private keys**, each an `OTPrivateKey` with `keyType` `1` |
| `keyData` | the **uncompressed public point followed by the private scalar** — 97 + 48 bytes on P-384 |
| `OTBottle.peerID` | the new peer's identifier |
| `.bottleID` | a **v4 UUID, upper-case** |
| `.escrowedSigningKey`, `.escrowedEncryptionKey` | the public halves of the two HKDF-derived keys, **DER SPKI** |
| `.peerSigningKey`, `.peerEncryptionKey` | the new peer's own public keys, DER SPKI |
| `.ciphertext` | the sealed `OTInternalBottle` |

**The seal is AES-256-GCM under the derived symmetric key, with a 32-byte IV.** Not 12, not 16 —
32 random bytes, carried in `initializationVector`, with the tag split off into
`authenticationCode` as §6.9 describes.

The outer `Bottle` then carries the serialised `OTBottle` bytes and two signatures over exactly
those bytes, both **ECDSA-SHA-384**, as §6.7 step 3 verifies them:

- `escrowedKeySignature` — by the **derived escrow signing key**
- `peerKeySignature` — by the **new peer's own signing key**

> §6.7 step 3 describes the second as verifying "under the sponsoring peer's signing key". For a
> bottle a peer creates for itself those are the same key, and the general rule is the one to
> implement: **the signature is by the peer the bottle belongs to**, which for every bottle this
> project creates is this client.

**The escrow label is `com.apple.icdp.record.<peerID>`** — the peer's full identifier, `SHA256:`
prefix and base64 included, appended to the prefix. §5.1's listing shows the same shape from the
read side.

> **§4.5.2's `escrowedSPKI` is the same value as `OTBottle.escrowedSigningKey`** — the DER SPKI of
> the HKDF-derived escrow *signing* key, not the encryption one and not the peer's own. That
> identity is what ties an escrow record to the bottle it accompanies, and it is why the record
> and the bottle have to be built from **one** derivation rather than from two calls that each
> generate entropy.

### 6.9.4 The order these happen in, which is not the order they are described in

**Enrol the escrow record before calling `joinWithVoucher`, not after.** The two failure modes are
not comparable:

- enrolment fails after joining → a peer in the circle whose entropy was never escrowed. **Nobody
  can ever recover it**, there is no listing that shows it, and §7's deletion does not reach it.
- joining fails after enrolment → an escrow record for a peer that does not exist. Visible in the
  listing of §5, and **deletable** by §7.1.

One is permanent and invisible; the other is tidy-up. So the sequence is:

1. Recover the bottle (§6.7 steps 1–4) → the recovered peer's identity.
2. Generate the new identity (§6.9.1). **This clears any keys and items held under a previous
   identity** — they were addressed to a peer that is being replaced.
3. Sign the voucher with the recovered peer, `reason` `1`, `beneficiary` the new peer.
4. Fetch the recovered peer's shares (§6.7.0). **If empty, stop** — §6.7 step 5.3.
5. Build the dynamic info (§6.8.2), which syncs the directory as its first act.
6. Create the bottle **and enrol it** (§6.9.3, §4.5).
7. Re-share the keys to the new peer (§6.9.2).
8. Call `joinWithVoucher` with `restorePoint` set to the sync token if one is held, the peer, the
   bottle, the shares, and **`keys` empty** — this project never establishes view keys.
9. Apply the returned changes, persist the dynamic info as sent, and store the keys.

> **Enrolment can fail because a record already exists under that label**, which is the one case
> worth handling rather than reporting: delete the record at that label and enrol again. Do that
> only for an error the escrow service *reports* — a transport failure has not established that
> anything exists, and deleting on one would remove a record this client cannot see the contents
> of. §7.1 covers the deletion and its residue rules.

### 6.7.0 Fetching the recovered peer's key shares — and why joining may be unnecessary

Step 5.3 above says to fetch the recovered peer's key shares. The method is:

```
Cuttlefish  fetchRecoverableTLKShares
```

| Message | # | Field |
| --- | --- | --- |
| Request | 1 | `forPeer` — the **recovered peer's** identifier, which §4.3 shows is already in the record's label |
| Response | 1 | `shares` — repeated `RecoverableTlkShare` |

**`RecoverableTlkShare`:**

| # | Field | Type |
| --- | --- | --- |
| 1 | `service` | string — the view this share is for, e.g. `Manatee` |
| 2 | `viewkeys` | `RecoverableViewKeys` — `tlk` (1), `classA` (2), `classB` (3) |
| 3 | `share` | the share itself |

Each of those four members is a wrapper whose **field 2** holds a **CloudKit `Record`** — the same
`Record` message as [Stage 4 §3.6](./04-cloudkit.md), field numbers and all.

> ### The share is a CloudKit record, not a protobuf message
>
> **This is the mistake to avoid, and it fails silently.** §6.9 specifies a `TlkShare` *message*
> with numbered fields. That message is what a client **sends** when joining. What it **receives**
> here is entirely different: a CloudKit record of type `tlkshare`, whose values live in
> **named fields** — the `recordField` list of Stage 4 §3.6, each with an `identifier.name` and a
> typed value.
>
> Decoding the record's bytes as a `TlkShare` message does not error. Protobuf skips fields whose
> numbers it does not recognise, so it yields a message with **every field empty** — which looks
> like a share with no sender rather than a parse that found nothing. Forty-one identical empty
> results is the signature of this mistake.
>
> **Read the fields by name.**

**Record type `tlkshare`** — the share:

| Field name | Type |
| --- | --- |
| `sender`, `receiver` | string |
| `receiverPublicEncryptionKey` | string |
| `wrappedkey` | string — base64 |
| `signature` | string — base64 |
| `curve`, `epoch`, `poisoned`, `version` | int64 |
| `parentkeyref` | reference |

**Record type `synckey`** — each of `tlk`, `classA` and `classB`:

| Field name | Type |
| --- | --- |
| `wrappedkey` | string — base64 |
| `class` | string |
| `uploadver` | string |
| `parentkeyref` | reference |

> **The record carries no `service` and no `keyId`.** Both exist on the `TlkShare` *message* and
> neither is on the record — the view name comes from `RecoverableTlkShare.service` at field 1, one
> level up, and the record's own identity comes from its `recordIdentifier`. Looking for them among
> the record's fields finds nothing, correctly.

**The important property: a share is wrapped to the *receiving* peer's encryption key.** Escrow
recovery (§6.7 step 4) yields exactly that key for the recovered peer — so **the shares it can
receive are shares this client can unwrap**, with nothing further required.

Unwrapping one:

1. **Verify the sender.** Each share carries a signature over a concatenation of its own fields,
   in this order and no other:

   | Order | Field | Encoding |
   | --- | --- | --- |
   | 1 | `version` | **64-bit little-endian** |
   | 2 | `receiver` | UTF-8 bytes |
   | 3 | `sender` | UTF-8 bytes |
   | 4 | `wrappedKey` | the **base64-decoded** bytes, not the string |
   | 5 | `curve` | **64-bit little-endian** |
   | 6 | `epoch` | **64-bit little-endian** |
   | 7 | `poisoned` | **64-bit little-endian** |

   > **All four integers are eight bytes**, `version` and `poisoned` included — even though the
   > §6.9 *message* declares those two as `uint32` and the CloudKit record carries them as its own
   > integer type. The signed form is not either of those forms.
   >
   > Earlier revisions of this table said 32-bit for those two. That is wrong in the way that costs
   > most: both are zero on real shares, so the digest is taken over four extra zero bytes, the
   > check fails, and step 1 **skips the share**. The symptom is a peer that appears to have no
   > shares to give, not a verification error.
   >
   > **The integers are little-endian.** Everything else in this protocol — CloudKit, the KeyVault
   > framing, the PCS structures — is big-endian. This one construction is not, and getting it
   > wrong produces a verification failure with no other symptom.

   Verify with **ECDSA over SHA-256** against the sender's signing key from the directory of §5.3.
   A share whose sender is not in the directory is to be skipped, not trusted.
2. **Decode the wrapped key.** It is base64 of an **`NSKeyedArchiver` archive**, which expands to
   an ECIES ciphertext structure. Another encoding appearing nowhere else in this protocol.

   > **[observed] The archive holds the three parts separately, under named members** — a reader
   > expecting one concatenated blob finds nothing to slice. On a real share:
   >
   > | Member | Size | What it is |
   > | --- | --- | --- |
   > | `SFEphemeralSenderPublicKeyExternaRepresentation` | 97 B | the uncompressed P-384 point |
   > | `SFCiphertext` | 230 B | the body |
   > | `SFIESAuthenticationCode` | 16 B | the tag |
   >
   > **The first name is misspelled, and that spelling is the wire format** — `Externa`, no final
   > `l`. Apple's own writer produced it and Apple's own reader looks for it. A reader can be
   > forgiving and match on the fragment `EphemeralSenderPublicKey`; **a writer cannot**, because
   > the correctly-spelled name is one Apple's unarchiver will not find the ephemeral key under,
   > and the failure arrives as a share that authenticates against nothing.
   >
   > That member is archived as **`NSMutableData`**; the other two are plain `NSData`.
   >
   > **These are Apple's `SFIESCiphertext` from SecurityFoundation**, so the construction is a
   > named framework class rather than anything bespoke to this protocol. **No initialisation
   > vector is archived** — it is derived, as §3 below sets out.

   ### The ciphertext member is longer than the ciphertext
   >
   > **This is the whole trap, and no amount of trying cipher parameters reaches it.**
   >
   > `SFCiphertext` carries the ciphertext followed by **113 bytes that are not part of it** —
   > 97 + 16, the sizes of the ephemeral point and the authentication code. The producer sizes
   > that buffer to hold all three parts, writes only the ciphertext into it, and archives the
   > whole buffer without trimming it back.
   >
   > **So the ciphertext is the member with its last 113 bytes discarded.** For the 230-byte
   > member observed above, 117 bytes are real. Passing all 230 to the cipher fails
   > authentication exactly as a wrong key or a wrong parameter would, which is why a search
   > over parameters can run to any size and never succeed.
   >
   > Derive the 113 from the sizes of the other two members rather than writing the constant —
   > it is a point length plus a tag length, and a curve other than P-384 changes it.
   >
   > The trailing bytes are **uninitialised heap**, so they are whatever was in that memory.
   > Do not read them, do not log them, and do not treat them as a field.

3. **Decrypt with the recovered peer's encryption private key.**

   | Step | |
   | --- | --- |
   | Agreement | ECDH between the recovered encryption private key and the archived ephemeral point. **Plain, not cofactor** — P-384's cofactor is 1 |
   | Derivation | **ANSI X9.63**, **SHA-256**, 4-byte big-endian counter from 1, **shared info = the 97-byte ephemeral point** exactly as archived. Output **48 bytes** |
   | Key | the **first 32** bytes — AES-256 |
   | Nonce | the **next 16** bytes. **A 16-byte GCM nonce, not 12** |
   | AAD | **none** |
   | Cipher | AES-256-GCM over the trimmed ciphertext, with `SFIESAuthenticationCode` as the tag |

   The plaintext is a protobuf message:

   | # | Field |
   | --- | --- |
   | 1 | `uuid` |
   | 2 | `zoneName` |
   | 3 | `keyclass` |
   | 4 | `key` — **the key bytes, and the thing this whole stage is for** |

   > **This message is the view's top-level key.** It is not a container the real key is nested
   > inside, and `key` is **symmetric key material, not an EC private key** — trying to read it as
   > one fails for a reason nothing announces.

4. **Unwrap the class keys the entry carries.** `viewkeys` holds `classA` and `classB` as `synckey`
   records, and their `wrappedkey` is wrapped **under the key from step 3**.

   > **These are not ECIES**, and this is the second place a reader reasonably assumes the
   > construction carries over from the step before. They are **AES-256-CMAC-SIV**
   > ([RFC 5297](https://www.rfc-editor.org/rfc/rfc5297), CMAC rather than PMAC), keyed with the
   > `key` bytes from step 3, with **no associated data** — an empty vector of headers, which is
   > not the same as one empty header.
   >
   > **`tlk` is not unwrapped here.** Step 3's plaintext *is* the top-level key, so an entry's
   > `tlk` member is not a fourth thing to decrypt. Keep the step 3 message alongside the two
   > class keys.

   Each class key becomes a key of the same shape as step 3's message: the `synckey` record's own
   `recordIdentifier` as the UUID, the zone name carried over from step 3, the record's `class`,
   and the unwrapped bytes.

   Keep all three. The top-level key alone is not what Stage 5 matches against.

> ### This may remove every write from the flow
>
> §6.7 presents joining as the way to obtain keys, and for a device that wants to *be* in the
> circle it is. But this project only wants to **read**, and the keys arrive at step 5.3 —
> **before** any peer is created, any voucher signed, any bottle enrolled, or anything sent to
> Cuttlefish.
>
> If the shares unwrap and yield the `Manatee` view keys, then steps 5.4 and 5.5 and everything in
> §6.9 are **unnecessary for reading**, and the flow becomes: recover the bottle, unwrap the
> shares, decrypt. **No peer, no escrow record, nothing written to the user's account.**
>
> That is a materially better position than this document has assumed throughout — it would make
> the whole feature read-only apart from the device registration of
> [Stage 1 §13](./01-authentication.md), and it removes the artefact the residue rules of §7 exist
> to manage.
>
> **It is not yet established**, and the cheap way to find out is the order above: fetch the
> shares, unwrap them, and see whether the keys they yield satisfy §6.8's contract. If they do,
> stop there. **Nothing in steps 1 to 5.3 writes anything**, so trying costs a passcode and no
> account change.
>
> **What would make joining necessary anyway:** wanting to *stay* in the circle across key
> rotations. A non-member receives no new shares, so a client that never joins may find its keys
> going stale and have to recover again. That is a real cost, but it is a later one, and it trades
> against never writing to the account at all.

### 6.7.1 The steady state — what the passcode buys

**The device passcode is needed once, not per fetch.** Everything this stage establishes persists,
and ordinary use afterwards never touches it again. That is what makes the feature a connection
rather than a one-time import, and it is what lets a newly-paired accessory be noticed later.

**What persists locally, and must be stored as carefully as any key:**

| | |
| --- | --- |
| the **new peer identity** — its signing and encryption private keys | this client's membership of the circle |
| the **keychain view keys** synced from `Manatee` and `ProtectedCloudStorage` | what [Stage 5](./05-pcs-decryption.md) decrypts with |
| the trust **sync token** | so later syncs are incremental |
| the CloudKit **continuation token** | so later fetches are incremental — see [Stage 4 §3.5](./04-cloudkit.md) |

**What persists on the account:** the device registration of [Stage 1 §13](./01-authentication.md),
and the escrow record this stage creates for the new identity.

**What an ordinary later run does:**

1. Stage 1 — re-authenticate. **Silent**: no second factor, because the machine is registered.
2. Stage 2 — refresh the service tokens if they have aged out, roughly weekly.
3. Stage 4 — fetch changes from the continuation token.
4. Stage 5 — decrypt with the keys already held.

No passcode, no escrow call, no join. **Stage 3 does not run at all.**

**What forces a re-sync, but not a passcode:** a record whose protection structure names a key not
held. Keys roll — that is what `rollCount` and the fallback signature in
[Stage 5 §3.1](./05-pcs-decryption.md) are for — so sync the two views again and retry once before
concluding anything is wrong. This is a cache miss, not a failure.

**What forces the whole stage again:** losing the local state above, or the user removing this
client from the circle. Both are recoverable; neither is silent.

### 6.7.2 Losing local state need not cost another passcode

The bottle this client creates for itself (§6.7 step 5) is sealed under a **password the client
chooses**, not under the user's device passcode.

So if local state is lost and that password was kept, the client recovers **from its own bottle**
and the user is never asked for anything. If the password was not kept, or the record was deleted,
recovery falls back to a first-party device's bottle and the user's device passcode again.

> ### A generated password stored locally is not a recovery path
>
> The obvious implementation — generate a random password, keep it with the peer identity — reads
> as self-recovery and **is not**. The case it exists for is *losing local state*, and the password
> is in that state. Where the client still holds the password it also still holds the identity, so
> there is nothing to recover; where it has lost the identity it has lost the password too.
>
> **Nothing here is wasted, but it is worth being clear what it buys.** Enrolment is what stops
> the peer being permanently unrecoverable by *anyone* — §4.5 — so the record earns its place
> regardless. What it does not do is spare the user a passcode after a reinstall.
>
> A password that would survive has to come from outside that state: shown once as a recovery code
> the user keeps, or derived from something they can re-supply. Both are real options and both cost
> the user something, so **treat the device passcode as the fallback and say so**, rather than
> implying a self-recovery that only works in the case where it is not needed.
>
> [Stage 1 §13](./01-authentication.md)'s device registration is the reason the fallback is
> tolerable: re-authenticating is silent, so recovering after a reinstall costs a passcode and not
> a second-factor round trip.

> **This sharpens the trade in §7.** Deleting the client's own escrow record removes the artefact
> from the user's account and removes the client's ability to recover itself. Keeping it means one
> more record on an account that already accumulates them. There is no free option, and the choice
> should be the user's rather than a default nobody explained.
>
> **The chosen password must be stored as securely as the keys it protects**, because it is
> equivalent to them: anyone holding it and the record can reconstitute this client's membership of
> the keychain circle.

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

### 6.8.1 From a view's keys to its items

§6.7.0 ends holding three keys per view. This is the step that turns them into the dictionary
§6.8 asks for, and **none of it is a search** — every unknown below is a name or a field number.

**The container is not the one the shares came from.**

| | |
| --- | --- |
| Container | `com.apple.security.keychain` — the same as Cuttlefish |
| Bundle | **`com.apple.securityd`** — *not* `com.apple.security.cuttlefish` |
| Database | private |
| Environment | production |

> Same container, different bundle. A client that reuses the Cuttlefish container here is asking
> the wrong service.

**The zone is the view name.** `Manatee` is a private zone in that container, as is
`ProtectedCloudStorage`. Enumerate it with the same `RetrieveChanges` paging as
[Stage 4 §3.7](./04-cloudkit.md), keeping the continuation token per zone.

Two record types matter, and the rest can be ignored:

| Type | What it is |
| --- | --- |
| `item` | one encrypted keychain item |
| `currentitem` | a **named pointer** to an `item` |

#### The pointer is how the Find My key is found

A `currentitem` record carries a single field, `item` — a **weak reference** whose record
identifier names an `item` record. Its own record identifier is the **tag**, and the tag for this
project's key is:

```
com.apple.ProtectedCloudStorage-com.apple.icloud.searchparty
```

**So the service key is a direct lookup, not a scan**: read that `currentitem` in the `Manatee`
zone, follow its reference, decrypt that `item`. The `acct` match of §6.8 is the *other* way in —
used when a record's protection structure names a key and the item holding it must be found — and
both are worth having, because they fail differently.

#### The `item` record

| Field | Type | |
| --- | --- | --- |
| `data` | bytes | the encrypted item |
| `wrappedkey` | string | base64 — this item's own key, wrapped |
| `parentkeyref` | reference | **which key unwraps it** |
| `encver` | int64 | 1 or 2, and it changes the additional data |
| `gen` | int64 | |
| `uploadver` | string | |
| `pcsservice` | int64 | optional |
| `pcspublickey` | bytes | optional — compressed |
| `pcspublicidentity` | bytes | optional |
| `server_wascurrent`, `server_suggestDeletion` | int64 | optional, server-set |

**`parentkeyref` answers "which of the three keys".** Its record identifier is a key **UUID**,
matched against the `uuid` of the keys §6.7.0 produced — the share's own key and the two class
keys. It is not a class name, so `classA`/`classB` are not what to match on.

#### Decrypting one

1. **Unwrap the item's own key.** AES-256-CMAC-SIV, keyed with the §6.7.0 key that `parentkeyref`
   names, over the base64-decoded `wrappedkey`, **no associated data**. Yields 64 bytes.
2. **Split `data`.** The **first 16 bytes are an initialisation vector**; the ciphertext is the
   rest.
3. **Decrypt** with AES-256-CMAC-SIV under the key from step 1. SIV takes a *vector of headers*,
   and here it is **the IV first, then the additional data values** of the next section, in order.
4. **Strip the padding.** The plaintext is padded to a multiple of **20 bytes**: walk backwards
   over trailing zero bytes to the first **`0x80`**, and truncate there. That byte is the marker,
   not data. Anything else encountered before it means the decryption is wrong.
5. **Parse** what remains as a **binary property list**. The result is the item dictionary.

#### The additional data, which is where this goes wrong

A sorted map of name to bytes; **only the values are passed**, in order of their names.

For `encver` **1**, exactly four entries. For anything else, those four plus the optional PCS
fields that are present, plus **every other field on the record**:

| Name | Value |
| --- | --- |
| `UUID` | the record's own identifier, as UTF-8 |
| `encver` | **64-bit little-endian** |
| `gen` | **64-bit little-endian** |
| `wrappedkey` | **the parent key's UUID as UTF-8** — *not* the `wrappedkey` field's value |
| `pcsservice` | 64-bit little-endian, if present |
| `pcspublicidentity`, `pcspublickey` | as-is, if present |

> **`wrappedkey` in the additional data is not the `wrappedkey` field.** It is the identifier
> `parentkeyref` points at. The name is reused for two different things one line apart, and using
> the wrapped key itself produces an authentication failure with nothing to say why.

Every remaining field on the record joins the map under its own name, **except** the ten already
spoken for — `gen`, `pcspublickey`, `UUID`, `data`, `pcsservice`, `pcspublicidentity`,
`parentkeyref`, `uploadver`, `wrappedkey`, `encver` — and **except anything beginning with
`server_`**. Their values are rendered by type:

| Type | Rendering |
| --- | --- |
| string | UTF-8 |
| bytes | as-is |
| int64 | **64-bit little-endian** |
| double | **cast to an integer, then 64-bit little-endian** |
| date | **RFC 3339, whole seconds, `Z`** — e.g. `2026-08-14T09:23:41Z` |

> Little-endian again, as in §6.7.0's signature, and against everything else in this protocol.
>
> **The map is sorted by name and the names are then discarded.** Only the values are passed, so
> the sort is the only thing that puts them in the right order — and a map that preserves
> insertion order instead produces a wrong order that is stable, repeatable, and wrong on every
> item.

#### What the dictionary holds

Ordinary keychain attributes — `class`, `acct`, `agrp`, `vwht`, `pdmn`, `atyp`, `labl`, `srvr`,
`cdat`, `mdat`, `sha1`, `musr`, `tomb` — and **`v_Data`**, which is the payload.

For the service key, `acct` is base64 of the **compressed** public key, `agrp` is
`com.apple.ProtectedCloudStorage`, `vwht` is `Manatee`, and `v_Data` is a **DER-encoded private
key structure** — the thing [Stage 5](./05-pcs-decryption.md) needs.

**That structure is a CHOICE of two forms**, and Find My's service uses the second:

| Form | Encoding |
| --- | --- |
| V1 | a sequence: the key octets, and optionally the public structure |
| V2 | **`[APPLICATION 5]`**, wrapping a protobuf carrying an **encryption key** and a **signing key**, each as compressed private key bytes with an optional DER public structure |

**Find My's service key is V2.** Its `com.apple.icloud.searchparty` service is type **82**, view
hint and zone both `Manatee`.

> ### The key octets are 64 bytes, and the public half comes first
>
> **This layout is shared by both forms** — V1's octets and each of V2's protobuf key fields are
> written the same way, so a single reader serves both:
>
> | Bytes | Content |
> | --- | --- |
> | 0–31 | the **public x coordinate** |
> | 32–63 | the **private scalar** |
>
> **Public first.** Reading the leading 32 bytes as the scalar does not fail — it yields a
> perfectly valid key on the curve, with a public x that matches nothing. Everything downstream
> then reports "no key held" rather than "wrong key", because the two are indistinguishable once
> the wrong key is a valid one.
>
> The check that catches it costs nothing and is worth doing at the point of parse: **derive the
> public x from the scalar and confirm it equals bytes 0–31**. The blob carries both halves
> precisely so that this is possible.
>
> **A 32-byte blob also occurs**, and is the scalar alone with no public half — so dispatch on
> length rather than assuming either. At 32 bytes there is nothing to check against.

> **This is the join Stage 5 was missing.** A 64-byte AES-SIV view key never becomes an EC private
> key, and no derivation turns one into the other. The view key decrypts an *item*; the item's
> `v_Data` **contains** the EC private key. Two different things, one step apart.

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

Both carry only `command`, `label`, `transactionUUID`, `userActionLabel` and `version`.

### What `.double` is

The suffix names a **companion record** created alongside the main one. Two things about it are
worth knowing before writing either a delete path or a listing:

**Companions are created by Apple's own clients, not by this protocol's enrolment.** Nothing in
§7's enrolment produces one; only deletion mentions them. So a record this project creates has no
companion, and deleting `<label>.double` for it addresses nothing — harmless, and worth expecting.

**A listing shows both, which inflates the apparent count.** `get_records` returns companions as
ordinary entries, so a device that escrowed once through a first-party client appears **twice**.
A count of records is therefore not a count of devices.

> **[observed] Companions do not explain records that repeat per serial.** An earlier version of
> this note guessed they might. They do not: across twelve records on a live account **not one
> label ended in `.double`**, and records sharing a serial had entirely different peer digests.
> They are distinct peers, not a record and its companion.
>
> The companion mechanism is real and deletion must still issue both calls — but it is not what a
> repeated serial means. See §5.2.

> **The protocol requires nothing but the label.** No password, no blob, no proof that the caller
> could have recovered the record. Any client holding a valid PET can delete **any** escrow record
> on the account, including one belonging to a real device that the user still depends on.

### [observed] A deletion that addressed nothing reports success

**The service returns success for a `delete` that removed nothing.** A wrong label — one that
names no record — is indistinguishable at the call site from one that names a record and removed
it.

So **the return value is not evidence.** The only proof a record is gone is to **list again and
find it absent**, and an interface that offers deletion must do that rather than reporting what it
attempted. Telling a user "removed" on the strength of a success code is telling them something
the protocol did not say.

This is not hypothetical. An earlier version of this document had deletion addressing a record's
`bottleID` rather than its label (see above); a client built from that text would have deleted
nothing, reported success, and left the user believing their account was clean — the worst of the
available outcomes, because they would not check again.

**[observed] The flow is otherwise verified end to end**: records were deleted from a live account
on 2026-08-13 and a re-listing confirmed the count had dropped.

Two things follow, and they are the whole design of this operation:

> **[observed] The guard works in practice.** On the live account, every record left by the
> retired export route sorted into the non-viable list and was offered for deletion, while the
> three live recovery paths were withheld — with no judgement required from the user. That is the
> case for making viability the guard rather than the warning.

**Viability is a safety signal, and a better one than any prompt.**

The listing of §5 already separates records that can be recovered from from those that cannot. That
distinction maps directly onto the risk:

| Record | Deleting it destroys | Risk |
| --- | --- | --- |
| **Not viable** — described, but no usable bottle | nothing that could have been used | **low** |
| **Viable** — a usable bottle exists | that device's ability to recover its keychain | **high** |

A record with no viable bottle cannot be recovered from, so removing it takes away a capability
nobody had. A viable one is a live recovery path for a real device, and destroying it means its
owner cannot get their keychain back after a wipe — a loss they will discover at the worst
possible moment and cannot undo.

**[observed] The distinction is not theoretical.** On the account examined, all the records
attributable to the retired export route were among the **non-viable** ones — their peers no longer
exist in the circle, so their bottles are unusable. The debris and the danger sorted themselves.

**So the default should be: offer only non-viable records for deletion.** Viable ones require a
deliberate, separately-worded step, if they are offered at all. That is a stronger protection than
any confirmation prompt, because it removes the dangerous option rather than asking the user to
be careful around it.

**Viability is a signal, not a guarantee.** A record could be non-viable for a transient reason —
a service unavailable, a peer temporarily unreachable — and a client cannot tell that apart from a
peer that is gone for good. So the confirmation still matters underneath: list first, show the full
identifying detail — device name, model, serial, escrow date — require the user to re-enter the
**serial**, and never accept a list position. Never offer a bulk or "clean up all" action.

If a listing cannot be obtained at all, **do not offer deletion**. Without viability information
every record looks alike, and that is precisely the situation the rule above exists to avoid.

**A cleanup is not a fix.** Removing accumulated records does nothing to stop them accumulating —
whatever regenerated its identity per run is still doing so, and the count will climb again.
**[observed]** Two of the records deleted from the live account dated from the preceding week. Any
interface offering deletion should say this plainly, or a user who has just cleaned up will
reasonably conclude the problem is solved.

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

**Answered by the runs of 2026-08-13:**

1. ~~Is the escrow host really `p<N>-escrowproxy.icloud.com`?~~ **Yes.** Derived from the account's
   CloudKit partition, and the service answered. Ruled out along the way: the MobileMe delegate
   configuration, which carries none, and `ckAppInit`'s `values` array, which is a per-environment
   endpoint table for the container being opened.
2. ~~What is the exact SRP exchange for recovery?~~ **Specified** in §6.1 to §6.5 — framing,
   parameters, both blob layers and the passcode's two uses. **Written, not run.**
3. ~~Can bottles be listed without any prior trust state?~~ **Yes.** The listing of §5 was
   performed against a live account with no trust circle, no keychain state and no passcode, and
   returned both halves of the join.

**Still open:**

4. **Which fields of `PeerStableInfo` must a client populate, and what must the policy version and
   hash contain?** §6.9 enumerates every message but does not settle what a valid peer *asserts*.
   A peer that signs an incomplete stable info may be admitted and then behave oddly rather than
   being rejected, which is the worse failure.
5. **Which token authenticates what?** Stage 1 issues `com.apple.gs.icloud.escrow.auth`, but the
   escrow proxy is authenticated with the PET and works. Whether that token is needed at all is
   unknown.
6. **Does an account with Advanced Data Protection enabled behave differently?** ADP changes how
   keychain material is protected, and Stage 1 §12 already carries an unverified claim that it
   breaks the two-factor configuration endpoint. It is likely to matter more here.
7. **What happens on an account whose keychain circle is empty?** Presumably no viable bottles and
   no way in. That case needs detecting and explaining, not failing obscurely.
8. **Is a non-viable bottle ever transiently non-viable?** §7.1 makes viability the primary guard
   on deletion, which is sound only if non-viability is a durable property. If a service outage can
   make a live bottle look dead, the guard could invite deleting something real.

