# Request to FindMy.py: let a client name itself in the account's device list

> **This is a request from a consumer, not a specification.** It is addressed to whoever works on
> FindMy.py. Delete it from this repository once the library has an answer, either way.
>
> Written against `feat/icloud-keychain-export`, the branch OpenTagViewer pins. Companion to
> [the serial request](./findmy-py-serial-request.md), which asks for the other half of the same
> problem.

## The ask, in one line

**Let a client set the device name it registers under, and make the call that sets it** — the
`postdata` announce of
[Stage 2 §7](./findmy-export/02-mobileme-delegate.md#7-naming-the-registered-device), which nothing
in the library currently makes.

## Why this consumer needs it

Signing in registers a device on the user's Apple account. They see it, on a page with a
*Remove from Account* button next to the words *"If you do not recognise this device"*.

Without the announce call, the entry is named after whatever hardware the client claims to be — a
bare `iPhone` or `MacBook Pro`, sitting in a list of the user's real hardware with nothing to tell
it apart. The serial request covers one field of that row. This covers the one a person actually
reads first.

Two clients are built on this library here, and both would set it:

| | Would register as |
| --- | --- |
| The Android app | `OpenTagViewer App` |
| The desktop exporter | `OpenTagViewer Exporter` |

Which is the whole point: a user looking at that list should be able to tell which of their own
programs made each entry, and remove the right one.

## What exists today

Nothing. `postdata`, `liveness` and `gsas.apple.com` appear nowhere in the library, so a
FindMy.py client cannot be named at all — it inherits whatever its claimed hardware implies.

## What a consumer has to do without it

Reimplement a GSA-style request outside the library: its own header set, its own
`X-Apple-HB-Token` built from the `com.apple.gs.idms.hb` token, its own plist envelope — using
account internals it has no business reaching into. Every consumer that wants a recognisable
entry writes the same thing, and each one gets its own chance to send `ptkn` by mistake.

## Proposed shape

Same as the serial: **part of the account's identity, settable once, persisted with it.**

```python
account = AsyncAppleAccount(anisette, device_name="OpenTagViewer App")
```

and a method that performs the announce, callable after login:

```python
await account.announce_device()      # sends the postdata liveness event
```

Whether it is called automatically at login or left to the caller is the library's choice. Doing
it automatically is friendlier; leaving it explicit is more honest about the fact that it writes
to the user's account. Either is better than not being able to do it.

[Stage 2 §7](./findmy-export/02-mobileme-delegate.md#7-naming-the-registered-device) gives the
endpoint, the headers and every field of the body.

## The one thing that must not be optional

**Never send `ptkn`.** The push token is the most likely reason a registered device becomes
trusted for verification codes, and a library that made its consumers into second factors for
their users' Apple IDs would be doing real harm. Omit the key entirely — not empty, absent.

If the announce is implemented at all, this should be structural: no parameter, no way to pass
one, nothing a caller can switch on.

## What is not being asked for

- **No validation.** Whether a name is sensible is the caller's problem.
- **No renaming flow.** Setting it at registration is enough. What Apple does with a second
  announce under a changed name is not something this consumer needs answered.
- **No push support.** The absence of `ptkn` is the feature, not a gap to fill later.
