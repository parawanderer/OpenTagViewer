from abc import ABC, abstractmethod
import base64
import os
import re
import shlex
import subprocess
import plistlib
import argparse
import traceback
from pathlib import Path
from Crypto.Cipher import AES

from main.utils import MACOS_VER


# Author: Shane B. <shane@wander.dev>
#
# Based on: https://gist.github.com/airy10/5205dc851fbd0715fcd7a5cdde25e7c8
#
# Tested on MacOS:
#
#   ProductName:            macOS
#   ProductVersion:         14.7.4
#   BuildVersion:           23H420
#
# With python version: 3.13.2


KEYCHAIN_LABEL = "BeaconStore"

BASE_FOLDER = "com.apple.icloud.searchpartyd"
HOME = '' if os.getenv('HOME') is None else os.getenv('HOME')
INPUT_PATH = os.path.join(HOME, 'Library', BASE_FOLDER)

OWNED_BEACONS = "OwnedBeacons"
MASTER_BEACONS = "MasterBeacons"
BEACON_NAMING_RECORD = "BeaconNamingRecord"

WHITELISTED_DIRS = {
    OWNED_BEACONS,
    MASTER_BEACONS,  # <- MacOS 11 (see: https://github.com/parawanderer/OpenTagViewer/issues/24)
    BEACON_NAMING_RECORD
}

RENAME_LEGACY_MAP = {
    MASTER_BEACONS: OWNED_BEACONS  # <- MacOS 11 (see: https://github.com/parawanderer/OpenTagViewer/issues/24)
}

OUTPUT_PATH = os.path.join(HOME, "plist_decrypt_output")


class KeyStoreKeyNotFoundException(Exception):
    pass


class AbstractSubprocessRunner(ABC):
    """
    Wrapper class to enable easier unit testing
    """

    @abstractmethod
    def run(self, command: list[str]) -> str:
        ...


class SubprocessRunner(AbstractSubprocessRunner):
    """
    Runs generic `subprocess` implementation
    """

    def run(self, command: list[str]) -> str:
        return subprocess.run(command, capture_output=True, text=True).stdout


def get_key(label: str, runner: AbstractSubprocessRunner = SubprocessRunner()) -> bytearray:
    """
    Tries to extract the key via the command `security find-generic-password -l <label> -w`.

    :throws KeyStoreKeyNotFoundException: when not found or unable to extract
    """
    try:
        key_in_hex_format: str = runner.run([
            "security",
            "find-generic-password",
            "-l",
            label,
            "-w"
        ])

        if len(key_in_hex_format.strip()) == 0:
            raise KeyStoreKeyNotFoundException("security command output with flag -w returned empty")

        return extract_key(key_in_hex_format)

    except Exception as e:
        raise KeyStoreKeyNotFoundException("Failed to retrieve keystore value") from e


def extract_key(key_in_hex_format: str) -> bytearray:
    key: bytearray = bytearray.fromhex(key_in_hex_format)

    if len(key) == 0:
        raise KeyStoreKeyNotFoundException("Key was empty!")

    return key


def get_key_from_full_output(label: str, runner: AbstractSubprocessRunner = SubprocessRunner()):
    """
    Tries to extract the key via the command `security find-generic-password -l <label>`.

    It assumes that the key is stored in the `"gena"<blob>=0x<64 chars>` part of the output.

    :throws KeyStoreKeyNotFoundException: when not found in output or unable to extract
    """
    try:
        full_key_info: str = runner.run([
            "security",
            "find-generic-password",
            "-l",
            label
        ])

        if len(full_key_info.strip()) == 0:
            raise KeyStoreKeyNotFoundException("security command full output returned empty")

        return extract_gena_key(full_key_info)
    except Exception as e:
        raise KeyStoreKeyNotFoundException("Failed to retrieve keystore value") from e


def extract_gena_key(output: str):
    """Tries to extract the key from the full output
    (see issue: https://github.com/parawanderer/OpenTagViewer/issues/13), e.g.:

    ```
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
        "gena"<blob>=0x4D792D5365637265742D4B65792D4142434445464748494A4B4C4D4E4F504849  "Password"
        "icmt"<blob>=<NULL>
        "invi"<sint32>=<NULL>
        "mdat"<timedate>=0x32303235303630383136303533305A00  "20250608160530Z\000"
        "nega"<sint32>=<NULL>
        "prot"<blob>=<NULL>
        "scrp"<sint32>=<NULL>
        "svce"<blob>="BeaconStore"
        "type"<uint32>=<NULL>
    ```

    We will just assume this "gena" output contains what we need as per the user report here (if it exists)
    """

    matches = re.findall(r'"gena"<blob>=0x([0-9A-F]{64})\s*".+"', output)
    if len(matches) == 0:
        raise KeyStoreKeyNotFoundException("gena value not found!")

    key_in_hex_format: str = matches[0]
    key: bytearray = bytearray.fromhex(key_in_hex_format)

    return key


def get_key_fallback(label: str, runner: AbstractSubprocessRunner = SubprocessRunner()) -> bytearray:
    try:
        return get_key(label, runner)
    except KeyStoreKeyNotFoundException:
        # Fallback to alternate solution (see issue #13)
        return get_key_from_full_output(label, runner)


def decrypt_plist(in_file_path: str, key: bytearray) -> dict:
    """
    Given an encrypted plist file at path `in_file_path`, decrypt it using `key` and AES-GMC and return
    the decrypted plist `dict`

    :param in_file_path:    Source path of the encrypted plist file.

                            Generally something like
                            `/Users/<username>/Library/com.apple.icloud.searchpartyd/OwnedBeacons/<UUID>.record`

    :param key:             Raw key to decrypt plist file with.

                            Get it from the system shell command:

                            `security find-generic-password -l '<LABEL>' -w`

                            See: `get_key(label: str)`


    :returns:               The decoded plist dict
    :rtype: dict

    :raises Exception:      On failure to decrypt the encrypted plist
    """
    with open(in_file_path, 'rb') as f:
        encrypted_data: bytes = f.read()

    try:
        plist = plistlib.loads(encrypted_data)
    except Exception:
        raise Exception("Invalid file format")

    if not isinstance(plist, list) or len(plist) < 3:
        raise Exception("Invalid plist format")

    nonce, tag, ciphertext = plist[0], plist[1], plist[2]
    cipher = AES.new(key, AES.MODE_GCM, nonce=nonce)
    decrypted_plist = cipher.decrypt_and_verify(ciphertext, tag)

    try:
        decrypted_plist = plistlib.loads(decrypted_plist)
    except Exception:
        raise Exception("Invalid decrypted data")

    if not isinstance(decrypted_plist, dict):
        raise Exception(f"Expected plist to be a dictionary, but it was a {type(decrypted_plist)}")

    return decrypted_plist


def dump_plist(plist: dict, out_file_path: str) -> None:
    """
    Given a parsed `plist` dict, dump the decrypted plist file contents (this is xml) at `out_file_path`.
    This function will try to create missing folders.

    :param plist:           Decrypted plist, created using any means.

                            See also: `decrypt_plist(in_file_path: str, key: bytearray) -> dict`

    :param out_file_path:   The output file name to create the decrypted & parsed plist xml file at.
    """

    os.makedirs(os.path.dirname(out_file_path), exist_ok=True)

    with open(out_file_path, 'wb') as out_f:
        plistlib.dump(plist, out_f)


def make_output_path(
        output_root: str,
        input_file_path: str,
        input_root_folder: str,
        rename_legacy: bool = False) -> str:
    """
    Transforms `input_file_path` into a dumping `output_file_path` along the lines of this idea (but it works
    generically for any level of nesting):

    Given:
    - `output_root` = `/Users/<user>/my-target-folder`
    - `input_file_path` = `/Users/<user>/Library/com.apple.icloud.searchpartyd/SomeFolder/.../<UUID>.record`
    - `input_root_folder` = `/Users/<user>/Library/com.apple.icloud.searchpartyd`

    This will create the path:
    `/Users/<user>/my-target-folder/SomeFolder/.../<UUID>.plist`


    `rename_legacy` controls the behaviour to do some legacy-related folder name remapping

    """

    # Given the sample inputs, this would produce: `SomeFolder/.../<UUID>.record`
    rel_path: str = os.path.relpath(input_file_path, input_root_folder)

    # This would extract the `SomeFolder` part
    first_path_part: str = Path(rel_path).parts[0]

    if rename_legacy and first_path_part in RENAME_LEGACY_MAP:
        # this is to solve issues like https://github.com/parawanderer/OpenTagViewer/issues/24
        # basically a rename, like `SomeFolder/UUID.record` -> `AnotherFolder/UUID.record`
        rel_path = RENAME_LEGACY_MAP[first_path_part] + rel_path[rel_path.index("/"):]

    # replace the file extension: `SomeFolder/.../<UUID>.record` -> `SomeFolder/.../<UUID>.plist`
    replace_file_ext: str = os.path.splitext(rel_path)[0] + ".plist"

    # absolutify it again
    return os.path.join(output_root, replace_file_ext)


def decrypt_folder(input_base_path: str, folder_name: str, key: bytearray, output_to: str, rename_legacy: bool = False):
    """
    Decrypt contents of folder `<input_base_path>/<folder_name>` to file path `output_to` recursively using `key`
    """
    search_path: str = os.path.join(input_base_path, folder_name)

    for path, folders, files in os.walk(search_path):
        for filename in files:
            try:
                file_fullpath: str = os.path.join(path, filename)

                print(f"Trying to decrypt plist file at: {file_fullpath}...")
                plist: dict = decrypt_plist(file_fullpath, key)

                file_dumpath: str = make_output_path(
                    output_to,
                    file_fullpath,
                    input_base_path,
                    rename_legacy
                )
                print(f"Now trying to dump decrypted plist file to: {file_dumpath}")
                dump_plist(plist, file_dumpath)

                print("Success!")
            except Exception:
                print(_red("ERROR decrypting plist file"))
                traceback.print_exc()


def _red(text: str) -> str:
    return f"\033[91m{text}\n\033[0m"


def _parse_b64_key(key: str) -> bytearray:
    if len(key) == 0:
        return None

    try:
        return bytearray(base64.b64decode(key))
    except Exception:
        traceback.print_exc()
        return None


def _determine_key_to_use(args: argparse.Namespace) -> bytearray:
    key: bytearray

    if args.key is not None:

        key = _parse_b64_key(args.key)

        if key is None:
            print(_red("Invalid base64 key provided via --key argument"))
            exit(1)
    else:

        if MACOS_VER[0] >= 15:
            print(_red(f"For MacOS >= 15, extracting the '{KEYCHAIN_LABEL}' key automatically is not supported due to newly introduced OS keychain access limitations. \n\nPlease consider using the --key argument (see --help) and see alternative key retrieval strategies here:\n\n\thttps://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Manually-Export-AirTags"))  # noqa: E501
            exit(1)

        # this thing will pop up 1 or 2 Password Input windows...
        key = get_key_fallback(KEYCHAIN_LABEL)

    return key


def _assert_output_path_valid(output_to: str) -> None:
    if not os.path.exists(output_to):
        print("foo")
        return  # Valid because doesn't exist yet, we can just init it, probably

    if os.path.isdir(output_to):
        if os.listdir(output_to):
            print(_red(f"Output path '{output_to}' already exists and this directory is not empty. To prevent overwriting files we will avoid running the script. \n\nEither purge the contents of the directory, like so:\n\n\trm -rf '{output_to}'\n\n or use --output to specify an alternative folder first."))  # noqa: E501
            exit(1)

    elif os.path.isfile(output_to):
        print(_red(f"Output path '{output_to}' already existed and is a file. Delete the file or use --output to provide an alternative output path"))  # noqa: E501
        exit(1)


def main():
    if MACOS_VER is None:
        print(_red("This tool is only supported on MacOS machines"))
        exit(1)

    parser = argparse.ArgumentParser(
        description="CLI utility for decrypting/dumping MacOS <= 14.x FindMy cache .plist files into a folder",
        add_help=True,
        epilog="More here: https://github.com/parawanderer/OpenTagViewer/wiki/How-To:-Manually-Export-AirTags"
    )

    parser.add_argument(
        "-o",
        "--output",
        type=str,
        default=OUTPUT_PATH,
        help=f"which folder to output the decrypted data to. Defaults to '{OUTPUT_PATH}'"
    )

    parser.add_argument(
        "-a",
        "--all",
        action='store_true',
        default=False,
        help=f"whether to decrypt all folders or just the standard subset ({', '.join(WHITELISTED_DIRS)})"
    )

    parser.add_argument(
        "-k",
        "--key",
        type=str,
        default=None,
        help="base64 key belonging to BeaconStore keystore record, in case it is impossible to extract the BeaconStore keychain key but you managed to obtain the key through other means. More here: https://github.com/parawanderer/OpenTagViewer/tree/main/python#-python-utility-scripts",  # noqa: E501
    )

    parser.add_argument(
        "--rename-legacy",
        default=False,
        action='store_true',
        help="whether to remap old MacOS folders like 'MasterBeacons' to the new name 'OwnedBeacons'. Required for use in the OpenTagViewer Android app."  # noqa: E501
    )

    args = parser.parse_args()

    # args
    output_to: str = args.output
    _assert_output_path_valid(output_to)

    rename_legacy: bool = args.rename_legacy
    decode_all: bool = args.all
    key: bytearray = _determine_key_to_use(args)

    os.makedirs(output_to, exist_ok=True)

    for path, folders, _ in os.walk(INPUT_PATH):
        for foldername in folders:

            if not decode_all and foldername not in WHITELISTED_DIRS:
                continue

            decrypt_folder(path, foldername, key, output_to, rename_legacy)

        break

    print("DONE")
    os.system(f'open {shlex.quote(output_to)}')


if __name__ == '__main__':
    main()
