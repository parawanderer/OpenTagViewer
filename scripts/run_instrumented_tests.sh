#!/usr/bin/env bash
#
# Run the instrumented tests on an emulator, and recover from a wedged one.
#
# By default this runs on the Gradle managed device, which Gradle provisions and tears down
# itself. Two reasons that is the default rather than connectedAndroidTest against an emulator
# you started by hand:
#
#   - Espresso will not touch a window that lacks focus, and a guest window only has focus
#     while the emulator's own window has focus on the host desktop. So clicking anything in a
#     UI test fails with RootViewWithoutFocusException the moment you alt-tab away from it.
#   - The wedged-bridge problem below does not arise, because there is no connection left over
#     between runs to go stale.
#
# GRADLE_TASK=:app:connectedDebugAndroidTest still works, and everything below is for it.
#
# The Android Gradle Plugin talks to devices over a long-lived ADB bridge that the Gradle
# daemon holds across invocations. When the emulator's adb daemon dies or goes stale, that
# handle is not reconnected: the next run fails to install, or hangs outright, with an error
# that has nothing to do with the code under test. Restarting the emulator fixes it, but only
# if the Gradle daemon is also stopped - otherwise it hands out the same dead bridge again.
# That is the whole reason this script exists.
#
# Retries are only for that. Once tests have actually run and reported, a failure is a failure:
# the emulator is left alone and the run stops, rather than repeating the whole suite and a
# two-minute emulator restart to reach the same answer.
#
# It pins ANDROID_SERIAL to an emulator so a run can never install to a physical phone.
#
# Usage:
#     scripts/run_instrumented_tests.sh
#     AVD=Pixel_9_Pro_API_36 scripts/run_instrumented_tests.sh
#     GRADLE_TASK=:app:connectedDebugAndroidTest ATTEMPTS=3 scripts/run_instrumented_tests.sh
#
# Environment overrides:
#     AVD            AVD to launch if no emulator is running   (default: first one listed)
#     SERIAL         Emulator serial to pin to                 (default: emulator-5554)
#     ATTEMPTS       How many times to try before giving up    (default: 2)
#     RUN_TIMEOUT    Seconds before a run counts as hung       (default: 900)
#     BOOT_TIMEOUT   Seconds to wait for the emulator to boot  (default: 300)
#     GRADLE_TASK    Task to run                               (default: :app:connectedDebugAndroidTest)
#
set -uo pipefail

AVD="${AVD:-}"
SERIAL="${SERIAL:-emulator-5554}"
ATTEMPTS="${ATTEMPTS:-2}"
RUN_TIMEOUT="${RUN_TIMEOUT:-900}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
GRADLE_TASK="${GRADLE_TASK:-:app:testEmulatorDebugAndroidTest}"

# A managed device is provisioned, booted and torn down by Gradle itself, so none of the
# emulator handling below applies to it - and must not run, or it would kill an emulator that
# has nothing to do with the run.
case "$GRADLE_TASK" in
    *testEmulator*) MANAGED_DEVICE=1 ;;
    *)              MANAGED_DEVICE=0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

log() { printf '\n==> %s\n' "$*"; }
warn() { printf '\n!!! %s\n' "$*" >&2; }

# --------------------------------------------------------------------------------------
# Locate the SDK and the JDK.
# --------------------------------------------------------------------------------------

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then
    if [[ -n "${LOCALAPPDATA:-}" && -d "$LOCALAPPDATA/Android/Sdk" ]]; then
        SDK="$LOCALAPPDATA/Android/Sdk"
    elif [[ -d "$HOME/Android/Sdk" ]]; then
        SDK="$HOME/Android/Sdk"
    elif [[ -d "$HOME/Library/Android/sdk" ]]; then
        SDK="$HOME/Library/Android/sdk"
    fi
fi

if [[ -z "$SDK" || ! -d "$SDK" ]]; then
    warn "Could not find the Android SDK. Set ANDROID_HOME and try again."
    exit 2
fi

# .exe on Windows, bare everywhere else.
pick_tool() {
    local base="$1"
    if [[ -x "$base.exe" ]]; then printf '%s' "$base.exe"
    elif [[ -x "$base" ]]; then printf '%s' "$base"
    else printf ''
    fi
}

ADB="$(pick_tool "$SDK/platform-tools/adb")"
EMULATOR="$(pick_tool "$SDK/emulator/emulator")"

if [[ -z "$ADB" ]]; then
    warn "No adb under $SDK/platform-tools."
    exit 2
fi

# A JAVA_HOME pointing at anything below 17 fails deep inside the Android Gradle Plugin with
# a message that reads like a plugin bug, so fall back to the JDK that ships with Studio.
if [[ -z "${JAVA_HOME:-}" ]] || ! "${JAVA_HOME}/bin/java" -version 2>&1 | grep -qE '"(1[7-9]|2[0-9])'; then
    for candidate in \
        "/c/Program Files/Android/Android Studio/jbr" \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "$HOME/.jdks/jbr-17"
    do
        if [[ -x "$candidate/bin/java" ]] || [[ -x "$candidate/bin/java.exe" ]]; then
            export JAVA_HOME="$candidate"
            log "Using JAVA_HOME=$JAVA_HOME"
            break
        fi
    done
fi

if [[ -x "./gradlew.bat" ]]; then GRADLEW="./gradlew.bat"; else GRADLEW="./gradlew"; fi

# --------------------------------------------------------------------------------------
# Emulator lifecycle.
# --------------------------------------------------------------------------------------

emulator_is_booted() {
    local booted
    booted="$(timeout 15 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')"
    [[ "$booted" == "1" ]]
}

wait_for_boot() {
    local waited=0
    log "Waiting for $SERIAL to finish booting (up to ${BOOT_TIMEOUT}s)"
    while (( waited < BOOT_TIMEOUT )); do
        if emulator_is_booted; then
            log "$SERIAL is up"
            return 0
        fi
        sleep 5
        waited=$((waited + 5))
    done
    warn "$SERIAL did not boot within ${BOOT_TIMEOUT}s"
    return 1
}

start_emulator() {
    if [[ -z "$EMULATOR" ]]; then
        warn "No emulator binary under $SDK/emulator; start the AVD from Android Studio instead."
        return 1
    fi

    local avd="$AVD"
    if [[ -z "$avd" ]]; then
        avd="$("$EMULATOR" -list-avds 2>/dev/null | head -n 1 | tr -d '\r')"
    fi
    if [[ -z "$avd" ]]; then
        warn "No AVDs found. Create one in Android Studio, or set AVD=<name>."
        return 1
    fi

    log "Starting AVD '$avd'"
    # -no-snapshot-load matters: a snapshot can restore the same broken adb daemon state we
    # are trying to escape, which makes the restart pointless.
    "$EMULATOR" -avd "$avd" -no-snapshot-load -no-boot-anim >/dev/null 2>&1 &
    disown || true

    wait_for_boot
}

stop_emulator() {
    log "Stopping $SERIAL"
    timeout 30 "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1

    # emu kill is a request, not a guarantee - an emulator that is already hung ignores it.
    sleep 5
    if command -v taskkill >/dev/null 2>&1; then
        taskkill //F //IM qemu-system-x86_64.exe >/dev/null 2>&1 || true
        taskkill //F //IM qemu-system-aarch64.exe >/dev/null 2>&1 || true
    else
        pkill -f "qemu-system" >/dev/null 2>&1 || true
    fi
    sleep 5
}

reset_toolchain() {
    # Both halves are needed. Restarting only the emulator leaves the Gradle daemon holding a
    # dead ADB bridge, which is exactly the state that produces "Could not create ADB Bridge"
    # and installs that hang forever.
    log "Stopping the Gradle daemon and restarting the adb server"
    "$GRADLEW" --stop >/dev/null 2>&1 || true
    timeout 30 "$ADB" kill-server >/dev/null 2>&1 || true
    timeout 30 "$ADB" start-server >/dev/null 2>&1 || true
}

ensure_emulator() {
    if (( MANAGED_DEVICE )); then
        return 0
    fi
    if timeout 20 "$ADB" devices 2>/dev/null | grep -q "^${SERIAL}[[:space:]]*device$" && emulator_is_booted; then
        return 0
    fi
    log "$SERIAL is not ready"
    start_emulator
}

# --------------------------------------------------------------------------------------
# Run.
# --------------------------------------------------------------------------------------

# Pinned so a run can never install onto a physical device that happens to be plugged in.
export ANDROID_SERIAL="$SERIAL"

RESULTS_DIR="app/build/outputs/androidTest-results"

# Did this attempt actually get as far as running tests?
#
# This is what separates "the code is broken" from "the toolchain is broken". A wedged bridge
# fails during install, before any test executes, and leaves no fresh results behind; a real
# failure leaves a report. Retrying the first is the point of this script. Retrying the second
# runs the whole suite a second time to reach the same answer, and restarts a perfectly healthy
# emulator on the way - which is slower than the test run itself.
tests_actually_ran() {
    local since="$1"
    [[ -n "$(find "$RESULTS_DIR" -name '*.xml' -newer "$since" 2>/dev/null)" ]]
}

run_tests() {
    log "Running $GRADLE_TASK on $SERIAL (timeout ${RUN_TIMEOUT}s)"
    timeout --foreground "$RUN_TIMEOUT" "$GRADLEW" "$GRADLE_TASK"
    return $?
}

overall=1
for (( attempt = 1; attempt <= ATTEMPTS; attempt++ )); do
    log "Attempt $attempt of $ATTEMPTS"

    if ! ensure_emulator; then
        warn "Could not get an emulator ready"
        if (( attempt < ATTEMPTS )); then
            stop_emulator
            reset_toolchain
            continue
        fi
        break
    fi

    # A file to compare result timestamps against, so "fresh" means "from this attempt".
    started_at="$(mktemp)"

    run_tests
    status=$?

    if (( status == 0 )); then
        log "Instrumented tests passed"
        rm -f "$started_at"
        overall=0
        break
    fi

    if (( status == 124 )); then
        warn "Run exceeded ${RUN_TIMEOUT}s and was killed - treating the emulator as wedged"
    elif tests_actually_ran "$started_at"; then
        # The device was healthy enough to install, launch and report. Nothing about the
        # emulator is going to change the answer, so do not spend another full run and an
        # emulator restart arriving at it again.
        warn "Tests ran and failed - this is a test failure, not a wedged emulator"
        rm -f "$started_at"
        break
    else
        warn "Run failed with exit code $status before any test reported"
    fi

    rm -f "$started_at"

    if (( attempt < ATTEMPTS )); then
        if (( MANAGED_DEVICE )); then
            warn "Retrying (Gradle owns this device, so there is nothing here to restart)"
        else
            warn "Restarting the emulator and trying again"
            stop_emulator
            reset_toolchain
        fi
    fi
done

if (( overall != 0 )); then
    warn "Instrumented tests did not pass"
    warn "Report: app/build/reports/androidTests/connected/debug/index.html"
fi

exit "$overall"
