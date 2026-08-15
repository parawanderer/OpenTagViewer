# Required for `tuple[int, int] | None` below. macOS ships Python 3.9 with the Command Line
# Tools, and a module-level annotated assignment IS evaluated at import time there, so
# without this the wizard dies on `unsupported operand type(s) for |` before it can start.
# Running from source on a stock Mac is the normal case for testing an export.
from __future__ import annotations

import platform

DARWIN = "Darwin"
LINUX = "Linux"
SYSTEM: str = platform.system()
MACOS_VER: tuple[int, int] | None = tuple(map(int, platform.mac_ver()[0].split('.'))) if SYSTEM == DARWIN else None
