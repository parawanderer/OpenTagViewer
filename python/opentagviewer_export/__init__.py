"""
Writes the export bundle OpenTagViewer imports: a zip of plists plus OPENTAGVIEWER.yml.

**Pure.** No UI, no network, no Apple ID, no platform assumptions - data and a path in, a file
out. That is what lets two callers with nothing else in common share it: the desktop exporter
here, and the Android app, which runs Python through Chaquopy and is forbidden from importing
Android or Java types in it.

One implementation because the format has traps and the failure is invisible on the producing
side: a malformed bundle fails at *import*, in Java, on someone else's phone. See
`docs/findmy-export/06-output.md` section 5.5 for the format, and :mod:`.bundle` for what is
checked before anything is written.

Two steps, deliberately separate:

    bundle = build_export(accessories, via=..., source_user=..., exported_at_ms=...)
    write_zip(bundle, "export.zip", password=code)

:func:`~.bundle.build_export` produces bytes in memory and touches no filesystem;
:func:`~.zipsink.write_zip` is one sink for them. A caller that wants a different container -
the Android app zipping natively, a test asserting on a single file - takes `bundle.entries`
and never calls the second.

**Every name below is imported on first use rather than up front.** Writing a bundle needs PyYAML,
and locking one needs pyzipper; identifying an accessory needs neither, and it is the only part of
this the Android app calls today. An eager import here would make both of those dependencies of
the app in exchange for a label on a row - so `from opentagviewer_export.hardware import identify`
costs exactly what it looks like it costs.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover - for editors and type checkers, not for runtime
    # These are re-exported lazily through __getattr__ below. flake8 cannot see that, so
    # .flake8 ignores F401 for this file specifically.
    from .bundle import (
        BEACON_NAMING_RECORD_DIR,
        CUSTOM_ACCESSORIES_DIR,
        CUSTOM_ACCESSORY_TYPE,
        EXPORT_FORMAT_VERSION,
        EXPORT_FORMAT_VERSION_WITH_CUSTOM,
        KEY_ALIGNMENT_RECORDS_DIR,
        METADATA_FILENAME,
        OWNED_BEACONS_DIR,
        AccessoryExport,
        CustomAccessoryExport,
        ExportBundle,
        ExportError,
        build_export,
    )
    from .keyfiles import PRIVATE_KEY_LENGTH, KeyFileError, ParsedTag, parse_key_file
    from .passcode import PASSCODE_ALPHABET, format_passcode, generate_passcode, normalise_passcode
    from .zipsink import write_zip

# Which submodule each public name lives in. One entry per name in __all__, and
# `test_every_public_name_can_actually_be_imported` keeps the two in step.
_SOURCES = {
    "AccessoryExport": "bundle",
    "BEACON_NAMING_RECORD_DIR": "bundle",
    "CUSTOM_ACCESSORIES_DIR": "bundle",
    "CUSTOM_ACCESSORY_TYPE": "bundle",
    "CustomAccessoryExport": "bundle",
    "EXPORT_FORMAT_VERSION": "bundle",
    "EXPORT_FORMAT_VERSION_WITH_CUSTOM": "bundle",
    "ExportBundle": "bundle",
    "ExportError": "bundle",
    "KEY_ALIGNMENT_RECORDS_DIR": "bundle",
    "METADATA_FILENAME": "bundle",
    "OWNED_BEACONS_DIR": "bundle",
    "build_export": "bundle",
    "KeyFileError": "keyfiles",
    "PRIVATE_KEY_LENGTH": "keyfiles",
    "ParsedTag": "keyfiles",
    "parse_key_file": "keyfiles",
    "PASSCODE_ALPHABET": "passcode",
    "format_passcode": "passcode",
    "generate_passcode": "passcode",
    "normalise_passcode": "passcode",
    "write_zip": "zipsink",
}

__all__ = (
    "AccessoryExport",
    "BEACON_NAMING_RECORD_DIR",
    "CUSTOM_ACCESSORIES_DIR",
    "CUSTOM_ACCESSORY_TYPE",
    "CustomAccessoryExport",
    "EXPORT_FORMAT_VERSION",
    "EXPORT_FORMAT_VERSION_WITH_CUSTOM",
    "ExportBundle",
    "ExportError",
    "KEY_ALIGNMENT_RECORDS_DIR",
    "KeyFileError",
    "METADATA_FILENAME",
    "OWNED_BEACONS_DIR",
    "PASSCODE_ALPHABET",
    "PRIVATE_KEY_LENGTH",
    "ParsedTag",
    "build_export",
    "format_passcode",
    "generate_passcode",
    "normalise_passcode",
    "parse_key_file",
    "write_zip",
)


def __getattr__(name: str) -> Any:  # noqa: ANN401 - it returns whatever was asked for
    """Import a name's own module the first time somebody asks for it."""
    module = _SOURCES.get(name)
    if module is None:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")

    import importlib  # noqa: PLC0415 - deferred with everything else

    return getattr(importlib.import_module(f".{module}", __name__), name)


def __dir__() -> list[str]:
    """So tab completion and `dir()` still show what this package offers."""
    return sorted(__all__)
