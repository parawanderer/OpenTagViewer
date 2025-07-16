import platform

DARWIN = "Darwin"
SYSTEM: str = platform.system()
MACOS_VER: tuple[int, int] | None = tuple(map(int, platform.mac_ver()[0].split('.'))) if SYSTEM == DARWIN else None
