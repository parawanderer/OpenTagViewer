# Report to FindMy.py: a raw `DerError` escapes `service_keys_from_der` and kills the run

> **A bug report from a consumer**, addressed to whoever works on FindMy.py. Delete it from this
> repository once the library has an answer.
>
> Traced from a real user's run — [OpenTagViewer#89](https://github.com/parawanderer/OpenTagViewer/issues/89).
> Their account is ordinary: five Apple AirTags, all owned by the signed-in Apple ID, all visible
> under *Items*. Nothing exotic about it, which is the point — this is reachable by anybody.

## One sentence

`service_keys_from_der` converts a `DerError` to `ServiceKeyError` for the *outer* parse and not
for the descent, so an item whose first element parses but whose contents are not DER escapes as a
raw `DerError` and aborts the export — where the two items immediately before it, failing for the
same underlying reason, were skipped correctly.

## The evidence, from one run

The last three lines of the user's log, in order:

```
DEBUG findmy.keychain.session: Item for acct 504353426f756e64 holds no key: An item's v_Data is
      not DER at all: Truncated DER: element claims 42 bytes, 30 remain
DEBUG findmy.keychain.session: Item for acct 64656661756c74 holds no key: An item's v_Data is
      not DER at all: Truncated DER: element claims 67 bytes, 19 remain

DerError: Truncated DER: element claims 109 bytes, 61 remain      <- fatal
```

The first two are hex for `PCSBound` and `default`. Both were **handled**: caught, logged, skipped,
run continues. The third is the same class of malformed payload and it takes down the whole export.

The view held 61 items and 59 were read, so this is one item in sixty-one.

## Why the difference

`servicekey.py`:

```python
try:
    element, _ = der.parse_one(payload)          # guarded
except der.DerError as e:
    msg = f"An item's v_Data is not DER at all: {e}"
    raise ServiceKeyError(msg) from None

if element.tag_class == der.CLASS_APPLICATION and element.tag_number == PRIVATE_KEY_V2_TAG:
    return _from_v2(element)

return _from_v1(element)                          # not guarded
```

`_from_v1` opens with `children = element.children()` (line 398), which calls `parse_all` on the
element's *content*. If the outer TLV is well-formed but its content is not DER — which is exactly
what a non-key item looks like — that raises `DerError`, and `DerError` is not a `ServiceKeyError`.

The caller only catches the latter:

```python
# session.py:603
try:
    found = service_keys_from_der(payload_of(item))
except (ItemError, ServiceKeyError) as e:
    logger.debug("Item for acct %s holds no key: %s", account[:8].hex(), e)
    continue
```

So the skip-and-continue that exists for precisely this situation is bypassed on a technicality of
which exception type came out.

## Suggested fix

Make the whole of `service_keys_from_der` answer in its own vocabulary, rather than only its first
statement:

```python
try:
    element, _ = der.parse_one(payload)
    if element.tag_class == der.CLASS_APPLICATION and element.tag_number == PRIVATE_KEY_V2_TAG:
        return _from_v2(element)
    return _from_v1(element)
except der.DerError as e:
    msg = f"An item's v_Data is not DER at all: {e}"
    raise ServiceKeyError(msg) from None
```

`_from_v1` and `_from_v2` both descend, so both can raise it; wrapping at this level covers the
two arms and anything added later. The docstring already promises `:raises ServiceKeyError: If the
payload is neither form` — this makes that true.

Worth considering whether `session.py:603` should also catch `DerError` defensively. Belt and
braces, but the failure mode is a user's whole export dying on one unreadable item out of sixty-one,
which is a bad trade against one extra exception type in a tuple.

## What this is not

- **Not the user's account.** Owned tags, on the right account, visible under *Items*.
- **Not Advanced Data Protection**, which they initially suspected. Keychain material is end-to-end
  encrypted under trusted-device custody either way.
- **Not the two `carries no signing key` warnings** in the same log; those peers are skipped by
  design.

## Reproducing without an account

The failing shape is an outer TLV whose length is honest and whose content is not DER:

```python
from findmy.keychain.servicekey import service_keys_from_der

# SEQUENCE, length 3, content that is not parseable as DER elements
service_keys_from_der(bytes([0x30, 0x03, 0x02, 0x7F, 0x41]))
```

Expected: `ServiceKeyError`. Actual: `DerError`.
