from dataclasses import dataclass
import os
import re
import shutil
import time
import uuid
from unittest.mock import Mock

import plistlib
import pytest
from Crypto.Cipher import AES
from Crypto.Random import get_random_bytes

from test.unittestutils import DIRNAME, skip_unless_macos_le14, skip_unless_unix

from main.airtag_decryptor import (
    AbstractSubprocessRunner,
    KEY_ALIGNMENT_RECORDS,
    KeyStoreKeyNotFoundException,
    WHITELISTED_DIRS,
    decrypt_folder,
    decrypt_plist,
    dump_plist,
    extract_gena_key,
    extract_key,
    get_key,
    get_key_fallback,
    get_key_from_full_output,
    make_output_path
)

TEST_FULL_OUTPUT = """
keychain: "/Users/<user>/Library/Keychains/login.keychain-db"
version: 512
class: "genp"
attributes:
    0x00000007 <blob>="BeaconStore"
    0x00000008 <blob>=<NULL>
    "acct"<blob>="BeaconStoreKey"
    "cdat"<timedate>=0x32303235303630383136303533305A00  "20250608160530Z\000"
    "crtr"<uint32>=<NULL>
    "cusi"<sint32>=<NULL>
    "desc"<blob>=<NULL>
    "gena"<blob>=0x4D792D5365637265742D4B65792D4142434445464748494A4B4C4D4E4F504849  "<IGNORED>"
    "icmt"<blob>=<NULL>
    "invi"<sint32>=<NULL>
    "mdat"<timedate>=0x32303235303630383136303533305A00  "20250608160530Z\000"
    "nega"<sint32>=<NULL>
    "prot"<blob>=<NULL>
    "scrp"<sint32>=<NULL>
    "svce"<blob>="BeaconStore"
    "type"<uint32>=<NULL>
"""


@dataclass
class GetKeyTestCase:
    name: str
    output: str


def _make_output(gena_value: str) -> str:
    return f"""
keychain: "/Users/<user>/Library/Keychains/login.keychain-db"
version: 512
class: "genp"
attributes:
    0x00000007 <blob>="BeaconStore"
    0x00000008 <blob>=<NULL>
    "acct"<blob>="BeaconStoreKey"
    "cdat"<timedate>=0x32303235303630383136303533305A00  "20250608160530Z\000"
    "crtr"<uint32>=<NULL>
    "cusi"<sint32>=<NULL>
    "desc"<blob>=<NULL>
    "gena"<blob>={gena_value}  "<IGNORED>"
    "icmt"<blob>=<NULL>
    "invi"<sint32>=<NULL>
    "mdat"<timedate>=0x32303235303630383136303533305A00  "20250608160530Z\000"
    "nega"<sint32>=<NULL>
    "prot"<blob>=<NULL>
    "scrp"<sint32>=<NULL>
    "svce"<blob>="BeaconStore"
    "type"<uint32>=<NULL>
"""


def _create_plist(plistData: dict, key: bytes | None = None) -> tuple[bytes, bytes]:
    if key is None:
        key = get_random_bytes(16)

    nonce: bytes = get_random_bytes(12)
    data: bytes = plistlib.dumps(plistData)

    cipher = AES.new(key, AES.MODE_GCM, nonce=nonce)
    ciphertext, tag = cipher.encrypt_and_digest(data)

    plist_lvl2_data: list[bytes] = [nonce, tag, ciphertext]
    plist_lvl2: bytes = plistlib.dumps(plist_lvl2_data)

    return plist_lvl2, key


def _create_plist_path() -> str:
    timestamp = int(time.time())
    tmp_path = os.path.join(DIRNAME, f"resources/tmp_plist_{timestamp}.record")
    return tmp_path


def _create_many_nested_paths(count: int) -> tuple[str, list[str]]:
    id_part_root: str = str(uuid.uuid4()).upper()
    basepath: str = os.path.join(DIRNAME, f"resources/{id_part_root}")

    sub_paths: list[str] = []
    for _ in range(count):
        id_part: str = str(uuid.uuid4()).upper()
        item_path = os.path.join(basepath, f"Foo/{id_part}.record")
        sub_paths.append(item_path)

    return (basepath, sub_paths)


def _create_nested_plist_path() -> tuple[str, str]:
    result = _create_many_nested_paths(count=1)
    return (result[0], result[1][0])


def _create_tmp_out_folder() -> str:
    id_part: str = str(uuid.uuid4()).upper()
    return os.path.join(DIRNAME, f"resources/{id_part}")


@pytest.mark.skip(reason="In CI you'll never get the password prompt, so it just returns empty")
@skip_unless_macos_le14
def test_get_key():
    """
    Only works on MacOS <= 14.x, is supposed to prompt password
    """

    key = get_key("BeaconStore")
    assert key is not None


def test_mocked_get_key_success():
    runner = Mock(spec=AbstractSubprocessRunner)
    runner.run.return_value = "4D792D5365637265742D4B65792D4142434445464748494A4B4C4D4E4F504849"

    result = get_key("BeaconStore", runner)

    assert result is not None
    assert result == b'My-Secret-Key-ABCDEFGHIJKLMNOPHI'


GET_KEY_INVALID_CASES = [
    GetKeyTestCase(name="empty string", output=""),
    GetKeyTestCase(name="whitespace", output="     "),
    GetKeyTestCase(name="whitespace + linebreaks", output="   \n "),
    GetKeyTestCase(name="not hexadecimal", output="ghijk"),
    GetKeyTestCase(name="not hexadecimal + linebreaks", output="  \nghijk  \n"),
]


@pytest.mark.parametrize(
    "test_output",
    [(c.output) for c in GET_KEY_INVALID_CASES],
    ids=[f"when {c.name}" for c in GET_KEY_INVALID_CASES]
)
def test_mocked_get_key_invalid(test_output: str):
    runner = Mock(spec=AbstractSubprocessRunner)
    runner.run.return_value = test_output

    with pytest.raises(KeyStoreKeyNotFoundException):
        # this should throw
        get_key("BeaconStore", runner)


@pytest.mark.skip(reason="In CI you'll never get the password prompt, so it just returns empty")
@skip_unless_macos_le14
def test_get_key_from_full_output():
    """
    Supposedly works on MacOS 13.3.1 (see: https://github.com/parawanderer/OpenTagViewer/issues/13)
    """
    res = get_key_from_full_output("BeaconStore")
    assert res is not None


def test_mocked_get_key_from_full_output_success():
    runner = Mock(spec=AbstractSubprocessRunner)
    runner.run.return_value = TEST_FULL_OUTPUT

    result = get_key_from_full_output("BeaconStore", runner)

    assert result is not None
    assert result == b'My-Secret-Key-ABCDEFGHIJKLMNOPHI'


GET_KEY_GENA_INVALID_CASES = [
    GetKeyTestCase(name="output is empty", output=""),
    GetKeyTestCase(name="output is whitespace", output="   "),
    GetKeyTestCase(name="output is whitespace + linebreak", output="  \n  "),
    GetKeyTestCase(name="null", output=_make_output("<NULL>")),
    GetKeyTestCase(name="invalid hex", output=_make_output("invalid")),
    GetKeyTestCase(name="missing", output=_make_output("")),
    GetKeyTestCase(name="empty", output=_make_output("0x0")),
    GetKeyTestCase(
        name="less than 64 hex character",
        output=_make_output("1"*63)
    ),
    GetKeyTestCase(
        name="more than 64 hex character",
        output=_make_output("1"*65)
    )
]


@pytest.mark.parametrize(
    "test_output",
    [(c.output) for c in GET_KEY_GENA_INVALID_CASES],
    ids=[f"when {c.name}" for c in GET_KEY_GENA_INVALID_CASES]
)
def test_mocked_get_key_from_full_output_invalid(test_output: str):
    runner = Mock(spec=AbstractSubprocessRunner)
    runner.run.return_value = test_output

    with pytest.raises(KeyStoreKeyNotFoundException):
        # this should throw
        get_key_from_full_output("BeaconStore", runner)


def test_get_key_fallback_first_success():
    runner = Mock(spec=AbstractSubprocessRunner)
    runner.run.side_effect = [
        # first call returns full result
        "4D792D5365637265742D4B65792D4142434445464748494A4B4C4D4E4F504849"
    ]

    result = get_key_fallback("BeaconStore", runner)

    assert result is not None
    assert result == b'My-Secret-Key-ABCDEFGHIJKLMNOPHI'


def test_get_key_fallback_second_success():
    runner = Mock(spec=AbstractSubprocessRunner)
    runner.run.side_effect = [
        # first call returns empty
        "",
        # second call returns full result
        TEST_FULL_OUTPUT
    ]

    result = get_key_fallback("BeaconStore", runner)

    assert result is not None
    assert result == b'My-Secret-Key-ABCDEFGHIJKLMNOPHI'


def test_get_key_fallback_both_fail():
    runner = Mock(spec=AbstractSubprocessRunner)
    runner.run.side_effect = [
        # first call returns empty
        "",
        # second call returns empty
        ""
    ]

    with pytest.raises(KeyStoreKeyNotFoundException):
        # this should throw
        get_key_fallback("BeaconStore", runner)


@skip_unless_unix
def test_make_output_path():
    result: str = make_output_path(
        input_file_path="/Users/user/Library/com.apple.icloud.searchpartyd/SomeFolder/Foo/Bar/88674E0D-7BC5-412E-A7D2-7A9B278F6B0E.record",  # noqa: E501
        output_root="/Users/user/my-target-folder",
        input_root_folder="/Users/user/Library/com.apple.icloud.searchpartyd"
    )

    assert result == "/Users/user/my-target-folder/SomeFolder/Foo/Bar/88674E0D-7BC5-412E-A7D2-7A9B278F6B0E.plist"


def test_extract_gena_key():
    result = extract_gena_key(TEST_FULL_OUTPUT)

    assert result is not None
    assert result == b'My-Secret-Key-ABCDEFGHIJKLMNOPHI'


def test_extract_gena_key_when_empty():
    with pytest.raises(KeyStoreKeyNotFoundException):
        extract_gena_key("")


def test_extract_key():
    output: str = "4D792D5365637265742D4B65792D4142434445464748494A4B4C4D4E4F504849"

    result = extract_key(output)

    assert result is not None
    assert result == b'My-Secret-Key-ABCDEFGHIJKLMNOPHI'


def test_extract_key_when_empty():
    with pytest.raises(KeyStoreKeyNotFoundException):
        extract_key("")


def test_decrypt_plist():
    # create temporary plist
    original_data: dict = {
        "foo": "bar",
        "one": 1,
        "nested": [
            {
                "stuff": 1.123,
            }
        ]
    }

    encrypted_plist, key = _create_plist(original_data)
    tmp_path = _create_plist_path()

    try:
        # write to temp file:
        with open(tmp_path, 'wb') as f:
            f.write(encrypted_plist)

        # try to read
        plist = decrypt_plist(tmp_path, key)

        # must be same!
        assert plist == original_data

    finally:
        # cleanup
        if os.path.exists(tmp_path):
            os.remove(tmp_path)


def test_dump_plist():
    plist_data: dict = {
        "foo": "bar"
    }

    base_path, tmp_path = _create_nested_plist_path()

    try:
        dump_plist(plist_data, tmp_path)

        assert os.path.exists(tmp_path)
        assert os.path.isfile(tmp_path)

    finally:
        # Cleanup
        if os.path.exists(base_path):
            shutil.rmtree(base_path)


def test_decrypt_folder():
    plist1: dict = {
        "foo": "bar"
    }

    plist2: dict = {
        "baz": 123
    }
    key = get_random_bytes(16)

    base_path, [item1_path, item2_path] = _create_many_nested_paths(count=2)
    folder_name = 'Foo'
    tmp_output_to: str = _create_tmp_out_folder()

    def setup():
        # make temp directory to test against
        os.makedirs(os.path.dirname(item1_path), exist_ok=True)
        os.makedirs(os.path.dirname(item2_path), exist_ok=True)

        with open(item1_path, 'wb') as f:
            f.write(_create_plist(plist1, key)[0])

        with open(item2_path, 'wb') as f:
            f.write(_create_plist(plist2, key)[0])

    try:
        setup()

        # test target:
        decrypt_folder(
            base_path,
            folder_name,
            key,
            tmp_output_to
        )

        # assert expected:
        assert os.path.exists(item1_path)
        assert os.path.isfile(item1_path)

        assert os.path.exists(item2_path)
        assert os.path.isfile(item2_path)

    finally:
        if os.path.exists(base_path):
            shutil.rmtree(base_path)

        if os.path.exists(tmp_output_to):
            shutil.rmtree(tmp_output_to)


# ---------------------------------------------------------------------------------------
# KeyAlignmentRecords
#
# These records are what stop a freshly imported tag searching its entire key history on the
# first fetch (see issue #30). They were originally left out of WHITELISTED_DIRS, so exports
# never carried them and nothing failed - imports were just enormously more expensive than
# they needed to be. The tests below pin both halves of that: that the folder is exported at
# all, and that it lands at the exact path the Android importer looks for.
# ---------------------------------------------------------------------------------------

# Must stay in step with FILE_TYPE.KEY_ALIGNMENT_RECORD in
# app/src/main/java/.../util/parse/AppleZipImporterUtil.java. If this test and that matcher
# ever disagree, exports silently lose their alignment records again.
ANDROID_KEY_ALIGNMENT_MATCHER = re.compile(
    r"^KeyAlignmentRecords/"
    r"([0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12})/"
    r"([0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12})\.plist$"
)


def _uppercase_uuid4() -> str:
    return str(uuid.uuid4()).upper()


def test_key_alignment_records_are_exported():
    assert KEY_ALIGNMENT_RECORDS in WHITELISTED_DIRS


def test_make_output_path_keeps_the_key_alignment_nesting():
    # macOS stores these as KeyAlignmentRecords/<accessory>/<record>.record. The accessory id
    # only exists as the parent directory name - unlike naming records, the file itself has no
    # "associatedBeacon" field - so flattening the path would lose the association entirely.
    input_root = "/Users/someone/Library/com.apple.icloud.searchpartyd"
    accessory_id = _uppercase_uuid4()
    record_id = _uppercase_uuid4()

    result = make_output_path(
        "/Users/someone/export",
        f"{input_root}/{KEY_ALIGNMENT_RECORDS}/{accessory_id}/{record_id}.record",
        input_root
    )

    rel_path = os.path.relpath(result, "/Users/someone/export").replace(os.sep, "/")

    assert rel_path == f"{KEY_ALIGNMENT_RECORDS}/{accessory_id}/{record_id}.plist"

    match = ANDROID_KEY_ALIGNMENT_MATCHER.match(rel_path)
    assert match is not None, f"the Android importer would skip {rel_path}"
    assert match.group(1) == accessory_id


def test_decrypt_folder_writes_key_alignment_records():
    alignment = {
        "lastIndexObserved": 4321,
        "lastIndexObservationDate": "2026-08-01T12:00:00Z",
    }
    key = get_random_bytes(16)

    accessory_id = _uppercase_uuid4()
    record_id = _uppercase_uuid4()

    base_path = os.path.join(DIRNAME, f"resources/{_uppercase_uuid4()}")
    record_path = os.path.join(
        base_path, KEY_ALIGNMENT_RECORDS, accessory_id, f"{record_id}.record")
    tmp_output_to = _create_tmp_out_folder()

    try:
        os.makedirs(os.path.dirname(record_path), exist_ok=True)
        with open(record_path, 'wb') as f:
            f.write(_create_plist(alignment, key)[0])

        # test target:
        decrypt_folder(
            base_path,
            KEY_ALIGNMENT_RECORDS,
            key,
            tmp_output_to
        )

        expected = os.path.join(
            tmp_output_to, KEY_ALIGNMENT_RECORDS, accessory_id, f"{record_id}.plist")

        assert os.path.isfile(expected), (
            f"expected a decrypted alignment record at {expected}")

        with open(expected, 'rb') as f:
            written = plistlib.load(f)

        assert written["lastIndexObserved"] == 4321

    finally:
        if os.path.exists(base_path):
            shutil.rmtree(base_path)

        if os.path.exists(tmp_output_to):
            shutil.rmtree(tmp_output_to)
