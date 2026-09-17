"""Fetch Apple's three ADI libraries for one ABI out of the Apple Music APK with HTTP range
requests (no full download), and verify them against app/src/main/assets/adi-libraries.json.

    python app/src/test/adi-on-bionic/fetch_apple_libs.py arm64-v8a out/apple
"""
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(HERE))))
sys.path.insert(0, os.path.join(ROOT, "scripts"))

import update_adi_stub_symbols as apk  # noqa: E402


def main() -> int:
    abi, out = sys.argv[1], sys.argv[2]
    manifest = json.load(open(os.path.join(ROOT, "app", "src", "main", "assets",
                                           "adi-libraries.json")))
    expected = manifest["libraries"][abi]
    os.makedirs(out, exist_ok=True)

    index = {name: (method, comp, off)
             for name, method, comp, off in apk.entries(apk.central_directory(apk.apk_size()))}
    bad = 0
    for library, want in expected.items():
        blob = apk.extract(*index[f"lib/{abi}/{library}"])
        digest = hashlib.sha256(blob).hexdigest()
        ok = digest == want["sha256"] and len(blob) == want["size"]
        print(f"{'ok ' if ok else 'BAD'} {library} {len(blob)} bytes sha256 {digest}")
        bad += not ok
        with open(os.path.join(out, library), "wb") as f:
            f.write(blob)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
