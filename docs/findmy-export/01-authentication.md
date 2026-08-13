# Stage 1 — Apple ID authentication (Grand Slam / GSA)

Specification of the authentication exchange between a client and Apple's Grand Slam
authentication service, from an Apple ID and password to a set of session tokens that later
stages consume.

Read [README.md](./README.md) first. In particular: **implement from this document alone.**

> **Verified against a live account on 2026-08-13.** A probe built from this document
> authenticated end to end: SRP handshake, server proof verified, session payload decrypted,
> trusted-device two-factor, 25 service tokens collected. Values from that run are marked
> **[observed]**. Everything unmarked is still derived by reading rather than running — the SMS
> path in particular was not exercised.
>
> That the server proof verified is the strongest single result: `M2` only matches if the
> client's entire SRP computation agrees with Apple's, so all three deviations in §4.6 are
> confirmed rather than merely plausible.
>
> The run also settled the question that shapes the whole feature: **signing in registers a
> device on the account.** See §13.

---

## 1. What this stage produces

On success the client holds:

| Value | Origin | Used by |
| --- | --- | --- |
| `adsid` | decrypted session payload | every later stage; Apple's numeric account identifier (the "DSID") |
| `GsIdmsToken` | decrypted session payload | building the two-factor identity token |
| `acname` | decrypted session payload | the account's canonical Apple ID; may differ from what the user typed |
| **PET** (`com.apple.gs.idms.pet`) | response header, or the session payload | Stage 2. Short-lived — assume 5 minutes. |
| Service tokens, keyed by reverse-DNS service name | response headers, or the session payload | later stages; `com.apple.gs.idms.hb` is needed by several GSA endpoints |
| The password's SHA-256 digest | computed locally | silently re-authenticating when a token expires |

The PET ("password-equivalent token") is the handover to Stage 2: it is presented as the
password in an HTTP Basic credential against the MobileMe delegate endpoint. Because it expires
in minutes, Stage 1 and Stage 2 must run back to back, or Stage 1 must be repeatable without
user interaction (see §11).

## 2. Prerequisites

### 2.1 Anisette

Every request in this stage carries **Anisette** headers: a one-time password and machine
identifier produced by Apple's ADI libraries from provisioned, per-installation state. Without
valid Anisette, GSA rejects the request regardless of whether the password is correct.

This document treats Anisette as a solved dependency and specifies only the header names it
must supply. OpenTagViewer generates these on-device from Apple's own Android ADI libraries —
see `app/src/main/java/.../anisette/` — falling back to a remote Anisette server when it
cannot.

The Anisette provider must supply:

| Header | Meaning |
| --- | --- |
| `X-Apple-I-MD` | The one-time password. Changes per request. |
| `X-Apple-I-MD-M` | The machine identifier from the provisioned ADI state. |
| `X-Apple-I-MD-RINFO` | Routing information, a decimal integer as a string. |
| `X-Apple-I-MD-LU` | The local user identifier — a stable hex string. |
| `X-Mme-Device-Id` | A stable UUID identifying this installation. |
| `X-Apple-I-Client-Time` | Current UTC time, ISO 8601, whole seconds, `Z` suffix — e.g. `2026-08-13T09:41:07Z`. |
| `X-Apple-I-TimeZone` | `UTC`. |
| `X-Apple-Locale` | `en_US`. |

Headers are selected **by exact name**, and anything unrecognised is silently dropped rather
than rejected. So a provider that omits one — or spells it differently — does not fail at the
point of the mistake; authentication fails later, for a reason that looks unrelated. Verify the
provider's output against the six names above before debugging anything else. (Observed:
macOS's own Anisette omits `X-Apple-I-MD-RINFO` and `X-Apple-I-Client-Time` entirely.)

Two hard requirements:

- **The identity must be stable across logins.** `X-Apple-I-MD-LU` and `X-Mme-Device-Id` must be
  derived from the same persisted state that provisioned ADI, and must not be regenerated. An
  identity that changes per login makes every session look like a new machine, which is the
  pattern two-factor authentication exists to detect.
- **A session is bound to the identity that established it.** Switching between a local Anisette
  provider and a remote server, or between two different remote servers, presents a different
  machine to Apple and invalidates the session. This is inherent to how Apple binds sessions.

Anisette headers may be cached and reused for a short window; **60 seconds** is the interval the
reference implementations use before regenerating. The one-time password is genuinely one-time
in the sense that it is time-derived, not that Apple rejects a repeat within its validity window.

### 2.2 Client identity

The client presents itself as an iPhone. The values are invented and are not validated against
real hardware, but they must be **internally consistent** and **stable across logins**.

A worked example, using an iPhone 14 Pro on iOS 17.4:

| Field | Example value |
| --- | --- |
| Hardware model | `iPhone15,2` |
| OS version | `17.4` |
| Build | `21E219` |
| CFNetwork version | `1494.0.7` |
| Darwin version | `23.4.0` |
| Device serial | **User-visible — choose it as a label.** Uppercase alphanumeric, 10–12 characters. `0PENTAGVIEWR` is confirmed accepted and displayed. See §13. |
| Device name | any user-visible string |
| Device UUID | a random v4 UUID, **uppercase**, generated once and persisted |
| UDID | 32 uppercase hex characters, generated once and persisted |

The OS version, build, CFNetwork version and Darwin version must correspond to one another —
they describe one real iOS release, and Apple's own clients never disagree with themselves.
Changing the hardware model means changing all of them together.

From those, three composite strings are built:

```
mmeClientInfo     = <iPhone15,2> <iPhone OS;17.4;21E219> <com.apple.AuthKit/1 (com.apple.MobileSMS/1262.500.151.1.2)>
mmeClientInfoAkd  = <iPhone15,2> <iPhone OS;17.4;21E219> <com.apple.AuthKit/1 (com.apple.akd/1.0)>
akdUserAgent      = akd/1.0 CFNetwork/1494.0.7 Darwin/23.4.0
```

The general shape is `<MODEL> <iPhone OS;VERSION;BUILD> <BUNDLE>`, where the trailing bundle
identifies which Apple daemon or app is speaking. The client also uses a fixed browser
user-agent string for the two-factor web endpoints:

```
browserUserAgent  = Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)
```

That it names macOS while everything else names iOS is not a mistake to correct — the
two-factor endpoints are web endpoints and are addressed as a browser would address them.

The client also presents an application identity. Throughout this stage it claims to be
Messages:

```
akContextType   = imessage
clientAppName   = Messages
clientBundleId  = com.apple.MobileSMS
```

Finally, a **hardware headers** map exists for values a genuine Apple device would send
(`X-Apple-I-SRL-NO` and similar). For an invented identity it is **empty**, and every header
set below that mentions it contributes nothing.

### 2.3 TLS

`gsa.apple.com` presents a certificate chaining to Apple's original **Apple Root CA**, which is
not in Android's system trust store. A plain HTTPS request fails the handshake:

```
Unacceptable certificate: CN=Apple Root CA, O=Apple Inc., C=US
```

**Do not disable certificate verification.** Add that single root, for those Apple hosts only,
alongside the system trust store — a strictly narrower trust decision than the default, not a
looser one. The certificate is published at <https://www.apple.com/appleca/>; its SHA-256
fingerprint is:

```
B0:B1:73:0E:CB:C7:FF:45:05:14:2C:49:F1:29:5E:6E:DA:6B:CA:ED:7E:2C:68:C5:BE:91:B5:A1:10:01:F0:24
```

OpenTagViewer already carries this root in its network security configuration for the Anisette
provisioning work. Reuse that rather than adding a second copy.

### 2.4 HTTP client

- **Cookies must persist across requests for the whole stage.** The two-factor endpoints depend
  on cookies set during the SRP exchange. A stateless client fails partway through with errors
  that do not mention cookies.
- Apple's servers are sensitive to header casing. Send headers in the conventional
  `X-Apple-I-MD` title case rather than lowercasing them, and do not rely on an HTTP/2 client
  that normalises them.

---

## 3. Message envelope

The GSA service endpoint is:

```
POST https://gsa.apple.com/grandslam/GsService2
```

Requests and responses are **XML property lists**. Every request body is a dictionary with
exactly two keys:

```
Header  → dictionary: { "Version": "1.0.1" }
Request → dictionary: the operation-specific payload
```

Every response body is a dictionary whose `Response` key holds the reply dictionary.

### 3.1 Errors

Inside the reply, a `Status` dictionary carries the outcome. If `Status` is absent, the reply
dictionary itself carries these keys:

| Key | Type | Meaning |
| --- | --- | --- |
| `ec` | integer | Error code. `0` means success. |
| `em` | string | Human-readable message. |

Check `ec` after **every** exchange, before reading any other field. A non-zero `ec` with its
`em` is the only diagnostic Apple gives, so surface both verbatim in logs and in whatever the
user sees; the messages are specific enough to act on and guessing at them wastes hours.

Content type for these requests is `text/x-xml-plist`, and `Accept` is `*/*`.

---

## 4. The SRP handshake

Authentication is **SRP-6a** — a password-authenticated key exchange in which the password
never crosses the network and both sides end up holding a shared session key. It runs as two
round trips against the endpoint in §3.

Apple's variant deviates from RFC 5054 in three specific ways. Each is called out below, and
each will produce an indistinguishable "authentication failed" if implemented to the letter of
the RFC instead. **§4.6 lists them together; read it before writing any of this.**

### 4.1 Parameters

- Group: the **2048-bit group from RFC 5054, appendix A** — modulus `N`, generator `g = 2`. Both
  are public constants; take them from the RFC.
- Hash: **SHA-256** throughout.
- Client secret `a`: **32 random bytes** from a cryptographically secure source.
- `A = g^a mod N`.

### 4.2 Round trip one — `init`

Request payload:

| Key | Type | Value |
| --- | --- | --- |
| `o` | string | `init` |
| `u` | string | The Apple ID as the user typed it |
| `A2k` | data | `A`, big-endian |
| `ps` | array of string | `["s2k", "s2k_fo"]` — the password-derivation schemes offered |
| `cpd` | dictionary | See §5 |

Reply:

| Key | Type | Meaning |
| --- | --- | --- |
| `s` | data | Salt. **[observed]** 16 bytes. |
| `B` | data | Server public value, big-endian. **[observed]** 256 bytes. |
| `i` | integer | PBKDF2 iteration count. **[observed]** 20082 — note it is neither round nor a power of two. Do not hardcode it; it is Apple's to choose and may vary by account or over time. |
| `c` | string | An opaque continuation value, echoed in round trip two |
| `sp` | string | The scheme the server chose: `s2k` or `s2k_fo`. **If absent, assume `s2k`.** **[observed]** present, and `s2k`. |

### 4.3 Deriving the SRP password

The raw password is never used directly. Let `P₀ = SHA-256(password_utf8)` — this digest is
also what gets cached in §11.

Then, depending on `sp`:

| `sp` | Input to PBKDF2 |
| --- | --- |
| `s2k` | `P₀` — the 32 raw digest bytes |
| `s2k_fo` | The **lowercase hexadecimal ASCII encoding** of `P₀` — 64 bytes, not 32 |

Then:

```
P = PBKDF2-HMAC-SHA256(input, salt = s, iterations = i, output length = 32)
```

`P` is what the SRP computation below calls the password. Note that the `s2k_fo` case is a
double transformation: hash the password, then hex-encode that hash *as text*, then stretch it.

**[observed]** Apple selected `s2k`. The `s2k_fo` branch is therefore specified but unexercised,
and should be treated as the less trustworthy of the two — it is offered in `ps`, so Apple may
select it for some accounts, but nothing here has seen it happen.

### 4.4 Client computation

```
x  = SHA-256( s ‖ SHA-256( "" ‖ ":" ‖ P ) )          ← deviation 1, see §4.6
u  = SHA-256( A ‖ B )                                 ← deviation 2, see §4.6
k  = SHA-256( N ‖ PAD(g) )
S  = (B − k·g^x) ^ (a + u·x)  mod N
K  = SHA-256(S)
M1 = SHA-256( (SHA-256(N) ⊕ SHA-256(PAD(g))) ‖ SHA-256(I) ‖ s ‖ A ‖ B ‖ K )
M2 = SHA-256( A ‖ M1 ‖ K )
```

where `I` is the Apple ID as typed, `PAD(g)` is `g` left-padded with zero bytes to the byte
length of `N` (256 bytes), and `‖` is concatenation.

`M1` and `M2` are standard SRP-6a. `K` is the session key that decrypts the payload in §6.

Reject the exchange if `B mod N == 0`.

Computing `B − k·g^x` in modular arithmetic can go negative. Add `N` before subtracting:
`((N + B) − (k·g^x mod N)) mod N`.

### 4.5 Round trip two — `complete`

Request payload:

| Key | Type | Value |
| --- | --- | --- |
| `o` | string | `complete` |
| `u` | string | The same Apple ID string sent in `init` |
| `M1` | data | `M1` |
| `c` | string | The `c` value from the `init` reply, echoed unchanged |
| `cpd` | dictionary | See §5 — **the same request UUID as `init`** |

Reply:

| Key | Type | Meaning |
| --- | --- | --- |
| `M2` | data | Server proof. Compare against the locally computed `M2` and abort on mismatch. |
| `spd` | data | The encrypted session payload — see §6 |
| `Status` | dictionary | `ec`/`em`, plus `au` if a second factor is required — see §7 |

Verifying `M2` is what authenticates Apple to the client. It is not optional: skipping it means
a party who never knew the password can complete the exchange.

### 4.6 The three deviations, and the serialisation trap

**Deviation 1 — the identity in `x` is empty.** RFC 5054 computes
`x = H(s ‖ H(I ‖ ":" ‖ P))` with `I` the username. Apple computes the inner hash with the
username **omitted**: `H("" ‖ ":" ‖ P)`, which is `SHA-256(":" ‖ P)` — the colon is still there.
Note the asymmetry: `M1` *does* use the real username, as `SHA-256(I)`. Getting this backwards
is the single most likely cause of a handshake that fails with correct credentials.

**Deviation 2 — `u` is not padded.** RFC 5054 computes `u = H(PAD(A) ‖ PAD(B))`. Apple hashes
`A` and `B` at their natural lengths, with no padding. `k` and `M1` *do* pad `g`. So padding
applies to `g` and not to `A` or `B`, within the same handshake.

**Deviation 3 — big integers are serialised minimally.** `A`, `B` and the `g` inside `k` and
`M1` are big-endian byte strings **with no leading zero bytes and no sign byte**. Two things
follow, and both are silent failures rather than errors:

- Whenever `A` or `B` has a leading zero byte in its natural 256-byte form — roughly one time in
  256 — a fixed-width encoding produces a different `u` and the handshake fails. This is an
  intermittent, unreproducible login failure if implemented wrongly, so it will not show up in
  testing.
- Many big-integer libraries emit a leading `0x00` on export to keep the value positive. That
  byte must be stripped. Equally, `g = 2` must be encoded as the single byte `0x02` before
  padding, not as a wider machine word.

**[observed]** On the verified run both `A` and `B` were the full 256 bytes, so the
leading-zero case did not arise. That is the expected outcome roughly 255 times in 256 and is
emphatically not evidence the trap is absent — it is the reason the trap is dangerous. A wrong
implementation passes this test almost every time.

Round-trip your encoder against a known value before debugging anything else.

---

## 5. The `cpd` dictionary

`cpd` is a dictionary carried inside both SRP request payloads. It repeats some of the Anisette
headers as payload fields and adds client capability flags.

Generate **one** uppercase v4 UUID per login attempt and use it for `X-Apple-I-Request-UUID` in
both round trips. A different UUID in `complete` breaks the correlation with `init`.

Copy these straight from the Anisette headers, unchanged:

```
X-Apple-I-Client-Time      X-Apple-I-MD        X-Apple-I-MD-LU
X-Apple-I-MD-M             X-Apple-I-MD-RINFO  X-Mme-Device-Id
```

Add, as strings:

| Key | Value |
| --- | --- |
| `X-Apple-I-Device-Configuration-Mode` | `0` |
| `X-Apple-I-Request-UUID` | the per-login UUID |
| `X-Apple-Requested-Partition` | `0` |
| `X-Apple-Security-Upgrade-Context` | `com.apple.authkit.generic` |
| `capp` | `Messages` |
| `cbid` | `com.apple.MobileSMS` |
| `cou` | `US` |
| `loc` | `en_US` |
| `svct` | `imessage` |

Add, as booleans and integers — the plist types matter, a boolean sent as the string `"true"`
is not the same value:

| Key | Type | Value |
| --- | --- | --- |
| `X-Apple-Offer-Security-Upgrade` | boolean | true |
| `at` | integer | 0 |
| `bootstrap` | boolean | true |
| `ckgen` | boolean | true |
| `fcd` | boolean | true |
| `icdrsDisabled` | boolean | false |
| `icscrec` | boolean | true |
| `pbe` | boolean | false |
| `prkgen` | boolean | true |
| `webAccessEnabled` | boolean | false |

Finally, merge in the hardware headers map (§2.2), which is empty for an invented identity.

A push token field `ptkn` also exists here, carrying an uppercase hex APNs token. It belongs to
clients that maintain a push connection. This flow does not, so **omit the key entirely** —
do not send it empty.

---

## 6. Decrypting the session payload

`spd` from the `complete` reply is AES-encrypted under keys derived from the SRP session key
`K`. Derive them by HMAC, using `K` as the HMAC key and two fixed ASCII strings as messages:

```
key = HMAC-SHA256( K, "extra data key:" )              → 32 bytes, the AES key
iv  = HMAC-SHA256( K, "extra data iv:" )[0 .. 16]      → first 16 bytes only
```

Both trailing colons are part of the strings.

Decrypt `spd` with **AES-256-CBC** and **PKCS#7** padding. The plaintext is an XML property list
dictionary:

| Key | Type | Meaning |
| --- | --- | --- |
| `adsid` | string | The account's numeric identifier (DSID) |
| `GsIdmsToken` | string | Paired with `adsid` to form the two-factor identity token (§7.1) |
| `acname` | string | The account's canonical Apple ID. **Store this** in preference to what the user typed. |
| `fn`, `ln` | string | Given and family name |
| `t` | dictionary | Service tokens — see §6.1 |

The payload is decrypted even when a second factor is still required; `adsid` and `GsIdmsToken`
are available at that point and are needed to request the second factor.

**[observed]** The dictionary held **49 keys** from 4096 bytes of ciphertext on a first login,
and **50 keys** from 18160 bytes on a subsequent one — the second being larger because the full
token set is inlined. **Its keys are not fixed**: `url` appeared only when a second factor was
pending, while `ck` and `prk` appeared only when one was not. So treat it as an open-ended
structure, read by name, and never assume a key is present. Beyond those above, several
matter to later stages and are worth knowing exist now:

| Key | Why it matters later |
| --- | --- |
| `DsPrsId` | a second form of the account identifier, alongside `adsid` |
| `sk`, `c` | session key and cookie for the separate app-token exchange, which this flow does not use but which exists |
| `SOSNeeded`, `SOSCompatibilityOptInNeeded`, `hasSOSActiveDevice` | "Secure Object Sharing" — Apple's name for the iCloud Keychain circle. Directly relevant to Stage 3, and `hasSOSActiveDevice` looks like the signal for whether the account has anything to recover from. |
| `isPasscodeAuth`, `passcodeAuthEnabled` | relates to the device-passcode authentication Stage 3 needs |
| `hasRK` | presence of a recovery key |
| `passkeyEligible`, `passkeyPresent` | an alternative authentication route this document does not cover |
| `primaryEmail`, `countryCode`, `authmode`, `ut` | account attributes |

### 6.1 The token dictionary

Each key of `t` is a service name; each value is a dictionary:

| Key | Meaning |
| --- | --- |
| `token` | The token string |
| `expiry` | Absolute expiry, **milliseconds since the Unix epoch** |
| `duration` | Lifetime in **seconds** from now |

**[observed]** `expiry` and `duration` are **not** alternatives — the one token present before
two-factor carried both. Prefer `expiry`, since it is absolute and needs no clock arithmetic,
and fall back to `now + duration`. Do not assume the presence of one implies the absence of the
other.

**[observed]** Which tokens arrive here depends on whether a second factor was needed:

| Situation | `t` contained |
| --- | --- |
| Second factor required | **one** entry, `com.apple.gs.appleid.auth`. The rest arrive later via response headers (§8). |
| No second factor (known machine) | **all 25**, including the PET and the heartbeat token. No header parsing needed. |

So both routes must be implemented: an implementation that only reads tokens from headers works
on first run and silently gets nothing afterwards, and one that only reads the payload fails the
other way round.

**[observed]** In the no-second-factor case every one of the 25 entries carried *both* `expiry`
and `duration`, reinforcing that they are not alternatives.

---

## 7. Two-factor authentication

> **[observed] A second login from the same machine identity required no second factor at all.**
> `au` was absent, the login completed in zero extra rounds, and the full token set arrived in
> the decrypted payload rather than in headers. This is the behaviour §11 depends on for silent
> refresh, and it is the practical reward for the identity stability demanded in §2.1 — Apple
> remembers the machine, and stops challenging it.
>
> It also means **the two-factor path is the first-run path**. An implementation that only ever
> tests against an already-known identity will not exercise §7 at all.

After a successful `complete`, inspect `Status.au`:

| `au` | Meaning | Go to |
| --- | --- | --- |
| absent | Login is complete. | §9 |
| `trustedDeviceSecondaryAuth` | A code can be pushed to the user's trusted devices. | §7.2 |
| `secondaryAuth` | A code must be sent by SMS. | §7.3 |
| anything else | An unhandled requirement. Report the value verbatim. | — |

Do not hard-fail on an unrecognised `au` before checking whether a PET was nonetheless issued;
some variants complete anyway.

The endpoints in this section are **not** the plist service of §3. They are web endpoints, they
mostly speak JSON, and they carry a different header set (§7.1).

### 7.1 Two-factor header set

Take from the Anisette headers:

```
X-Apple-I-MD    X-Apple-I-MD-M    X-Apple-I-MD-LU    X-Apple-I-MD-RINFO    X-Mme-Device-Id
```

Add the identity token, which is what authenticates these calls:

```
X-Apple-Identity-Token = base64( adsid + ":" + GsIdmsToken )
```

Add the following fixed headers. Note that `X-MMe-Client-Info` here is the **Messages** variant
from §2.2, not the `akd` variant used in §8, and the user-agent is the **browser** one.

| Header | Value |
| --- | --- |
| `X-Apple-Client-App-Name` | `Messages` |
| `X-Apple-I-Client-Bundle-Id` | `com.apple.MobileSMS` |
| `X-MMe-Client-Info` | `mmeClientInfo` |
| `X-Apple-AK-Context-Type` | `imessage` |
| `User-Agent` | `browserUserAgent` |
| `Accept-Language` | `en-US,en;q=0.9` |
| `X-Apple-I-Locale` | `en_US` |
| `X-Apple-I-TimeZone` | `UTC` |
| `X-Apple-I-TimeZone-Offset` | `0` |
| `X-MMe-Country` | `US` |
| `X-Apple-Requested-Partition` | `0` |
| `X-Apple-I-Device-Configuration-Mode` | `0` |
| `X-Apple-I-DeviceUserMode` | `0` |
| `X-Apple-Security-Upgrade-Context` | `com.apple.authkit.generic` |
| `X-Apple-I-CDP-Status` | `false` |
| `X-Apple-I-CDP-Circle-Status` | `false` |
| `X-Apple-I-OT-Status` | `false` |
| `X-Apple-I-ICSCREC` | `true` |
| `X-Apple-I-PRK-Gen` | `true` |
| `Sec-Fetch-Site` | `same-origin` |
| `Sec-Fetch-Mode` | `cors` |
| `Sec-Fetch-Dest` | `empty` |
| `X-Apple-I-CFU-State` | base64 of an XML plist whose root is an empty `<array/>` |

Then merge the hardware headers map, and set content negotiation per endpoint:

- **Plist endpoints** (`/grandslam/GsService2/validate`): `Content-Type: text/x-xml-plist` and
  `Accept: text/x-xml-plist`.
- **JSON endpoints** (everything under `/auth`): `Accept: application/json`, and
  `Content-Type: application/json` where there is a body.

For reference, the `X-Apple-I-CFU-State` value is the base64 of this exact document, trailing
newline included:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<array/>
</plist>
```

### 7.2 Trusted-device flow

**Request a code:**

```
GET https://gsa.apple.com/auth/verify/trusteddevice
```

with the §7.1 headers. Any non-2xx is a failure; report the status code. On success Apple pushes
a code to the user's trusted devices and the client prompts for it.

**Submit the code:**

```
GET https://gsa.apple.com/grandslam/GsService2/validate
```

with the §7.1 headers plus a header carrying the code:

```
security-code: 123456
```

The reply is a **property list**, not JSON. Check `ec`/`em` per §3.1. Tokens arrive in the
response headers — see §8.

### 7.3 SMS flow

**Discover the numbers.** SMS delivery needs the identifier of a specific trusted phone number,
obtained from:

```
GET https://gsa.apple.com/auth
```

with the §7.1 headers and `Accept: application/json`. The JSON reply carries
`trustedPhoneNumbers`, an array of:

| Field | Meaning |
| --- | --- |
| `id` | Numeric identifier used to request delivery |
| `numberWithDialCode` | Display form, already partly masked by Apple |
| `lastTwoDigits` | Display aid |
| `pushMode` | Delivery mode |

Two response statuses have specific meanings:

- **`201`** — Apple has *already sent* a code to the first trusted number as a side effect of
  this request. Go straight to submitting the code; requesting delivery again sends a second
  code and invalidates the first.
- **`403`** — the two-factor configuration cannot be read. The reference implementations
  attribute this to **Advanced Data Protection** being enabled on the account. That attribution
  is unverified here, but it is the message to surface, because if it is right the user's only
  remedy is to turn ADP off. Do not present it as a network error.

> **Do not treat "no trusted phone numbers" as fatal on the trusted-device path.** This endpoint
> is the only source of phone numbers, so it is tempting to call it for both flows and abort
> when the list is empty — which breaks accounts that have trusted devices but no registered
> phone number. Only the SMS path needs a number. This is a known defect in at least one
> implementation of this protocol; do not reproduce it.

Offer SMS whenever trusted numbers exist, even when `au` was `trustedDeviceSecondaryAuth`. A
user without a second Apple device to hand needs it, and Apple accepts it.

**Request delivery:**

```
PUT https://gsa.apple.com/auth/verify/phone
```

§7.1 headers, `Accept: application/json`, JSON body:

```json
{ "phoneNumber": { "id": 1 }, "mode": "sms" }
```

**Submit the code:**

```
POST https://gsa.apple.com/auth/verify/phone/securitycode
```

§7.1 headers, `Accept: application/json`, and the **same body as the delivery request** with the
code added:

```json
{ "phoneNumber": { "id": 1 }, "mode": "sms", "securityCode": { "code": "123456" } }
```

The `phoneNumber` and `mode` fields must match what was sent to request delivery. Anything other
than HTTP 200 means the code was rejected.

---

## 8. Harvesting tokens from response headers

Both code-submission endpoints return tokens in **response headers**, not in the body. Three
header names matter, and a header may appear **more than once** — collect all values.

| Header | Carries |
| --- | --- |
| `X-Apple-GS-Token` | A service token |
| `X-Apple-HB-Token` | The heartbeat token, service `com.apple.gs.idms.hb` |
| `X-Apple-PE-Token` | The PET |

Each value is base64. Decode to ASCII and split on `:`. Field 0 is the service name, field 1 is
the token; what follows is inconsistent:

| Source | Layout | Fields |
| --- | --- | --- |
| `validate` — PET | `identifier:token` — no lifetime at all | **2** [observed] |
| `validate` — heartbeat | `service:token` — **no lifetime either** | **2** [observed] |
| `validate` — service tokens | `service:token:durationSeconds` | **3** [observed] |
| `securitycode` — all | `service:token:durationSeconds:absoluteExpiry` | 4, unverified |

**[observed] The heartbeat token carries no lifetime.** A reference implementation documents it
as `service:token:absoluteExpiry`; the live run returned two fields, not three, so it falls to
the one-year default like everything else. Trust the field count you actually receive rather
than any documented layout, this one included.

**[observed] The first field of the PET is not a service name.** Service tokens key themselves
by their own first field, but the PET's is an account identifier — so it must be filed under
`com.apple.gs.idms.pet` by the code, not by what the header says.

**[observed] `X-Apple-GS-Token` appeared 23 times in one response**, alongside one
`X-Apple-HB-Token` and one `X-Apple-PE-Token` — 25 tokens in total. An HTTP client that returns
only the first value for a header name will silently discard 22 of them.

So the third field means different things depending on which endpoint answered. Rather than
special-casing the endpoint, disambiguate by magnitude — an absolute expiry in milliseconds
since 1970 is a far larger number than any plausible lifetime in seconds:

```
if value > 40 years expressed in milliseconds:
        treat as milliseconds since the Unix epoch
else:   treat as a lifetime in seconds, counted from now
```

For the `securitycode` layout, prefer field 3 and fall back to field 2. Defaults when no
lifetime is present: **300 seconds for the PET**, and one year (31,536,000 seconds) for
everything else. The one-year default is a guess made by the reference implementations, not
something Apple states — treat any token as possibly-expired and be ready to re-authenticate.

### 8.1 After the second factor

If the code-submission response carried an `X-Apple-PE-Token`, the login is complete.

If it did not, the second factor succeeded but no session was issued. **Repeat the entire SRP
handshake from §4** with the same credentials. It will now complete without an `au` in `Status`,
and the token dictionary will arrive in the decrypted payload (§6.1) instead of in headers. This
second pass needs no user interaction — the cached password digest is enough.

---

## 9. State machine

```
                 ┌──────────────┐
                 │ SRP §4       │
                 └──────┬───────┘
                        │  Status.au?
        ┌───────────────┼────────────────┐
     absent      trustedDevice       secondaryAuth
        │               │                 │
        │        ┌──────┴───────┐    ┌────┴─────┐
        │        │ offer choice: trusted device │
        │        │ or any trusted phone number  │
        │        └──────┬───────┘    └────┬─────┘
        │           §7.2 request      §7.3 request
        │               └────────┬────────┘
        │                   prompt for code
        │                        │
        │                   submit code
        │                        │
        │                  PET in headers?
        │                   ┌────┴────┐
        │                 yes         no
        │                   │          │
        └───────────────────┴──────────┴──→ repeat §4 once
                            │
                        ┌───▼────┐
                        │ Done   │
                        └────────┘
```

Two rules that fall out of it:

- **Bound the loop.** A repeat of §4 that again asks for a second factor is a failure, not a
  reason to prompt again. Cap re-entry at one pass.
- **Present both second-factor options at once**, rather than following `au` blindly. The user
  picks; the client already knows which endpoints each choice needs.

---

## 10. Endpoint header sets, at a glance

Three distinct header sets appear in this stage. Mixing them up is a plausible failure and a
tedious one to diagnose, so they are tabulated together.

| | §3 plist service | §7.1 two-factor web | Anisette provisioning |
| --- | --- | --- | --- |
| Anisette headers copied | `MD`, `MD-M`, `MD-LU`, `MD-RINFO`, `X-Mme-Device-Id` | same five | n/a |
| `X-MMe-Client-Info` | `mmeClientInfoAkd` | `mmeClientInfo` | `mmeClientInfo` |
| `User-Agent` | `akdUserAgent` | `browserUserAgent` | `akdUserAgent` |
| `X-Apple-Client-App-Name` | `Messages` | `Messages` | `akd` |
| `X-Apple-I-Client-Bundle-Id` | ✓ | ✓ | — |
| `X-Apple-AK-Context-Type` | ✓ | ✓ | — |
| `Accept-Language` | `en-US,en;q=0.9` | `en-US,en;q=0.9` | `en-US,en;q=0.9` |
| `Sec-Fetch-*` | — | ✓ | — |
| `X-Apple-Identity-Token` | — | ✓ | — |
| Content type | `text/x-xml-plist` | per endpoint | — |

The full §3 header set is: the five Anisette headers above, plus `X-Apple-AK-Context-Type:
imessage`, `X-Apple-Client-App-Name: Messages`, `X-Apple-I-Client-Bundle-Id:
com.apple.MobileSMS`, `X-MMe-Client-Info: mmeClientInfoAkd`, `Accept-Language: en-US,en;q=0.9`,
`User-Agent: akdUserAgent`, `Content-Type: text/x-xml-plist`, `Accept: */*`.

---

## 11. Persistence, refresh and storage

To avoid a full interactive login on every run, persist:

| Item | Why |
| --- | --- |
| `acname` | the canonical Apple ID |
| The decrypted session payload | `adsid` and `GsIdmsToken` are needed by later stages |
| Every token, with its absolute expiry | so an unexpired token can be reused |
| **SHA-256 of the password** | so §4 can be replayed without prompting |

Include a schema version and refuse to load anything else, so a format change fails loudly
rather than misreading old data.

**Refreshing.** When a needed token has expired, re-run §4 with the stored Apple ID and stored
password digest. On an account with two-factor enabled this normally completes without a second
factor, because Apple has seen this machine identity before. That is exactly why §2.1 insists
the identity be stable.

### 11.1 Security

> **The stored password digest is password-equivalent.** SRP consumes `SHA-256(password)`, not
> the password, so anything holding that digest can authenticate as the user indefinitely. It is
> not a "hashed password" in the sense that protects a leaked database — there is no salt and no
> stretching before storage, and possession is sufficient.

On Android this means:

- Store it, the session payload and the tokens under hardware-backed encryption. Never in plain
  `SharedPreferences` and never in a file the app's own export or backup paths can reach.
- Never log it, and never log the PET, the service tokens, `GsIdmsToken`, or `M1`/`K`. Truncated
  is still too much: these are secrets, not identifiers.
- The raw password itself must never be written to disk in any form.
- Offer an explicit sign-out that deletes all of it.

Apply the same care to Anisette provisioning state, which identifies the machine to Apple.

---

## 12. Open questions

Things this specification cannot answer without running against a real account. Each needs
confirming on first implementation, and the answer belongs back in this document.

**Answered by the run of 2026-08-13:**

1. ~~Which scheme does Apple select?~~ **`s2k`.** The `s2k_fo` branch remains specified but
   unexercised — Apple could still select it for another account.
2. ~~Does the invented client identity pass?~~ **Yes**, and it is reflected straight back to the
   user in their device list. See §13.
3. ~~Does signing in register a device?~~ **Yes.** This was the most consequential unknown, and
   the answer is the unwelcome one. See §13.

**Still open:**

4. **Is the 403-means-Advanced-Data-Protection attribution correct?** The run returned 200 from
   `GET /auth`, so the 403 path never arose. It comes from another implementation's error text,
   not from Apple. If wrong, the app will tell users to disable a feature that is not the
   problem.
5. **Does the SMS path work as specified?** Untested end to end. The account had one trusted
   number and the trusted-device path was taken instead. Both the request and the
   code-submission bodies, and the four-field token layout in §8, are unverified.
6. **How does an account with no second factor at all behave?** The `au`-absent path is
   specified but is the least likely to have been exercised by anyone.
7. **Do the two code-submission endpoints return different token sets?** `validate` returned 25
   tokens; what `securitycode` returns is unknown. Stage 2 needs the PET from either.
8. **What does GSA do on repeated sign-ins from the same synthetic machine?** One run tells us
   nothing about rate limiting or flagging over time.
9. **Does the iteration count vary?** One account, one observation (20082). Whether it differs
   per account, or drifts, is unknown — which is the argument for never hardcoding it.

---

## 13. Signing in registers a device [observed]

Completing this stage adds an entry to the account's device list, visible at appleid.apple.com
and in iOS and macOS Settings. **No further call is required** — there is a separate endpoint by
which a client announces itself, and the verified run never touched it. Authentication alone was
enough.

The entry is synthesised entirely from the client identity of §2.2:

| Shown to the user | Comes from | On the verified run |
| --- | --- | --- |
| Model | the claimed hardware model | `iPhone15,2` → **"iPhone 14 Pro"** |
| Version | the claimed OS version | `17.4` → **"iOS 17.4"** |
| Name | **nothing this stage sends** | defaulted to a bare **"iPhone"** |

So Apple resolves the model code into a marketing name and presents the result as an ordinary
device. The registered entry was, to the eye, indistinguishable from a real iPhone 14 Pro.

**There is exactly one distinguishing marker**, on the device's detail page:

> This device cannot be used to receive Apple Account verification codes.

It is one line, on a subpage, and a user with a real iPhone 14 Pro must open both entries and
compare to know which is which. As a way for a person to find the right entry, that is thin.

### Three display fields, three independent levers [observed]

Comparing our entry against a device registered by the older macOS-VM export route — itself
synthetic, so the difference is purely what each flow sent:

| Shown to the user | VM-registered entry | This stage alone | Set by |
| --- | --- | --- | --- |
| Name | `sb's iMac Pro` | bare `iPhone` | the device-name field of the announce call |
| Serial Number | `C02XXXXXXXXX` *(redacted)* | **absent entirely** | `X-Apple-I-SRL-NO`, among the hardware headers |
| Trusted for verification codes | **yes** | **no** | almost certainly the push token |

**The serial is displayed when it is sent, and this stage can send it.** [observed] Supplying
`X-Apple-I-SRL-NO` among the hardware headers put the value straight onto the device's page as
its Serial Number, with no announce call and no other change. A serial is an arbitrary uppercase
alphanumeric string of roughly 10–12 characters, so it is the one identity field that can be
made to read as a label rather than as hardware. The verified run registered as:

```
iPhone
iPhone 14 Pro
    Model          iPhone 14 Pro
    Version        iOS 17.4
    Serial Number  0PENTAGVIEWR        <- ours, and legible
This device cannot be used to receive Apple Account verification codes.
```

**So send one, and make it self-describing.** It costs a single header, it is the only field
this stage controls that the user actually sees, and without it Apple omits the row entirely —
leaving an entry with nothing to tell it apart from real hardware. The device page also carries
a *Remove from Account* button next to the text "If you do not recognise this device", so a
legible serial is the difference between a user confidently removing the right entry and not
daring to touch either.

Beware the spelling: `X-Apple-I-SRL-NO` is the form the hardware-header map and remote Anisette
servers use, while macOS's own Anisette emits `X-Apple-SRL-NO`, without the `I`. The `-I-` form
is the one confirmed to work here. Note also that a serial arriving *from* an Anisette provider
is filtered out before the request is built (§10) — it must be supplied as a hardware header.

### Do not make the device trusted

The synthetic device is *not* trusted for verification codes, and the difference from the VM
entry points at the push token: this flow maintains no push connection and omits the field
(§5), while the VM had one.

**Treat that as a boundary to hold rather than a gap to close.** A trusted device can receive
Apple Account verification codes — so an installation that became trusted would make itself a
second factor for the user's Apple ID. For an application whose purpose is reading accessory
locations, that is a large and unnecessary expansion of what a compromise of it would mean.
Implementing push in order to look more like a real device would buy legitimacy at exactly the
wrong price.

### What follows for the implementation

**The name is the problem, and this stage cannot set it.** The name field is carried by the
announce-yourself call, not by anything here. So an implementation that does only Stage 1 gets
an anonymous entry named after the hardware it claims. Two ways out, and the second is better:

- **Choose the claimed model for recognisability.** Weak: every real model code resolves to a
  name that looks like real hardware, and claiming an implausible one risks rejection.
- **Make the announce call once, with a deliberate name.** It carries a device-name field, and
  is the mechanism by which an entry stops reading as "iPhone" and starts reading as
  "OpenTagViewer". This needs specifying in Stage 2, and it is the recommended route.

**One identity means one entry.** The registration keys on the machine identity, so a stable
identity re-registers nothing on later sign-ins. This is the concrete payoff of the stability
requirement in §2.1: it is not merely good hygiene, it is the difference between one entry and
one per run.

**Removal is manual today.** The user can remove the device from appleid.apple.com or Settings.
A sign-out sequence exists in the protocol — a per-service sign-out event followed by a global
one — but whether it removes the account entry or merely signs services out is **unverified**,
and should not be claimed until it is.
