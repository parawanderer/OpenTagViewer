"""
Writes a bundle to a zip file, encrypted or not.

One sink among possible ones. A bundle is bytes in memory, and the Android app will hand the same
bytes to its own zip writer - so this file is the desktop's answer to "where does it go", not part
of the format.

**Encryption is AES-256 in the WinZip scheme**, which needs `pyzipper`: the standard library's
`zipfile` can only *read* the old ZipCrypto and cannot write encryption of any kind. That import
is deferred to the call that needs it, so writing a plain bundle works in an environment that
never installed it.
"""

from __future__ import annotations

import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .bundle import ExportBundle

_COMPRESSION = zipfile.ZIP_DEFLATED


def write_zip(
    bundle: ExportBundle,
    path: str | Path,
    *,
    password: str | None = None,
) -> Path:
    """
    Write a bundle to `path`, and return where it went.

    :param bundle: What :func:`~.bundle.build_export` produced.
    :param path: Where to write. Overwritten if it exists.
    :param password: The code the bundle is locked with, or None for a plain zip. See
        :mod:`.passcode` - pass exactly what `normalise_passcode` returns, since a zip password is
        compared as bytes.

    .. warning::
        **An encrypted bundle cannot be imported by any OpenTagViewer release published so far.**
        The Android importer reads zips with `java.util.zip.ZipInputStream`, which cannot decrypt
        anything - not AES, not ZipCrypto. Support for one is a separate change on that side.

    .. note::
        Entry data is encrypted; the **listing is not**. Anyone holding the file can see it
        contains `OwnedBeacons/<uuid>.plist` and how many tags there are, without the code. No key
        material is in a filename, but the bundle is not opaque.
    """
    path = Path(path)

    # Both the order and the timestamps are fixed, so the same bundle written twice is the same
    # file twice. That is what lets a test assert on bytes; encryption breaks it anyway, since
    # every AES entry carries a fresh random salt.
    entries = sorted(bundle.entries.items())
    date_time = _zip_date_time(bundle.exported_at_ms)

    if password is None:
        with zipfile.ZipFile(path, "w", compression=_COMPRESSION) as archive:
            _write_entries(archive, entries, date_time)
        return path

    if not password:
        raise ValueError("An empty password would produce a bundle nobody could open. Pass None for a plain zip.")

    try:
        import pyzipper  # noqa: PLC0415 - deferred so a plain write needs no AES library
    except ImportError as e:  # pragma: no cover - depends on the environment, not the code
        raise RuntimeError(
            "Writing an encrypted bundle needs `pyzipper`: the standard library's zipfile cannot"
            " write encryption of any kind. Install it, or write the bundle without a password.",
        ) from e

    with pyzipper.AESZipFile(
        path,
        "w",
        compression=_COMPRESSION,
        encryption=pyzipper.WZ_AES,
    ) as archive:
        archive.setpassword(password.encode("utf-8"))
        _write_entries(archive, entries, date_time)

    return path


def _write_entries(
    # Not `zipfile.ZipFile`: pyzipper vendors its own copy of the module rather than subclassing
    # the standard library's, so an AESZipFile is the same shape and not the same type.
    archive: Any,
    entries: list[tuple[str, bytes]],
    date_time: tuple[int, int, int, int, int, int],
) -> None:
    """Write every entry, dated from the bundle rather than from the clock."""
    # An encrypting archive carries its own ZipInfo subclass and rejects the standard-library one -
    # it holds the per-entry encryption settings, so a plain ZipInfo would have nowhere to record
    # that an entry is encrypted at all.
    info_class = getattr(archive, "zipinfo_cls", zipfile.ZipInfo)

    for name, content in entries:
        info = info_class(filename=name, date_time=date_time)
        info.compress_type = _COMPRESSION
        # 0o600: the bundle is key material, and a zip that carries a mode carries this one.
        info.external_attr = 0o600 << 16
        archive.writestr(info, content)


def _zip_date_time(exported_at_ms: int) -> tuple[int, int, int, int, int, int]:
    """
    The bundle's own timestamp, as a zip entry date.

    Zip stores local time with no zone and cannot represent anything before 1980, so this is UTC
    and clamped - an exporter's clock set to 1970 should produce an odd date rather than an
    exception halfway through writing.
    """
    moment = datetime.fromtimestamp(exported_at_ms / 1000, tz=timezone.utc)
    if moment.year < 1980:
        return (1980, 1, 1, 0, 0, 0)

    return (moment.year, moment.month, moment.day, moment.hour, moment.minute, moment.second)
