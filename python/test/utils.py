import pytest
import os

from ..utils import DARWIN, MACOS_VER, SYSTEM

DIRNAME = os.path.dirname(os.path.abspath(__file__))


skip_unless_macos_le14 = pytest.mark.skipif(
    SYSTEM != DARWIN or MACOS_VER[0] > 14,
    reason="Requires macOS version ≤ 14"
)
