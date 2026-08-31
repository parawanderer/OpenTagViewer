# Matching and ringing a tag while it is in owner-connected mode

> **An investigation record, not a plan.** Nothing here is scheduled and nobody is assigned. It
> exists so that whoever does look into this starts from what is already known rather than
> rediscovering it — including one finding that would otherwise cost a week on the wrong protocol.

A reading list. Everything here is about the case the app does **not** solve: a tag
that is with its owner, so the Find My network never sees it. The separated case is solved and
none of this is about that.

## Why this is a separate problem

An AirTag has three states, and they behave differently on the radio:

| State | What it does |
| --- | --- |
| **Connected** | in contact with an owner device — e.g. during setup |
| **Nearby** | recently in contact, still close. Broadcasts a **primary key**, rotating roughly every 15 minutes |
| **Separated** | away from the owner. Broadcasts a **secondary key**, rotating daily at 04:00 local, and the Offline Finding network starts reporting it |

**A nearby tag is not in the Offline Finding network at all.** No stranger's iPhone reports it,
so there is nothing on Apple's servers to fetch however far back you look — which is why Apple's
own app falls back to "last seen by your device" rather than a network position. Anything in this
area has to happen over Bluetooth, locally, on the device standing next to the tag.

---

## The states, and what is broadcast in each

**[FindMy.py #137 — "Nearby, separated, lost?"](https://github.com/malmeloo/FindMy.py/issues/137)**
malmeloo's own one-paragraph statement of the problem: nearby tags *limit the information they
broadcast*, are excluded from OF, and therefore cannot be located by the library — hence the
offline Bluetooth scanner existing at all. The most authoritative short answer, and the place to
start.

**[The Binary Hick — Endtroducing… Lost Apples](https://thebinaryhick.blog/2025/11/30/endtroducing-lost-apples/)**
and **[Old Dog, New Tricks — Lost Apples 2.0](https://thebinaryhick.blog/2026/03/22/old-dog-new-tricks-lost-apples-2-0/)**
Joshua Hickman, from a digital-forensics angle. Where the three-state model above comes from,
including the primary-key/15-minute versus secondary-key/daily-at-04:00 split. 2.0 also documents
a **powered-down iPhone** being seen over Find My, which is its own rabbit hole.

**[Adam Catley — Apple AirTag Reverse Engineering](https://adamcatley.com/AirTag.html)**
The comprehensive hardware and BLE teardown. The detail that matters here: the first 6 bytes of
the P-224 public key are carried as the **BLE device address** rather than in the payload, so only
23 of the 29 bytes appear in the advertisement — and the tag only starts broadcasting the finding
advertisement once away from its owner.

---

## Ringing a tag over BLE

**[FindMy.py #88 — Request "ring" the device](https://github.com/malmeloo/FindMy.py/issues/88)**
The whole thread is the state of the art, and it is worth reading in order because the
understanding changes as it goes. malmeloo starts at *"there is a public BLE endpoint… not very
difficult"* and *"there's not really any security involved, because other people should be able to
ring the device as well"*, and it gets more complicated from there.

**[stek29's comment, 13 June 2026](https://github.com/malmeloo/FindMy.py/issues/88#issuecomment-4699342119)**
— **the single most useful thing on this list.** He tested the proof of concept against real
AirTags **with the owner device nearby** and it failed: the characteristic it writes to,
`4F860003-943B-49EF-BED4-2F730304427A`, was not present on the device at all. His own correction
is the finding:

> I just realised this PoC is about unauthorized sound command, not about ringing when AirTag is
> in "owner is nearby" mode. authorised sound playback seems to be a completely different beast,
> requiring l2cap communication

So **unauthorised ringing (any passer-by) and authorised ringing (the owner, tag nearby) are two
different protocols**, and only the first is a simple GATT write. Anyone starting here should
assume the GATT path does not cover the case they care about.

**[FindMy.py #211 — feat: Play sound on accessories](https://github.com/malmeloo/FindMy.py/pull/211)**
malmeloo's experimental branch (`feat/accessory-communication`), still a draft. His own note is
that he *"cannot get it to work reliably"* and suspects his Linux Bluetooth stack. This is the
unauthorised path. Installable as
`pip install git+https://github.com/malmeloo/FindMy.py#feat/accessory-communication`.

**[seemoo-lab/airtag](https://github.com/seemoo-lab/airtag)** — Apache-2.0, JavaScript
The FRIDA instrumentation scripts, and **the resource stek29 named for the L2CAP side**.
`hook_durian.js` plays arbitrary sound sequences ("AirTechno") on a paired AirTag — i.e. it does
work in the connected case, which is the interesting part. The cost is the setup: **a jailbroken
iPhone** on iOS 14.6–14.8 (checkm8-supported hardware), paired with the AirTag, plus a host
running FRIDA. Not a route to a shipping feature, but the best available description of what the
authorised path actually looks like.

---

## Implementations that reportedly already do this

**[seemoo-lab/AirGuard](https://github.com/seemoo-lab/AirGuard)** — Kotlin, Apache-2.0, ~2.5k stars
An Android anti-stalking app that scans for nearby Find My accessories. **malmeloo states in #88
that AirGuard supports ringing**, and that Apple's own Android app likely does too. If that is
right, it is an existence proof that this is doable from Android without a jailbreak, and its
source is the obvious place to look. *(Not verified line-by-line — worth confirming before relying
on it.)*

**Apple's "Tracker Detect" for Android** — same claim, closed source, but it demonstrably interacts
with tags it does not own.

**[Apple's Find My Network Accessory Program](https://developer.apple.com/find-my/)**
The official specification for **MFi third-party accessories**, which documents the GATT services
a licensed accessory exposes, including sound. Worth reading for the shape of the thing — but note
it describes third-party accessories rather than AirTags, and those carry factory certificates
precisely so that arbitrary software cannot drive the privileged paths.

---

## Papers

**[AirTag of the Clones: Shenanigans with Liberated Item Finders](https://www.researchgate.net/publication/362264435_AirTag_of_the_Clones_Shenanigans_with_Liberated_Item_Finders)**
— Roth, Freyer, Hollick, Classen. WOOT'22 / IEEE SPW 2022. The firmware-level work behind
`seemoo-lab/airtag`; a copy ships in that repo as `woot22-paper.pdf`. Covers getting code
execution on the AirTag itself, which is how the sound behaviour was characterised.
[Register write-up](https://www.theregister.com/2022/05/27/apple_airtag_sounds/) if you want the
short version.

**[Who Can Find My Devices? Security and Privacy of Apple's Crowd-Sourced Bluetooth Location Tracking System](https://petsymposium.org/popets/2021/popets-2021-0045.pdf)**
— Heinrich, Stute, Kornhuber, Hollick. PoPETs 2021. The original protocol analysis that OpenHaystack
and everything downstream is built on. Background rather than directly about connected mode, but it
is the document that defines the terms everyone else uses.

**[AirGuard — Protecting Android Users From Stalking Attacks By Apple Find My Devices](https://arxiv.org/abs/2202.11813)**
— Heinrich, Bittner, Hollick. The paper behind the app above, covering how Apple's own tracking
protection was reverse engineered.

**[Track You: A Deep Dive into Safety Alerts for Apple AirTags](https://petsymposium.org/popets/2023/popets-2023-0102.pdf)**
— Shafqat et al., PoPETs 2023. Analyses the anti-stalking alerting, which is the other half of
"how does a device that does not own a tag interact with it".

---

## In this repository

- **[#48](https://github.com/parawanderer/OpenTagViewer/issues/48)** and **[#139](https://github.com/parawanderer/OpenTagViewer/pull/139)** (@ubrt) — local Bluetooth scanning and playing a nearby accessory's sound.
  **[#139](https://github.com/parawanderer/OpenTagViewer/pull/139) is directly affected by stek29's finding**: if the GATT path only covers the unauthorised
  command, then the owner-nearby case — the one that matters when your own tag is next to you —
  is a different protocol.
- **[#17](https://github.com/parawanderer/OpenTagViewer/issues/17)** — the original "Play Sound" request, and where @ubrt's work started.
- **[#131](https://github.com/parawanderer/OpenTagViewer/issues/131)** — locating the owner's own iPhones, iPads and Macs. A different mechanism again (those
  self-report to iCloud), but it is the other half of "why does this app show less than Apple's".

---

*Compiled by Claude Code from a conversation with @parawanderer. Links checked at time of writing;
nothing here was read out of `rustpush`, `apple-private-apis` or `export-findmy`, per the
clean-room note in [`findmy-export/README.md`](./findmy-export/README.md).*
