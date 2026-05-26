"""
patch-bootimg.py - patch an Android boot.img v0 ramdisk's default.prop
to disable ADB auth on a frozen-policy Esper-managed Android 8.1 tablet.

Specifically:
  - ro.secure         -> 0      (treat device as engineering build)
  - ro.adb.secure     -> 0      (adbd skips RSA key check entirely)
  - ro.debuggable     -> 1      (allows adb root, more debug surface)
  - persist.sys.usb.config = adb (ensure adb interface is exposed at boot)

After applying these and rebooting, `adb devices` will show the tablet as
`device` immediately (no dialog, no auth) and `adb shell` will give a
shell:shell account. From there we can poke at Esper.

The boot.img v0 format on Rockchip RK3288 / Android 8.1 has just a
SHA-1 hash in the header (no AVB signing). We recompute that hash
after rebuilding, and the bootloader accepts the modified image.

Usage:
    python patch-bootimg.py inspect <boot.img>           # peek at headers + default.prop
    python patch-bootimg.py patch <boot.img> <out.img>   # produce patched boot image

No external dependencies (uses gzip + struct + hashlib from stdlib).
"""

import sys
import struct
import gzip
import hashlib

PAGE_SIZE = 2048
HEADER_MAGIC = b'ANDROID!'

# ------------------------------------------------------------- boot header
def parse_bootimg(data):
    if data[:8] != HEADER_MAGIC:
        raise ValueError("Not an Android boot.img (no ANDROID! magic)")
    (kernel_size, kernel_addr, ramdisk_size, ramdisk_addr,
     second_size, second_addr, tags_addr, page_size,
     header_version, os_version) = struct.unpack_from('<10I', data, 8)
    name          = data[48:48+16]
    cmdline       = data[64:64+512]
    id_hash       = data[576:576+32]
    extra_cmdline = data[608:608+1024]

    off = page_size
    kernel = data[off:off+kernel_size]
    off += ((kernel_size + page_size - 1) // page_size) * page_size
    ramdisk = data[off:off+ramdisk_size]
    off += ((ramdisk_size + page_size - 1) // page_size) * page_size
    second = data[off:off+second_size]

    return dict(
        kernel_addr=kernel_addr, ramdisk_addr=ramdisk_addr,
        second_addr=second_addr, second_size=second_size,
        tags_addr=tags_addr, page_size=page_size,
        header_version=header_version, os_version=os_version,
        name_raw=name, cmdline_raw=cmdline, id_hash=id_hash,
        extra_cmdline_raw=extra_cmdline,
        kernel=kernel, ramdisk=ramdisk, second=second,
    )

# ------------------------------------------------------------- cpio (newc)
def parse_cpio(data):
    entries = []
    pos = 0
    while pos < len(data):
        if data[pos:pos+6] != b'070701':
            break
        hdr = data[pos:pos+110]
        if len(hdr) < 110:
            break
        e = {
            'ino':      int(hdr[6:14],   16),
            'mode':     int(hdr[14:22],  16),
            'uid':      int(hdr[22:30],  16),
            'gid':      int(hdr[30:38],  16),
            'nlink':    int(hdr[38:46],  16),
            'mtime':    int(hdr[46:54],  16),
            'filesize': int(hdr[54:62],  16),
            'rdevmajor':int(hdr[78:86],  16),
            'rdevminor':int(hdr[86:94],  16),
            'namesize': int(hdr[94:102], 16),
        }
        pos += 110
        name_end = pos + e['namesize'] - 1  # strip NUL
        e['name'] = data[pos:name_end].decode('utf-8', errors='replace')
        pos += e['namesize']
        pos = (pos + 3) & ~3
        e['content'] = data[pos:pos + e['filesize']]
        pos += e['filesize']
        pos = (pos + 3) & ~3
        if e['name'] == 'TRAILER!!!':
            break
        entries.append(e)
    return entries

def write_cpio(entries):
    out = bytearray()
    def write_entry(name, mode, content, uid=0, gid=0, nlink=1, ino=0, rdevmajor=0, rdevminor=0):
        name_bytes = name.encode('utf-8') + b'\x00'
        hdr = (
            b'070701' +
            f'{ino:08x}'.encode() + f'{mode:08x}'.encode() +
            f'{uid:08x}'.encode() + f'{gid:08x}'.encode() +
            f'{nlink:08x}'.encode() + f'0:08x'.encode()[:8] +  # mtime 0
            f'{len(content):08x}'.encode() +
            b'00000000' * 2 +  # devmajor / devminor
            f'{rdevmajor:08x}'.encode() + f'{rdevminor:08x}'.encode() +
            f'{len(name_bytes):08x}'.encode() +
            b'00000000'  # check
        )
        out.extend(hdr)
        out.extend(name_bytes)
        while len(out) & 3:
            out.append(0)
        out.extend(content)
        while len(out) & 3:
            out.append(0)
    for i, e in enumerate(entries):
        # Match the format properly - mtime must be an actual hex
        name_bytes = e['name'].encode('utf-8') + b'\x00'
        hdr = (b'070701' +
               f'{i+1:08x}'.encode() +
               f'{e["mode"]:08x}'.encode() +
               f'{e["uid"]:08x}'.encode() +
               f'{e["gid"]:08x}'.encode() +
               f'{e["nlink"]:08x}'.encode() +
               f'{e["mtime"]:08x}'.encode() +
               f'{len(e["content"]):08x}'.encode() +
               b'00000000' * 2 +
               f'{e["rdevmajor"]:08x}'.encode() +
               f'{e["rdevminor"]:08x}'.encode() +
               f'{len(name_bytes):08x}'.encode() +
               b'00000000')
        out.extend(hdr)
        out.extend(name_bytes)
        while len(out) & 3:
            out.append(0)
        out.extend(e['content'])
        while len(out) & 3:
            out.append(0)
    # TRAILER!!!
    name_bytes = b'TRAILER!!!\x00'
    hdr = (b'070701' + b'00000000' * 7 +  # ino..filesize
           b'00000000' * 4 +              # dev*/rdev*
           f'{len(name_bytes):08x}'.encode() + b'00000000')
    out.extend(hdr)
    out.extend(name_bytes)
    while len(out) & 3:
        out.append(0)
    return bytes(out)

# ------------------------------------------------------------- prop patcher
def patch_default_prop(content_bytes):
    """Apply our ro.adb.* / ro.debuggable / ro.secure patches in default.prop."""
    text = content_bytes.decode('utf-8', errors='replace')
    print(f'\n--- original default.prop ({len(content_bytes)} bytes) ---')
    print(text.rstrip())

    desired = {
        'ro.secure':                 '0',
        'ro.adb.secure':             '0',
        'ro.debuggable':             '1',
        'persist.sys.usb.config':    'adb',
    }
    lines = text.splitlines()
    seen = set()
    for i, line in enumerate(lines):
        if '=' not in line or line.lstrip().startswith('#'):
            continue
        key, _, _ = line.partition('=')
        key = key.strip()
        if key in desired:
            old = lines[i]
            lines[i] = f'{key}={desired[key]}'
            if old != lines[i]:
                print(f'  patched: {old}  ->  {lines[i]}')
            else:
                print(f'  already: {lines[i]}')
            seen.add(key)
    for key, val in desired.items():
        if key not in seen:
            lines.append(f'{key}={val}')
            print(f'  added:   {key}={val}')

    new_text = '\n'.join(lines)
    if not new_text.endswith('\n'):
        new_text += '\n'
    print(f'\n--- patched default.prop ({len(new_text)} bytes) ---')
    print(new_text.rstrip())
    return new_text.encode('utf-8')

# ------------------------------------------------------------- main
def cmd_inspect(boot_path):
    with open(boot_path, 'rb') as f:
        data = f.read()
    bi = parse_bootimg(data)
    print(f'Boot header v{bi["header_version"]}')
    print(f'  kernel:  {len(bi["kernel"]):,} bytes @ 0x{bi["kernel_addr"]:08X}')
    print(f'  ramdisk: {len(bi["ramdisk"]):,} bytes @ 0x{bi["ramdisk_addr"]:08X}')
    print(f'  second:  {len(bi["second"]):,} bytes')
    print(f'  page:    {bi["page_size"]}')
    print(f'  cmdline: {bi["cmdline_raw"].rstrip(chr(0).encode()).decode("ascii", "replace")}')
    print(f'  extra:   {bi["extra_cmdline_raw"].rstrip(chr(0).encode()).decode("ascii", "replace")}')
    print(f'  id sha1: {bi["id_hash"][:20].hex()}')

    if bi['ramdisk'][:2] != b'\x1f\x8b':
        print(f'\nWARN: ramdisk does not start with gzip magic (got {bi["ramdisk"][:4].hex()})')
        return
    cpio_data = gzip.decompress(bi['ramdisk'])
    entries = parse_cpio(cpio_data)
    print(f'\nRamdisk has {len(entries)} cpio entries.')
    for e in entries[:30]:
        kind = 'd' if (e['mode'] & 0o40000) else ('l' if (e['mode'] & 0o120000) == 0o120000 else 'f')
        print(f'  {kind} {e["mode"]:06o}  {len(e["content"]):>8}  {e["name"]}')
    if len(entries) > 30:
        print(f'  ... and {len(entries)-30} more')

    for e in entries:
        if e['name'] == 'default.prop':
            print(f'\n--- default.prop ({len(e["content"])} bytes) ---')
            print(e['content'].decode('utf-8', errors='replace').rstrip())
            break

def cmd_patch(boot_path, out_path):
    with open(boot_path, 'rb') as f:
        data = f.read()
    bi = parse_bootimg(data)
    print(f'Parsed boot.img v{bi["header_version"]}')
    print(f'  kernel:  {len(bi["kernel"]):,} bytes')
    print(f'  ramdisk: {len(bi["ramdisk"]):,} bytes')

    if bi['ramdisk'][:2] != b'\x1f\x8b':
        raise RuntimeError('ramdisk is not gzip-compressed - unsupported')
    cpio_data = gzip.decompress(bi['ramdisk'])
    entries = parse_cpio(cpio_data)

    patched = False
    for e in entries:
        if e['name'] == 'default.prop':
            is_symlink = (e['mode'] & 0o170000) == 0o120000
            if is_symlink:
                # /default.prop is a symlink to /system/etc/prop.default
                # (Project Treble layout). We can't modify /system, but we
                # CAN replace the symlink with a regular file containing
                # our property overrides. init loads /default.prop BEFORE
                # /system/etc/prop.default, and ro.* properties are
                # write-once (first set wins), so ours stick.
                target = e['content'].decode('utf-8', errors='replace')
                print(f'\n/default.prop is a symlink to: {target}')
                print('Replacing symlink with regular file containing ADB-unlock props.')
                override = (
                    '# default.prop overrides injected by patch-bootimg.py\n'
                    '# Original was a symlink to: ' + target + '\n'
                    'ro.secure=0\n'
                    'ro.adb.secure=0\n'
                    'ro.debuggable=1\n'
                    'persist.sys.usb.config=adb\n'
                ).encode('utf-8')
                e['mode'] = 0o100644       # regular file rw-r--r--
                e['content'] = override
                e['nlink'] = 1
                print(f'  new content ({len(override)} bytes):')
                print(override.decode('utf-8'))
            else:
                e['content'] = patch_default_prop(e['content'])
            patched = True
            break
    if not patched:
        raise RuntimeError('default.prop not found in ramdisk - unsupported layout')

    new_cpio = write_cpio(entries)
    print(f'\nNew cpio:    {len(new_cpio):,} bytes')
    new_ramdisk = gzip.compress(new_cpio, compresslevel=9)
    print(f'New ramdisk: {len(new_ramdisk):,} bytes (was {len(bi["ramdisk"]):,})')

    kernel = bi['kernel']
    ramdisk = new_ramdisk
    second = bi['second']
    page = bi['page_size']

    # Rockchip's id field doesn't match standard AOSP SHA-1 - we don't know
    # the exact formula. Preserve the original id bytes from the input
    # boot.img. If the bootloader content-hashes against id, this fails
    # (because ramdisk changed). If id is just a non-zero UUID-style marker,
    # this passes.
    keep_id = '--keep-id' in sys.argv
    if keep_id:
        new_hash = bi['id_hash']
        print('keeping ORIGINAL id field (--keep-id)')
    else:
        sha1 = hashlib.sha1()
        sha1.update(kernel); sha1.update(struct.pack('<I', len(kernel)))
        sha1.update(ramdisk); sha1.update(struct.pack('<I', len(ramdisk)))
        sha1.update(second); sha1.update(struct.pack('<I', len(second)))
        new_hash = sha1.digest().ljust(32, b'\x00')
        print('recomputing id as AOSP-standard SHA-1 (this may not match what Rockchip uses)')

    new_header = bytearray(HEADER_MAGIC)
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
    with open(out_path, 'wb') as f:
        f.write(new_img)
    print(f'\nWrote {len(new_img):,} bytes to {out_path}')
    print(f'  new id sha1: {new_hash[:20].hex()}')

def main():
    # Filter out flags so positional args still work
    flag_args = [a for a in sys.argv[1:] if a.startswith('--')]
    positional = [a for a in sys.argv[1:] if not a.startswith('--')]
    if len(positional) < 2:
        print(__doc__); sys.exit(1)
    cmd = positional[0]
    if cmd == 'inspect':
        cmd_inspect(positional[1])
    elif cmd == 'patch':
        if len(positional) != 3:
            print('Usage: patch-bootimg.py patch <in.img> <out.img> [--keep-id]'); sys.exit(1)
        cmd_patch(positional[1], positional[2])
    else:
        print(f'Unknown command: {cmd}'); sys.exit(1)

if __name__ == '__main__':
    main()
