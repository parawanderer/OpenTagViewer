"""Generate the contributor list bundled into the app's Information page.

Writes `app/src/main/assets/contributors.json` and one avatar per contributor into
`app/src/main/assets/contributors/`. The app reads those at runtime and never talks to
GitHub itself, which matters for three reasons:

- the unauthenticated GitHub API allows 60 requests per hour *per IP*, and mobile carriers
  put thousands of users behind a handful of CGNAT addresses, so a runtime call would be
  rate limited in practice
- a token cannot be shipped to raise that limit, because anything in the APK can be read
  back out
- users of this app are avoiding one company's location network; silently handing their IP
  to another company's CDN every time they open an info screen is a poor trade

Ordering
--------
By recency-weighted volume rather than raw commit count. `/stats/contributors` reports
weekly buckets of additions and deletions per author, so each week's lines are compressed
with log1p - one vendored dependency or reformat should not outrank a year of real work -
and then decayed by how long ago that week was.

    week_score = log1p(additions + deletions) * 0.5 ** (weeks_ago / HALF_LIFE_WEEKS)

This is a heuristic, and it is worth being honest about what it is not: churn is not value.
Someone who deletes 5,000 lines of dead code scores well, and someone whose contribution
was one deeply considered ten-line fix scores badly. It is only meant to put people who are
active now near the front.

Never fails the build. If GitHub is unreachable or rate limited, the previously generated
files are left exactly as they are and this exits 0 with a warning.

Usage:
    python scripts/fetch_contributors.py
    GITHUB_TOKEN=ghp_... python scripts/fetch_contributors.py   # 5000 req/hr instead of 60
"""

from __future__ import annotations

import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = "parawanderer/OpenTagViewer"

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS = REPO_ROOT / "app" / "src" / "main" / "assets"
MANIFEST = ASSETS / "contributors.json"
AVATAR_DIR = ASSETS / "contributors"

# Avatars render in a small circle. 144px covers xxxhdpi without bloating the APK.
AVATAR_SIZE_PX = 144

# A week of work counts half as much once it is this old.
HALF_LIFE_WEEKS = 52.0

SECONDS_PER_WEEK = 7 * 24 * 60 * 60

# GitHub computes contributor stats asynchronously and answers 202 with an empty body while
# it does. It is expected on a cold cache, not an error.
STATS_RETRIES = 6
STATS_RETRY_DELAY_S = 3


def _request(url: str, accept: str = "application/vnd.github+json"):
    request = urllib.request.Request(url)
    request.add_header("Accept", accept)
    request.add_header("User-Agent", "OpenTagViewer-build-script")
    request.add_header("X-GitHub-Api-Version", "2022-11-28")

    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")

    return urllib.request.urlopen(request, timeout=30)


def fetch_stats() -> list[dict]:
    url = f"https://api.github.com/repos/{REPO}/stats/contributors"

    for attempt in range(1, STATS_RETRIES + 1):
        with _request(url) as response:
            if response.status == 202:
                print(f"  GitHub is still computing stats, retrying ({attempt}/{STATS_RETRIES})")
                time.sleep(STATS_RETRY_DELAY_S)
                continue

            body = response.read()
            if not body.strip():
                print(f"  empty stats body, retrying ({attempt}/{STATS_RETRIES})")
                time.sleep(STATS_RETRY_DELAY_S)
                continue

            return json.loads(body)

    raise RuntimeError("GitHub never finished computing contributor stats")


def score(weeks: list[dict], now_s: float) -> float:
    """Recency-weighted, log-compressed churn. See the module docstring."""
    total = 0.0

    for week in weeks:
        lines = week.get("a", 0) + week.get("d", 0)
        if lines <= 0:
            continue

        weeks_ago = max(0.0, (now_s - week.get("w", 0)) / SECONDS_PER_WEEK)
        total += math.log1p(lines) * (0.5 ** (weeks_ago / HALF_LIFE_WEEKS))

    return total


def download_avatar(login: str, avatar_url: str) -> str | None:
    # Strip any existing size parameter so ours is the one that applies.
    base = avatar_url.split("?")[0]
    url = f"{base}?s={AVATAR_SIZE_PX}&v=4"
    filename = f"{login.lower()}.png"

    try:
        with _request(url, accept="image/png") as response:
            data = response.read()
    except (urllib.error.URLError, urllib.error.HTTPError) as error:
        print(f"  could not fetch avatar for {login}: {error}")
        return None

    if not data:
        print(f"  empty avatar for {login}")
        return None

    AVATAR_DIR.mkdir(parents=True, exist_ok=True)
    (AVATAR_DIR / filename).write_bytes(data)
    return filename


def main() -> int:
    print(f"Fetching contributors for {REPO} ...")

    try:
        stats = fetch_stats()
    except Exception as error:  # noqa: BLE001 - never fail the build over this
        print(f"\nCould not reach GitHub ({error}).", file=sys.stderr)
        if MANIFEST.is_file():
            print("Keeping the previously generated contributor list.", file=sys.stderr)
            return 0
        print("No previously generated list exists; writing an empty one.", file=sys.stderr)
        ASSETS.mkdir(parents=True, exist_ok=True)
        MANIFEST.write_text(json.dumps({"contributors": []}, indent=2) + "\n", encoding="utf-8")
        return 0

    now_s = time.time()
    ranked = []

    for entry in stats:
        author = entry.get("author") or {}
        login = author.get("login")

        # Anonymous entries have no author object at all, and bots (dependabot and friends)
        # are not people to credit.
        if not login or author.get("type") == "Bot":
            continue

        ranked.append({
            "login": login,
            "profileUrl": author.get("html_url", f"https://github.com/{login}"),
            "avatarUrl": author.get("avatar_url", ""),
            "commits": entry.get("total", 0),
            "score": score(entry.get("weeks", []), now_s),
        })

    ranked.sort(key=lambda c: c["score"], reverse=True)

    contributors = []
    for person in ranked:
        filename = download_avatar(person["login"], person["avatarUrl"])
        contributors.append({
            "login": person["login"],
            "profileUrl": person["profileUrl"],
            "avatar": filename,
            "commits": person["commits"],
        })
        print(f"  {person['login']:<24} {person['commits']:>4} commits  score {person['score']:.1f}")

    ASSETS.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(
        json.dumps({"contributors": contributors}, indent=2) + "\n",
        encoding="utf-8")

    # Anyone who left the project should not linger in the assets forever.
    keep = {c["avatar"] for c in contributors if c["avatar"]}
    if AVATAR_DIR.is_dir():
        for stale in AVATAR_DIR.glob("*.png"):
            if stale.name not in keep:
                print(f"  removing stale avatar {stale.name}")
                stale.unlink()

    print(f"\nWrote {len(contributors)} contributor(s) to "
          f"{MANIFEST.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
