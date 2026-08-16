# Request to FindMy.py: let a client skip the BLE scanner

> **A request from a consumer, not a specification.** Addressed to whoever works on FindMy.py.
> Independent of [the serial request](./findmy-py-serial-request.md) — accepting one does not
> commit you to the other. Delete this from this repository once the library has an answer.
>
> Written against `feat/icloud-keychain-export`.

## The ask

**Make `bleak` optional** — either by moving `findmy.scanner` behind a module-level `__getattr__`
in `findmy/__init__.py`, or by declaring `bleak` an extra (`findmy[scanner]`) and importing it
lazily.

## Why

`findmy/__init__.py` imports `.scanner` at package import, and `findmy.scanner.scanner` imports
`bleak`. So **any** use of the library — reading reports, fetching accessories from iCloud, an
account login that never goes near a radio — requires the Bluetooth stack to be installed and
importable.

On desktop that is not a small thing. From the branch's own `uv.lock`:

| Platform | What `bleak` pulls in |
| --- | --- |
| macOS | `pyobjc-core`, `pyobjc-framework-corebluetooth`, `pyobjc-framework-libdispatch` |
| Windows | seven `winrt-*` packages |
| Linux | `dbus-fast` |

`pyobjc-core` alone is tens of megabytes installed, and all of it is dead weight in a frozen
application that will never scan.

**The consumer here is a desktop exporter**, being repointed from local macOS files to iCloud. It
authenticates, reads a keychain view, fetches CloudKit records and writes a zip. It has no
Bluetooth code path at all, and its whole purpose is to be a PyInstaller bundle small enough to
hand to someone — so it pays for CoreBluetooth in download size for every user, forever, to import
a module it never calls.

The same applies on Android, where `findmy` is packaged into the APK by Chaquopy: the app locates
accessories through Apple's network, not over the air.

## Why the consumer cannot fix it

The obvious lever — PyInstaller's `excludes` — does not work here, because the exclusion has to
survive `import findmy`:

```python
excludes=['bleak']          # in OpenTagViewer.spec
...
import findmy               # ImportError: findmy/__init__.py line 44, from .scanner import ...
```

Importing a submodule directly does not help either: `import findmy.reports.account` executes the
parent package's `__init__.py` first, so `.scanner` is imported regardless of what the client
asked for. There is no import path into this library that avoids `bleak`.

## Two shapes that would work

Either is fine; the first is the smaller change and keeps every existing import working.

**Lazy attribute access** — `from findmy import OfflineFindingScanner` keeps working, and
`import findmy` stops needing `bleak`:

```python
_SCANNER_NAMES = {
    "NearbyOfflineFindingDevice", "OfflineFindingDevice",
    "OfflineFindingScanner", "SeparatedOfflineFindingDevice",
}

def __getattr__(name: str):
    if name in _SCANNER_NAMES:
        from . import scanner
        return getattr(scanner, name)
    raise AttributeError(name)
```

**An extra** — `dependencies` drops `bleak`, `[project.optional-dependencies]` gains
`scanner = ["bleak>=3.0.2,<3.1.0"]`, and `findmy.scanner` raises an import error naming
`findmy[scanner]` when it is missing. Cleaner for anyone reading the dependency list, but a
breaking change for existing users who install plain `findmy` and scan.

## What is not being asked for

- **No change to the scanner itself.** It works; this is about when its dependency is paid for.
- **No removal of `bleak` from any default install** if that is judged too disruptive — the lazy
  import alone gets a frozen application most of the win, since PyInstaller only bundles what it
  can see being imported.
