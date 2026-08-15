# Stage 2 — MobileMe delegate

Specification of the exchange that converts a Grand Slam session into iCloud service tokens.
This is the step between authenticating (Stage 1) and doing anything with iCloud.

Read [README.md](./README.md) first. In particular: **implement from this document alone.**

> **Verified against a live account on 2026-08-13**, and verified in the strongest available
> sense: the probe that exercised it was written **from this document**, not by calling any
> reference implementation. HTTP 200, top-level status 0, delegate status 0, service data
> returned, 11 tokens. So what follows is a description that someone has successfully built from.
>
> Values from that run are marked **[observed]**. One section — §6, the configuration
> dictionary — was **wrong** and has been corrected; the terms-of-service flow of §5.2 and the
> device lifecycle of §7 remain unexercised.

---

## 1. What this stage produces

A single request returns a set of named tokens and a service configuration dictionary. Three
tokens matter to this project:

| Token | Needed by |
| --- | --- |
| `cloudKitToken` | Stage 4 — CloudKit record fetches |
| `mmeAuthToken` | Stage 4 — the general iCloud credential, paired with the numeric account id |
| `searchPartyToken` | the Find My "search party" service, which is what actually holds beacon data |

**[observed] Eleven tokens were returned**, and all three above were among them. The full set is
in §5.

What does **not** come back is any service configuration — see §6, and do not plan on getting
endpoints from this stage.

## 2. Prerequisites

From Stage 1:

| Value | Where it came from |
| --- | --- |
| **PET** | `com.apple.gs.idms.pet`. Used as the HTTP Basic password. |
| `acname` | the account's canonical Apple ID. Used as the HTTP Basic username. |
| `adsid` | the account identifier, sent as a header |
| Anisette headers | the same provider as Stage 1, and it must be the *same machine identity* |

> **The PET expires in about five minutes.** This is the binding constraint on how Stage 1 and
> Stage 2 fit together: either they run back to back, or Stage 1 must be replayable without user
> interaction from the cached password digest. There is no way to hold a PET across a session.

A session is bound to the machine identity that established it (Stage 1 §2.1), so the Anisette
source must not change between the two stages.

## 3. The request

```
POST https://setup.icloud.com/setup/iosbuddy/loginDelegates
```

**This URL encodes the client's claimed platform.** `iosbuddy` is the iOS Setup Assistant's
endpoint, and it matches the iPhone identity of Stage 1 §2.2. A client presenting as a Mac uses
a different path. Claiming iOS in the identity and macOS in the URL is the kind of mismatch that
produces an unhelpful error, so keep them consistent.

### 3.1 Authentication

HTTP Basic, with:

- **username** — the Apple ID (`acname` from Stage 1, not necessarily what the user typed)
- **password** — the **PET**

The PET is why this works without re-entering a password, and why it must be fresh.

### 3.2 Headers

Send **all** the Anisette headers — unlike Stage 1's GSA requests, this endpoint is not given a
filtered subset. Then add:

| Header | Value |
| --- | --- |
| `X-Apple-ADSID` | `adsid` |
| `X-Mme-Client-Info` | `mmeClientInfoAkd` — the `com.apple.akd/1.0` variant from Stage 1 §2.2 |
| `User-Agent` | `com.apple.iCloudHelper/282 CFNetwork/<version> Darwin/<version>` |
| `Accept-Encoding` | `gzip` |

The user-agent follows the same `<item> CFNetwork/<v> Darwin/<v>` shape as elsewhere, with the
item naming the Apple component being impersonated — here the iCloud helper rather than `akd`.
Its version must match the CFNetwork and Darwin versions of the claimed OS release.

Two conditional headers:

- `X-Mme-Nas-Qualify` — base64 **validation data**, an attestation blob a genuine Apple device
  produces. This flow has none and **must omit the header entirely** rather than send it empty.
  See §3.4.
- `Cookie` — only when re-sending after accepting terms of service. See §5.2.

### 3.3 Body

An XML property list. **There are two forms, and which one to send depends on whether you have
validation data.** With none — which is this project's case — send the legacy form, whose keys
are hyphenated:

| Key | Type | Value |
| --- | --- | --- |
| `apple-id` | string | the Apple ID, same as the Basic username |
| `client-id` | string | a fresh v4 UUID, **lowercase**, per request |
| `password` | string | the **PET**, repeated here as well as in the Basic credential |
| `delegates` | dictionary | which services to request — see below |

The newer form, for clients that do have validation data, uses camelCase `delegates`,
`protocolVersion` (`"1.0"`) and a `userInfo` sub-dictionary containing hyphenated `client-id`
(**uppercase** UUID), `language` (`en-US`) and `timezone`. It is documented here only so that an
implementer recognises it; **this project sends the legacy form.**

`delegates` is a dictionary keyed by service identifier:

| Key | Value | Wanted here |
| --- | --- | --- |
| `com.apple.mobileme` | empty dictionary | **yes** |
| `com.apple.private.ids` | `{ "protocol-version": "4" }` | no — iMessage identity, irrelevant to this project |

Request only `com.apple.mobileme`. Asking for delegates you do not need means more of the
account's surface touched for no benefit.

### 3.4 On validation data

Validation data is an attestation blob that a real Apple device generates to prove it is one.
Producing it is a separate reverse-engineering problem, and this flow does not attempt it.

**The endpoint accepts requests without it**, via the legacy request form — this is load-bearing
for the whole project, and is the reason the two body shapes exist. An implementation must
therefore never send an empty `X-Mme-Nas-Qualify` or an empty validation field; absence and
emptiness are not the same to this endpoint.

## 4. The response

An XML property list dictionary. Check it in this order — the first two checks are cheap and
produce far better diagnostics than the third.

**1. `localizedError`.** If present, the request failed:

| Value | Meaning |
| --- | --- |
| `UNAUTHORIZED` | the credential was rejected. Usually an expired PET — re-run Stage 1 rather than reporting an auth failure to the user. |
| anything else | report verbatim, along with the `description` key if present |

**2. `status`.** An integer, and `0` means success. Anything else is a failure and the whole
response is the diagnostic.

**`dsid`.** The response also carries a top-level `dsid`: the **numeric** account identifier.
This is the same value as `DsPrsId` in the Stage 1 session payload, and it is what
[Stage 4 §2.1](./04-cloudkit.md) needs as its HTTP Basic username.

**Prefer this one.** Stage 1 §6 records that the session payload's keys vary between logins, so
depending on it for a value that is also available here is needless fragility. Taking `dsid` from
this response means Stage 4 needs nothing retained from Stage 1 at all.

**3. `delegates`.** A dictionary keyed by the same service identifiers that were requested. Each
value is:

| Key | Type | Meaning |
| --- | --- | --- |
| `service-data` | dictionary | the payload. **Absent when that delegate failed.** |
| `status` | integer | per-delegate status |
| `status-message` | string | per-delegate message |

**Per-delegate status is separate from the top-level status.** The request as a whole can
succeed while an individual delegate fails, so a missing `service-data` is not a parse error —
it is that delegate's failure, and its `status` and `status-message` are the explanation. Report
them rather than dereferencing nothing.

### 4.1 The MobileMe service data

The `service-data` dictionary for `com.apple.mobileme` holds:

| Key | Type | Contents |
| --- | --- | --- |
| `tokens` | dictionary | token name → token string. See §5. |
| everything else | — | the service configuration. See §6. |

> **The configuration is the service-data dictionary itself, not a nested key inside it.** An
> earlier reading of this protocol expected the configuration under a `com.apple.mobileme` key
> *within* `service-data`, and that is wrong. Read the tokens from the `tokens` key, and treat
> the same dictionary as the configuration. Expect a nested `com.apple.mobileme` key not to
> exist, and do not fail if it does.

## 5. Tokens

`tokens` maps names to opaque strings. **[observed]** All eleven returned for an ordinary
account, requesting only the MobileMe delegate:

| Name | Purpose |
| --- | --- |
| `mmeAuthToken` | the general iCloud credential. Presented as an HTTP Basic password with the **numeric** account id (`DsPrsId` from the Stage 1 session payload) as the username — note that is a *different* identifier from `adsid`. |
| `cloudKitToken` | CloudKit access, Stage 4 |
| `searchPartyToken` | **the one this project exists for** — the Find My search-party service, which holds beacon data |
| `mmeFMIPToken`, `mmeFMIPAppToken`, `mmeFMIPSiriToken` | Find My iPhone: service, application and Siri variants |
| `mmeFMFToken`, `mmeFMFAppToken`, `mmeFMFNotificationToken` | Find My Friends: service, application and notification variants |
| `keyTransparencyToken` | Apple's key-transparency service |
| `mapsToken` | Maps |

Keep the whole map, look tokens up by name, and fail with the name that was missing rather than
an index error. The Find My families each have three variants whose distinctions are not
established here, so store all of them rather than guessing which one a later stage wants.

### 5.1 Lifetime and refresh

These tokens are long-lived relative to the PET — a reference implementation refreshes them
**weekly** — but the refresh path is a full repeat of this stage, which needs a fresh PET, which
needs Stage 1. So the dependency chain for any long-running use is:

```
cached password digest -> Stage 1 -> PET (~5 min) -> Stage 2 -> service tokens (~1 week)
```

Cache the delegate response with the time it was obtained. Do not try to infer expiry from the
tokens themselves; nothing in the response states one.

### 5.2 Terms of service

If the account has unaccepted iCloud terms, this stage fails until they are accepted. **This is
implemented in the app**, because sending the user to an Apple device to do it is a dead end for
the very users this feature exists for.

#### How the failure arrives

The delegate response carries **two independent error channels**, and the one that matters here is
the one a reader is least likely to check:

| Where | Key | |
| --- | --- | --- |
| **Top level** of the response | `localizedError` | a short machine-ish string. `UNAUTHORIZED` is one known value |
| alongside it | `description` | the human-readable explanation |
| Top level | `status` | non-zero for a failure |
| Inside `delegates.com.apple.mobileme` | `status`, `status-message` | the per-delegate result |

**Check `localizedError` before `status`.** A reader that only looks at the two `status` fields —
which is the obvious implementation, since those are what a success is defined by — will report a
generic failure for a response that said exactly what was wrong in a field it never read.

> **[unestablished] Which `localizedError` value means "terms pending" is not known**, only that
> this is the channel it arrives on and that `UNAUTHORIZED` is a different value on the same
> channel. Do not branch on a guessed string. Surface `localizedError` and `description` verbatim,
> and offer the terms flow below as a remedy the user can choose — that behaves correctly whether
> or not the guess would have been right, and it records the real value the first time an account
> hits it.

The one rule that makes this acceptable: **the terms are fetched and displayed, and the user
performs the acceptance.** The protocol returns the terms text, so there is no reason to accept
on their behalf, and doing so would be agreeing to a contract on someone else's account without
showing it to them. Fetch, render, let them read, let them decide.

**Step 1 — fetch the terms.**

```
POST https://setup.icloud.com/setup/iosbuddy/ui/genericTermsUI
```

with the setup headers of §5.3, plus `Accept: application/x-buddyml`,
`Content-Type: text/plist` and `X-Apple-I-Appearance: 1`. Body is a plist:

| Key | Value |
| --- | --- |
| `format` | `plist/buddyml` |
| `terms` | array of dictionaries, each `{ "name": "iCloud" }` |

**Step 2 — read the response.** It is *not* a plist. It is **BuddyML**, an Apple XML dialect
used by Setup Assistant, and it must be parsed as XML:

| Element | What to take |
| --- | --- |
| `clientInfo` | its `agreeUrl` attribute — the URL that records acceptance |
| `page` | its `id` attribute, identifying which terms document this is (`iCloud`) |
| `html` | character data — the terms themselves, as HTML, usually inside CDATA |

Configure the XML reader to **treat CDATA as characters**, or the terms text arrives empty. Take
the HTML for the page whose id matches the terms requested, and render it.

> ### Renew the PET before accepting, not before fetching only
>
> §5.3 authenticates these endpoints with the **PET**, and §5.1 puts a PET at about five minutes.
> This flow puts *a person reading a contract* in the middle of that. **The token will usually have
> expired by the time they agree.**
>
> The failure lands at the worst possible moment: after the user has read the terms and said yes.
> To them, they accepted a contract and were told to sign in again — and an app that loops them
> back through login has asked for agreement twice to record it once.
>
> **So re-authenticate immediately before each terms request**, the acceptance especially. It costs
> one GSA exchange in a flow that already contains a human, and the alternative is unrecoverable
> without asking them to agree a second time.
>
> **This is always possible here, and that is not a coincidence.** Renewing a PET means repeating
> the SRP exchange, which needs the password — and terms can only block a login that is *in
> progress*, so the password is necessarily in hand. A session restored from storage has already
> passed this stage.
>
> The exception is the token refresh of §5.1, which repeats this stage unattended a week later. If
> Apple has published new terms by then and no password is retained, the PET cannot be renewed and
> there is nothing to do but ask for a fresh sign-in. **Fall back to the token already held rather
> than failing at the renewal** — that lets the request fail on its own terms, which says more than
> a pre-emptive error would.

**Step 3 — the user accepts.** Only after they have actually seen it:

```
POST <agreeUrl>
```

with the setup headers, `Content-Type: application/xml`, and no body. A non-success status means
acceptance failed and must be surfaced, not swallowed — an app that reports "accepted" when
Apple said otherwise will fail confusingly at the next step instead.

**Step 4 — repeat this stage.** The delegate request of §3 should now succeed.

A client presenting as a Mac uses a different route, re-sending the delegate request with a
`Cookie: termsAccepted=true` header. That path is irrelevant here — the identity of Stage 1 §2.2
is an iPhone — and is noted only so it is not mistaken for the one to implement.

### 5.3 Setup headers

The terms endpoints take a different header set from §3.2. All the Anisette headers, plus:

| Header | Value |
| --- | --- |
| `Authorization` | `Basic ` + base64 of the Apple ID + `:` + the **PET** |
| `User-Agent` | `iOS iPhone <build> iPhone Setup Assistant` |
| `X-MMe-Client-Info` | `<model> <iPhone OS;version;build> <com.apple.AppleAccount/1.0 (com.apple.Preferences/1112.96)>` |
| `X-MMe-Country` | `US` |
| `X-MMe-Language` | `en,en-US` |
| `Cookie` | `repairSteps=` |

Note the bundle in `X-MMe-Client-Info` is Preferences here, not `akd` — these endpoints are
addressed as Settings would address them.

## 6. The configuration dictionary

> **Correction. [observed] There is no configuration.** An earlier draft of this section, taken
> from reading how other services use this protocol, said the service data carries per-dataclass
> endpoints keyed as `com.apple.Dataclass.<Name>`. For a request that asks only for
> `com.apple.mobileme`, it does not. The service data held `tokens` and exactly one other key:
>
> | Key | Value |
> | --- | --- |
> | `protocolVersion` | a version marker |
>
> No dataclass keys, no service URLs, and no nested `com.apple.mobileme` key either.

Dataclass configuration does exist in this protocol — other clients read a quota URL and a
shared-streams URL from exactly such keys — so the likeliest explanation is that Apple returns
configuration only for dataclasses the client asks about or claims to support, and this request
asks for none. That is a hypothesis, not a finding.

**What follows for Stage 4, now settled:** the CloudKit *credential* comes from here, but the
CloudKit *endpoint* does not — and it turns out not to be needed from here either. Opening the
container returns the service URLs directly. See
[04-cloudkit.md §2.3](./04-cloudkit.md). Nothing further is required of this stage.

## 7. Naming the registered device

[Stage 1 §13](./01-authentication.md#13-signing-in-registers-a-device-observed) established that
signing in registers a device on the account, and that this stage's predecessor cannot set its
name — the entry appears named after whatever hardware the client claims to be. A separate
endpoint carries a device name, and calling it once is what turns a bare `iPhone` into something
the user recognises.

```
POST https://gsas.apple.com/grandslam/GsService2/postdata
```

> **Note the host: `gsas.apple.com`, not `gsa.apple.com`.** One letter, a different host, and no
> useful error if you get it wrong.

This is a Grand Slam endpoint, not an iCloud one, so it takes GSA-style headers rather than the
ones in §3.2: the Anisette subset, plus `Content-Type: text/x-xml-plist`, plus

| Header | Value |
| --- | --- |
| `X-Apple-HB-Token` | base64 of `adsid` + `:` + the `com.apple.gs.idms.hb` token from Stage 1 |
| `X-Apple-I-UrlSwitch-Info` | base64 of `adsid` + `:postdata` |
| `X-MMe-Client-Info` | `mmeClientInfoAkd` |
| `User-Agent` | `akdUserAgent` |
| `X-Apple-I-Service-Type` | `itunesstore` |
| `X-Apple-I-CDP-Status`, `X-Apple-I-OT-Status`, `X-Apple-I-CK-Presence` | `true` |
| `X-Apple-AK-DataRecoveryService-Status` | `1` |
| `X-Apple-I-Device-Configuration-Mode`, `X-Apple-I-DeviceUserMode`, `X-Apple-Requested-Partition`, `X-Apple-I-TimeZone-Offset` | `0` |
| `x-apple-i-device-type` | `1` |

Body is the usual `Header`/`Request` plist envelope. The `Request` dictionary announces the
client's state; the field that matters here is `dn`, the device name:

| Key | Type | Value |
| --- | --- | --- |
| `dn` | string | **the device name** — what appears in the account's device list |
| `event` | string | `liveness` |
| `loc` | string | `en_US` |
| `services` | array | empty, for a client that provides none |
| `cdpStatus`, `circleStatus`, `otStatus`, `icscStatus`, `prkgen`, `denyICloudWebAccess` | boolean | `true`, except `icloudMailEnabled` and `stingrayDisabledIndicator` which are `false` |
| `rep`, `ut`, `signinPartition`, `isLegacyContactAssignee`, `isRecoveryContactAssignee` | integer | `1` |
| `reason` | integer | `5` |
| `usrt` | integer | `4` |
| `pkc` | string | `"1"` |
| `cfuids` | array | empty |
| `ptkn` | string | an APNs push token, **omitted entirely** by this flow — see below |

**Never send `ptkn`.** The push token is the most likely reason a device becomes trusted for
verification codes, and Stage 1 §13 argues at length that this client must never become a second
factor for the user's Apple ID. Omit the key entirely rather than sending it empty.

## 7.1 The device is the app's identity, not a temporary artefact

**[observed, Stage 1 §7]** A second sign-in from the same machine identity required no second
factor. Apple remembers the machine, and stops challenging it.

That matters because this feature is not a one-shot export. Noticing a newly-paired accessory
means re-reading the account periodically, which means re-authenticating periodically — and that
is only silent while a device registration exists for Apple to recognise. **Registering and
removing around each operation would demand a two-factor code every time**, which is fatal for a
background refresh and worse for the user than the entry it avoids.

So the registration is created once and kept for the life of the installation.

### Announce it, in the app, in plain terms

The user should learn what exists on their account from the application, not from finding it
themselves:

> Signing in registers this app as a device on your Apple account, so Apple will keep talking to
> it without asking for a verification code every time. It appears in your device list as
> **OpenTagViewer**, serial **0PENTAGVIEWR**. It cannot receive verification codes. Signing out
> removes it.

Both identifiers must be the ones actually sent — the name from `dn` above, the serial from
`X-Apple-I-SRL-NO` in Stage 1 §2.2 — because those are the two fields visible in the device list,
and they are what the user will match against.

### Remove it at sign-out, and only then

Sign-out is the one moment removal is correct: the user has deliberately disconnected the
account, so the registration has no further purpose and a two-factor prompt is no longer a cost
anyone is paying. The protocol offers a sequence of sign-out events through the same endpoint,
with the request body changed:

| Key | Value |
| --- | --- |
| `event` | `signout-<service>`, and finally `signout-all` |
| `dn` | the same device name |
| `services` | the services **still** signed in — i.e. shrinking to empty across the sequence |
| `cdpStatus`, `circleStatus`, `otStatus` | `false` |
| `prkgen` | `true` |
| `rep`, `ut` | `1` |
| `loc` | `en_US` |

and the headers to match: `X-Apple-I-CDP-Status` and `X-Apple-I-OT-Status` become `false`, and
`X-Apple-I-Service-Type` and `X-Apple-AK-DataRecoveryService-Status` are dropped entirely.

The reference walks every service it might have used — iCloud, iMessage, FaceTime — signing each
out in turn with the remainder listed, then a final `signout-all`. This client only ever uses
iCloud, so `signout-icloud` followed by `signout-all` is the plausible minimum, **and that is an
inference, not an observation**.

> **Two things here are unverified and must not be stated to the user as fact.** Whether this
> sequence removes the device from the account list at all, rather than merely signing services
> out. And how far `signout-all` reaches — it carries a device name, which *suggests* it is
> scoped to this device, but nothing here has confirmed that. Until both are established, prefer
> attempting `signout-icloud` alone, report what was attempted rather than what was achieved,
> and point the user at their device list.

### The failure path is the one that matters

A crash, a lost connection or a force-quit during sign-out leaves the registration behind. That
is the path back to an accumulating device list, so:

- Removal must be **idempotent and retried** — attempt it again on next launch if the last
  sign-out did not complete.
- The app should be able to say *"an earlier sign-out may not have finished; a device called X
  may still be listed"* and link the user to their device list, rather than staying silent.
- **Never regenerate the identity to recover from a failed removal.** A fresh identity does not
  clean up the old entry, it adds a second one — which is exactly how twenty of them appear.

### The escrow record follows the same logic, in reverse

Stage 3 creates an escrow record whose purpose is to let the client *re-establish* keychain trust
if it loses its local state. So the two artefacts have opposite lifetimes: the device registration
is what keeps ordinary access working and is kept, while the escrow record is only insurance and
can be deleted once trust is established — at the cost of needing the user's device passcode
again should local state ever be lost. Stage 3 specifies that trade properly.

## 8. Open questions

**Answered by the runs of 2026-08-13:**

1. ~~Does the legacy request form still work?~~ **Yes.** This was the load-bearing unknown for the
   whole project — the basis for authenticating without an attestation blob. Apple has not
   withdrawn it.
2. ~~What is the full token set?~~ **Eleven names**, listed in §5.
3. ~~Which configuration key carries the CloudKit endpoint?~~ **None — no configuration is
   returned at all.** It comes from opening the container instead; see §6.
4. ~~Is the configuration nested under a `com.apple.mobileme` key?~~ **No**, and there is no such
   key.

**Still open:**

5. **Is `UNAUTHORIZED` really always an expired PET?** It is the obvious cause and the useful
   default, but treating every rejection as "retry Stage 1" risks an infinite loop against an
   account that is genuinely refused. Bound the retry.
6. **Does requesting only `com.apple.mobileme` return the same service data as requesting it
   alongside the IDS delegate?** Assumed yes; unverified.
7. **What do the terms endpoints return for an account with nothing outstanding?** The acceptance
   flow of §5.2 must not fire spuriously, so the no-terms-pending response needs to be
   distinguishable from one carrying terms.

**Open, and only relevant if the device-naming call of §7 is implemented:**

8. **Does it rename an existing entry, or only name a new one?** If only at registration, calling
   it later is useless and the serial of Stage 1 §2.2 is the only lever.
9. **Does the sign-out sequence remove the device from the account, or only sign services out?**
   §7.1 makes removal at sign-out the policy, and this decides whether that promise can be kept.
   Directly testable: run it and watch the device list.
10. **Does re-registering after a removal create a fresh entry or restore the old one?** Bears on
    whether removal at sign-out costs a two-factor prompt on the next run — see the README.

