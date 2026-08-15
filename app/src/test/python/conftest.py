"""Make the app's Chaquopy sources importable by the tests.

app/src/main/python is what Chaquopy packages into the APK; nothing in it may
import Android or Java types, which is exactly what makes it testable on a plain
CPython interpreter.
"""

import sys
from pathlib import Path

APP_PYTHON = Path(__file__).resolve().parents[2] / "main" / "python"
RESOURCES = Path(__file__).resolve().parents[1] / "resources"

# The shared export package, which Chaquopy adds as a second source directory - see the
# `sourceSets` block in app/build.gradle.kts. Added here for the same reason: it is packaged into
# the APK alongside app/src/main/python, so the tests have to see it the way the app does.
SHARED_PYTHON = Path(__file__).resolve().parents[4] / "python"

sys.path.insert(0, str(APP_PYTHON))
sys.path.insert(0, str(SHARED_PYTHON))


def pytest_configure(config):
    config.addinivalue_line("markers", "fixture_required: needs a redacted beacon plist")
