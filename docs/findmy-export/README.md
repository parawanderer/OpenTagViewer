# Exporting FindMy accessory keys without a Mac — protocol specification

This directory specifies the network protocol an application must speak to Apple in order to
read a user's FindMy accessory (AirTag) key material directly from iCloud, with no Mac and no
macOS export step.

It exists so that the flow can be implemented under a permissive licence, without deriving from
any existing implementation.

**The implementation target is [FindMy.py](https://github.com/malmeloo/FindMy.py), in Python, not
Java.** OpenTagViewer already embeds FindMy.py through Chaquopy, so Python written there *is* app
code — and FindMy.py already performs Stages 1 and 2 and persists their output, meaning the
hardest prerequisites are met before this feature starts. Building it there rather than in the
app's Java means the work lands where the session already lives, and everyone else using the
library gets it too.

---

## Why a specification and not a port

The one working implementation of this flow is
[OpenBubbles/rustpush](https://github.com/OpenBubbles/rustpush), together with
[stek29/export-findmy](https://github.com/stek29/export-findmy), a headless command-line
exporter built on a fork of it.

Their licensing rules out reuse:

| Component | Licence | Consequence |
| --- | --- | --- |
| `rustpush` — CloudKit, keychain, escrow, FindMy record handling | **SSPL v1**, with a written exception granting unrestricted rights to OpenBubbles alone | Copyleft. Deriving from it would relicense OpenTagViewer. |
| `apple-private-apis` — GSA authentication, Anisette (`icloud-auth`, `omnisette`) | **MPL 2.0** | File-level copyleft. A direct port would have to stay MPL, putting a second licence inside an MIT app. |
| `apple-private-apis/icloud-auth/rustcrypto-srp` — forked SRP | MIT / Apache-2.0 | Permissive, but describing it costs nothing. |
| `stek29/export-findmy` — the CLI that glues the above together | **No licence file** — all rights reserved by default | Not usable as source at all. |

What those licences cover is *expression*: code layout, identifiers, comments, structure. They
do not and cannot cover **Apple's wire protocol** — the endpoints, header names, payload
layouts, cryptographic constructions and state transitions written down here. Those are facts
about a third party's service, and facts are not copyrightable.

## The clean-room split

These documents are the "specification" half of a clean-room reimplementation.

- **The specification is written by someone who has read the reference implementations.** That
  is deliberate: it is the only way to learn the protocol without a packet capture of a real
  Apple device.
- **The implementation must be written by someone who has not.** The implementer works from
  these documents alone. The language is Python, targeting FindMy.py; the discipline would be
  identical in any language, because what must not cross the boundary is the *expression* of the
  reference implementations, not any particular syntax.

So, for anyone implementing:

> **Do not open `rustpush`, `apple-private-apis`, or `export-findmy` while writing this code.**
> If something here is ambiguous, incomplete or wrong, fix *this document* — raise the gap, get
> the specification amended, and implement from the amendment. Reading the reference to resolve
> a question destroys the separation that makes the reimplementation defensible.
>
> This matters more, not less, for an upstream contribution: FindMy.py is MIT, and a patch
> carrying SSPL-derived expression into it would be a problem for its maintainer as well as for
> this project.

Nothing below quotes, paraphrases at the level of expression, or names any type, function or
file from those projects.

---

## The stages

The full flow from an Apple ID to accessory private keys is six stages. They are specified
separately because they are separately testable and separately hard. **All six are now written.**

| # | Stage | Document | Status |
| --- | --- | --- | --- |
| 1 | **Apple ID authentication** — Anisette headers, SRP handshake against GSA, two-factor, session tokens | [01-authentication.md](./01-authentication.md) | **Specified and verified** |
| 2 | **MobileMe delegate** — exchange the short-lived PET for iCloud service tokens | [02-mobileme-delegate.md](./02-mobileme-delegate.md) | **Specified and verified** |
| 3 | **iCloud Keychain trust circle** — join via escrow recovery, using a device passcode | [03-keychain-trust.md](./03-keychain-trust.md) | Listing **verified**; recovery and join unverified |
| 4 | **CloudKit** — open the container, fetch encrypted `BeaconStore` records | [04-cloudkit.md](./04-cloudkit.md) | §2 **verified**; §3 specified, unverified |
| 5 | **PCS decryption** — decrypt those records with keychain-held keys | [05-pcs-decryption.md](./05-pcs-decryption.md) | Specified, unverified |
| 6 | **Output** — write the layout OpenTagViewer already imports | [06-output.md](./06-output.md) | Mapping **confirmed**; unexercised |

Stage 1 is specified first because it is self-contained, because it is the stage that the app's
existing native Anisette work already feeds, and because finishing one stage is the only honest
way to estimate the other five.

## Shared accessories: reachable, and new

The macOS export route this replaces reads a directory named `OwnedBeacons`, and that is exactly
what it gets — accessories the user owns. An AirTag shared *with* the user, via Apple's sharing
feature, does not appear.

**The iCloud route can reach them**, and that would be capability the current app does not have.
The container holds a distinct record type for them, alongside the owned one. But the two are not
symmetrical, and the asymmetry is the whole story:

| Owned accessory record | Shared accessory record |
| --- | --- |
| private key, public key | — |
| shared secret, secondary shared secret | — |
| secure-locations shared secret | — |
| pairing date, stable identifier | share date, correlation identifier |
| — | owner handle, accepted flag, role, share type |

**A shared record carries no key material.** It describes the share — who owns the accessory,
whether the user accepted it, what role they hold — and nothing that would let anyone locate
anything. The keys live behind a separate structure: the container also holds owner and member
*sharing circle* records and a *sharing circle secret*, and the plausible reading is that the
circle secret is what unlocks a shared accessory's keys.

So supporting shared accessories means **a second key hierarchy on top of the PCS one**, not a
handful of extra fields. It is a deliberate scope decision rather than something that falls out
of the work, and it is not part of Stages 1 to 6 as specified.

**[observed] The sharing machinery is in the same zone as everything else.** A fetch of
`BeaconStore` returned `SharingCircleSecret`, `OwnerSharingCircle` and `OwnerPeerTrust` records
alongside the owned accessories — not in CloudKit's separate shared database, and reachable with
no extra credential. `SharingCircleSecret` carries a `secretType`, a `sharingCircleIdentifier`
and a `secretData` blob, which is very likely the key material a shared accessory's records are
protected under.

That makes shared accessories **substantially cheaper than a separate-database design would**:
the records are already in hand after the ordinary fetch, and the remaining work is understanding
how a circle secret unwraps a shared accessory's keys.

One question is still open, and it is the one that decides the cost: **whether any of it requires
Apple's identity service.** Some sharing machinery in the reference reaches into per-participant
messaging targets, which would be a far larger dependency. The plausible reading is that identity
is needed to *establish* or *accept* a share and not to read one already accepted — but that is
an inference from naming, not something established.

## Stage 6 is a rename, not a translation

The macOS export route this replaces reads a cache directory. **[observed] That cache is a local
copy of the CloudKit records** — the plists even carry a `cloudKitMetadata` field holding
CloudKit's own system fields.

Comparing a real `MasterBeaconRecord` fetched from CloudKit against the `OwnedBeacons` plist
OpenTagViewer already imports:

| | CloudKit record | macOS cache plist |
| --- | --- | --- |
| Key material | `privateKey`, `publicKey`, `sharedSecret` | same names, but wrapped in `{key: {data: …}}` |
| Secondary secret | `sharedSecret2` | `secondarySharedSecret` — **the one rename** |
| Everything else | `productId`, `vendorId`, `model`, `isZeus`, `systemVersion`, `pairingDate`, `batteryLevel`, `stableIdentifier` | identical names |
| Record identity | in `recordIdentifier` | an `identifier` field |
| CloudKit system fields | in the envelope | a `cloudKitMetadata` blob |

`BeaconNamingRecord` matches exactly: `name`, `associatedBeacon`, `roleId`, same camelCase.

So Stage 6 is: unwrap the key dictionaries, rename one field, synthesise or omit
`cloudKitMetadata`, and decide what to do about `stableIdentifier` — a string in CloudKit, a list
in the plist. **It is not a format redesign**, which is why the layout parawanderer specified for
the community export matched what the Rust exporter produces.

Two caveats. **Optional fields really are optional**: `emoji` on the naming record, and
`secureLocationsSharedSecret` and `groupIdentifier` on the beacon record, were all absent on the
observed account despite appearing in the schema. And `groupIdentifier` has no home in the plist
format at all — it is what FindMy.py 0.10.0 added support for — which is an argument for emitting
FindMy.py's native JSON rather than the older plist layout.

## Two constraints worth knowing before planning around this

**It removes the Mac, not the Apple device.** Stage 3 joins the iCloud Keychain trust circle by
escrow recovery, which requires the screen-lock passcode of a device already in the user's
circle — an iPhone PIN or a Mac login password. The device itself is not needed and is never
contacted, but the user has to know that passcode. An Apple ID that has never had an Apple
device in its keychain circle has nothing to recover from.

**It writes to the user's Apple account**, in more than one way. See below.

## Account-side residue is a first-class design problem

This feature does not only read. It leaves artefacts behind in the user's Apple account, and
each one persists until something deletes it. Two are known so far:

| Artefact | Created by | Persists as | Lifetime |
| --- | --- | --- | --- |
| A **registered device** | signing in with a machine identity Apple has not seen before | an entry in the account's device list, at appleid.apple.com and in iOS Settings | **kept while signed in** — see below |
| An **escrow record** ("bottle") | joining the keychain trust circle in Stage 3 | a recoverable keychain escrow entry, **not shown in any Apple UI** | **permanent unless explicitly deleted** — see below |

**[observed] Escrow records outlive the devices that made them, and nothing cleans them up.**
The account these documents were written against showed *one* iMac Pro in its device list while
holding *eight* iMac Pro escrow records — leftovers from the macOS-VM route, whose device entries
had long since been removed. Deleting a device does not delete its escrow record.

So of the two artefacts, the **escrow record is the one that actually accumulates**, and it does
so invisibly. The twenty phantom iMacs were the visible symptom; their escrow records are still
there.

**The escrow record has no user-facing surface at all.** Apple's internal name for the mechanism
is *secure backup* — the last time it was directly visible to users was the iCloud Security Code
flow, since folded into iCloud Keychain and Account Recovery. There is a toggle and a
recovery-key screen; there is nothing enumerating records.

What a bottle carries is essentially a copy of a device's identity: `device_name`,
`device_model`, `device_model_class`, `serial`, `build`, an escrow timestamp and a bottle id. So
it mirrors a device-list entry, which is why there is no separate screen for it — and which has
two consequences worth designing around:

- **The labelling of Stage 1 §2.2 carries over for free.** A bottle created with the same
  identity inherits the same recognisable serial, so it is identifiable by the same means as the
  device — provided the identity is consistent across stages, which it must be anyway.
- **Deleting one is correspondingly dangerous.** A bottle belonging to a real iPhone looks like a
  bottle belonging to us, and destroying the wrong one destroys that device's ability to recover
  its keychain. Hence the enumerate-before-deleting rule below, and hence any deletion must
  confirm against the serial rather than a list position.

Neither is hypothetical. The macOS-VM export route this feature replaces accumulated **around
twenty phantom iMacs** in one maintainer's account, because every VM run minted a fresh identity
and every fresh identity registered as a new device. Nothing broke and nothing was banned — it
was simply irreversible clutter in a place the user cannot easily reason about.

So the standing rule for every stage:

> **Anything this feature creates in a user's Apple account must be created at most once,
> named so a human can recognise it among their real devices, and removable from inside the
> app.**

Which means concretely:

- **Never regenerate a persisted identity silently.** Identity state that is missing or
  unreadable is an error to report, not a cue to mint a new one. Silent regeneration is the
  precise mechanism by which twenty iMacs appear.
- **Reuse before creating.** One identity, one escrow record, reused across every run.
- **Label for the human, not the protocol.** An entry indistinguishable from real hardware is one
  the user dare not delete; an obviously-ours one they can remove without a second thought. Use
  whichever field the stage actually controls — in Stage 1 that is the serial number, not the
  name.
- **Removal belongs in sign-out**, not in a hidden maintenance command that only someone reading
  the source would find.
- **Enumerate before deleting.** The escrow list in particular contains the user's *real*
  devices. Anything that deletes must show what it is about to delete and require the user to
  confirm against identifying detail.

**Settled, and the answer is the unwelcome one: signing in is enough.** Authentication alone
registers the device; no separate announce call is needed, so there is no read-only flow that
avoids it. See [01-authentication.md](./01-authentication.md) §13.

The mitigation is cheap and confirmed to work: **supply a self-describing serial number**, which
Apple displays verbatim on the device's page. An entry reading `0PENTAGVIEWR` is one the user can
recognise and remove; one showing no serial at all is indistinguishable from real hardware. The
device also stays untrusted for verification codes, and should be kept that way.

### The registration is what suppresses two-factor, so it stays

**[observed]** A second sign-in from the same machine identity needed **no second factor at
all** — Apple remembered the machine and did not challenge it.

That settles a design question that looked open. This feature is not a one-shot export: to notice
a newly-paired AirTag, the app has to re-read the account periodically. Recurring access means
recurring authentication, and authentication is only silent because a device registration exists
for Apple to recognise.

**So the device is not residue. It is the application's identity on the account**, and it should
be treated like the identity of any other device the user owns:

- **Created once**, and reused for the life of the installation.
- **Never churned.** Registering and removing around each operation would demand a two-factor
  code every time, which is fatal for a background refresh and worse for the user than the entry
  it was trying to avoid.
- **Removed at sign-out** — when the user deliberately disconnects the account — and at no other
  time.
- **Legible while it exists**, via the serial number and, if the naming call is implemented, the
  name.

Residue is what an application leaves behind *after* it is done. A registration that is actively
in use is not that. The failure the twenty phantom iMacs represent was never "a device exists" —
it was *twenty* of them, unlabelled, from an identity regenerated on every run.

## Verification status

**Stages 1 and 2 are verified** against a real Apple account on 2026-08-13. Stage 1:
SRP handshake, server proof verified, session payload decrypted, trusted-device two-factor, 25
service tokens. Stage 2: delegate exchange accepted, 11 iCloud service tokens returned —
including `searchPartyToken`, the one this project exists for.

Stage 2 is verified in the stronger sense: the probe that exercised it was written **from the
specification**, not by calling any reference implementation. It worked on the first attempt, so
the document is one somebody has successfully built from.

Findings are marked `[observed]` in each, and several corrected what reading the references had
suggested. Paths that remain untested: SMS two-factor, the 403 attributed to Advanced Data
Protection, the terms-of-service flow, and the device lifecycle of Stage 2 §7.

**Stages 2 to 6 are not written and nothing about them has been executed.** They will be derived
by reading implementations that their authors report working. Each needs the same validation
against a real account before any claim that it works.
