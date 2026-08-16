---
name: watch-pr
description: Watch a pushed PR's checks with the Monitor tool, and act on the verdict. Use after opening or pushing to a PR.
---

# Watching a PR through to a verdict

A pushed PR is not finished work. It is work awaiting a verdict that `gh` can read — so read it,
and say what happened. Offer this every time a PR is pushed, unless told otherwise.

**Arm a `Monitor`. Do not poll in a loop, and do not go idle waiting.** The notification arrives
on its own; keep working on the next thing in the meantime.

## The trap this skill exists for

A monitor that emits nothing looks exactly like a monitor watching something that has not
happened yet. **Silence is not success.** One was armed on PR #97 here, produced nothing for its
whole lifetime, and exited 0 — and the PR's status was found by hand, while the monitor was still
believed to be running.

So: **run the poll body once in the foreground first, and see it print.** One `Bash` call. If it
prints nothing there, it will print nothing for the next half hour either.

```bash
# Prove it emits BEFORE arming anything.
PR=99
gh pr view "$PR" --json statusCheckRollup --jq \
  '.statusCheckRollup[] | select(.status == "COMPLETED") | "\(.name): \(.conclusion)"'
```

## Use `gh pr view`, not `gh pr checks`

`gh pr checks` sets its exit code from the checks themselves, so the usual
`s=$(gh pr checks …) || continue` swallows the real answer and loops silently. `gh pr view
--json statusCheckRollup` exits 0 whatever the checks say, which is what a poll loop needs.

Note the two fields differ: `.status` is the lifecycle (`QUEUED`, `IN_PROGRESS`, `COMPLETED`) and
`.conclusion` is the verdict (`SUCCESS`, `FAILURE`, `CANCELLED`, `SKIPPED`). A check that has not
finished has an **empty** conclusion, so filter on `.status == "COMPLETED"` and report
`.conclusion`.

## The monitor

Emits one line per check as it lands, then one line when the run is over. Copy it as-is and
change `PR`:

```bash
cd 'c:\Users\shaneb\git\OpenTagViewer'
PR=99
seen=""
while true; do
  now=$(gh pr view "$PR" --json statusCheckRollup --jq \
        '.statusCheckRollup[] | select(.status == "COMPLETED") | "\(.name): \(.conclusion)"' 2>/dev/null | sort)
  comm -13 <(printf '%s\n' "$seen") <(printf '%s\n' "$now")
  seen="$now"
  if [ "$(gh pr view "$PR" --json statusCheckRollup --jq \
          '[.statusCheckRollup[].status] | all(. == "COMPLETED")' 2>/dev/null)" = "true" ]; then
    echo "PR #$PR: all checks finished"
    break
  fi
  sleep 30
done
```

`timeout_ms`: the emulator suite here takes several minutes and the whole run rarely exceeds 20,
so 2700000 (45 min) is comfortable. `persistent: false` — it ends itself.

Why it is shaped this way:

- **`comm -13` against the previous set** reports each check once, as it finishes, rather than
  re-reporting the finished ones every 30 seconds.
- **`printf '%s\n'`, not `echo`**, so an empty `seen` is one empty line and `comm` behaves.
- **It reports failures as readily as successes.** A filter matching only `SUCCESS` stays silent
  through a red build, and silence reads as "still running".
- **`2>/dev/null` on the `gh` calls, but no `|| continue`.** A transient API failure yields an
  empty result and the loop tries again; it must not be able to exit quietly.

## When it lands

- **Green** — merge if that was asked for, otherwise say it is green and offer.
  `gh pr merge <n> --merge --delete-branch`. Then `git fetch && git rebase origin/main` on
  anything stacked on it.
- **Red** — read the failing step and fix it. `--log-failed` refuses while a run is still going,
  so get the step list from the API first:

  ```bash
  gh pr checks <n>                                    # which job, and its URL
  gh api repos/parawanderer/OpenTagViewer/actions/jobs/<job-id> \
    --jq '.steps[] | "\(.conclusion // .status)\t\(.name)"'
  gh run view --job <job-id> --log-failed | tail -30   # once the run has finished
  ```

  **Read the failure before assuming it is flaky.** On #99 a red build was not CI noise: it was
  the repo type-checking against PyPI's `FindMy` while the app built a fork, which was a real
  defect the failure exposed.
- **Pushed a fix?** Re-arm. A green run on the previous commit says nothing about this one.

## Related

- `AGENTS.md` on `gh` — why an agent without it can only guess at a red build.
- The memory notes `propose-watching-a-pushed-pr` (act on the one just pushed) and
  `sweep-open-prs-read-only` (look at all the others, and only look).
