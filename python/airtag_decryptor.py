import os
import re
import shlex
import subprocess
import plistlib
from Crypto.Cipher import AES


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
INPUT_PATH = os.path.join(os.getenv('HOME'), 'Library', BASE_FOLDER)

# NOTE FROM AUTHOR: For my purposes these are sufficient.
# You can add more if you need more, or remove the filter entirely below
WHITELISTED_DIRS = {"OwnedBeacons", "BeaconNamingRecord"}

# NOTE FROM AUTHOR: PROVIDE YOUR OWN OUTPUT PATH HERE IF DESIRED!!!
OUTPUT_PATH = os.path.join(os.getenv('HOME'), "plist_decrypt_output")


class KeyStoreKeyNotFoundException(Exception):
    pass


def get_key(label: str) -> bytearray:
    """
    Tries to extract the key via the command `security find-generic-password -l <label> -w`.

    :throws KeyStoreKeyNotFoundException: when not found or unable to extract
    """
    try:
        result = subprocess.run(
            [
                "security",
                "find-generic-password",
                "-l",
                label,
                "-w"
            ],
            capture_output=True,
            text=True
        )
        key_in_hex_format: str = result.stdout
        key: bytearray = bytearray.fromhex(key_in_hex_format)
        
        if len(key) == 0:
            raise KeyStoreKeyNotFoundException(f"Key for '{label}' was empty!")

        return key
    except Exception as e:
        raise KeyStoreKeyNotFoundException("Failed to retrieve keystore value") from e


def get_key_from_full_output(label: str):
    """
    Tries to extract the key via the command `security find-generic-password -l <label>`.

    It assumes that the key is stored in the `"gena"<blob>=0x<64 chars>` part of the output.

    :throws KeyStoreKeyNotFoundException: when not found in output or unable to extract
    """
    try:
        result = subprocess.run(
            [
                "security",
                "find-generic-password",
                "-l",
                label
            ],
            capture_output=True,
            text=True
        )
        full_key_info: str = result.stdout
        return extract_gena_key(full_key_info)
    except Exception as e:
        raise KeyStoreKeyNotFoundException("Failed to retrieve keystore value") from e


def extract_gena_key(output: str):
    """Tries to extract the key from the full output (see issue: https://github.com/parawanderer/OpenTagViewer/issues/13), e.g.:
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
        "gena"<blob>=0x80B8CBED<HIDEN BY ME BUT 64 charachters>  "\200\270\313\355\302QS\352H-]\207\323\<HIDEN BY ME>"
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


def make_output_path(output_root: str, input_file_path: str, input_root_folder: str) -> str:
    """
    Transforms `input_file_path` into a dumping `output_file_path` along the lines of this idea (but it works
    generically for any level of nesting):

    Given:
    - `input_file_path` = `/Users/<user>/Library/com.apple.icloud.searchpartyd/SomeFolder/.../<UUID>.record`
    - `output_root` = `/Users/<user>/my-target-folder`
    - `input_root_folder` = `/Users/<user>/Library/com.apple.icloud.searchpartyd`

    This will create the path:
    `/Users/<user>/my-target-folder/SomeFolder/.../<UUID>.plist`

    """
    rel_path: str = os.path.relpath(input_file_path, input_root_folder)
    replace_file_ext: str = os.path.splitext(rel_path)[0] + ".plist"
    return os.path.join(output_root, replace_file_ext)


def decrypt_folder(input_base_path: str, folder_name: str, key: bytearray, output_to: str):
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
                    input_base_path
                )
                print(f"Now trying to dump decrypted plist file to: {file_dumpath}")
                dump_plist(plist, file_dumpath)

                print("Success!")
            except Exception as e:
                print(f"ERROR decrypting plist file: {e}")


def main():
    os.makedirs(OUTPUT_PATH, exist_ok=True)

    # this thing will pop up 2 Password Input windows...
    key: bytearray = get_key(KEYCHAIN_LABEL)

    for path, folders, _ in os.walk(INPUT_PATH):
        for foldername in folders:

            if foldername not in WHITELISTED_DIRS:
                continue

            decrypt_folder(path, foldername, key, OUTPUT_PATH)

        break

    print("DONE")
    os.system(f'open {shlex.quote(OUTPUT_PATH)}')


if __name__ == '__main__':
    main()
