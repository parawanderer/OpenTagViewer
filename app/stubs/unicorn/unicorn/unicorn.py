"""The emulator surface anisette expects, stubbed out.

Every method raises. If any of these are ever reached it means something tried to
use *local* Anisette on Android, which is not supported - the app should have
stopped that at the boundary (see AnisetteProviderGuard on the Java side).
"""

_UNSUPPORTED = (
    "unicorn stub: local Anisette is not supported on Android. "
    "OpenTagViewer uses RemoteAnisetteProvider only. If you are seeing this, an "
    "anisette provider was constructed from local (aniLocal) state - check "
    "AnisetteProviderGuard."
)


class Uc:
    def __init__(self, arch, mode):
        raise NotImplementedError(_UNSUPPORTED)

    def mem_map(self, address, size, perms=7):
        raise NotImplementedError(_UNSUPPORTED)

    def mem_write(self, address, data):
        raise NotImplementedError(_UNSUPPORTED)

    def mem_read(self, address, length):
        raise NotImplementedError(_UNSUPPORTED)

    def reg_write(self, reg_id, value):
        raise NotImplementedError(_UNSUPPORTED)

    def reg_read(self, reg_id):
        raise NotImplementedError(_UNSUPPORTED)

    def hook_add(self, hook_type, callback, user_data=None, begin=1, end=0, arg1=0):
        raise NotImplementedError(_UNSUPPORTED)

    def emu_start(self, begin, until, timeout=0, count=0):
        raise NotImplementedError(_UNSUPPORTED)

    def emu_stop(self):
        raise NotImplementedError(_UNSUPPORTED)
