import pytest
import os

from exporter.utils import (
    DARWIN,
    MACOS_VER,
    SYSTEM,
    LINUX
)

DIRNAME = os.path.dirname(os.path.abspath(__file__))


skip_unless_macos_le14 = pytest.mark.skipif(
    SYSTEM != DARWIN or MACOS_VER[0] > 14,
    reason="Requires macOS version ≤ 14"
)

skip_unless_unix = pytest.mark.skipif(
    SYSTEM != DARWIN and SYSTEM != LINUX,
    reason="Requires Unix-like OS"
)

skip_unless_posix_permissions = pytest.mark.skipif(
    os.name == "nt",
    reason=(
        "Windows has no POSIX mode bits to assert on. CPython synthesises st_mode as 0o666 for "
        "every regular file and os.chmod cannot clear the group and other bits, so a test of "
        "who can read a file proves nothing there - two of these used to pass for the wrong "
        "reason, because the guard they check refused every file on the system. Real "
        "permissions on Windows are ACLs, which stat cannot see."
    )
)
