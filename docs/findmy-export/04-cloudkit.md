# Stage 4 — CloudKit

Specification of how to reach the Find My accessory records, which live in a private CloudKit
container.

Read [README.md](./README.md) first. In particular: **implement from this document alone.**

> **§2 is verified against a live account on 2026-08-13**, by a probe written from this document
> rather than from any reference implementation: HTTP 200, container opened, `cloudKitUserId`
> returned. Values from that run are marked **[observed]**.
>
> **§3 is now detailed enough to implement from, but is unverified** — it is derived by reading,
> and no record has been fetched. Expect corrections; Stage 1 had three and Stage 2 had one,
> and both were better understood going in than this is.
>
> It is written out of protocol order, before Stage 3, deliberately: §2 needs nothing from the
> keychain, so it can prove CloudKit access works *before* anyone commits to the expensive and
> account-modifying work of joining the trust circle. If §2 fails, Stage 3 is pointless.

---

## 1. What this stage needs, and what it produces

**Needs**, all of which [Stage 2](./02-mobileme-delegate.md) already produced:

| Value | From |
| --- | --- |
| `mmeAuthToken` | the MobileMe delegate tokens |
| `cloudKitToken` | the same, for record operations |
| `DsPrsId` | the **numeric** account identifier from the Stage 1 session payload — not `adsid` |
| Anisette headers | the same identity as every other stage |

> **These are already in OpenTagViewer's database.** The app's existing FindMy.py session
> persists the whole MobileMe service-data, `cloudKitToken` included, even though FindMy.py has
> no CloudKit code and never uses it. This stage may not need any new authentication at all.

**Produces:** the encrypted Find My accessory records. They are **not readable at this point** —
their contents are protected by PCS (Stage 5), whose keys come from the keychain (Stage 3). This
stage gets the ciphertext and the metadata around it.

## 2. Opening the container

Find My accessory data lives in one private CloudKit container:

| Property | Value |
| --- | --- |
| Container id | `com.apple.icloud.searchparty` |
| Bundle id | `com.apple.icloud.searchpartyd` |
| Database | **private** |
| Environment | `Production` |

"Search party" is Apple's internal name for the Find My network. It is the same service the
`searchPartyToken` of Stage 2 belongs to.

### 2.1 The request

```
POST https://gateway.icloud.com/setup/setup/ck/v1/ckAppInit?container=com.apple.icloud.searchparty
```

**Authentication is HTTP Basic**, and the credential pair is worth stating precisely because both
halves are easy to get wrong:

- **username** — the *numeric* account identifier. Not `adsid`, which is a different identifier
  entirely. It is available as `DsPrsId` in the Stage 1 session payload **and** as the top-level
  `dsid` of the Stage 2 delegate response; **prefer the latter**, since Stage 1's payload keys
  vary between logins and Stage 2's do not.
- **password** — `mmeAuthToken`. Not `cloudKitToken`, despite this being CloudKit.

Headers are the CloudKit set of §2.2.

### 2.2 CloudKit headers

Every request in this stage carries these, including `ckAppInit`:

| Header | Value |
| --- | --- |
| `x-cloudkit-containerid` | `com.apple.icloud.searchparty` |
| `x-cloudkit-bundleid` | `com.apple.icloud.searchpartyd` |
| `x-cloudkit-databasescope` | the database scope — `PRIVATE` for this container |
| `x-cloudkit-environment` | `Production` |
| `x-cloudkit-duetpreclearedmode` | `None` |
| `x-apple-operation-group-id` | a random identifier, **uppercase hex**, per logical operation group |
| `x-apple-operation-id` | a random identifier, **uppercase hex**, per operation |
| `x-apple-request-uuid` | a v4 UUID, **uppercase**, per request |
| `x-apple-c2-metric-triggers` | `0` |
| `user-agent` | `CloudKit/1970 (19H384)` |
| `x-mme-client-info` | the client-info string of Stage 1 §2.2, with bundle `com.apple.cloudkit.CloudKitDaemon/1970 (com.apple.cloudd/1970)` |
| `accept` | `application/x-protobuf` |
| `accept-encoding` | `gzip` |
| `accept-language` | `en-US,en;q=0.9` |
| `cache-control` | `no-transform` |

Plus **all** the Anisette headers, as in Stage 2.

The three identifier headers are what CloudKit uses to correlate and deduplicate requests.
Generate them fresh per request rather than reusing a constant — a client that sends the same
operation id repeatedly is describing a retry of one operation, which is not what is meant.

### 2.3 The response

JSON. **[observed]** Eleven keys, and they matter more than expected — this response is where
the client learns *where* CloudKit lives:

| Key | Meaning |
| --- | --- |
| `cloudKitUserId` | this account's CloudKit user identifier. **[observed]** 33 characters. |
| `cloudKitDatabaseUrl` | **the endpoint record operations go to** |
| `cloudKitDatabaseGatewayUrl` | the gateway-routed form of the same |
| `cloudKitShareUrl`, `cloudKitShareGatewayUrl` | sharing operations |
| `cloudKitDeviceUrl`, `cloudKitDeviceGatewayUrl` | device operations |
| `cloudKitCodeUrl`, `cloudKitCodeGatewayUrl` | server-side code |
| `cloudKitMetricsUrl` | telemetry, of no interest here |
| `values` | further server-supplied configuration; contents not yet examined |

> **This is where the service endpoint comes from.** [Stage 2 §6](./02-mobileme-delegate.md)
> records that the MobileMe delegate returns no configuration and no URLs, which left an open
> question about where CloudKit's host was meant to be found. It is answered here: **`ckAppInit`
> hands it back.** Do not hardcode a CloudKit host — take `cloudKitDatabaseUrl` from this
> response, because iCloud partitions accounts across service hosts and a URL that works for one
> account is not guaranteed to work for another.

**[observed] Each service appears twice, and the pair means something specific:**

| Form | Points at | Example |
| --- | --- | --- |
| plain `…Url` | the account's **own partition**, directly | `p<N>-ckdatabase.icloud.com` |
| `…GatewayUrl` | the shared front door, with a routing path segment | `gateway.icloud.com/…` |

The `p<N>` prefix is a partition shard — iCloud spreads accounts across numbered partitions, and
the observed account's was a two-digit value. That is what makes hardcoding a CloudKit host wrong: another account will
be on another partition, and the plain URL for one is meaningless for the other.

`cloudKitMetricsUrl` is the exception, pointing at an unpartitioned `metrics.icloud.com`, which
fits — telemetry has no per-account state to be sharded by.

Which form to prefer is **still unestablished**. Both are offered; the direct one is the more
specific and avoids a hop, while the gateway is what `ckAppInit` itself was addressed to.

**Keep `cloudKitUserId`.** It is not the same as `adsid` or `DsPrsId`, and it is required to
address record zones in §3 — a private zone is identified by its name *and* its owner, and the
owner is this value.

**HTTP 401 means the MobileMe token has expired**, not that the account is wrong. Refresh by
repeating Stage 2 — which needs a fresh PET, hence Stage 1 — and retry once. Bound the retry;
a 401 loop against a genuinely rejected account is otherwise indistinguishable from a slow
network.

### 2.4 Why this is the milestone worth reaching first

**[observed] All of this held.** Reaching a `cloudKitUserId` proved, in one request and with no
side effects:

- the tokens from Stage 2 are the right ones and are accepted by a *different* Apple service
- the invented client identity passes CloudKit's checks, not just Grand Slam's
- the container exists and this account may open it
- and, unexpectedly, it yields the service endpoints as well

And it does so without touching the keychain, without an escrow record, without the user's device
passcode, and without writing anything to the account. Everything after this point is more
expensive; nothing after this point is worth attempting if this fails.

## 3. Fetching records

### 3.1 The wire format is protobuf

CloudKit operations are **Protocol Buffers over HTTP**, not JSON. Requests carry:

```
content-type: application/x-protobuf; desc="https://gateway.icloud.com:443/static/protobuf/CloudDB/CloudDBClient.desc"; messageType=RequestOperation; delimited=true
content-encoding: gzip
```

Three things follow. The message type is `RequestOperation`. Messages are **length-delimited**,
so a response is a stream of messages rather than one — multiple operations are batched into a
single request and answered in kind. And the `desc` parameter points at Apple's own descriptor
set, served from `gateway.icloud.com`, which is the authoritative schema.

> **[observed] The descriptor URL is not fetchable.** It is tempting to read that `desc`
> parameter as a published schema that can simply be downloaded — it names a `.desc` file, which
> is what a compiled protobuf `FileDescriptorSet` is called. It returns **HTTP 400 with an empty
> body**, and so does a bare request to `gateway.icloud.com` itself, so the host rejects plain
> requests generally rather than that path being wrong.
>
> Treat the parameter as a *declaration* of which schema the payload conforms to — part of the
> content type, addressed to a client that already has the schema — and not as a way to obtain
> it. Whether it becomes retrievable once a request is properly authenticated is untested and
> worth one attempt from inside a working session, but nothing should be planned around it.

So the schema is written down here instead. What follows covers only what reading accessory
records requires — the protocol is much larger, and the rest of it writes, deletes, shares and
subscribes, none of which this project does.

### 3.2 The envelope

Every call is a `RequestOperation`. Its two universal fields:

| # | Field | Type | Meaning |
| --- | --- | --- | --- |
| 1 | `header` | `Header` | who is calling — see §3.3 |
| 2 | `request` | `Operation` | which operation this is |

Then one further field carrying the operation's own request message. **The field number equals
the operation type**, which is the neatest thing in this protocol and worth relying on:

| # | Field | Operation |
| --- | --- | --- |
| 200 | `zoneSaveRequest` | create a zone |
| **201** | **`zoneRetrieveRequest`** | **list zones** |
| 202 | `zoneDeleteRequest` | delete a zone |
| **203** | **`retrieveZoneChangesRequest`** | **which zones changed** |
| 210 | `recordSaveRequest` | write records |
| 211 | `recordRetrieveRequest` | fetch records by identifier — **not needed**, see below |
| **213** | **`retrieveChangesRequest`** | **which records changed — the main fetch** |
| 214 | `recordDeleteRequest` | delete records |
| 220 | `queryRetrieveRequest` | query records |

The `Operation` message declares the same thing again as an enum:

| # | Field | Type |
| --- | --- | --- |
| 1 | `operationUUID` | string — a fresh uppercase v4 UUID per operation |
| 2 | **`type`** | the enum: `ZONE_RETRIEVE_TYPE = 201`, `RECORD_RETRIEVE_CHANGES_TYPE = 213`, … |
| 3 | `synchronousMode` | bool |
| 4 | `last` | bool — set on the final operation of a batch |

> **`type` is field 2, not field 1.** Field 1 is a string. Putting the type there encodes a
> varint where the server expects a length-delimited string, and the result is **HTTP 500 with an
> empty body** — no protobuf error, nothing to decode, no hint as to the cause. **[observed.]**
> Any 500 with an empty body from this API should be read as "the request did not parse", and the
> field numbering is the first thing to check.

So the operation is declared twice — once as this enum, once by which high-numbered field of
`RequestOperation` is populated — and the two must agree.

Only the three in bold are needed to read accessory data: list the zones, find what changed, and
fetch the changes. `recordRetrieveRequest` (211) fetches records by identifier and is **not
required** — `record/sync` returns everything — so its messages are not specified here.

Everything else in this schema writes, deletes, shares or subscribes, and this project does none
of those.

### 3.2.1 Where operations are sent

**Use the `GatewayUrl`, not the plain one.** This settles the question §2.3 leaves open: record
operations are addressed to `cloudKitDatabaseGatewayUrl`, which is `gateway.icloud.com` plus a
`/ckdatabase` path segment. The plain `p<N>-ckdatabase` host is *not* what a client talks to for
operations, despite being the more specific-looking of the two.

Each operation family then has its own path beneath that. **Note that the "changes" operations
are called `sync`**, which is not obvious from their message names:

| Operation | Full path |
| --- | --- |
| Zone retrieve | `/ckdatabase/api/client/zone/retrieve` |
| **Zone changes** | `/ckdatabase/api/client/zone/sync` |
| Record retrieve | `/ckdatabase/api/client/record/retrieve` |
| **Record changes** | `/ckdatabase/api/client/record/sync` |
| Record save | `/ckdatabase/api/client/record/save` |
| Record delete | `/ckdatabase/api/client/record/delete` |
| Query retrieve | `/ckdatabase/api/client/query/retrieve` |
| Zone save, zone delete | `/ckdatabase/api/client/zone/{save,delete}` |
| Asset token | `/ckdatabase/api/client/asset/retrieve/token` |
| Subscription create | `/ckdatabase/api/client/subscription/create` |

**Three services, not one.** `ckdatabase` is only part of it, and the other two matter to other
stages:

| Service | Base | Used for |
| --- | --- | --- |
| `ckdatabase` | `cloudKitDatabaseGatewayUrl` | records and zones — this stage |
| `ckcoderouter` | `cloudKitCodeGatewayUrl` | `/api/client/code/invoke` — **server-side functions, which is how [Stage 3](./03-keychain-trust.md) reaches Cuttlefish** |
| `ckshare` | `cloudKitShareGatewayUrl` | `/api/client/share/accept`, membership queries — relevant only if shared accessories are ever in scope |

So a zone listing is `POST https://gateway.icloud.com/ckdatabase/api/client/zone/retrieve`.

### 3.2.0 Authenticating an operation

**Operations are authenticated by HTTP headers, not by the protobuf header.** The `userToken`
field exists in the request header message and is **left unset**; sending the CloudKit token
there does not authenticate the call.

| Header | Value |
| --- | --- |
| `x-cloudkit-userid` | the `cloudKitUserId` from §2.3 |
| `x-cloudkit-authtoken` | the `cloudKitToken` from [Stage 2](./02-mobileme-delegate.md) |

That answers what `cloudKitToken` is for: not the container-open call, which uses
`mmeAuthToken` over HTTP Basic, but every operation afterwards. Both tokens are needed, at
different points, and neither substitutes for the other.

Two status codes have specific meanings here: **401** means the token expired — repeat Stage 2 —
and **429** means throttled.

### 3.2.2 The response envelope

Responses are `ResponseOperation`, mirroring the request:

| # | Field | Meaning |
| --- | --- | --- |
| 1 | `operationCost` | uint32 — what the operation cost against the account's budget |
| 2 | `response` | the `Operation`, echoing the type |
| 3 | `result` | **success or failure — check this first** |
| 4 | `bundled` | bundled sub-requests |
| 200–1101 | per-operation response | at the **same field number** as the request, so `zoneRetrieveResponse` is 201 |

`Result` carries a `code` at field 1 and an `error` at field 2. The codes are
`SUCCESS = 1`, `PARTIAL = 2`, `FAILURE = 3`, `INDETERMINATE = 4` — note that **`PARTIAL` exists**,
so a batched request can half-succeed and an implementation that treats anything non-`SUCCESS` as
total failure will discard good results.

`Error` is worth decoding rather than logging as bytes:

| # | Field | Type | Meaning |
| --- | --- | --- | --- |
| 1 | `clientError` | **message** | a wrapper holding the client-fault code at its own field 1 |
| 2 | `serverError` | **message** | a wrapper holding the server-fault code at its own field 1 |
| 3 | `retryAfterSeconds` | int32 | back-off hint. **Honour it.** |
| 4 | `errorDescription` | string | human-readable |
| 5 | `errorKey` | string | a stable key for the error |
| 6 | `errorInternal` | string | Apple-internal detail |
| 7 | `extensionError` | message | 1 `extensionName` (string), 2 `typeCode` (uint32), 3 `extensionPayload` (bytes) |

> **`clientError` and `serverError` are wrapper messages, not bare enums.** Each is a message
> whose field 1 holds the code. Declaring them as plain integers decodes as
> `invalid wire type: LengthDelimited (expected Varint)` — **[observed]** — which is at least a
> diagnostic that names the field, unlike the empty 500 that a malformed *request* produces.

The client-fault codes are specific enough to act on, and several name situations this project
will hit: `BAD_SYNTAX = 4`, `FORBIDDEN = 5`, `THROTTLED = 6`, `BAD_AUTH_TOKEN = 11`,
`NEEDS_AUTHENTICATION = 12`, `NOT_SUPPORTED = 8`, `EXISTS = 9`. A `BAD_AUTH_TOKEN` means repeat
Stage 2; a `THROTTLED` with `retryAfterSeconds` means wait rather than retry.

### 3.2.3 Zone retrieve

The simplest operation, and the one that answers what zones the container holds.

**Request** — `ZoneRetrieveRequest`, field 201: a single optional `zoneIdentifier` at field 1.
Omitting it asks for everything.

**[observed] The container holds two zones**, and accessory data is in the first:

| Zone | `protectionInfo` | `recordProtectionInfo` | `deviceCount` | continuation token |
| --- | --- | --- | --- | --- |
| **`BeaconStore`** | present | **absent** | 10 | present |
| `_defaultZone` | absent | absent | — | absent |

**`BeaconStore` is the zone**, and `_defaultZone` is CloudKit's empty default. That closes the
open question about the zone name; §3.4's `RecordZoneIdentifier` is built from `BeaconStore` plus
the `cloudKitUserId` as owner.

`deviceCount` counts the devices participating in the zone — on the observed account, ten, which
is more than its device list showed and is consistent with the zone outliving individual devices
in the same way escrow records do (see [Stage 3 §5](./03-keychain-trust.md)).

**Response** — `ZoneRetrieveResponse`, field 201: repeated `zoneSummary` at field 1, each:

| # | Field | Meaning |
| --- | --- | --- |
| 1 | `targetZone` | the `Zone`: 1 `zoneIdentifier`, 2 `etag` (string), 3 `protectionInfo`, 6 `recordProtectionInfo` |
| 2 | `currentServerContinuationToken` | the sync token to start from |
| 3 | `clientChangeToken` | the client's own change marker |
| 4 | `deviceCount` | devices participating in the zone |
| 5, 6 | `assetQuotaUsage`, `metadataQuotaUsage` | storage accounting |

### 3.3 The header

`RequestOperation.Header` is large and mostly optional. The fields that matter:

| # | Field | Value for this project |
| --- | --- | --- |
| 1 | `userToken` | **leave unset** — authentication is by HTTP header, see §3.2.0 |
| 2 | `applicationContainer` | `com.apple.icloud.searchparty` |
| 3 | `applicationBundle` | `com.apple.icloud.searchpartyd` |
| 4 | `applicationVersion` | leave unset |
| 7 | `deviceIdentifier` | an `Identifier` for this device |
| 8, 9 | `deviceSoftwareVersion`, `deviceHardwareVersion` | consistent with the identity of Stage 1 §2.2 |
| 10, 11 | `deviceLibraryName`, `deviceLibraryVersion` | the CloudKit client library's own name and version |
| 16 | `deviceProtocolVersion` | leave unset |
| 18 | `mmcsProtocolVersion` | `5.0` |
| 19 | `applicationContainerEnvironment` | `PRODUCTION = 1` |
| 23 | `targetDatabase` | `PRIVATE_DB = 1` |
| 25 | `isolationLevel` | `ZONE = 1` or `OPERATION = 2` |
| 26 | `group` | operation group, matching the correlation headers of §2.2 |
| 10 | `deviceLibraryName` | `com.apple.cloudkit.CloudKitDaemon` |
| 11 | `deviceLibraryVersion` | `1970`, as a **string** |
| 29, 34 | undocumented | `0` |
| 35 | undocumented | `1` |

Three fields have no meaningful names in any schema and are simply set to constants — `0`, `0`
and `1` at tags 29, 34 and 35. Send them; a server that expects them will not say so.

**`deviceHardwareID` (22) is required, even for a read.** [observed] Omitting it is rejected with
`BAD_SYNTAX` and the message `deviceHardwareID is required field`. It is the 32-hex-character
device identifier of Stage 1 §2.2 — generated once, persisted, and stable, like everything else
about the identity.

The other device-identifying fields — `deviceIdentifier` (7), `deviceAssignedName` (21) and
`deviceSerial` (33) — appear to be needed only by operations that *write*. **That is not
confirmed for reads other than zone retrieve**, and the server's validation makes it awkward to
confirm cheaply: it reports **one missing required field at a time**, so discovering the full set
means a request-per-field. Expect to iterate rather than to get a list.

> **Error reporting here is genuinely good, once the schema is right.** A `FAILURE` result with
> `BAD_SYNTAX` carried `errorDescription` naming the exact field, and an `errorKey` — an
> eight-character stable identifier — that is worth logging for correlation. This is the same API
> that answers a malformed *request* with an empty 500, so the difference is stark: get the
> protobuf syntactically valid and the server becomes helpful.

The enums are small and their numbering matters: `ContainerEnvironment` is `PRODUCTION = 1`,
`SANDBOX = 2`; `Database` is `PRIVATE_DB = 1`, `PUBLIC_DB = 2`, `SHARED_DB = 3`. Note none of
them use zero, so a defaulted field is not a valid value — an implementation must set these
explicitly rather than relying on protobuf's zero default.

### 3.4 Identifiers

Nothing in CloudKit is addressed by a bare string. The base type is:

**`Identifier`**

| # | Field | Type |
| --- | --- | --- |
| 1 | `name` | string |
| 2 | `type` | `Type` enum |

with `RECORD = 1`, `DEVICE = 2`, `SUBSCRIPTION = 3`, `SHARE = 4`, `COMMENT = 5`,
`RECORD_ZONE = 6`, `USER = 7`.

**`RecordZoneIdentifier`** — a zone is a name plus its owner:

| # | Field | Value |
| --- | --- | --- |
| 1 | `value` | an `Identifier` naming the zone, type `RECORD_ZONE` |
| 2 | `ownerIdentifier` | an `Identifier` holding the **`cloudKitUserId` from §2.3**, type `USER` |
| 3 | `environment` | `PRODUCTION` |

**`RecordIdentifier`** — a record is a name plus the zone it lives in:

| # | Field | Value |
| --- | --- | --- |
| 1 | `value` | an `Identifier` naming the record, type `RECORD` |
| 2 | `zoneIdentifier` | the `RecordZoneIdentifier` above |

This is why §2.3 insists on keeping `cloudKitUserId`: without it no private zone can be named.

### 3.5 Fetching changes

The main fetch is a **changes** operation rather than a listing, because CloudKit's model is
incremental sync. Two levels exist, and both work the same way.

**Which zones changed** — `RetrieveZoneChangesRequest`, field 203:

| # | Field | Meaning |
| --- | --- | --- |
| 1 | `syncContinuationToken` | omit on a first run; everything is returned |
| 2 | `maxChangedZones` | page size |

**`RetrieveZoneChangesResponse`:**

| # | Field | Type |
| --- | --- | --- |
| 1 | `changes` | repeated `ChangedZone` |
| 2 | `syncContinuationToken` | bytes |
| 3 | `status` | int32 |

**`ChangedZone`:** 1 `identifier`, 2 `changeType`, 3 `deleteType`, 4 `capabilities` (bytes),
5 `isAnonymous` (bool), 6 `anonymousZoneInfo` (bytes), 7 `zoneParentIdentifier`.

**Which records changed** — `RetrieveChangesRequest`, field 213, sent to
`/ckdatabase/api/client/record/sync`:

| # | Field | Meaning |
| --- | --- | --- |
| 1 | `syncContinuationToken` | omit on a first run |
| 2 | `zoneIdentifier` | the zone — `BeaconStore` plus the owner |
| 3 | `requestedFields` | which fields to return; omit for all |
| 4 | `maxChanges` | page size |
| 5 | `requestedChangesTypes` | which kinds of change |
| 7 | `newestFirst` | ordering |
| 8 | `ignoreCallingDeviceChanges` | skip changes this client made |
| 9 | `includeMergeableDeltas` | include deltas rather than whole records |

**`RetrieveChangesResponse`:**

| # | Field | Type |
| --- | --- | --- |
| 1 | `change` | repeated `RecordChange` |
| 2 | `syncContinuationToken` | bytes |

each `RecordChange` being:

| # | Field | Meaning |
| --- | --- | --- |
| 1 | `identifier` | the `RecordIdentifier` |
| 2 | `etag` | version tag |
| 3 | `recordType` | the record's type name |
| 4 | `type` | the kind of change |
| 5 | `record` | the record itself, when the change carries one |

plus a `syncContinuationToken` for the next call.

**Persist that token.** It is both the way to avoid refetching everything and the mechanism by
which this feature would notice a newly-paired accessory on a later run — which, per the
[README](./README.md), is why the account is re-read at all.

### 3.5.1 What the zone actually contains [observed]

A first `record/sync` against `BeaconStore` returned **35 records across nine types**, with a
continuation token for the next page:

| Record type | Count | What it is |
| --- | --- | --- |
| `MasterBeaconRecord` | 6 | **the owned accessories, with their key material** |
| `BeaconNamingRecord` | 5 | user-assigned names, one per accessory |
| `KeyAlignmentRecord` | 4 | last observed key index — see OpenTagViewer's rule 6 |
| `OwnedDeviceKeyRecord` | 8 | key pairs belonging to the user's *devices*, not accessories |
| `SharingCircleSecret` | 5 | secrets for accessories shared with others |
| `OwnerSharingCircle` | 1 | a sharing circle this account owns |
| `OwnerPeerTrust` | 1 | peer trust state |
| `SafeLocation` | 4 | named geofences — "notify when left behind" |
| `LeashRecord` | 1 | accessory grouping |

Three of these are worth calling out.

**Six accessories, five name records, four alignment records.** These are not one-to-one. An
accessory may have no name record and no alignment record, so an implementation must join on
`associatedBeacon`/`beaconIdentifier` and tolerate absence rather than zipping lists.

**`SafeLocation` holds the user's home and work coordinates** — `latitude`, `longitude`,
`radius`, `name`. This project has no use for it. It is in the same zone and will be fetched
whether or not it is wanted, so an implementation should discard it explicitly rather than
letting it flow into storage or logs by default.

**The sharing machinery is in this zone**, not in a separate shared database. See the
[README](./README.md) on shared accessories — this makes them materially cheaper to reach than a
separate-database design would.

### 3.6 Records and their fields

**`Record`** — the fields worth knowing:

| # | Field | Meaning |
| --- | --- | --- |
| 1 | `etag` | version tag |
| 2 | `recordIdentifier` | which record this is |
| 3 | `type` | the record type, a message wrapping a `name` string |
| 4 | `createdBy` | an `Identifier` |
| 5 | `timeStatistics` | creation and modification times |
| 7 | `recordField` | **repeated `Field`** — the actual contents |
| 9 | `modifiedBy` | an `Identifier` |
| **13** | **`protectionInfo`** | **which PCS key protects this record — the input to Stage 5** |
| 15 | `permission` | access level |
| 24 | `pcsKey` | key material associated with the record |

**`Record.Field`** is a name and a value:

| # | Field | Meaning |
| --- | --- | --- |
| 1 | `identifier` | **a message** whose field 1 is the `name` string |
| 2 | `value` | the value |

> **Three places wrap a name in a message rather than using a bare string**, and they are easy to
> get wrong because both encode as length-delimited on the wire: a record's `type` (field 3 of
> `Record`), a change's `recordType` (field 3 of `RecordChange`), and a field's `identifier`
> here. **All three are messages with the string at field 1.** Declaring one as a bare string does
> not degrade gracefully — it throws, and takes the whole response with it.

**`Record.Field.Value`** is a tagged union — a `type` enum plus whichever typed field is
populated:

| # | Field | For type |
| --- | --- | --- |
| 1 | `type` | the discriminator |
| 2 | `bytesValue` | `BYTES_TYPE`, and the encrypted types |
| 4 | `signedValue` | `INT64_TYPE` |
| 5 | `doubleValue` | `DOUBLE_TYPE` |
| 6 | `dateValue` | `DATE_TYPE` |
| 7 | `stringValue` | `STRING_TYPE` |
| 8 | `locationValue` | `LOCATION_TYPE` |
| 9 | `referenceValue` | `REFERENCE_TYPE` |
| 10 | `assetValue` | `ASSET_TYPE` |
| 11 | `listValues` | the list types — repeated `Value` |
| 13 | `isEncrypted` | flags an encrypted field |

The type enum runs `BYTES_TYPE = 1` through `UNKNOWN = 22`.

> **[observed] `type` describes the *plaintext*, not the ciphertext.** Every field of every
> record in `BeaconStore` came back with `isEncrypted = true`, but their `type` values were
> spread across `STRING_TYPE = 3`, `INT64_TYPE = 7`, `DATE_TYPE = 2`, `DOUBLE_TYPE = 8`,
> `STRING_LIST_TYPE = 15` and `ENCRYPTED_BYTES_TYPE = 20`.
>
> So the type is a promise about what decryption will yield, not a description of what is on the
> wire. `ENCRYPTED_BYTES_TYPE` is what a field whose plaintext is *binary* uses; a field whose
> plaintext is a string is `STRING_TYPE` with `isEncrypted` set. **Branch on `isEncrypted`, and
> use `type` only to decide how to interpret the result.** An implementation that switches on
> `type` alone will try to read ciphertext as a string.

That also explains the separate `EncryptedValue` message, which holds a signed value, a date or a
string: it is the shape of a field *after* Stage 5 has done its work.

### 3.7 Protection info

**`ProtectionInfo`** is small and appears on records, zones and participants alike:

| # | Field | Type |
| --- | --- | --- |
| 1 | `protectionInfo` | bytes — the encoded protection structure |
| 2 | `protectionInfoTag` | string — a tag identifying it |

A `Zone` has room for **two**: `protectionInfo` at field 3 and `recordProtectionInfo` at field 6.

> **[observed] Protection is per-record, and it is universal.** On the `BeaconStore` zone,
> `protectionInfo` was present and `recordProtectionInfo` absent. On the records themselves,
> **every one of the 35 carried its own `protectionInfo` at field 13, and none carried a
> `pcsKey` at field 24.**
>
> So the hierarchy is: the zone's `protectionInfo` gives the zone keys, and each record's own
> `protectionInfo` describes how that record is protected under them. `recordProtectionInfo` on
> the zone and `pcsKey` on the record are both optional and were both absent — an implementation
> must not require either.

This is the boundary of Stage 4. Everything above yields records whose interesting fields are
ciphertext plus a `ProtectionInfo` describing how to unlock them; turning that into plaintext is
Stage 5 and needs the keychain from Stage 3.

## 4. Open questions

**Answered by the run of 2026-08-13:**

1. ~~Does `ckAppInit` succeed with only Stage 2's tokens?~~ **Yes.** The whole argument for doing
   this before Stage 3 rested on it, and it holds: CloudKit access needs no keychain state.
2. ~~Where does the CloudKit endpoint come from?~~ **From `ckAppInit` itself**, as
   `cloudKitDatabaseUrl`. Not from the MobileMe delegate, and not hardcoded.
3. ~~Can Apple's protobuf descriptor set be fetched?~~ **No** — see §3.1.

**Still open:**

4. ~~What is the zone name holding accessory records?~~ **[observed] `BeaconStore`.**
5. **Plain URL or `GatewayUrl`?** ~~The distinction is unexplained~~ — it is now known to be
   direct-to-partition versus routed-through-the-gateway (§2.3). **Which to prefer is still
   open**, and it may not matter; trying the direct form first is the obvious default.
6. ~~Can records be listed without any keychain state?~~ **[observed] Yes.** All 35 records were
   fetched with no keychain, no trust circle and no device passcode. Fetching and decrypting are
   cleanly separable, and Stage 3 is required only to *read* what has already been retrieved.
7. **Is `cloudKitToken` used at all**, given `ckAppInit` authenticates with `mmeAuthToken`?
   Presumably it authenticates record operations rather than the setup call, but which requests
   take which credential is not established.
8. ~~What is in `values`?~~ **[observed]** An array of two objects, one per environment
   (`PRODUCTION` and `SANDBOX`), each carrying the container `name`, its `env`, a database `url`
   and a `ckDeviceUrl`. It is a per-environment endpoint table for the container being opened —
   useful for confirming the partition, and **not** a general service directory. Notably it does
   not carry the escrow host that [Stage 3](./03-keychain-trust.md) needs.
