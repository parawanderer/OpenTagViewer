#!/usr/bin/env python
"""
Watch a Gradle instrumented-test run and say what happened.

Written because hand-rolled greps kept reporting nothing and being believed. Three separate
monitors in one afternoon matched no lines each - one required ``(0 skipped)`` against a run with
five skipped, one required a space before ``[testEmulator]`` that is not there, one truncated
before the verdict with ``head``. Every one of them exited quietly, and quiet reads as green.

So the patterns live here, once, with ``--once`` to prove they match before anything relies on
them. Nothing about a run should be matched by a regex typed fresh at the call site.

Usage::

    python watch_tests.py <logfile> --once      # print what the log says now, and exit
    python watch_tests.py <logfile>             # poll, one line per new event, exit when done

Every line printed is an event worth a notification. Silence means nothing new, and only after
``--once`` has shown the patterns matching something.
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import sys
import time
import xml.etree.ElementTree as ET

# **Anchored on the word FAILED, not on the decorations around it.** The name is followed
# immediately by `[deviceName]` with no space, and the line carries ANSI colour codes; both have
# already broken a hand-written pattern.
FAILED_LINE = re.compile(r"^(dev\.wander\S*) > (\S+?)\[")

# `(N skipped)` is not always `(0 skipped)`. Capture the numbers rather than matching a shape.
PROGRESS = re.compile(r"Tests (\d+)/(\d+) completed\. \((\d+) skipped\) \((\d+) failed\)")

# **MULTILINE, or `^` only matches the start of the whole file** and this reports "not yet"
# forever on a finished run. The same omission has already shipped once in redact.py.
TERMINAL = re.compile(r"^BUILD (SUCCESSFUL|FAILED)|^FAILURE: ", re.MULTILINE)

# Where AGP leaves the authoritative answer. The console can be truncated, retried or interleaved;
# these cannot.
RESULT_XML = "app/build/outputs/androidTest-results/**/*.xml"

LOCK_FILE = os.path.expanduser("~/.android/avd/gradle-managed/active_gradle_devices")


def failures_in(log: str) -> list[str]:
    """Every test the console has reported as failed, by name, without duplicates."""
    seen = []
    for line in log.splitlines():
        if "FAILED" not in line:
            continue
        match = FAILED_LINE.match(line.strip())
        if match:
            name = f"{match.group(1).split('.')[-1]}.{match.group(2)}"
            if name not in seen:
                seen.append(name)
    return seen


def progress_of(log: str):
    """The most recent (done, total, skipped, failed), or None before any test has run."""
    found = PROGRESS.findall(log)
    return tuple(int(n) for n in found[-1]) if found else None


def leaked_device_locks() -> int:
    """
    How many managed-device slots AGP believes are in use.

    Above zero with nothing running means the next run waits forever for a slot and boots no
    emulator at all - see AGENTS.md. It is the single most misdiagnosable hang here, because it
    produces no output whatsoever.
    """
    try:
        with open(LOCK_FILE, encoding="utf-8") as handle:
            match = re.search(r"MDLockCount\s+(\d+)", handle.read())
            return int(match.group(1)) if match else 0
    except OSError:
        return 0


def verdict_from_xml() -> str | None:
    """
    The result as the XML reports it, which is the one to trust.

    **Scoped to the newest run's own directory.** AGP keeps ``connected/`` and
    ``managedDevice/`` side by side and neither is cleared, so summing everything on disk mixes
    this run with whatever ran before it - a 22-test run reported as 74 the first time this was
    written, and a stale green would have masked a fresh red exactly as readily.

    The age is always stated. A verdict that is quietly minutes old is the same failure in a
    different coat.

    Returns None when no results exist - itself an answer, and a different one from "zero
    failures": a run that dies before any test reports writes nothing at all, and reading that as
    success is how a red build gets called green.
    """
    files = glob.glob(RESULT_XML, recursive=True)
    if not files:
        return None

    newest = max(files, key=os.path.getmtime)
    run_dir = os.path.dirname(newest)
    files = [f for f in files if os.path.dirname(f) == run_dir]

    tests = failures = skipped = 0
    named = []
    for path in files:
        root = ET.parse(path).getroot()
        tests += int(root.get("tests", 0))
        failures += int(root.get("failures", 0)) + int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))
        for case in root.iter("testcase"):
            if list(case.iter("failure")) or list(case.iter("error")):
                named.append(f"{case.get('classname', '').split('.')[-1]}.{case.get('name')}")

    age = (time.time() - os.path.getmtime(newest)) / 60
    where = os.path.basename(run_dir) or run_dir
    summary = f"{tests} tests, {failures} failed, {skipped} skipped ({where}, {age:.0f} min old)"
    return summary if not named else summary + " -> " + ", ".join(named)


def describe_now(log: str) -> list[str]:
    """Everything the log currently says, for --once."""
    lines = []
    done = progress_of(log)
    lines.append(f"progress: {done[0]}/{done[1]}, {done[3]} failed, {done[2]} skipped"
                 if done else "progress: no test has reported yet")

    found = failures_in(log)
    lines.extend(f"FAILED  {name}" for name in found)
    if not found:
        lines.append("failures: none reported on the console")

    lines.append("terminal: " + ("yes" if TERMINAL.search(log) else "not yet"))
    lines.append("xml verdict: " + (verdict_from_xml() or "no results written yet"))

    locks = leaked_device_locks()
    if locks:
        lines.append(f"NOTE  MDLockCount is {locks} - if nothing is running, that is the hang")
    return lines


def diagnose_stall(log: str, age: float) -> list[str]:
    """
    Say which of the two silent hangs this is, because the fixes are unrelated.

    Whether any test has reported is what separates them. Nothing at all means the run never got
    a device - overwhelmingly a leaked managed-device slot. Tests that ran and then stopped means
    the device died under them, which no amount of lock-clearing helps.
    """
    done = progress_of(log)
    where = f"at {done[0]}/{done[1]}" if done else "before any test ran"
    lines = [f"STALLED  no output for {age / 60:.0f} min, {where}"]

    locks = leaked_device_locks()
    if not done and locks:
        lines.append(f"STALLED  MDLockCount is {locks} with no test output - almost certainly "
                     f"leaked managed-device slots. ./gradlew --stop && rm {LOCK_FILE}")
    elif done:
        lines.append("STALLED  tests had been running, so suspect the device rather than the "
                     "lock: adb logcat -b crash")
    return lines


def read(path: str) -> str:
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def final_report(log: str) -> list[str]:
    """What to say once the run is over. The XML has the last word, not the console."""
    lines = []
    done = progress_of(log)
    if done:
        lines.append(f"FINISHED  {done[0]}/{done[1]}, {done[3]} failed, {done[2]} skipped")
    lines.append("VERDICT  " + (verdict_from_xml()
                                or "NO RESULTS WRITTEN - the run died before any test reported"))
    return lines


def watch(args) -> int:
    """Poll until the run reaches a terminal state, printing each new event once."""
    reported: set[str] = set()
    stalled_at = None
    started = time.time()

    while True:
        log = read(args.logfile)

        for name in failures_in(log):
            if name not in reported:
                reported.add(name)
                print(f"FAILED  {name}", flush=True)

        if TERMINAL.search(log):
            for line in final_report(log):
                print(line, flush=True)
            return 0

        # **A run that stops producing output is a result too.** Left unsaid it is
        # indistinguishable from a slow test, which is how ten minutes goes by twice.
        age = time.time() - os.path.getmtime(args.logfile) if os.path.exists(args.logfile) else 0
        if age > args.stall_minutes * 60 and stalled_at != int(age // 60):
            stalled_at = int(age // 60)
            for line in diagnose_stall(log, age):
                print(line, flush=True)

        if time.time() - started > 3 * 60 * 60:
            print("GIVING UP  watched for three hours", flush=True)
            return 1

        time.sleep(args.interval)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logfile", help="the file the gradle run is redirected to")
    parser.add_argument("--once", action="store_true",
                        help="print the current state and exit, to prove the patterns match")
    parser.add_argument("--interval", type=int, default=40, help="seconds between polls")
    parser.add_argument("--stall-minutes", type=float, default=8.0,
                        help="say so if the log stops growing for this long")
    args = parser.parse_args()

    if args.once:
        for line in describe_now(read(args.logfile)):
            print(line)
        return 0

    return watch(args)


if __name__ == "__main__":
    sys.exit(main())
