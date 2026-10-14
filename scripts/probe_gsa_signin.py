#!/usr/bin/env python3
"""
Send arbitrary Grand Slam sign-in variants to Apple's edge and compare the answers.

**Who this is for.** Somebody investigating an ecosystem-wide refusal like the one of
2026-10-12 (`ec=-80045` at SRP init, reported across AltStore, SideStore, xtool, iloader,
atvloadly and this project's #248), who wants to test a hypothesis about *which* property
of a request Apple is refusing. `check_gsa_edge.py` asks the fixed question "are our
requests still let through"; this one lets you vary the request and see what changes.

**No account is involved.** Every probe is an unauthenticated POST with a junk body, the
same as `check_gsa_edge.py`. The edge decides on the headers alone. Nothing is signed in,
nothing is registered, and no Apple ID is read from anywhere -- which is what makes it safe
to run a variant loop without the account-locking risk the atvloadly thread warns about:
refusals at the edge are not sign-in attempts.

**How to read an answer.** Grand Slam answers in plists and never in HTML. An HTML page on
an error status is the edge refusing; anything else means the request got past the edge.
With a junk body, getting through usually means a 404 or 401 rather than a success, and
that is fine -- the question is never "did it work", only "was it let in".

**The body is deliberately junk.** Some edge rules look at the body's shape rather than its
content, so the body is a fixed 1-byte stub in every probe: identical across variants, so
any difference in answers is the headers.

Examples:

    # The library's own request, as a baseline
    python scripts/probe_gsa_signin.py

    # Does the edge care about the User-Agent?
    python scripts/probe_gsa_signin.py --set User-Agent="Probe/1.0"

    # Does it care about a header being absent?
    python scripts/probe_gsa_signin.py --unset X-MMe-Client-Info

    # A specific client-info string, e.g. from another project's bug report
    python scripts/probe_gsa_signin.py --client-info "<Mac14,2> <macOS;15.7.5;24G624> <com.apple.AuthKit/1 (com.apple.akd/1.0)>"

    # Several variants in one run, compared against each other
    python scripts/probe_gsa_signin.py --set Accept="text/html" --unset Accept

    # The handler layer: a structurally real SRP init, three Anisette variants --
    # none, junk, provisioned -- each reported as the response plist's ec
    python scripts/probe_gsa_signin.py --real-init
    python scripts/probe_gsa_signin.py --real-init --anisette-junk
    python scripts/probe_gsa_signin.py --real-init --anisette-json anisette.json \
        --cpd-unset X-Apple-I-SRL-NO --cpd-unset X-Apple-I-MD

Each probe is named by its variant and reported as `through` or `refused`, with the full
answer (status, content type, length, and the first bytes of the body, which is where an
`ec=` code or an HTML error page identifies itself). Repeating the same variant twice in
one run is a good habit: two identical answers say the edge is in a steady state, one
answer says nothing about that.

Needs the pinned FindMy.py installed: `pip install -r app/src/test/python/requirements.txt`.
"""

from __future__ import annotations

import argparse
import sys
import time
from dataclasses import dataclass

GSA = "https://gsa.apple.com/grandslam/GsService2"

# As in check_gsa_edge.py: a probe that connects may still be refused, and one that
# cannot connect says nothing about the edge at all.
ATTEMPTS = 2
TIMEOUT_S = 20
RETRY_SLEEP_S = 2

BODY = b"t"
"""The junk body every edge probe carries. Identical across variants by design.

**Where the extension point was.** The edge probes only ever asked "was the request
let in"; the GsService2 *handler* behind the edge reads the body's content -- the SRP
exchange and the Anisette data inside it -- and a refusal that arrives as HTTP 200 with
`ec=-80045` in a plist body is the handler, not the edge. `--real-init` reaches that
layer: it builds a structurally real SRP init, parses the response plist's `ec`, and
lets the Anisette contents be varied through `--cpd-set`/`--cpd-unset`. A variant that
flips the answer from `-80045` to anything else is the rule's location.
"""

# A synthetic Apple ID, not a sign-in: init runs before any password is checked, so
# probing with it is not an authentication attempt against any account.
DEFAULT_PROBE_APPLE_ID = "probe-nonexistent@example.com"


def _real_init_body(
    apple_id: str,
    cpd: dict,
) -> bytes:
    """The structurally real SRP init body `AsyncAppleAccount._gsa_authenticate` sends.

    Identical shape to the library's: `srp.User.start_authentication()` mints a fresh
    `A2k` every call, so two runs are never byte-identical -- which is also true of real
    sign-ins, and means the handler cannot be answering on body equality.
    """
    import plistlib

    import srp

    usr = srp.User(apple_id, b"", hash_alg=srp.SHA256, ng_type=srp.NG_2048)
    _, a2k = usr.start_authentication()
    body = {
        "Header": {"Version": "1.0.1"},
        "Request": {
            "cpd": cpd,
            "A2k": a2k,
            "u": apple_id,
            "ps": ["s2k", "s2k_fo"],
            "o": "init",
        },
    }
    return plistlib.dumps(body)


def _baseline_cpd() -> dict:
    """The constant `cpd` flags `BaseAnisetteProvider.get_cpd` always sets.

    Without `--anisette-json` or `--anisette-junk` this is the whole `cpd` -- the
    "no Anisette data at all" variant, the cheapest possible question: does the
    handler even reach the Anisette check before refusing?
    """
    return {
        "bootstrap": True,
        "icscrec": True,
        "pbe": False,
        "prkgen": True,
        "svct": "iCloud",
    }


def _junk_anisette() -> dict[str, str]:
    """Anisette-shaped values that are provably not provisioned data.

    The "present but wrong" variant: every field a real `get_cpd` emits, filled
    deterministically. If this and the missing-fields variant answer alike while a
    provisioned set answers differently, the handler is reading the values; if all
    three answer `-80045`, the refusal precedes the Anisette check.
    """
    return {
        "X-Apple-I-Client-Time": "2000-01-01T00:00:00Z",
        "X-Apple-I-TimeZone": "UTC",
        "loc": "en_US",
        "X-Apple-Locale": "en_US",
        "X-Apple-I-MD": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "X-Apple-I-MD-LU": "AAAAAAAAAAA=",
        "X-Apple-I-MD-M": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                          "AAAAAAAAAAAAAAAAAAAAAAAA",
        "X-Apple-I-MD-RINFO": "17106176",
        "X-Mme-Device-Id": "00000000-0000-0000-0000-000000000000",
        "X-Apple-I-SRL-NO": "PROBE000000",
    }


def _load_anisette_json(path: str) -> dict:
    """Provisioned Anisette fields exported by a live provider.

    Dump format is a flat string map, e.g. produced next to a working install by
    `await anisette.get_headers(uid, devid)` serialised to JSON -- for this project,
    the local provider behind `ani_libs.bin` on the backend host.
    """
    import json

    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        msg = f"{path}: expected a JSON object of header names to values"
        raise SystemExit(msg)
    return {str(k): str(v) for k, v in data.items()}


@dataclass(frozen=True)
class Answer:
    """What came back, including enough of the body to identify the refusal."""

    status: int
    content_type: str
    length: int
    server: str
    body_head: str
    # The unabridged response -- the handler's plist can exceed body_head's window.
    raw: bytes = b""

    @property
    def refused(self) -> bool:
        """Whether the edge refused this: an HTML page, where a plist was expected."""
        return self.status >= 400 and self.content_type.lower().startswith("text/html")


def _baseline_headers(client_info: str) -> dict[str, str]:
    """The header set `AsyncAppleAccount._gsa_request` sends, from the library itself."""
    from findmy.reports.account import _GSA_USER_AGENT

    return {
        "Content-Type": "text/x-xml-plist",
        "Accept": "*/*",
        "User-Agent": _GSA_USER_AGENT,
        "X-MMe-Client-Info": client_info,
    }


def send(headers: dict[str, str], body: bytes = BODY) -> Answer:
    """One request, retried on connection failure but never on a refusal."""
    import urllib.error
    import urllib.request

    from findmy.util.tls import apple_trust_context

    tls = apple_trust_context()
    last: Exception | None = None
    for attempt in range(ATTEMPTS):
        request = urllib.request.Request(GSA, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_S, context=tls) as response:
                return _answer(response.status, response.headers, response.read())
        except urllib.error.HTTPError as e:
            return _answer(e.code, e.headers, e.read())
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last = e
            time.sleep(RETRY_SLEEP_S * (attempt + 1))
    print(f"  unreachable: {type(last).__name__}: {last}", file=sys.stderr)
    raise SystemExit(2)


def _answer(status: int, headers, body: bytes) -> Answer:
    return Answer(
        status,
        headers.get("Content-Type", ""),
        len(body),
        headers.get("Server", ""),
        # The body head is where the answer names itself: an `ec=` code, an HTML doctype,
        # or a plist's opening tag. 160 bytes is enough to tell those apart and small
        # enough to quote whole in an issue.
        body[:160].decode("utf-8", errors="replace").replace("\n", " "),
        body,
    )


def _handler_verdict(body: bytes) -> str | None:
    """The plist `Status` a real init gets, or None when the answer is not a plist.

    `ec=0` means the handler took the exchange; `-80045` is the 2026-10-12 refusal.
    `em`/`au` are quoted when Apple sends them, because `em` is the only sentence the
    handler ever writes back.
    """
    import plistlib

    try:
        parsed = plistlib.loads(body)
    except Exception:
        return None
    status = (parsed.get("Response") or {}).get("Status") or {}
    if not isinstance(status, dict) or "ec" not in status:
        return None
    parts = [f"ec={status['ec']}"]
    for key in ("em", "au"):
        if status.get(key) is not None:
            parts.append(f"{key}={status[key]!r}")
    return " ".join(parts)


def report(name: str, answer: Answer, is_real_init: bool = False) -> None:
    """One line per probe: the handler's `ec` when the request was a real init,
    through/refused for edge probes, plus the body head on an HTML refusal."""
    verdict = _handler_verdict(answer.raw) if is_real_init else None
    if verdict is None:
        verdict = "refused" if answer.refused else "through"
    print(
        f"{name}: {verdict} (HTTP {answer.status}, {answer.content_type or 'no content type'}, "
        f"{answer.length} bytes, Server {answer.server or '-'})",
    )
    if verdict == "refused":
        print(f"    body: {answer.body_head}")


def main() -> int:
    """Build the variant list from the arguments, send each, and compare."""
    parser = argparse.ArgumentParser(
        description="Probe Apple's Grand Slam edge with header variants (no account used).",
    )
    parser.add_argument(
        "--client-info", metavar="STRING",
        help="X-MMe-Client-Info to send. Default: the exporter's, composed by FindMy.py.",
    )
    parser.add_argument(
        "--set", action="append", default=[], metavar="NAME=VALUE", dest="sets",
        help="Set or override a header. Repeatable.",
    )
    parser.add_argument(
        "--unset", action="append", default=[], metavar="NAME", dest="unsets",
        help="Remove a header from the baseline. Repeatable.",
    )
    parser.add_argument(
        "--repeat", type=int, default=1, metavar="N",
        help="Send each variant N times, to see whether the edge is in a steady state.",
    )
    parser.add_argument(
        "--real-init", action="store_true",
        help="Probe the GsService2 handler instead of the edge: send a structurally "
             "real SRP init body and report the response plist's Status.ec. "
             "Still no account -- init runs before any password is checked.",
    )
    parser.add_argument(
        "--apple-id", metavar="EMAIL", default=DEFAULT_PROBE_APPLE_ID,
        help="The `u` value of the init. Default: a synthetic address, used so the "
             "probe is never mistaken for a sign-in against a real account.",
    )
    parser.add_argument(
        "--anisette-json", metavar="FILE",
        help="JSON object of provisioned Anisette fields to merge into `cpd` "
             "(e.g. a dump of a live provider's get_headers output).",
    )
    parser.add_argument(
        "--anisette-junk", action="store_true",
        help="Fill `cpd`'s Anisette fields with deterministic junk -- the "
             "'present but not provisioned' variant.",
    )
    parser.add_argument(
        "--cpd-set", action="append", default=[], metavar="NAME=VALUE", dest="cpd_sets",
        help="Set or override a `cpd` field in a real-init body. Repeatable; "
             "each becomes its own variant, like --set does for headers.",
    )
    parser.add_argument(
        "--cpd-unset", action="append", default=[], metavar="NAME", dest="cpd_unsets",
        help="Remove a `cpd` field in a real-init body. Repeatable.",
    )
    args = parser.parse_args()

    from findmy.reports.anisette import _AKD_BUNDLE, CLIENT_IDENTITY

    client_info = args.client_info or CLIENT_IDENTITY.client_info(_AKD_BUNDLE)
    baseline = _baseline_headers(client_info)

    variants: list[tuple[str, dict[str, str]]] = [("baseline", dict(baseline))]
    for i, pair in enumerate(args.sets, 1):
        name, _, value = pair.partition("=")
        headers = dict(baseline)
        headers[name] = value
        variants.append((f"set {pair}", headers))
    for i, name in enumerate(args.unsets, 1):
        headers = dict(baseline)
        headers.pop(name, None)
        variants.append((f"unset {name}", headers))

    cpds: list[tuple[str, dict]] = []
    if args.real_init:
        cpd = _baseline_cpd()
        if args.anisette_json:
            cpd.update(_load_anisette_json(args.anisette_json))
        if args.anisette_junk:
            cpd.update(_junk_anisette())
        cpds.append(("cpd:baseline", cpd))
        for pair in args.cpd_sets:
            name, _, value = pair.partition("=")
            variant = dict(cpd)
            variant[name] = value
            cpds.append((f"cpd-set {pair}", variant))
        for name in args.cpd_unsets:
            variant = dict(cpd)
            variant.pop(name, None)
            cpds.append((f"cpd-unset {name}", variant))

    for name, headers in variants:
        if args.real_init:
            for cpd_name, cpd in cpds:
                label = f"{name} | {cpd_name}" if len(cpds) > 1 else name
                body = _real_init_body(args.apple_id, cpd)
                for attempt in range(args.repeat):
                    suffix = f" [{attempt + 1}/{args.repeat}]" if args.repeat > 1 else ""
                    report(label + suffix, send(headers, body), is_real_init=True)
        else:
            for attempt in range(args.repeat):
                label = name if args.repeat == 1 else f"{name} [{attempt + 1}/{args.repeat}]"
                report(label, send(headers))

    return 0


if __name__ == "__main__":
    sys.exit(main())
