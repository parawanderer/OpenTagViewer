# Required for `tuple[int, int] | None` below. macOS ships Python 3.9 with the Command Line
# Tools, and a module-level annotated assignment IS evaluated at import time there, so
# without this the wizard dies on `unsupported operand type(s) for |` before it can start.
# Running from source on a stock Mac is the normal case for testing an export.
from __future__ import annotations

import platform

DARWIN = "Darwin"
LINUX = "Linux"
SYSTEM: str = platform.system()
# A tuple of however many parts the version has - `14.7.4` is three, `26.0` is two - so this is
# not annotated as a pair. It is read by index, and only ever index 0.
MACOS_VER: tuple[int, ...] | None = (
    tuple(map(int, platform.mac_ver()[0].split('.'))) if SYSTEM == DARWIN else None
)
