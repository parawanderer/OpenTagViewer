# How exporting works, and who runs it

> **Status: plan, not description.** None of this is built yet. It records decisions taken while
> the protocol work was being specified, so that the UI work does not have to re-derive them.
>
> The protocol itself is in [findmy-export/](./findmy-export/). This document is about product
> shape: which flows exist, what each one costs the user, and which of them writes to their Apple
> account.

---

## The change this plans for

Today, getting tags out of an Apple account needs a **Mac** — the exporter reads the plists Find
My leaves on disk, which is why there is a VM bootstrap and why the wizard is macOS-only. The
protocol work replaces that with reading the same records out of iCloud.

**So the Mac disappears, and with it the operating system requirement.** What is left is an Apple
ID, the screen-lock passcode of a device already on that account, and Python — which runs
anywhere, including inside the Android app through Chaquopy.

The one thing it does not remove is the iPhone or iPad that paired the tag in the first place.
See [findmy-export/README.md](./findmy-export/README.md#the-one-thing-left-and-why-it-is-not-worth-starting).

---

## Three roles, and only one of them writes

| | Who | Needs | Writes to the account |
| --- | --- | --- | --- |
| **Owner, connected** | has an Apple account with tags, wants live tracking on Android | Apple ID, device passcode | **yes** — a peer and an escrow record |
| **Owner, exporting** | has tags, wants a zip — for themselves or a friend | Apple ID, device passcode | no |
| **Recipient** | has no tags of their own | a free Apple ID | no |

**The recipient's Apple ID is not optional and is often misremembered as being so.** Fetching
location reports is authenticated: any Apple ID may fetch reports for any tag's hashed keys, but
some Apple ID must. That is exactly why the app has a login flow today, and it is what makes an
export to a friend work at all — the service does not check that the requesting account owns the
tag. No Apple *device* is needed, and the account can be free and new.

---

## Two exporters, one artefact

The zip is the interface, and it does not care what produced it:

- **The Android app**, for an owner who has an Android phone anyway.
- **The desktop wizard** (`python/`), repointed at the iCloud route, for an owner who does not —
  or who would rather not put an Apple ID into an Android app.

Both emit the layout in [findmy-export/06-output.md](./findmy-export/06-output.md), and both stamp
`via:` in `OPENTAGVIEWER.yml`, which is how anyone looking at a bundle later works out which
produced it. That stamping rule already exists — `AGENTS.md` rule 9 — and now has two producers to
distinguish rather than one to version.

**Repointing the wizard is the larger user-visible win of the two.** It retires the VM bootstrap
and the macOS-only packaging, and it is the step people actually complain about.

---

## Joining the trust circle is a separate decision from signing in

**It is tempting to always join**, on the reasoning that a user who buys a tag later should see it
appear without being asked for anything. The outcome is right; the mechanism is not what it looks
like, and the difference decides the design.

**A new tag does not need a join to be visible.** It arrives as a new record in the same zone,
protected under keys already held, and an incremental fetch finds it.

**What a join actually buys is surviving key rotation.** A non-member receives no new key shares,
so when Apple's view keys roll — which happens for reasons outside this app, such as a device
being removed from the account — a non-member's keys go stale and the user is asked for the
device passcode again. A member is handed the new keys and notices nothing.

So the choice is between *one passcode prompt, occasionally, at an unpredictable moment* and
*permanent artefacts on the user's Apple account*. That is a real trade, not an obvious one.

### The decision

**Join only when the user has asked for the connected experience.** Export is a read and must not
write anything.

| Flow | Joins |
| --- | --- |
| One-off export, in the app or the wizard | **no** — recover keys, decrypt, write the zip, leave nothing |
| Live tracking of your own account's tags | yes, and say so before doing it |

Bundling the join into sign-in would give every user who wanted a single export a peer in their
keychain trust circle and an escrow record that **no Apple interface displays**, acquired without
being asked. The residue rules in
[findmy-export/README.md](./findmy-export/README.md#account-side-residue-is-a-first-class-design-problem)
exist for precisely this.

### What joining costs, in words a user should see

- a **peer** in the keychain trust circle — the circle that protects every password on the account
- an **escrow record**, sealed under a passcode the user chooses, which is what recovers this
  client later
- both permanent; only the record can be deleted afterwards

The second passcode is only ever needed for this flow. **An export never asks for it**, because
nothing is created that would need protecting.

---

## Edge cases worth handling deliberately

**An account with no circle at all.** An Apple ID that has never had an Apple device has nothing
to recover from — no bottles, no peers, no keys. The only way in is `establish`, which creates a
circle, and this project never calls it (see
[findmy-export/03-keychain-trust.md](./findmy-export/03-keychain-trust.md) §6.7). It does not
matter in practice: such an account cannot have paired a tag, so it owns nothing to export. Detect
it and say so plainly rather than failing at a lower layer.

**A recipient signing in.** Their account has tags of nobody's; the keychain path should never run
for them at all. Importing a zip must not require a passcode, a circle, or anything from Stage 3.

**A user who exports and later wants live tracking.** They will be asked for the device passcode a
second time, because the first flow deliberately kept nothing. That is the correct trade and
should be explained at the point it happens, not treated as a bug.

---

## Open decisions

- **`sourceUser` in `OPENTAGVIEWER.yml`** names a person and travels inside a bundle that is meant
  to be shared. Keep, drop, or make optional.
- **Tag selection.** Exports should take an explicit list rather than defaulting to everything —
  sharing one tag with a friend is the common case, and sharing all of them by accident is the
  failure to design against.
- **Where the desktop wizard's Apple ID lives.** The app encrypts its account blob under a
  key held in `AndroidKeyStore`; the wizard has no equivalent, and FindMy.py's default writes
  the Apple ID password to `account.json` in plaintext.
