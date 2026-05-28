"""
roundtrip-test.py - sanity check that patch-bootimg.py can parse + rewrite
an unmodified boot.img and produce the exact same bytes back.

If the round-trip fails (output differs from input), then our cpio/header
rebuild has bugs and explains why the patched boot.img bricks the boot.
"""
import sys
import struct
import gzip
import hashlib
import os

# Re-use the parse/write helpers from patch-bootimg.py
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import importlib.util
spec = importlib.util.spec_from_file_location("pbi", os.path.join(os.path.dirname(os.path.abspath(__file__)), "patch-bootimg.py"))
pbi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pbi)


def show_first_diff(a, b, label, max_examples=8):
    print(f"\n--- {label}: comparing {len(a):,} vs {len(b):,} bytes ---")
    if a == b:
        print(f"  IDENTICAL")
        return True
    if len(a) != len(b):
        print(f"  SIZE DIFFERS: {len(a):,} vs {len(b):,}")
    diff_count = 0
    for i in range(min(len(a), len(b))):
        if a[i] != b[i]:
            diff_count += 1
            if diff_count <= max_examples:
                print(f"  byte 0x{i:08x} ({i}): expected 0x{a[i]:02X}, got 0x{b[i]:02X}")
    if diff_count == 0 and len(a) != len(b):
        print(f"  prefix matches, sizes differ")
    else:
        print(f"  total differing bytes (among common prefix): {diff_count}")
    return False


def main():
    boot_path = sys.argv[1] if len(sys.argv) > 1 else 'C:\\Users\\User\\Documents\\GitHub\\Mabu\\dumps\\boot.img'
    print(f"Reading {boot_path}")
    with open(boot_path, 'rb') as f:
        orig = f.read()

    bi = pbi.parse_bootimg(orig)
    print(f"Parsed v{bi['header_version']}: kernel={len(bi['kernel'])} ramdisk={len(bi['ramdisk'])} second={len(bi['second'])}")

    # Step 1: round-trip the cpio (gunzip ramdisk -> parse cpio -> write cpio -> gzip)
    if bi['ramdisk'][:2] == b'\x1f\x8b':
        cpio_orig = gzip.decompress(bi['ramdisk'])
        entries = pbi.parse_cpio(cpio_orig)
        cpio_rebuilt = pbi.write_cpio(entries)
        show_first_diff(cpio_orig, cpio_rebuilt, "cpio round-trip")
    else:
        print("Ramdisk is not gzipped, skipping cpio test")
        cpio_orig = None

    # Step 2: round-trip the whole boot image with NO modifications.
    # This uses the same path patch_bootimg.cmd_patch uses, minus the
    # default.prop modification.
    page = bi['page_size']
    kernel = bi['kernel']
    ramdisk = bi['ramdisk']   # original, untouched
    second = bi['second']

    sha1 = hashlib.sha1()
    sha1.update(kernel); sha1.update(struct.pack('<I', len(kernel)))
    sha1.update(ramdisk); sha1.update(struct.pack('<I', len(ramdisk)))
    sha1.update(second); sha1.update(struct.pack('<I', len(second)))
    new_hash = sha1.digest().ljust(32, b'\x00')

    new_header = bytearray(b'ANDROID!')
    new_header += struct.pack('<10I',
        len(kernel), bi['kernel_addr'],
        len(ramdisk), bi['ramdisk_addr'],
        bi['second_size'], bi['second_addr'],
        bi['tags_addr'], page,
        bi['header_version'], bi['os_version'])
    new_header += bi['name_raw']
    new_header += bi['cmdline_raw']
    new_header += new_hash
    new_header += bi['extra_cmdline_raw']
    new_header = bytes(new_header).ljust(page, b'\x00')

    def pad_page(x):
        rem = len(x) % page
        return x + (b'\x00' * (page - rem)) if rem else x

    new_img = new_header + pad_page(kernel) + pad_page(ramdisk) + pad_page(second)

    # Compare new_img to the equivalent prefix of orig
    expected_len = len(new_img)
    show_first_diff(orig[:expected_len], new_img, "full boot.img round-trip (no modifications)")

    print(f"\n  original SHA-1 in header: {bi['id_hash'][:20].hex()}")
    print(f"  re-computed SHA-1:        {new_hash[:20].hex()}")

    # Also check: what does the second image's tail look like?
    print(f"\n  original total size:  {len(orig):,}")
    print(f"  rebuilt total size:   {len(new_img):,}")
    if len(orig) > len(new_img):
        tail = orig[len(new_img):]
        nz = sum(1 for b in tail if b != 0)
        print(f"  trailing bytes in original ({len(tail):,} total): {nz:,} non-zero")
        if nz > 0:
            # Show some non-zero offsets
            for i, b in enumerate(tail):
                if b != 0:
                    print(f"    non-zero at offset 0x{len(new_img)+i:08x}: 0x{b:02X}")
                    if i > 20: break

if __name__ == '__main__':
    main()
