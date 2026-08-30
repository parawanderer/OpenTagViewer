---
name: watch-gradle-tests
description: Watch a Gradle instrumented-test run to a verdict without hand-writing greps. Use whenever running :app:testEmulatorDebugAndroidTest or :app:connectedDebugAndroidTest in the background.
---

# Watching a test run to a verdict

The emulator suite takes upwards of ten minutes, so it gets backgrounded, so something has to
report on it. **Do not write that something fresh each time.** Use the script.

```bash
# 1. Start the run, redirected to a file.
./gradlew :app:testEmulatorDebugAndroidTest --console=plain > tmp/run.log 2>&1

# 2. Prove the watcher sees the log before trusting it. One command, always.
python .claude/skills/watch-gradle-tests/watch_tests.py tmp/run.log --once

# 3. Arm a Monitor on the same script with no --once.
python .claude/skills/watch-gradle-tests/watch_tests.py tmp/run.log
```

Each line it prints is one event: `FAILED <Class>.<method>` as each failure appears,
`STALLED …` if the log stops growing, and `FINISHED` + `VERDICT` at the end.

### Never wrap `--once` in your own sleep loop

That is the shape step 3 exists to replace, and it looks close enough to right to pass review:

```bash
# WRONG - and this exact loop cost 1h22m
for i in $(seq 1 110); do
  grep -qE "BUILD SUCCESSFUL|BUILD FAILED" tmp/run.log && { ...--once; break; }
  sleep 20
done
```

It waits for a **terminal line** and nothing else, so it is blind to the run stopping without
one — which is the failure worth catching. A suite hung 25 minutes on a single test produced no
new output and no verdict, so the loop sat silent, then hit its own limit and exited **0 with no
output at all**: indistinguishable from success. Meanwhile `watch` mode would have said
`STALLED  no output for 8 min, at 424/687` seventeen minutes earlier.

Two rules follow, and they are the same rule twice:

- **Watch progress, not just completion.** "Still running" and "wedged" look identical unless
  something is measuring the gap between outputs.
- **A watcher that can exit silently is not a watcher.** If yours can end without printing,
  make the last thing it does print where the run got to.

## Why this exists

Three hand-written monitors in one afternoon each matched **nothing**, and each looked like a
green run:

| What was written | Why it matched nothing |
| --- | --- |
| `grep "(0 skipped)"` | the run had 5 skipped |
| `grep "\S+ \[testEmulator\]"` | there is no space before `[testEmulator]` |
| `... \| head -25` | truncated before the verdict, and `head`'s exit code hid it |

All three exited 0. **Silence from a monitor is indistinguishable from silence from a healthy
run**, so a red suite was reported as green until the log was read by hand. That is the whole
argument for a script: the patterns get fixed once, and `--once` proves they still match before
anything depends on them.

## What it knows that a grep does not

- **The XML has the last word.** The console can truncate, interleave, or count a retry
  (`605/600` is a real line from this repo). `verdict_from_xml` reads
  `app/build/outputs/androidTest-results/`, and **scopes to the newest run's own directory** —
  AGP keeps `connected/` and `managedDevice/` side by side and clears neither, so summing
  everything reported a 22-test run as 74. It always prints the results' age, because a verdict
  that is quietly ten minutes old is the same bug wearing a coat.
- **No results at all is not zero failures.** A run that dies before any test reports writes no
  XML, and reading that as success is exactly how a red build gets called green.
- **A stall is a result.** No output for eight minutes gets reported, with a diagnosis, rather
  than looking like a slow test for another twenty.

## The two silent hangs, which need opposite fixes

The script separates them by whether *any* test has reported. Do not guess between them — the
distinction is in the output.

| Symptom | Cause | Fix |
| --- | --- | --- |
| No test ever reports, no managed AVD in `adb devices` | leaked managed-device slots — `MDLockCount` above zero with nothing running | `./gradlew --stop && rm ~/.android/avd/gradle-managed/active_gradle_devices` |
| Tests ran, then stopped mid-suite | the device died under them | `adb logcat -b crash` — the emulator's Bluetooth stack aborting has done this here |
| `connectedDebugAndroidTest` fails to install or hangs at once | stale ADB bridge inside the Gradle daemon | restart the emulator **and** `./gradlew --stop` — either alone leaves it broken |

Killing a run is what leaks a slot, and every `Ctrl-C` adds one. Prefer letting a run finish; if
you must kill one, clear the count in the same breath rather than meeting it next time.

## Related

- `AGENTS.md`, "Building and testing" — the managed device, and why it beats a hand-started
  emulator.
- `.claude/skills/watch-pr/` — the same discipline for CI: prove the poll body emits before
  arming a Monitor on it.
