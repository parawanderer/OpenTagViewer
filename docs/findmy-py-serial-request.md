# Request to FindMy.py: let a client set the serial it presents as

> **This is a request from a consumer, not a specification.** It is addressed to whoever works on
> FindMy.py. Delete it from this repository once the library has an answer, either way.
>
> Written against `feat/icloud-keychain-export`, the branch OpenTagViewer pins.

## The ask, in one line

**Make the device serial part of an account's identity — settable once, persisted with the
account, and used by everything that sends it** — rather than a default argument repeated at
each call site.

## Why this consumer needs it

OpenTagViewer is two clients built on this library: the Android app, and a desktop exporter now
being repointed from local macOS files to iCloud. Both authenticate as an invented device, and
both therefore appear in the user's Apple account device list — with a *Remove from Account*
button next to the words "If you do not recognise this device".

The project's [rule 11](../AGENTS.md) exists because of what follows from that: paths that
disagree about the identity do not fail, they **register separate devices**, and the user is
invited to remove entries they cannot identify, which breaks the session that was working.

The serial is the part of that identity a person actually reads. FindMy.py's own docstring says
so, better than this document could:

> **It is what names this client in the account's device list**, which is the one place a person
> ever sees it — and the entry is otherwise indistinguishable from a real Mac, since the model and
> OS strings above claim to be one. A recognisable serial is the difference between a device
> somebody can identify as software they installed and one they are invited to remove because they
> do not recognise it.
>
> — `findmy/reports/anisette.py`, on `CLIENT_SERIAL`

**That reasoning is the whole request.** The library already holds that the serial is a label
chosen for a human reader; what it does not yet have is a way for a client to choose one. So
every client built on it currently presents as `0FINDMYPY001`, and a user with two of them cannot
tell which entry is which — or that either is software they installed rather than hardware.

## What exists today

`CLIENT_SERIAL` is a module constant used as a **default argument value** in five places:

| | |
| --- | --- |
| `findmy/reports/anisette.py` | `BaseAnisetteProvider.get_headers`, `get_cpd`, and the `get_headers` overrides on both the local and remote providers |
| `findmy/reports/account.py` | `BaseAppleAccount.get_anisette_headers` and both implementations |
| `findmy/cloudkit/client.py` | `AsyncCloudKitClient.__init__(device_serial=...)` — the one place it is already a parameter |

Two consequences:

- **Rebinding the constant after import does nothing.** Default values are evaluated at
  definition time, so a client that sets `findmy.reports.anisette.CLIENT_SERIAL = "..."` changes
  no header that is actually sent. This looks like it works and does not.
- **`AsyncCloudKitClient`'s existing `device_serial` cannot be reached from the top.**
  `AsyncFindMyClient.open()` builds `AsyncBeaconStore(account)`, which builds the CloudKit client
  with defaults; nothing threads a serial through. Setting it means building all three by hand and
  bypassing `open()`, whose docstring says to use it.

## What a consumer has to do without it

Subclass both providers to ignore the argument they are passed:

```python
class ExporterAnisette(LocalAnisetteProvider):
    async def get_headers(self, user_id, device_id, serial=EXPORTER_SERIAL, with_client_info=False):
        return await super().get_headers(user_id, device_id, EXPORTER_SERIAL, with_client_info)
```

…and then build the client by hand to reach the CloudKit half:

```python
store = AsyncBeaconStore(account, client=AsyncCloudKitClient(
    account, device_name="OpenTagViewer Exporter", device_serial=EXPORTER_SERIAL))
client = AsyncFindMyClient(account, await AsyncKeychainSession.open(account), store)
```

This works. It is also four things that must stay in step — two subclasses, a hand-built client,
and a constant — to express one value, and it goes wrong silently: a path that misses the override
sends `0FINDMYPY001`, which does not fail, it registers a second device.

**It is also not restorable.** An account serialized and reloaded comes back with a *base*
provider reconstructed from its mapping, not the subclass — so a client that persists sessions
(the Android app does) gets its chosen serial on first login and the library's default on every
run afterwards. That is the exact failure rule 11 is about, and a subclass cannot fix it, because
the identity has to survive serialization to survive at all.

## Proposed shape

Whatever form suits the library. What matters is that one value is set once and reaches every
header:

```python
class BaseAnisetteProvider:
    def __init__(self, *, serial: str = CLIENT_SERIAL) -> None:
        self._serial = serial

    async def get_headers(self, user_id, device_id, serial=None, with_client_info=False):
        serial = serial or self._serial
        ...
```

Three properties the request depends on, in rough order of how much they matter:

1. **It must be serialized with the provider.** `LocalAnisetteMapping` and `RemoteAnisetteMapping`
   gain an optional `serial`, written when it differs from the default so existing `account.json`
   files stay valid and — more importantly — keep the identity they already have. A restored
   account that silently reverts to the default adds a device-list entry rather than reusing one,
   and the library's own docstring records that this may require signing in again.
2. **CloudKit should default to it rather than to the constant.** `AsyncCloudKitClient` already
   reads identity off the account (`client_info`, `device_uuid`); the serial belongs in the same
   place. Then a client sets one value and both halves agree, which is the property being asked
   for — a second knob for the CloudKit serial would recreate the problem in a new spot.
3. **The default must not change.** `0FINDMYPY001` for anything that does not ask, so no existing
   session's identity moves.

An equally good answer is putting it on the account rather than the provider — `AsyncAppleAccount`
is where `device_uuid` and `client_info` already live, and the serial is the same kind of thing.
The provider is suggested only because that is where the constant is today.

## What is not being asked for

- **No validation.** Whether a serial is plausible is the caller's problem. Ours is
  `0PENTAGVIEWR`, which is deliberately not hardware-shaped.
- **No change to the default identity.** The model, OS and CFNetwork strings that make this client
  a MacBook Pro are fine as they are and should stay internally consistent with each other.
- **No per-call override.** Keeping the argument is fine; what is missing is a place to set it
  once. If the argument goes away entirely, nothing here objects.
