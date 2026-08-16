# Report to FindMy.py: `announce_device` returns HTTP 401

> **A bug report from a consumer.** Addressed to whoever works on FindMy.py. Delete it from this
> repository once the library has an answer.
>
> Against `feat/icloud-keychain-export` at `534867d` — the commit that added the feature. Follow-up
> to [the device-name request](./findmy-py-device-name-request.md), which this implements.
>
> **The specification is not at fault here.** Stage 2 §7 states both of the values below
> explicitly. This is a divergence between the implementation and the document it was written
> from, which is why it is a report rather than a question.

## What happens

```
findmy.errors.UnhandledProtocolError: Announcing the device failed with HTTP 401
  File ".../findmy/reports/account.py", line 1554, in announce_device
```

On a live account, immediately after a successful login, on a machine Apple already knows (no
second factor was requested). The export itself is unaffected — the consumer treats a failed
announce as cosmetic — but the device list entry keeps its hardware name, which is the entire
point of the call.

Not a missing-token failure: `announce_device` raises `InvalidStateError` when `adsid` or
`idms_hb` are absent, and it did not. The token was present and the request was rejected.

## Two headers disagree with the specification, and with each other

Stage 2 §7 gives the header set for `POST gsas.apple.com/grandslam/GsService2/postdata`:

| Header | §7 requires | `account.py` sends |
| --- | --- | --- |
| `X-MMe-Client-Info` | `mmeClientInfoAkd` | `self._anisette.client` |
| `User-Agent` | `akdUserAgent` | `"akd/1.0 CFNetwork/978.0.7 Darwin/18.7.0"` |

### 1. `X-MMe-Client-Info` is the wrong variant

Stage 1 §2.2 defines three composite strings, and the difference between two of them is the
trailing bundle — *which Apple daemon is speaking*:

```
mmeClientInfo     = <iPhone15,2> <iPhone OS;17.4;21E219> <com.apple.AuthKit/1 (com.apple.MobileSMS/…)>
mmeClientInfoAkd  = <iPhone15,2> <iPhone OS;17.4;21E219> <com.apple.AuthKit/1 (com.apple.akd/1.0)>
```

`AnisetteProvider.client` produces neither. Observed value:

```
<MacBookPro18,3> <Mac OS X;13.4.1;22F8> <com.apple.AOSKit/282 (com.apple.dt.Xcode/3594.4.19)>
```

So the request tells a Grand Slam endpoint that **Xcode** is speaking, while its `User-Agent`
says **akd**. §7 asks for the akd variant specifically, and §1 (Stage 1 §2.2) is explicit that
the bundle identifies the speaker.

There is currently no way for a consumer to fix this from outside: the provider exposes `client`
and nothing else.

### 2. The `User-Agent` describes a different macOS from the client info

```
akd/1.0 CFNetwork/978.0.7 Darwin/18.7.0     <- hardcoded
<... Mac OS X;13.4.1;22F8 ...>              <- what the client info claims
```

Darwin 18 is macOS 10.14; the client info says 13.4.1, which is Darwin 22. Stage 1 §2.2 requires
that the OS version, build, CFNetwork version and Darwin version correspond to one another,
because they describe one real release and Apple's own clients never contradict themselves. This
request contradicts itself.

The specification's `akdUserAgent` example (`akd/1.0 CFNetwork/1494.0.7 Darwin/23.4.0`) is the
matching pair for *its* example device, iOS 17.4 — so it is an illustration of the rule, not a
constant to hardcode. The value has to be derived from whatever release the identity claims.

## What is not ruled out

The 401 has not been traced to either header — both are stated as divergences from §7, not as
proven causes. Two other candidates are untested here:

- **The `X-Apple-HB-Token` contents.** Stage 1 §8 observes the heartbeat token arrives as
  `service:token` — two fields, no lifetime — and `account.py:1323` reads
  `tokens["com.apple.gs.idms.hb"]["token"]`. Whether that yields the token alone or something
  else has not been checked here.
- **Whether the token survives the 2FA route.** `_set_login_state(REQUIRE_2FA, …)` stores only
  `adsid` and `idms_token`; the heartbeat is read in the `au is None` branch. Whether a
  post-2FA re-authentication reaches that branch is not something this consumer can observe.

## Suggested shape

The client-info variant is the one a consumer cannot work around:

```python
provider.client       # what exists: the AOSKit/Xcode form
provider.client_akd   # the com.apple.akd/1.0 form, per Stage 1 §2.2
```

with `announce_device` sending `client_akd`, and the `User-Agent` built from the same identity
rather than fixed — so the CFNetwork and Darwin versions match the OS the client info claims.

## How to reproduce

Any account, on a machine that has signed in before so no second factor is requested:

```python
account = AsyncAppleAccount(anisette, device_name="Anything")
await account.login(email, password)
await account.announce_device()      # HTTP 401
```

The consumer's own call site is `exporter/icloud.py::_announce`, which logs and continues.
