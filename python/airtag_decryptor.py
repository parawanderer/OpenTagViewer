import os
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
INPUT_PATH = f"{os.getenv("HOME")}/Library/{BASE_FOLDER}"

# NOTE FROM AUTHOR: For my purposes these are sufficient.
# You can add more if you need more, or remove the filter entirely below
WHITELISTED_DIRS = { "OwnedBeacons", "BeaconNamingRecord" }

# NOTE FROM AUTHOR: PROVIDE YOUR OWN OUTPUT PATH HERE IF DESIRED!!!
OUTPUT_PATH = f"/Users/{os.getenv("USER")}/plist_decrypt_output"


def get_key(label: str):
    """
    TODO: consider contributing this type of command to
    the `microsoft/keyper` library, as they are missing an output with the `-w`
    flag like this.

    The problem with this particular key seems to be that it is stored in binary.
    You can find it in the Keychain Access app on your mac but you can't unhide it (it shows as empty).

    Both these libraries can't seem to support it due to that:
    - https://github.com/microsoft/keyper/blob/main/keyper/keychain.py
    - https://pypi.org/project/keyring/

    However the Microsoft one seems to be very easy to adapt to support this `-w`
    flag since it's using the same `subprocess.getoutput` approach that I use here.
    """

    # TODO: if I contribute this, properly escape the label argument here...
    key_in_hex_format: str = subprocess.getoutput(f"security find-generic-password -l '{label}' -w")
    key: bytearray = bytearray.fromhex(key_in_hex_format)

    return key


def decrypt_plist(in_file_path: str, key: bytearray) -> dict:
    """
    Given an encrypted plist file at path `in_file_path`, decrypt it using `key` and AES-GMC and return the decrypted plist `dict`

    :param in_file_path:    Source path of the encrypted plist file.

                            Generally something like `/Users/<username>/Library/com.apple.icloud.searchpartyd/OwnedBeacons/<UUID>.record`

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
    Transforms `input_file_path` into a dumping `output_file_path` along the lines of this idea (but it works generically for any level of nesting):

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

                print(f"Success!")
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
    os.system(f'open {OUTPUT_PATH}')


if __name__ == '__main__':
    main()