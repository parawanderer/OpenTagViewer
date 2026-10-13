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
"""The junk body every probe carries. Identical across variants by design.

**Where the extension point is.** This probes the edge only: an edge rule reads headers
(and perhaps a body's shape, which is why the body is fixed), while the GsService2
*handler* behind it reads the body's content -- the SRP exchange and the Anisette data
inside it. A refusal that arrives as HTTP 200 with `ec=-80045` in a plist body is the
handler, not the edge, and this script cannot see it: with a junk body it only ever asks
"was the request let in", never "would it authenticate". The 2026-10-12 refusal is
believed to be that kind (xtool's provisioning succeeds, then GsService init is refused),
which is why the edge probes pass while every real sign-in fails.

To probe the handler instead, the body has to be a structurally real SRP init:

    {"o": "init", "u": <apple id>, "ps": ["s2k", "s2k_fo"], "A2k": <hex SRP A value>}

built the way `AsyncAppleAccount._gsa_authenticate` in the pinned FindMy.py builds it
(`srp.User(...).start_authentication()` for `A2k`), with the Anisette values carried in
the same body (`X-Apple-I-MD`, `X-Apple-I-MD-M` and the rest -- see
`BaseAnisetteProvider.headers`). None of that needs a password: init runs before any
credential is checked, so a probe at this layer is still not a sign-in attempt. A
`--real-init` mode would build that body, send it, and parse the response plist's `ec`
-- `ec=0` means the handler took the exchange and it is no longer the edge's answer that
matters; `-80045` means the handler refused, and *then* varying the Anisette contents
(fresh vs. stale provisioning, serial present vs. absent) is the experiment that localises
the rule. Vary the body through the same `--set`/`--unset` mechanics by treating those
keys as body fields rather than headers.
"""


@dataclass(frozen=True)
class Answer:
    """What came back, including enough of the body to identify the refusal."""

    status: int
    content_type: str
    length: int
    server: str
    body_head: str

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


def send(headers: dict[str, str]) -> Answer:
    """One request, retried on connection failure but never on a refusal."""
    import urllib.error
    import urllib.request

    from findmy.util.tls import apple_trust_context

    tls = apple_trust_context()
    last: Exception | None = None
    for attempt in range(ATTEMPTS):
        request = urllib.request.Request(GSA, data=BODY, headers=headers, method="POST")
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
    )


def report(name: str, answer: Answer) -> None:
    """One line per probe, plus the body head when it is an HTML refusal."""
    verdict = "refused" if answer.refused else "through"
    print(
        f"{name}: {verdict} (HTTP {answer.status}, {answer.content_type or 'no content type'}, "
        f"{answer.length} bytes, Server {answer.server or '-'})",
    )
    if answer.refused:
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

    for name, headers in variants:
        for attempt in range(args.repeat):
            label = name if args.repeat == 1 else f"{name} [{attempt + 1}/{args.repeat}]"
            report(label, send(headers))

    return 0


if __name__ == "__main__":
    sys.exit(main())
