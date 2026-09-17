#!/usr/bin/env bash
# Runs the probe against the stubs that ship, and against makeWorkQueue as it was before #232 was
# fixed, and checks both outcomes.
#
#   run_probe.sh <dir with fixed/ and old/ builds> <apple libs dir>
#
# The shipping stubs must load and initialise (exit 0). The old ones must not - by crashing inside
# dlopen, or where the leftover stack happens to be zero, by a generated stub having been called.
# That second half is what proves this job can fail for the bug it exists for; a check that passes
# with the bug put back is not checking anything.
set -uo pipefail
builds="$(realpath "$1")"; apple="$(realpath "$2")"
failed=0; summary=""
for variant in fixed old; do
  echo "::group::$variant stubs"
  provisioning="$(mktemp -d)/provisioning"
  timeout 60 "$builds/$variant/adi_probe" "$builds/$variant" "$apple" "$provisioning" 2>&1 \
    | grep -v 'unused DT entry'   # bionic 9 on Apple's libc++_shared.so; harmless
  rc=${PIPESTATUS[0]}
  echo "::endgroup::"
  outcome="exit $rc"; ((rc > 128)) && outcome="killed by signal $((rc - 128))"
  verdict=ok
  if [[ $variant == fixed && $rc != 0 ]]; then
    verdict="FAIL: the shipping stubs do not load Apple's library"; failed=1
  fi
  if [[ $variant == old && $rc == 0 ]]; then
    verdict="FAIL: the pre-#232 stub passed, so this job cannot catch that bug"; failed=1
  fi
  line="$variant stubs: $outcome - $verdict"
  echo "$line"; summary+="- $line"$'\n'
done
[[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && printf '%s' "$summary" >> "$GITHUB_STEP_SUMMARY"
exit $failed
