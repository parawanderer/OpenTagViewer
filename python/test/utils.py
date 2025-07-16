import pytest
import os
import platform

DIRNAME = os.path.dirname(os.path.abspath(__file__))

DARWIN = "Darwin"
SYSTEM: str = platform.system()

MACOS_VER: tuple[int, int] | None = tuple(map(int, platform.mac_ver()[0].split('.'))) if SYSTEM == DARWIN else None

skip_unless_macos_le14 = pytest.mark.skipif(
    SYSTEM != DARWIN or MACOS_VER[0] > 14,
    reason="Requires macOS version ≤ 14"
)
