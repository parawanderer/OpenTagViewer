# Third-party notices for the emulated Mac

## sacca97/os-findmy-e

`macho.*`, `vm.*` and `core_adi.*` are a C++ port of
[sacca97/os-findmy-e](https://github.com/sacca97/os-findmy-e) `native/adi-core/src/{macho,vm,adi}.rs`,
and the provisioning headers in `MacProvisioning.java` and the identity shapes in `MacIdentity.java`
follow its `tools/machoadi/{gsa,mac_anisette}.py`. Its licence:

```
MIT License

Copyright (c) 2026 sacca

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Unicorn

[Unicorn](https://github.com/unicorn-engine/unicorn) 2.1.4 is fetched from source at build time
(`MacAdi.cmake`, pinned by SHA-256) and linked into `libmacadi.so`. It is licensed under
**GPL-2.0**, so an APK containing `libmacadi.so` contains GPL-2.0 code.

## Apple's CoreADI

Not part of this repository or of any build. The app downloads it at runtime from Apple's own
server - see `MacCoreAdiFetcher.java` and `scripts/locate_mac_coreadi.py`.
