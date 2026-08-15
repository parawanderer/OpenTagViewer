"""Make the app's Chaquopy sources importable by the tests.

app/src/main/python is what Chaquopy packages into the APK; nothing in it may
import Android or Java types, which is exactly what makes it testable on a plain
CPython interpreter.
"""

import sys
from pathlib import Path

APP_PYTHON = Path(__file__).resolve().parents[2] / "main" / "python"
RESOURCES = Path(__file__).resolve().parents[1] / "resources"

# The shared export package. Put on the path here so these tests can exercise it, and **the app
# cannot yet**: Chaquopy is not given it as a second source directory, so `import
# opentagviewer_export` fails inside the APK and main.py's two bridge functions return None there.
#
# That is a known gap rather than an oversight - `../python` also holds the tkinter wizard and a
# top-level package called `test`, which would shadow the standard library's on a phone. See
# "Wire the shared package into Chaquopy" in docs/android-import-handover.md for the two ways out.
# Whichever is taken changes the layout, and this line with it.
SHARED_PYTHON = Path(__file__).resolve().parents[4] / "python"

sys.path.insert(0, str(APP_PYTHON))
sys.path.insert(0, str(SHARED_PYTHON))


def pytest_configure(config):
    config.addinivalue_line("markers", "fixture_required: needs a redacted beacon plist")
