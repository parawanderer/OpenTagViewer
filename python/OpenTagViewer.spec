# -*- mode: python ; coding: utf-8 -*-
#
# Build by hand with:  uv run pyinstaller OpenTagViewer.spec
#
# > **Running `pyinstaller ... ./exporter/wizard.py` overwrites this file.** Giving PyInstaller a
# > script rather than a spec makes it generate a spec next to it, under the name from `--name` -
# > which is this one. So a hand build that passes flags silently replaces everything below with
# > whatever those flags implied. If this file suddenly looks machine-written, that is what
# > happened; `git checkout` it.
#
# The release workflow passes flags rather than using this file, because it needs three different
# invocations for three platforms. The two must agree, and the flags there carry the same comment.

from PyInstaller.utils.hooks import collect_all, collect_data_files

# unicorn loads its native library through ctypes at runtime, so PyInstaller's analysis never sees
# it. Without this the build succeeds and the binary dies at startup with "Failed to load the
# Unicorn dynamic library" - which is why the release workflow runs the result once before
# publishing it. unicorn is here because local Anisette emulates Apple's ADI library, which is what
# lets signing in work without a third-party Anisette server.
unicorn_datas, unicorn_binaries, unicorn_hiddenimports = collect_all('unicorn')

# FindMy.py keeps Apple's pinned root certificates as `.crt` files beside its code, and anisette
# keeps `apple-root.pem` beside its own. PyInstaller bundles code, not the files next to it - so
# without these the binary starts, signs in, and dies opening the keychain session with
# "[Errno 2] No such file or directory", several minutes and one Apple ID into the flow.
findmy_datas = collect_data_files('findmy')
anisette_datas = collect_data_files('anisette')


a = Analysis(
    ['exporter/wizard.py'],
    # This directory, so `exporter` and `opentagviewer_export` both resolve. PyInstaller otherwise
    # puts only the script's own directory on the path, and every import in it fails.
    pathex=['.'],
    binaries=unicorn_binaries,
    datas=unicorn_datas + findmy_datas + anisette_datas,
    hiddenimports=unicorn_hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    # Nothing is excluded, deliberately. FindMy.py brings a Bluetooth stack this never uses, and
    # dropping it saves 1.6 MB of about 68 - which does not pay for a binary that fails at runtime
    # if the library ever reaches for the scanner.
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='OpenTagViewer',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    codesign_identity=None,
    entitlements_file=None,
    icon=['OpenTagViewer.icns'],
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='OpenTagViewer',
)
app = BUNDLE(
    coll,
    name='OpenTagViewer.app',
    icon='OpenTagViewer.icns',
    bundle_identifier='dev.wander.opentagviewer',
)
