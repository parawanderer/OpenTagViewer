---
name: copilot-style-review
description: Adversarial pre-merge review of a diff in the style of GitHub Copilot's pull request reviewer - traces mechanisms end-to-end and reports verified failures, not style nits. Use before pushing branches or opening/updating a PR, or when asked to "review like Copilot".
---

# Copilot-style adversarial review

The thing to imitate is not a lint pass. Copilot's PR reviews reliably find bugs that
compile, pass every test, and read as correct - because each one lives in the *gap
between two correct-looking pieces*: a filter that requires a byte the parser would
accept elsewhere, a lifecycle that leaves a subscription orphaned, an empty-state that
claims the wrong reason. This skill reproduces that: review the diff, then trace every
changed mechanism end-to-end and report only findings you can pin to a concrete input or
sequence.

**Repo notes:** if this directory contains a `repo-notes-<repo>.md` whose name matches
the repository under review, read it and apply it - project conventions and its known
review precedents live there, not here.

## What Copilot actually produces (the format to match)

One finding per comment, each a single dense paragraph:

> "Opening this screen with Bluetooth off completes the scan subscription. Turning
> Bluetooth on while the activity remains resumed does not subscribe again, so devices
> remain undiscoverable until the screen is reopened. Resubscribe after completion with
> a delay, cancelled by onPause(); MapsActivity.onNearbyWatchEnded uses a 30-second retry
> for the same case."

The anatomy, every time:

1. **A concrete trigger** - an input or a state sequence, not "this could fail".
2. **The mechanism chain** - which line causes which intermediate state.
3. **The user-visible consequence** - what actually goes wrong on screen or on disk.
4. **The fix** - concrete, and when a precedent exists in the codebase, named by
   class and method.

No severity labels, no praise, no "consider adding tests". Findings only.

## Procedure

### 1. Scope the diff

```bash
git merge-base HEAD <base>    # for a branch review
git diff <base>...HEAD        # the whole review surface
git log --oneline <base>..HEAD
```

For stacked branches (`feature/a` on `feature/b` on `main`), review **each commit or
each branch boundary separately** - a bug belongs to the commit that introduces it, and
fixing it later in the stack leaves a broken commit in the middle.

### 2. For every changed hunk, trace the mechanism

Do not review lines; review the *mechanism the lines belong to*. For each hunk, answer
from the actual code (read callers, callees, lifecycle methods - do not assume):

- Who calls this, on which thread, in which lifecycle state?
- What happens on every termination path - success, error, *and silent completion*?
- What happens when this runs twice, out of order, or interleaved?
- What may have changed between the request and the deferred callback?
- What survives process death, screen rotation, re-entry?

### 3. Run the failure-class checklist

Work through every class; a "no" needs a reason you checked, not an assumption:

**Format/protocol boundaries**
- Does a parser assume an offset, order, or fixed size the format doesn't guarantee?
  Check masks, length bytes, signedness, endianness.
- If two components parse the same bytes, do they agree? Test what one accepts and the
  other rejects.

**Lifecycle & state machines**
- Walk the screen/service entering in *every* initial state: permission missing,
  radio off, hardware absent, cold vs warm start.
- Is every terminal callback handled - including `onComplete` (silent death) and
  error paths that leave "loading" or a misleading empty state?
- Does a callback ever invoke a lifecycle method manually? The framework re-enters on
  its own - a manual call plus the framework's re-entry means only the second pair of
  subscriptions/handlers gets cleaned up.

**Concurrency**
- Which scheduler runs `subscribe` callbacks vs the work? Is file/DB I/O ever on the
  main thread (check `observeOn` order - work after it is on the new thread)?
- Is shared mutable state (maps, trackers) touched from more than one thread?
- Can two instances of the same persistent store exist? (Two `File`-backed objects
  overwrite each other's flush - split-brain.)
- A deferred answer must re-validate the conditions it was requested under. A request
  fired while a condition held ("no markers yet", "no saved view") lands in a world
  that may have changed - the callback re-checks before acting, or a stale answer
  stomps a newer decision.
- Every async success has a failure arm. `addOnSuccessListener`/`getLastLocation`-
  style Task calls without `addOnFailureListener` strand whatever the success path was
  meant to reach - fatally so behind a "decide exactly once" flag, which is already
  consumed and never retries.

**Resources**
- Can a `Disposable`/stream/cursor/handler be assigned while a previous one is live?
  Overwrite-before-dispose leaks the first.
- Is anything cached that the platform revokes (permission, adapter, bluetooth state)?

**Honest states**
- Does an "empty"/"failed"/"unavailable" UI state say what actually happened? An error
  rendered as an empty list is a bug, not a nit.
- Are there silent fallbacks: catch-and-continue, `"{}"` on failure, fabricated values
  for missing data? Where the project's rules forbid fallbacks, these are findings,
  not robustness.
- RecyclerView rows are recycled: every per-row visual state (alpha, visibility, color)
  must be set unconditionally in `onBindViewHolder`.

**Persistence**
- Does a new field tolerate old records (and vice versa)? Old files must load with
  explicit nulls, not invented values.
- Bounded? A log that grows per event grows forever. Writes atomic (tmp + rename)?
  Corrupt mid-write kills the whole file otherwise.

**Numbers & boundaries**
- NaN reaching `Math.min/max` (NaN propagates to the wrong end), `hashCode() % n` going
  negative (use `Math.floorMod`), off-by-one on retention windows, int overflow.

**Android specifics**
- Deprecated APIs against `minSdk` (check the exact API level, not vibes);
  `Vibrator`, `vibrate(long)`, `startActivityForResult` are the classic ones.
- Contrast: normal text needs ≥ 4.5:1 - compute it from the theme colors, don't eyeball.
- Permissions declared and runtime-requested; background-work limits on modern SDK.
- Every new UI string has to exist in every locale the project ships - check the
  project's own string tooling/check, not the files by hand.
- Locale reads: `Locale.getDefault()` is the *device* locale - a per-app language
  (`locales_config`, `AppCompatDelegate`) never rewrites it. Read the activity's
  `getResources().getConfiguration().getLocales().get(0)` and match `getLanguage()`;
  `getCountry()` is empty on a language-only tag like `de`, so a region switch
  silently never fires.
- Map/View sizing: calls that compute in pixels (camera moves, scrollTo, projections)
  on a view that has not been laid out are a half-viewport offset, not a no-op -
  defer via `addOnFirstLayoutListener`/`post` until `getWidth() > 0`.

### 4. Verify before reporting - the step that makes it Copilot-quality

A finding is only a finding when you can write it as a concrete trace:

> *"Do X while Y → Z happens because line N → user sees W."*

- Read the *installed* version of any API you cite - assumed APIs have been wrong
  before; pin against the dependency version the build actually uses.
- If a finding can be turned into a unit test, sketch it mentally; if you can't name
  the failing input, drop the finding - speculation is how review noise is born.
- Check the fix you're about to suggest actually exists (an assumed overload once did
  not; the real signature took a `byte[]` where the suggestion wanted an int).

### 5. Report in Copilot's format

Numbered findings, ordered by impact:

```
[path/to/File.java:NNN] <mechanism statement as a fact>

<The trigger sequence>. <The chain through the code>. <The consequence
the user or the data sees>. <The concrete fix>, citing the codebase
precedent when one exists (ClassName.methodName does this already).
```

Lead with the sentence stating *what is wrong as a mechanism*, like Copilot does -
not "this might be a problem" but "A valid frame never reaches the list because ...".

### 6. Fix findings where they were born

In a stacked-branch workflow, an accepted finding is fixed by **amending the commit
that introduced the broken code**, then rebasing the rest of the chain - not by a fixup
commit on top. After fixes, run the project's full verification (build plus its test
suite and static checks, per the repo's own tooling).

## Anti-checklist - what this is not

- Not a style review. No naming opinions, no "consider extracting", no praise.
- Not a completeness audit. Missing features are not findings unless the diff
  *claims* them.
- Not hypothetical hardening. "An attacker could..." needs a concrete path here.
- Never soften a real finding because the rest of the code does it too - but say when
  the pattern is the codebase's own precedent, since then the fix is a convention
  question, not a bug.
