#!/usr/bin/env python3
"""
Ask Apple's edge whether it still lets this project's sign-in requests through.

**Why this exists.** In September 2026 Apple's edge began refusing every POST to Grand Slam
whose `X-MMe-Client-Info` named `com.apple.dt.Xcode`, before a credential was examined. It
answered with a small HTML page, which arrived as HTTP 503 and read as an outage, and sign-in
was broken for every user for days before anyone knew why (AGENTS.md rule 18). Nothing in this
repository changed; Apple did. The only way to notice the next rule like that before users do
is to ask the edge, on a schedule, with exactly the headers this project sends.

**No account is involved.** Each probe is an unauthenticated request with a junk body. The edge
decides on the headers alone, so a junk body is enough to see whether a request would be let
through; nothing is signed in and nothing is registered.

**What is sent is composed, not transcribed.** The hardware profiles are read out of
`AdiDeviceIdentity.java`, and every sign-in header is built by FindMy.py's own `DeviceIdentity`
from the same six fields Java hands the app's Python. The Grand Slam user agent and the akd and
Xcode bundles are the library's own values. A copy of a header string in here would be a second
source of truth that drifts, and a check that probes something the app no longer sends passes
while sign-in is broken (rule 11).

**How an answer is read.** Grand Slam itself answers in plists and never in HTML, so an HTML
page on an error status is the edge refusing. Anything else means the request got past the edge,
which is all this checks: with a junk body a request that got through is usually a 404 or a 401
rather than a success, and that is fine.

**The Xcode control.** One probe sends the identifier Apple blocked, and is expected to be
refused. It proves the probe can still see the rule. If it starts getting through, Apple has
lifted or reshaped the block; that is reported but is not a failure.

Exit codes: 0 all as expected, 1 one of this project's identities was refused, 2 Apple could
not be reached at all.

    python scripts/check_gsa_edge.py

Needs the pinned FindMy.py installed: `pip install -r app/src/test/python/requirements.txt`.
"""

from __future__ import annotations

import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

REPO = Path(__file__).resolve().parents[1]
HARDWARE_JAVA = (
    REPO / "app/src/main/java/dev/wander/android/opentagviewer/anisette/AdiDeviceIdentity.java"
)

GSA = "https://gsa.apple.com/grandslam/GsService2"
BAG = GSA + "/lookup"

THROUGH = "through"
REFUSED = "refused"
UNREACHABLE = "unreachable"

ATTEMPTS = 3
TIMEOUT_S = 20

# One enum constant of `AdiDeviceIdentity.Hardware`: a name, then eight string arguments -
# a display name, the six parts of the identity, and the app bundle Java's own client info names.
# Strings may hold escaped quotes: the legacy profile's display name is `"MacBook Pro 13\""`.
_JAVA_STRING = r'"((?:[^"\\]|\\.)*)"'
_PROFILE = re.compile(
    r"^\s*([A-Z][A-Z0-9_]*)\(\s*" + r",\s*".join([_JAVA_STRING] * 8) + r"\s*\)",
    re.MULTILINE,
)

# Every line that opens an enum constant, whether or not _PROFILE could read its arguments.
_CONSTANT = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\($", re.MULTILINE)


@dataclass(frozen=True)
class Profile:
    """One hardware profile the app can present, as `AdiDeviceIdentity.Hardware` declares it."""

    name: str
    model: str
    os_name: str
    os_version: str
    os_build: str
    cfnetwork: str
    darwin: str
    auth_kit_app: str

    def identity_json(self) -> dict[str, str]:
        """The six fields `Hardware.toJson` hands the app's Python, under the same keys."""
        return {
            "model": self.model,
            "os_name": self.os_name,
            "os_version": self.os_version,
            "os_build": self.os_build,
            "cfnetwork": self.cfnetwork,
            "darwin": self.darwin,
        }


@dataclass(frozen=True)
class Probe:
    """One request to make, and whether this project needs it let through."""

    name: str
    method: str
    url: str
    headers: dict[str, str]
    expect: str


@dataclass(frozen=True)
class Answer:
    """What came back: enough to classify it and to quote it in a report."""

    status: int | None
    content_type: str
    length: int
    server: str
    retry_after: str | None
    error: str | None = None


def read_profiles(java: Path = HARDWARE_JAVA) -> list[Profile]:
    """
    Every `Hardware` profile in the app, read from its source.

    Raises rather than returning nothing, because an empty list would make the check pass
    while probing none of the identities the app sends.
    """
    source = java.read_text()
    found = [
        Profile(m[1], *(_unescape(g) for g in m.groups()[2:])) for m in _PROFILE.finditer(source)
    ]

    # **A profile skipped is worse than a crash.** The first version of this pattern could not
    # read an escaped quote, and silently dropped the legacy profile - the one every install
    # uses - while reporting the rest as fine. So every constant the enum declares has to be
    # accounted for.
    missed = sorted(set(_CONSTANT.findall(source)) - {p.name for p in found})
    if missed or not found:
        raise RuntimeError(
            f"Could not read hardware profile(s) {missed or 'any'} in {java.name}. The enum's "
            "shape has changed; update _PROFILE in this script to match it."
        )
    return found


def _unescape(java_string: str) -> str:
    return re.sub(r"\\(.)", r"\1", java_string)


def classify(answer: Answer) -> str:
    """Whether the edge let the request through, refused it, or could not be reached."""
    if answer.status is None:
        return UNREACHABLE
    if answer.status >= 400 and answer.content_type.lower().startswith("text/html"):
        return REFUSED
    return THROUGH


def probes(profiles: list[Profile]) -> list[Probe]:
    """The requests to make: every identity this project sends, plus the Xcode control."""
    # The library's own values, deliberately private: this sends what the library sends, and a
    # rename on its side fails this loudly rather than leaving a stale copy here.
    from findmy.reports.account import _GSA_USER_AGENT
    from findmy.reports.anisette import _AKD_BUNDLE, _XCODE_BUNDLE, CLIENT_IDENTITY, DeviceIdentity

    def sign_in(client_info: str) -> dict[str, str]:
        # The header set `AsyncAppleAccount._gsa_request` sends. The Anisette values travel in
        # the body, not here.
        return {
            "Content-Type": "text/x-xml-plist",
            "Accept": "*/*",
            "User-Agent": _GSA_USER_AGENT,
            "X-MMe-Client-Info": client_info,
        }

    found: list[Probe] = []
    for profile in profiles:
        identity = DeviceIdentity.from_json(profile.identity_json())  # type: ignore[arg-type]
        found.append(Probe(
            f"sign-in, app {profile.name}", "POST", GSA,
            sign_in(identity.client_info(_AKD_BUNDLE)), THROUGH,
        ))
        # ADI provisioning starts from this bag, under Java's own client info and user agent
        # (`AdiProvisioning`, `Hardware.clientInfo` and `userAgent`). Those still name Xcode for
        # the legacy profile, and today the edge lets that through here. If it stops, fresh
        # installs cannot provision local Anisette.
        found.append(Probe(
            f"provisioning bag, app {profile.name}", "GET", BAG,
            {
                "User-Agent": identity.user_agent("akd/1.0"),
                "X-MMe-Client-Info": f"{identity.platform} <com.apple.AuthKit/1 ({profile.auth_kit_app})>",
            },
            THROUGH,
        ))

    found.append(Probe(
        "sign-in, exporter", "POST", GSA, sign_in(CLIENT_IDENTITY.client_info(_AKD_BUNDLE)), THROUGH,
    ))
    found.append(Probe(
        "sign-in, Xcode control", "POST", GSA,
        sign_in(CLIENT_IDENTITY.client_info(_XCODE_BUNDLE)), REFUSED,
    ))
    return found


def fetch(probe: Probe) -> Answer:
    """Make one request, retrying a failure to connect but never a refusal."""
    # **Apple's own root, as the library trusts it.** gsa.apple.com chains to Apple's private
    # root CA, which macOS trusts and a Linux runner's store does not - so the first CI run of
    # this failed certificate verification on every probe while passing on a Mac. FindMy.py
    # bundles that root, pinned by hash; using its context keeps verification on and trusts
    # exactly what the shipped library trusts.
    from findmy.util.tls import apple_trust_context

    tls = apple_trust_context()
    error = "no attempt made"
    for attempt in range(ATTEMPTS):
        request = urllib.request.Request(
            probe.url,
            data=b"t" if probe.method == "POST" else None,
            headers=probe.headers,
            method=probe.method,
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_S, context=tls) as response:
                return _answer(response.status, response.headers, response.read())
        except urllib.error.HTTPError as e:
            return _answer(e.code, e.headers, e.read())
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            error = f"{type(e).__name__}: {e}"
            time.sleep(2 * (attempt + 1))
    return Answer(None, "", 0, "", None, error)


def _answer(status: int, headers, body: bytes) -> Answer:
    return Answer(
        status,
        headers.get("Content-Type", ""),
        len(body),
        headers.get("Server", ""),
        headers.get("Retry-After"),
    )


def describe(answer: Answer) -> str:
    """One line a person reads in a CI log or an issue."""
    if answer.status is None:
        return f"no answer ({answer.error})"
    wait = f", Retry-After {answer.retry_after}" if answer.retry_after else ""
    kind = answer.content_type or "no content type"
    return f"HTTP {answer.status}, {kind}, {answer.length} bytes, Server {answer.server or '-'}{wait}"


def run(checks: list[Probe], ask: Callable[[Probe], Answer] = fetch, out=sys.stdout) -> int:
    """Make every probe, report each, and return the exit code."""
    refused: list[str] = []
    unreachable: list[str] = []

    for probe in checks:
        answer = ask(probe)
        verdict = classify(answer)
        mark = "ok  " if verdict == probe.expect else "FAIL"
        print(f"{mark} {probe.name}: {verdict} ({describe(answer)})", file=out)

        if verdict == UNREACHABLE:
            unreachable.append(probe.name)
        elif probe.expect == THROUGH and verdict == REFUSED:
            refused.append(probe.name)
        elif probe.expect == REFUSED and verdict == THROUGH:
            print(
                f"note {probe.name}: Apple no longer refuses the Xcode identifier. The block this "
                "check was written for has been lifted or has changed shape. Not a failure.",
                file=out,
            )

    if refused:
        print(
            f"\nApple's edge refused {len(refused)} of this project's identities: "
            f"{', '.join(refused)}. Sign-in or provisioning is likely failing for users. "
            "See AGENTS.md rule 18.",
            file=out,
        )
        return 1
    if unreachable:
        print(f"\nCould not reach Apple for: {', '.join(unreachable)}.", file=out)
        return 2
    return 0


def main() -> int:
    return run(probes(read_profiles()))


if __name__ == "__main__":
    sys.exit(main())
