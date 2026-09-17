"""Copy one logical partition out of an Android dynamic-partition "super" image (liblp format),
which is what API 30+ emulator system.img files hold instead of a plain system partition.

    python lp_extract.py <image> <byte offset of super in image> <partition name> <out>
"""
import struct
import sys

LP_PARTITION_RESERVED_BYTES = 4096
LP_METADATA_GEOMETRY_SIZE = 4096
GEOMETRY_MAGIC = 0x616C4467
HEADER_MAGIC = 0x414C5030
SECTOR = 512


def main() -> int:
    image, base, wanted, out = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    with open(image, "rb") as f:
        f.seek(base + LP_PARTITION_RESERVED_BYTES)
        magic, = struct.unpack("<I", f.read(4))
        if magic != GEOMETRY_MAGIC:
            raise SystemExit(f"no liblp geometry at {base + 4096} (magic {magic:#x})")

        start = base + LP_PARTITION_RESERVED_BYTES + 2 * LP_METADATA_GEOMETRY_SIZE
        f.seek(start)
        header = f.read(256)
        magic, major, minor, header_size = struct.unpack_from("<IHHI", header, 0)
        if magic != HEADER_MAGIC:
            raise SystemExit(f"no liblp header (magic {magic:#x})")
        partitions = struct.unpack_from("<III", header, 80)
        extents = struct.unpack_from("<III", header, 92)
        print(f"liblp {major}.{minor}: {partitions[1]} partitions, {extents[1]} extents")

        def table(descriptor):
            offset, count, size = descriptor
            f.seek(start + header_size + offset)
            blob = f.read(count * size)
            return [blob[i * size:(i + 1) * size] for i in range(count)]

        extent_table = table(extents)
        found = None
        for entry in table(partitions):
            name = entry[:36].split(b"\0")[0].decode()
            _attrs, first, count, _group = struct.unpack_from("<IIII", entry, 36)
            sectors = sum(struct.unpack_from("<Q", extent_table[i], 0)[0]
                          for i in range(first, first + count))
            print(f"  {name}: {count} extents, {sectors * SECTOR} bytes")
            if name == wanted:
                found = (first, count)
        if found is None:
            raise SystemExit(f"no partition named {wanted}")

        with open(out, "wb") as w:
            for i in range(found[0], found[0] + found[1]):
                sectors, target_type, target_data, _source = struct.unpack_from(
                    "<QIQI", extent_table[i], 0)
                length = sectors * SECTOR
                if target_type == 1:  # zero fill
                    w.seek(length, 1)
                    continue
                f.seek(base + target_data * SECTOR)
                while length:
                    chunk = f.read(min(length, 1 << 24))
                    w.write(chunk)
                    length -= len(chunk)
            w.truncate()
    return 0


if __name__ == "__main__":
    sys.exit(main())
