import time
import os
import shutil
import uuid

import plistlib
from Crypto.Cipher import AES
from Crypto.Random import get_random_bytes

from utils import DIRNAME, skip_unless_macos_le14

from python.airtag_decryptor import decrypt_folder, decrypt_plist, dump_plist, get_key, make_output_path


def create_plist(plistData: dict, key: bytes | None = None) -> bytes:
    if key is None:
        key = get_random_bytes(16)

    nonce: bytes = get_random_bytes(12)
    data: bytes = plistlib.dumps(plistData)

    cipher = AES.new(key, AES.MODE_GCM, nonce=nonce)
    ciphertext, tag = cipher.encrypt_and_digest(data)

    plist_lvl2_data: list[bytes] = [nonce, tag, ciphertext]
    plist_lvl2: bytes = plistlib.dumps(plist_lvl2_data)

    return plist_lvl2, key


def create_plist_path() -> str:
    timestamp = int(time.time())
    tmp_path = os.path.join(DIRNAME, f"resources/tmp_plist_{timestamp}.record")
    return tmp_path


def create_many_nested_paths(count: int) -> tuple[str, list[str]]:
    id_part_root: str = str(uuid.uuid4()).upper()
    basepath: str = os.path.join(DIRNAME, f"resources/{id_part_root}")

    sub_paths: list[str] = []
    for _ in range(count):
        id_part: str = str(uuid.uuid4()).upper()
        item_path = os.path.join(basepath, f"Foo/{id_part}.record")
        sub_paths.append(item_path)

    return (basepath, sub_paths)


def create_nested_plist_path() -> tuple[str, str]:
    result = create_many_nested_paths(count=1)
    return (result[0], result[1][0])


def create_tmp_out_folder() -> str:
    id_part: str = str(uuid.uuid4()).upper()
    return os.path.join(DIRNAME, f"resources/{id_part}")


@skip_unless_macos_le14
def test_get_key():
    """
    Only works on MacOS <= 14.x, is supposed to prompt password
    """

    key = get_key("BeaconStore")
    assert key is not None


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

    encrypted_plist, key = create_plist(original_data)
    tmp_path = create_plist_path()

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

    base_path, tmp_path = create_nested_plist_path()

    try:
        dump_plist(plist_data, tmp_path)

        assert os.path.exists(tmp_path)
        assert os.path.isfile(tmp_path)

    finally:
        # Cleanup
        if os.path.exists(base_path):
            shutil.rmtree(base_path)


def test_make_output_path():
    result: str = make_output_path(
        input_file_path="/Users/user/Library/com.apple.icloud.searchpartyd/SomeFolder/Foo/Bar/88674E0D-7BC5-412E-A7D2-7A9B278F6B0E.record",  # noqa: E501
        output_root="/Users/user/my-target-folder",
        input_root_folder="/Users/user/Library/com.apple.icloud.searchpartyd"
    )

    assert result == "/Users/user/my-target-folder/SomeFolder/Foo/Bar/88674E0D-7BC5-412E-A7D2-7A9B278F6B0E.plist"


def test_decrypt_folder():
    plist1: dict = {
        "foo": "bar"
    }

    plist2: dict = {
        "baz": 123
    }
    key = get_random_bytes(16)

    base_path, [item1_path, item2_path] = create_many_nested_paths(count=2)
    folder_name = 'Foo'
    tmp_output_to: str = create_tmp_out_folder()

    def setup():
        # make temp directory to test against
        os.makedirs(os.path.dirname(item1_path), exist_ok=True)
        os.makedirs(os.path.dirname(item2_path), exist_ok=True)

        with open(item1_path, 'wb') as f:
            f.write(create_plist(plist1, key)[0])

        with open(item2_path, 'wb') as f:
            f.write(create_plist(plist2, key)[0])

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
