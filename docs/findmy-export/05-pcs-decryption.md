# Stage 5 — PCS decryption

Specification of how an encrypted CloudKit record field becomes plaintext, using keys held in the
iCloud Keychain.

Read [README.md](./README.md) first. In particular: **implement from this document alone.**

> **Unverified.** Nothing here has been run. It is derived by reading implementations whose
> authors report them working, and it is the least-exercised stage in this set.
>
> The ASN.1 structures of §3 are enumerated field by field, and the wire format of §6 is exact.
> What remains unspecified is called out in §8.

---

## 1. What this stage is for

[Stage 4](./04-cloudkit.md) ends with records in hand whose every field is ciphertext. This stage
turns them into plaintext. It is the last thing between the data and being useful, and it is
where the keys from [Stage 3](./03-keychain-trust.md) are finally spent.

PCS is **Protected Cloud Storage**, Apple's layer for encrypting CloudKit records under keys the
server never sees.

## 2. What it needs from the keychain

The Find My service is identified to PCS by a fixed descriptor:

| Property | Value |
| --- | --- |
| Service name | `com.apple.icloud.searchparty` |
| Keychain view | **`Manatee`** |
| Service type | `82` |
| Version | v2 |

**`Manatee` is the keychain view holding these keys**, and it is the concrete answer to what
Stage 3 must deliver. Two views must be synced before decryption can begin:

```
Manatee
ProtectedCloudStorage
```

Within the `Manatee` view, the service key is the item labelled:

```
com.apple.ProtectedCloudStorage-com.apple.icloud.searchparty
```

**Keychain items are dictionaries**, and the attribute that matters for key lookup is `acct`,
which holds the **base64 of a compressed elliptic-curve public key**. That is how a protection
structure's key references are resolved against the keychain: match on `acct`.

> ### "Compressed" here means the bare x coordinate — 32 bytes, not 33
>
> **[observed]** An `acct` decodes to **32 bytes**, with no `02`/`03` sign byte and no `04`
> marker. X9.62's compressed form for P-256 is 33 bytes, and this is not that: it is the x
> coordinate alone. The same form is used for the public keys inside a protection structure's
> keyset, so it is the protocol's convention rather than a quirk of one field.
>
> **A 33-byte comparison therefore never matches anything**, and the failure presents as "no key
> is held for this record" — a far stronger and more discouraging claim than "the wrong bytes were
> compared", and indistinguishable from the legitimate case it is supposed to describe. Report the
> sizes being compared alongside any such failure.
>
> Matching on x alone is slightly weaker than matching a full point, since x does not fix the sign
> of y. That ambiguity is the protocol's, not the reader's.

> ### The service key does not open a record
>
> **It opens the zone.** The keychain service key is what a *zone's* protection structure names;
> the zone yields keys of its own, and those are what a record's protection structure names. A
> reader that scans a record's keyset for the service key finds nothing, however correct
> everything else is — see [§4 step 0](#4-unwrapping-step-by-step).

## 3. The protection structure

Every record carries a `protectionInfo` (Stage 4 §3.7), whose `protectionInfo` field is a byte
string. **It is DER-encoded ASN.1**, not protobuf — the one place in this whole protocol where
the encoding changes, and an easy thing to lose an afternoon to.

### 3.1 The structures

Written as ASN.1. Tags are **explicit** throughout; anything untagged is in sequence order.

**`ShareProtection`** — the top level, and what a record's `protectionInfo` bytes decode to:

```asn1
ShareProtection ::= [APPLICATION 1] EXPLICIT SEQUENCE {
    keyset            KeySet,
    meta          [0] EXPLICIT OCTET STRING,       -- encrypted; not needed to decrypt fields
    signatureData [1] EXPLICIT SignatureData,
    hmac              OCTET STRING,
    truncatedKeyId[2] EXPLICIT OCTET STRING,       -- first 4 bytes of the key id
    signature     [3] EXPLICIT Signature OPTIONAL,
    attributes    [4] EXPLICIT SEQUENCE OF Attribute OPTIONAL
}
```

Note `hmac` sits **between** two context-tagged fields without a tag of its own — a decoder that
assumes tags ascend monotonically will misparse it.

**`KeySet`** — the entries, one per party that can unwrap this record:

```asn1
KeySet ::= SEQUENCE {
    unknown           INTEGER,                     -- observed as 0
    keyset            SET OF ShareKey,             -- a SET, so unordered
    attributes    [0] EXPLICIT SEQUENCE OF Attribute OPTIONAL
}

ShareKey ::= SEQUENCE {
    decryptionKey     KeyRef,
    ciphertext        OCTET STRING,                -- the RFC 6637 wrapped key
    flags             INTEGER OPTIONAL             -- bit 0 set = read-only
}

KeyRef ::= SEQUENCE {
    keytype           INTEGER,
    pubKey            OCTET STRING                 -- compressed EC public key
}
```

`keyset` is a **SET OF**, not a SEQUENCE OF — the entries are unordered and must be searched by
matching `pubKey`, never indexed.

**`SignatureData`** — carries the version that §4 step 3 branches on, and a nested structure:

```asn1
SignatureData ::= SEQUENCE {
    version           INTEGER,
    data              OCTET STRING          -- itself DER: an ObjectSignature
}
```

**`ObjectSignature`**, found by decoding `SignatureData.data` — this is what §4 step 4 verifies
against:

```asn1
ObjectSignature ::= SEQUENCE {
    rollCount             INTEGER,
    outerSignKeyType      INTEGER,
    public                KeyRef,
    signature             Signature,
    symmKeyCount      [0] EXPLICIT INTEGER OPTIONAL,
    signature2        [1] EXPLICIT Signature OPTIONAL,
    ecKeyList         [2] EXPLICIT SEQUENCE OF KeyRef OPTIONAL,
    attributes        [3] EXPLICIT SEQUENCE OF Attribute OPTIONAL
}
```

`signature2` is the "past" signature §4 step 4 falls back to — it is key rotation, not corruption.

> The version numbering is **not clearly established**. The reference implementation's own note
> on it is self-contradictory, mapping the same values to different names in one breath. What is
> established is only what §4 step 3 needs: **the share-key derivation is skipped when the
> version is 5**. Treat any other interpretation of this field as unknown.

**`Signature`** and **`Attribute`** — small and reused:

```asn1
Signature ::= SEQUENCE {
    keyid             OCTET STRING,
    digest            INTEGER,                     -- 1 = SHA-256, 2 = SHA-512
    signature         OCTET STRING
}

Attribute ::= SEQUENCE {
    key               INTEGER,
    value             OCTET STRING                 -- itself DER, per key
}
```

Known attribute keys, each whose `value` is a further DER structure:

| Key | Contents |
| --- | --- |
| 1 | `SEQUENCE { build [0] UTF8String, time [1] GeneralizedTime }` |
| 3 | `SEQUENCE { flags INTEGER }` — the Manatee flags |
| 7 | a `Signature` — the owner signature checked in §4 step 4 |

### 3.2 Keys as they appear in the keychain

The keychain items of §2 hold these:

```asn1
PublicKey ::= [APPLICATION 1] EXPLICIT SEQUENCE {
    pcsService        INTEGER,                     -- 82 for Find My
    unknown           INTEGER,
    pubKey            OCTET STRING,
    attributes    [0] EXPLICIT SEQUENCE OF Attribute OPTIONAL,
    signature     [1] EXPLICIT Signature OPTIONAL
}

PrivateKey ::= CHOICE {
    v1                SEQUENCE { key OCTET STRING, public PublicKey OPTIONAL },
    v2                [APPLICATION 5] EXPLICIT SEQUENCE { data OCTET STRING }
}
```

> **`[APPLICATION 1]` is used by both `ShareProtection` and `PublicKey`.** They are told apart by
> where they appear, not by their tag, so a decoder must be told which one to expect rather than
> dispatching on the tag alone.

The Find My service is **v2** (§2), so its keys are the `[APPLICATION 5]` form. That tag is
**explicit**, like every other application tag here — it wraps the SEQUENCE rather than replacing
its tag, so the contents are one level deeper than an implicit reading would place them.

There is also a keyset wrapper used when keys are carried together:

```asn1
ShareProtectionKeySet ::= [APPLICATION 2] EXPLICIT SEQUENCE {
    unknown           UTF8String,
    keys              SET OF PrivateKey,
    unknown2          SET OF ANY,
    hash              OCTET STRING OPTIONAL        -- SHA-256 over this structure's DER,
}                                                  -- computed with `hash` itself absent
```

## 4. Unwrapping, step by step

> **There are two levels of this, not one.** Steps 1 to 6 below describe unwrapping *a* protection
> structure. They are performed **twice**: once on the zone's, with the keychain service key, and
> then once on the record's, with the keys the zone produced. Step 0 is what makes the difference.

**Step 0 — unwrap the zone first.**

A record's keyset does **not** name the keychain service key. It names a **zone key**, and zone
keys come from the zone's own protection structure:

| | Whose keyset names it | What you take from the result |
| --- | --- | --- |
| **Zone** — `protectionInfo` on the zone, from a zone retrieve | the **keychain service key** of §2 | the **EC private keys** — see §4 step 6's note |
| **Record** — `protectionInfo` on the record | one of the **zone keys** | the **PCS master keys**, which decrypt the fields |

So the sequence is: retrieve the zone, unwrap its protection structure with the service key, keep
the EC private keys it yields, and unwrap each record's structure against *those*.

> **Both halves come out of the same structure**, and which half you want depends on the level.
> Step 7's decryption uses the master keys; the zone level ignores them and uses the EC private
> keys instead. Taking the same half at both levels is the mistake this table exists to prevent.

> ### The zone's `meta` is the only source of zone keys
>
> There is no second place they come from. **A record's keyset holds a zone key's public part
> directly** — the same bytes, compared as the bare x of §2.
>
> One qualification, because "no derivation exists" would be too strong: §5's **master EC key** is
> derived from a *PCS master key* rather than being one of these, and it is the only construction
> in this protocol that turns a zone-level secret into an elliptic-curve key. It exists to verify
> signatures (§4 step 4). If a record names a key that is in neither the zone's identities nor the
> keychain, deriving it from the zone's master keys is the cheap thing to rule out — the code is
> already there.
>
> So if a record names a key the zone did not yield, the zone's `meta` was not read completely.
> Both levels of step 6 are `SET OF`: `identities` is a set, and each keyset's `keys` is a set.
> **The plural in "one of the zone keys" comes from there and nowhere else**, so taking the first
> element of either — or letting one unreadable entry end the loop — silently produces a short
> list that looks like a complete one.
>
> Neither `recordProtectionInfo` nor the key ids inside it feed a record's own keyset. It is
> decoded *against* the zone keys and produces master keys for records that carry no structure of
> their own; it is an alternative to a record's keyset, never an input to it.

**When a record carries no `protectionInfo` of its own**, it is covered by the zone's
`recordProtectionInfo` instead — a second structure on the zone, unwrapped once against the zone
keys to give a set of default record keys. The record's own `pcsKey` field is then a **key-id
prefix** naming which of them applies: compare it against the leading bytes of each key's key id
(§5). A record with neither its own structure nor a matching default key is genuinely not
readable.

**Step 1 — find the entry that belongs to us.** Take the public part of the key for this level —
the service key at the zone, a zone key at the record — as its **bare 32-byte x coordinate**, and
scan the keyset for an entry whose public key equals those bytes. That entry's ciphertext is the
wrapped key intended for us.

If none matches, we do not hold a key at this level. **Before reporting that, check the level.**
The overwhelmingly likely cause of no match on a record is that the zone step was skipped and the
service key was compared against a keyset that never names it — which looks identical to being
locked out, and is not.

**Step 2 — unwrap it.** The wrapping is **RFC 6637**, the ECDH key-wrap construction from
OpenPGP. It has three parts, and each has a detail worth stating.

*The ciphertext layout* is OpenPGP's MPI encoding, **not** an X9.62 point:

| Part | Size |
| --- | --- |
| Bit length of the ephemeral point | 2 bytes, big-endian — a count of **bits**, not bytes |
| Ephemeral public key | that many bits. **[observed] 256 bits — a 32-byte compact point**, not a 33- or 65-byte X9.62 encoding |
| Wrapped key length | 1 byte |
| Wrapped key | that many bytes |

*The key derivation* is ECDH against that ephemeral point, then:

```
KEK = SHA-256( be32(1) ‖ shared_secret ‖ 08 2A 86 48 CE 3D 03 01 07
                ‖ 0x12 ‖ 0x03 0x01 ‖ 0x08 ‖ 0x07
                ‖ "Anonymous Sender    " ‖ fingerprint )
```

where the OID bytes are length-prefixed P-256, `0x12` is ECDH, `0x08` names SHA-256 as the KDF
hash, `0x07` names **AES-128** as the key-wrap cipher, the sender string is exactly twenty
characters including its four trailing spaces, and:

> **The "fingerprint" is the literal ASCII word `fingerprint`, zero-padded to 20 bytes.** It is
> not a key fingerprint, not a hash of anything, and not eleven bytes — it occupies the 20-byte
> fingerprint slot of an otherwise ordinary RFC 6637 parameter block, right-padded with zeros.

Only the **first 16 bytes** of that 32-byte digest are used, as an AES-128 key-encryption key.
The unwrapping itself is RFC 3394 AES key unwrap, whose integrity check is the first signal that
everything above was assembled correctly.

*The plaintext* is framed, not bare:

| Part | Content |
| --- | --- |
| byte 0 | algorithm identifier, **must be `1`** |
| bytes 1 … *L* | the key — the PCS master key, 16 bytes |
| next 2 bytes | big-endian checksum: the sum of the key bytes, modulo 65536 |
| trailing *p* bytes | padding, every byte equal to *p* |

*L* is `len - p - 3`. Verify the checksum and the padding; both are cheap and both catch a wrong
key before it propagates.

**Step 3 — derive the share key, conditionally.**

```
if signature_data.version != 5 and not read_only:
        master_key = KDF(master_key, "MsaeEooevaX fooo 012")
```

Otherwise the master key is used directly. **This branch is easy to miss and produces a key that
fails every subsequent check**, so verify against the checksum in step 5 rather than pressing on.

**Step 4 — verify the signatures.**

The signed data is a **hand-built concatenation, not a DER structure** — nothing re-encodes it as
one message, so it must be assembled in exactly this order:

| Order | Bytes |
| --- | --- |
| 1 | DER of `keyset` |
| 2 | raw `meta` |
| 3 | `outerSignKeyType`, big-endian 32-bit |
| 4 | `rollCount`, big-endian 32-bit |
| 5 | `symmKeyCount`, big-endian 32-bit — **`0` when absent**, not omitted |
| 6 | `public.keytype`, big-endian 32-bit |
| 7 | `public.pubKey`, raw |
| 8 | DER of `attributes`, **only if present** |
| 9 | DER of `ecKeyList`, **only if present** |

Note the asymmetry: an absent `symmKeyCount` contributes four zero bytes, while absent
`attributes` or `ecKeyList` contribute nothing at all. Getting that backwards is a four-byte
difference that fails verification with no other symptom.

Signatures are **ECDSA over SHA-256**. `Signature.digest` is `1` for SHA-256; nothing observed
uses `2`.

**Which key verifies what:**

| Signature | Verified with |
| --- | --- |
| `ObjectSignature.signature` | the **master EC key** derived per §5 — this is what B4's bit-ordering answer is for |
| `ShareProtection.signature`, when present | the private key that unwrapped the entry, or a caller-supplied signing key |
| Attribute `7`, when present | a caller-supplied signing key |

**The master-key check is skipped when the read-only flag is set**, and falls back to `signature2`
if the first fails.

> **`Signature.keyid`, when non-empty, is the compressed public key of the signer** — not a hash
> or an identifier. Check it before verifying: a mismatch names the wrong key immediately, where a
> failed verification does not. An **empty** `keyid` means self-signed.

**Step 5 — check you have the right key.** Two independent checks, and both should be performed
before anything is decrypted:

- the first 4 bytes of the derived key's **key id** must equal the structure's truncated key id
- the structure's **HMAC** must verify under the derived key, over the concatenation of three
  things, in this order and nothing else:

  | | |
  | --- | --- |
  | 1 | the **DER of `keyset`** |
  | 2 | the **raw bytes of `meta`** |
  | 3 | the **DER of the `ObjectSignature`** — see below |

  > **The third part is the inner structure, not the `SignatureData` wrapper.** `SignatureData` is
  > `SEQUENCE { version, data }` and what the HMAC covers is the DER of what `data` *holds* — the
  > `ObjectSignature` of §3.1. Equivalently, and more simply: **the contents of the `data` OCTET
  > STRING**, which can be taken as bytes rather than re-encoded at all.
  >
  > Encoding the `SignatureData` SEQUENCE instead adds the version and the OCTET STRING's own
  > header, and the HMAC then fails on **every** structure while the key id still matches — which
  > is a distinctive symptom, because a wrong key fails both checks and this fails only one.

  Parts 1 and 3 are re-encodings rather than spans of the input, so a decoder that discards its
  input after parsing must re-encode faithfully.

  > **DER orders a `SET OF` by encoded value**, ascending, not by the order elements arrived in.
  > `keyset` is a `SET OF` (§3.1), so an encoder that preserves parse order produces different
  > bytes for a structure whose entries happened to arrive unsorted — and the HMAC fails with no
  > other symptom. The same applies to the nested keyset's own hash in step 6.

A mismatch means the wrong key, not corrupt data.

**Step 6 — decrypt `meta`, which is where the keys actually are.**

Steps 1 to 5 yield **one** key: the master key wrapped to us. That is not the set of keys the
structure carries — those live in `meta`, encrypted under it.

Decrypt `meta` with the master key using §6's field cipher, **with an empty context string** — the
AAD is the header alone, since a structure member has no zone, record or field name to bind to.
This is the one place §6's AAD rule does not apply, and it is the exception rather than a
contradiction.

The plaintext is DER:

| Tag | Member | |
| --- | --- | --- |
| `[0]` | `symmKeys` | SET OF OCTET STRING, optional — **additional PCS master keys** |
| `[1]` | — | two unnamed fields, carried and not understood |
| `[2]` | `identities` | SET OF `{ integer, keyset }`, optional — where the EC keys are |

Each `identities` entry holds a `keyset` **OCTET STRING that is itself DER**, one level down:

| Member | |
| --- | --- |
| a string | unnamed |
| `keys` | SET OF the **same private-key CHOICE** as a keychain item's `v_Data` — [Stage 3 §6.8.1](./03-keychain-trust.md), **including its 64-byte public-then-private layout** |
| a set | unnamed, of unconstrained type |
| `hash` | SHA-256 **over this structure's own DER with `hash` absent** — remove it, re-encode, compare |

So the two outputs of a fully unwrapped structure are:

- **PCS master keys** — the one from step 2, followed by each of `symmKeys`. These decrypt fields.
- **EC private keys** — every `keys` entry across every identity. These unwrap the *next* level
  down, and are what step 0 keeps at the zone.

> The nested `keyset` is an OCTET STRING holding DER rather than an inline structure, and its
> checksum is computed over a re-encoding of itself minus one field. Both mean a decoder that
> parses and discards cannot verify it — the same re-encoding requirement as step 5's HMAC.

**Step 7 — decrypt the fields.**

## 5. Key derivation

Every derived key comes from the master key by the same construction: **NIST SP 800-108
counter-mode KDF with HMAC-SHA256**, with a fixed label and an output the same length as the
input key.

The PRF input is, exactly:

```
HMAC-SHA256( master_key,  be32(i) ‖ label ‖ 0x00 ‖ context ‖ be32(L) )
```

| Part | Value |
| --- | --- |
| `i` | the block counter, **starting at 1**, big-endian 32-bit |
| `label` | the fixed label from the table below, as raw ASCII bytes |
| `0x00` | the separator SP 800-108 specifies |
| `context` | **empty** — no context is used |
| `L` | the output length **in bits**, big-endian 32-bit |

Blocks are concatenated and truncated to the requested length. Since every use here requests 16
bytes and SHA-256 produces 32, the counter never advances past 1 in practice — but implement the
loop anyway rather than assuming a single block.

| Derived key | Label |
| --- | --- |
| Share key | `MsaeEooevaX fooo 012` |
| HMAC key | `hmackey-of-masterkey` |
| Key-id label key | `master key id labell` |
| Encryption key | `encryption key key m` |

**Every label is exactly 20 characters.** That is not a coincidence and not something to
normalise away — treat the labels as opaque fixed byte strings and copy them exactly, spaces
included.

**The key id** is a two-stage construction rather than a plain digest:

```
label_key = KDF(master_key, "master key id labell")
key_id    = HMAC-SHA256(label_key, "M key input data 2 u")
```

**The master EC key** — used to verify the structure's own signature — is derived from the master
key by a route that is unusual enough to be worth spelling out:

```
out = PBKDF2-HMAC-SHA256(master_key, salt = "full master key", iterations = 10, length = 128)
out = reverse(out)                      # the output is little-endian; big-endian is needed
n   = keep_low_bits(out, order_bits(P-256))    # the LOW 256 bits, not the high ones
if n > order: n = n - order
private_scalar = n
```

Four traps in five lines. **Ten** iterations, not a realistic PBKDF2 count. The output is
**reversed**, because it is produced little-endian and consumed big-endian. The truncation keeps
the **low** 256 bits — the operation is a bit mask, not the `bits2int` convention that keeps the
high bits, and the two are indistinguishable by the arithmetic that follows. And the reduction is
a single **conditional subtraction**, not a modulo. The curve is **P-256**
(`prime256v1`/`secp256r1`).

## 6. Field decryption

Encrypted field values are **AES-128-GCM**.

The ciphertext is not bare. It carries a **variable-length header** that must be parsed, checked,
and then used as authenticated data:

| Offset | Size | Meaning |
| --- | --- | --- |
| 0 | 1 | **encryption version. Must be 3** — anything else is a format this document does not describe, and should be refused rather than guessed at. |
| 1 | 2 | first part of the key id |
| 3 | 1 | *N*, the length of the second part |
| 4 | *N* | second part of the key id |

so the header is `4 + N` bytes, and the key id to compare against is the two byte ranges
**concatenated** — bytes 1–2 followed by bytes 4 to 4+*N* — skipping the length byte at offset 3.
That reconstructed value must equal the leading bytes of the derived key's key id (§5). It is the
third and cheapest opportunity to detect the wrong key, and it costs nothing.

After the header:

| Part | Size |
| --- | --- |
| IV | 12 bytes |
| Tag | 12 bytes — **not the usual 16** |
| Ciphertext | the remainder |

### The AAD is the header **and** a field context string

> **Correction — this section previously said the AAD was the header alone, and that was wrong.**
> Decryption with only the header as AAD fails authentication on every field, with no diagnostic
> beyond "GCM error". The AAD is the concatenation of **two** parts:

```
AAD = header ‖ "<zoneName>-<recordName>-<fieldName>"
```

| Part | Content |
| --- | --- |
| First | the entire header — bytes 0 to `4 + N`, version and length byte included |
| Second | the context string, ASCII, no separator between it and the header |

where the three names are the **zone's name** (`BeaconStore`), the **record's own name** from its
`recordIdentifier`, and the **field's name** from its `identifier` — joined by single hyphens.

Two consequences worth stating:

- **Decryption needs the record's identity, not just its bytes.** A function taking only a
  ciphertext and a key cannot decrypt a PCS field. It needs the zone name, the record name and the
  field name too, so the plumbing has to carry them down.
- **It binds each field to its position.** A ciphertext moved to another field, another record or
  another zone will not authenticate, which is the point.

For reference, a header as actually produced is six bytes: `03`, two key-id bytes, `02`, two more
key-id bytes — version 3, then the two-byte-plus-two-byte split of §6's layout.

The key is `KDF(master_key, "encryption key key m")`, 16 bytes for AES-128.

> Note the tag length: **12 bytes, not GCM's default 16.** A library configured with the default
> will reject every message, and the failure looks like corruption rather than misconfiguration.

## 6.1 Field *encryption*, for a rename

The same key encrypts. Nothing further has to be recovered to write a field — which is what makes
renaming an accessory possible at all, and also what makes it easy to write something Apple's own
devices cannot read.

Producing a field value:

1. **Build the header.** Six bytes, and the length byte is not optional even though it is constant
   here:

   | Offset | Size | Value |
   | --- | --- | --- |
   | 0 | 1 | `03` — the version |
   | 1 | 2 | key id, first two bytes |
   | 3 | 1 | `02` — the length of what follows |
   | 4 | 2 | key id, bytes 2 and 3 |

2. **Generate a fresh 12-byte IV.** Random, per field, never reused — GCM under a repeated
   nonce and the same key leaks the plaintexts, and a record with several encrypted fields is
   exactly where a single IV gets reused by accident.
3. **Encrypt** with AES-128-GCM under `KDF(master_key, "encryption key key m")`, a **12-byte tag**,
   and `AAD = header ‖ "<zoneName>-<recordName>-<fieldName>"` — §6's construction unchanged.
4. **Lay the result out**, and note the order:

```
header ‖ IV ‖ tag ‖ ciphertext
```

> **The tag precedes the ciphertext.** Almost every AEAD interface in every language returns it
> appended, so the natural way to write this produces a value that decrypts as garbage and fails
> authentication — and §6's reader, written to the same layout, will happily round-trip it. **A
> field this client wrote and can read back is not evidence that Apple can read it.**

### What that means for verifying a write

A round trip through this implementation proves the layout is self-consistent, not that it is
right. The check that means something is that the write **preserves what it did not intend to
change** and that an untouched Apple device shows the new value.

Until that has been confirmed once, treat writing as unverified regardless of how cleanly it reads
back.

## 7. Interpreting the plaintext

Stage 4 §3.6 establishes that a field's declared `type` describes its **plaintext**. What the
plaintext *is* depends on that type, and the two cases differ:

| Declared type | Decrypted plaintext |
| --- | --- |
| `ENCRYPTED_BYTES_TYPE` (20) | **raw bytes.** No wrapper. |
| `STRING_TYPE`, `INT64_TYPE`, `DATE_TYPE`, and the other scalars | an **`EncryptedValue` message**, with the value in the field matching its type |

`EncryptedValue` carries `signedValue` at field 3, `dateValue` at 5 and `stringValue` at 6.

> **[observed] This is established by arithmetic, without decrypting anything.** Ciphertext
> overhead is fixed at 30 bytes — a 6-byte header, a 12-byte IV and a 12-byte tag (§6) — so
> plaintext length is the ciphertext length minus 30. Comparing that against the accessory data
> in the macOS cache format ([Stage 6](./06-output.md)):
>
> | Field | Ciphertext | Plaintext | macOS cache |
> | --- | --- | --- | --- |
> | `privateKey` | 115 | **85** | **85** |
> | `publicKey` | 87 | **57** | **57** |
> | `sharedSecret` | 62 | **32** | **32** |
> | `sharedSecret2` | 62 | **32** | **32** |
>
> Four exact matches: the `ENCRYPTED_BYTES_TYPE` fields decrypt to raw key material with nothing
> around it. The scalars do not fit that pattern and fit a wrapper exactly:
>
> | Field | Plaintext | As an `EncryptedValue` |
> | --- | --- | --- |
> | `stableIdentifier` | 38 | tag + length + **36** = a UUID string |
> | `model` | 8 | tag + length + **6** = `AirTag` |
> | `pairingDate` | 11 | tag + length + a 9-byte `Date` (tag + 8-byte double) |
> | `batteryLevel`, `isZeus`, `vendorId` | 2 | tag + a 1-byte varint |
>
> Every one lands exactly. Note the varint cases carry **no length prefix** — protobuf varint
> fields are not length-delimited — which is why they are two bytes rather than three.

**The date's epoch is still unsettled.** The `Date` message holds a double, and whether it counts
from 2001-01-01 as Apple's frameworks do or from the Unix epoch is not established here. Reject
an implausible result — a pairing date in the future, or before AirTags existed — rather than
exporting it.

## 8. Open questions

**Answered:**

1. ~~Is a decrypted field raw bytes, or the `EncryptedValue` message?~~ **Both, chosen by declared
   type** — see §7.

**Still open:**

2. **Does the date count from 2001 or from the Unix epoch?** §7. The `Date` message holds a double
   and nothing observed distinguishes the two. Reject an implausible result rather than exporting
   it.
3. ~~Does the zone's `protectionInfo` play any role?~~ **Yes — it is the level above.** A record's
   structure is unwrapped under a *zone* key, never under the keychain service key directly. See
   §4 step 0, which the answer added.
4. **What are the `SharingCircleSecret` records for?** [Stage 4 §3.5.1](./04-cloudkit.md) observes
   five in the zone, each with a `secretType`, a `sharingCircleIdentifier` and a `secretData` blob.
   The plausible reading is a parallel key hierarchy for accessories shared with others — see the
   [README](./README.md) — but nothing establishes it.
5. **What is the `meta` field, and the two unnamed integers?** All three are carried, none is needed
   to decrypt a field, and they are recorded rather than explained.
6. **What does `SignatureData.version` mean beyond `5`?** §3.1. Only the branch §4 step 3 depends on
   is established; the reference's own note on the numbering is self-contradictory.

**Unexercised:** nothing in this document has been run against a real record. It becomes testable
the moment Stage 3 can supply keys.

