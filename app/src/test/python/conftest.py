"""Make the app's Chaquopy sources importable by the tests.

app/src/main/python is what Chaquopy packages into the APK; nothing in it may
import Android or Java types, which is exactly what makes it testable on a plain
CPython interpreter.
"""

import sys
from pathlib import Path

APP_PYTHON = Path(__file__).resolve().parents[2] / "main" / "python"
RESOURCES = Path(__file__).resolve().parents[1] / "resources"

# The shared export package, and the four modules of `exporter/` that are stdlib plus FindMy.py.
# Chaquopy is given `../python` as a second source directory, so the same imports work inside the
# APK - `PythonPackagingTest` is what says so, and it names the wizard, the CLI and the prompts as
# things that must *not* be in there. They import tkinter, questionary and prompt_toolkit, none of
# which exist on a phone.
#
# So this line is not a test-only convenience: it mirrors what the build packages, and the two
# have to move together.
SHARED_PYTHON = Path(__file__).resolve().parents[4] / "python"

sys.path.insert(0, str(APP_PYTHON))
sys.path.insert(0, str(SHARED_PYTHON))


def pytest_configure(config):
    config.addinivalue_line("markers", "fixture_required: needs a redacted beacon plist")
