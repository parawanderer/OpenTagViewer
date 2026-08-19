"""
Clearing the Control Flow Guard bit from a built Windows executable.

**Tested on a synthetic PE, because the real one only exists on a Windows runner.** The header
layout is fixed by the PE specification, so a handful of bytes in the right places exercises the
same arithmetic the real file does - and the alternative is a script whose only test is a CI job
on another operating system, which is how a patch that silently does nothing gets shipped.

What matters here is not only that the bit is cleared. It is that the wrong file is *refused*:
a build step that quietly succeeds on something it did not understand produces a binary that looks
patched and still dies on a user's machine, with the one symptom that leaves no traceback.
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from clear_windows_cfg import (  # noqa: E402
    GUARD_CF,
    PE32,
    PE32_PLUS,
    NotAPortableExecutable,
    clear_guard_cf,
)

PE_OFFSET = 0x80
OPTIONAL = PE_OFFSET + 4 + 20
FIELD = OPTIONAL + 0x46


def build_pe(characteristics: int, *, magic: int = PE32_PLUS) -> bytes:
    """The smallest thing that is shaped like a PE image as far as this script is concerned."""
    data = bytearray(FIELD + 2)
    data[0:2] = b"MZ"
    struct.pack_into("<I", data, 0x3C, PE_OFFSET)
    data[PE_OFFSET:PE_OFFSET + 4] = b"PE\0\0"
    struct.pack_into("<H", data, OPTIONAL, magic)
    struct.pack_into("<H", data, FIELD, characteristics)

    return bytes(data)


def characteristics_of(path: Path) -> int:
    return struct.unpack_from("<H", path.read_bytes(), FIELD)[0]


@pytest.fixture
def executable(tmp_path):
    def write(characteristics: int, **kwargs) -> Path:
        path = tmp_path / "OpenTagViewer.exe"
        path.write_bytes(build_pe(characteristics, **kwargs))
        return path

    return write


class TestClearingTheBit:
    def test_it_turns_control_flow_guard_off(self, executable):
        path = executable(GUARD_CF)

        assert clear_guard_cf(path) is True
        assert characteristics_of(path) & GUARD_CF == 0

    def test_it_leaves_every_other_flag_alone(self, executable):
        # The field carries ASLR, DEP and the rest beside it. Clearing the wrong bits would turn a
        # mitigation problem into a different mitigation problem, on a binary handed to users.
        others = 0x0040 | 0x0100 | 0x8000  # DYNAMIC_BASE, NX_COMPAT, TERMINAL_SERVER_AWARE
        path = executable(GUARD_CF | others)

        clear_guard_cf(path)

        assert characteristics_of(path) == others

    def test_a_file_that_never_had_it_reports_no_change(self, executable):
        # Distinct from success, and the caller says so: it means either the toolchain stopped
        # setting the bit, or the wrong file was patched. Those want different follow-ups.
        path = executable(0x0100)

        assert clear_guard_cf(path) is False
        assert characteristics_of(path) == 0x0100

    @pytest.mark.parametrize("magic", [PE32, PE32_PLUS])
    def test_both_header_widths_are_handled(self, executable, magic):
        # PE32 and PE32+ differ earlier in the optional header and realign well before this field.
        path = executable(GUARD_CF, magic=magic)

        assert clear_guard_cf(path) is True


class TestRefusingTheWrongFile:
    """
    Each of these would otherwise be a build step that appears to work.

    That is the failure worth guarding: the exe reaches a user unpatched, and the symptom is a
    process that vanishes without an exception, which is the hardest thing there is to report.
    """

    def test_something_that_is_not_an_executable(self, tmp_path):
        path = tmp_path / "notes.txt"
        path.write_bytes(b"this is not a program")

        with pytest.raises(NotAPortableExecutable, match="does not start with MZ"):
            clear_guard_cf(path)

    def test_a_dos_header_pointing_at_nothing(self, tmp_path):
        data = bytearray(build_pe(GUARD_CF))
        data[PE_OFFSET:PE_OFFSET + 4] = b"junk"
        path = tmp_path / "OpenTagViewer.exe"
        path.write_bytes(bytes(data))

        with pytest.raises(NotAPortableExecutable, match="no PE signature"):
            clear_guard_cf(path)

    def test_an_optional_header_of_an_unknown_shape(self, executable):
        path = executable(GUARD_CF, magic=0x1234)

        with pytest.raises(NotAPortableExecutable, match="neither PE32 nor PE32"):
            clear_guard_cf(path)
