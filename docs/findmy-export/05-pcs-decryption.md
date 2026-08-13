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

**`SignatureData`** — carries the version that §4 step 3 branches on:

```asn1
SignatureData ::= SEQUENCE {
    version           INTEGER,
    data              OCTET STRING
}
```

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
    v2                [APPLICATION 5] SEQUENCE { data OCTET STRING }
}
```

> **`[APPLICATION 1]` is used by both `ShareProtection` and `PublicKey`.** They are told apart by
> where they appear, not by their tag, so a decoder must be told which one to expect rather than
> dispatching on the tag alone.

The Find My service is **v2** (§2), so its keys are the `[APPLICATION 5]` form.

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

**Step 1 — find the entry that belongs to us.** Compress our private key's public part and scan
the keyset for an entry whose public key matches those bytes. That entry's ciphertext is the
wrapped key intended for us. If none matches, we do not hold a key for this record and no amount
of retrying will change that — report it as a missing key, not as a decryption failure.

**Step 2 — unwrap it.** The wrapping is **RFC 6637** — the ECDH key-wrap construction from
OpenPGP — with the fixed parameter string `fingerprint`. The result is the PCS **master key**,
16 bytes.

**Step 3 — derive the share key, conditionally.**

```
if signature_data.version != 5 and not read_only:
        master_key = KDF(master_key, "MsaeEooevaX fooo 012")
```

Otherwise the master key is used directly. **This branch is easy to miss and produces a key that
fails every subsequent check**, so verify against the checksum in step 5 rather than pressing on.

**Step 4 — verify the signatures.** The structure is signed, and unless the read-only flag is set
the signature is verified against a key derived from the master key itself (§5). A second,
"past" signature may be present and should be tried if the first fails — that is key rotation,
not corruption. An owner signature may additionally be present as attribute `7`.

**Step 5 — check you have the right key.** Two independent checks, and both should be performed
before anything is decrypted:

- the first 4 bytes of the derived key's **key id** must equal the structure's truncated key id
- the structure's **HMAC** must verify under the derived key

A mismatch means the wrong key, not corrupt data.

**Step 6 — decrypt the fields.**

## 5. Key derivation

Every derived key comes from the master key by the same construction: a **counter-mode KDF with
HMAC** (the NIST SP 800-108 shape), with a fixed label and an output the same length as the input
key.

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
n   = mask_to_bit_length(out, order_bits(P-256))
if n > order: n = n - order
private_scalar = n
```

Three traps in five lines: **ten** iterations, not a realistic PBKDF2 count; the output is
**reversed** because it is produced little-endian and consumed big-endian; and the reduction is a
single conditional subtraction rather than a modulo. The curve is **P-256**
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

**The AAD is the entire header** — bytes 0 to `4 + N`, including the version byte and the length
byte, not merely the key id.

The key is `KDF(master_key, "encryption key key m")`, 16 bytes for AES-128.

> Note the tag length: **12 bytes, not GCM's default 16.** A library configured with the default
> will reject every message, and the failure looks like corruption rather than misconfiguration.

## 7. Interpreting the plaintext

Stage 4 §3.6 establishes that a field's declared `type` describes its **plaintext**, so once
decrypted the bytes are interpreted according to it — a `STRING_TYPE` field yields a string, an
`INT64_TYPE` field an integer, and so on. The protocol also defines a small `EncryptedValue`
message holding a signed value, a date or a string, which is the shape a decrypted field takes.

Which of those two forms applies to which field type is **not established** — see §8.

## 8. Open questions

1. **Is a decrypted field raw bytes, or the `EncryptedValue` message?** §7 cannot say, and the
   answer decides whether decryption yields a value or another parse step.
2. **What are the `SharingCircleSecret` records for?** [Stage 4 §3.5.1](./04-cloudkit.md)
   observes five of them in the zone, each with a `secretType`, a `sharingCircleIdentifier` and a
   `secretData` blob. The plausible reading is that they protect accessories shared with others,
   as a parallel key hierarchy to this one — but nothing here establishes it.
3. **Does the zone's `protectionInfo` play a role, given every record carries its own?**
4. **What is the `meta` field, and the two `unknown` integers?** All three are carried and none
   is needed to decrypt a field, so they are recorded rather than explained. Stage 4
   observed both present at the zone and universal at the record. Whether a record's structure is
   unwrapped under a zone key or directly under the keychain service key is not established, and
   it changes what §4 step 1 is matching against.
