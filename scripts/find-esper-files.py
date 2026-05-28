#!/usr/bin/env python3
"""find-esper-files.py

Resolve the on-disk byte / LBA locations of:
  - /system/etc/init/init.esper.rc
  - /system/bin/set-device-owner.sh

Uses inode walk over firmware/system-probes/system.img +
firmware/system-probes/system-etc-combined.img (combined covers partition
bytes 220 MiB..246 MiB) plus firmware/system-probes/system-bin-head.img
if present.
"""
import os, struct, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SYS_IMG = os.path.join(ROOT, 'firmware', 'system-probes', 'system.img')                  # first 35 MB (inode tables)
ETC_IMG = os.path.join(ROOT, 'firmware', 'system-probes', 'system-etc-combined.img')     # 220..246 MiB (covers /etc/init dirent)
BIN_IMG = os.path.join(ROOT, 'firmware', 'system-probes', 'system-bin-head.img')         # /bin region

SYS_LBA = 0x18A000

# Region base offsets within /system partition (from prior session)
ETC_BASE = 220 * 1024 * 1024  # combined starts at 220 MiB
# bin-head: 4 MB. We saw set-device-owner.sh in system.img at byte 34228480.
# system.img is the first 35 MB of /system partition. So /bin's content lives early.

BLK = 4096
INODE_SIZE = 256
INODES_PER_GROUP = 8064

def load(p):
    if not os.path.exists(p): return None
    with open(p, 'rb') as f: return f.read()

def read_inode(sysdata, num):
    group = (num - 1) // INODES_PER_GROUP
    idx_in_group = (num - 1) % INODES_PER_GROUP
    gd = sysdata[BLK + group * 64 : BLK + group * 64 + 64]
    it_lo = struct.unpack('<I', gd[8:12])[0]
    it_hi = struct.unpack('<I', gd[40:44])[0]
    it_block = (it_hi << 32) | it_lo
    off = it_block * BLK + idx_in_group * INODE_SIZE
    if off + INODE_SIZE > len(sysdata):
        return None, off
    return sysdata[off:off + INODE_SIZE], off

def parse_extents(ino_bytes):
    flags = struct.unpack('<I', ino_bytes[32:36])[0]
    iblock = ino_bytes[40:40 + 60]
    if not (flags & 0x80000): return None
    eh_magic, eh_entries, _, eh_depth, _ = struct.unpack('<HHHHI', iblock[0:12])
    if eh_magic != 0xF30A or eh_depth != 0:
        return None
    extents = []
    for k in range(eh_entries):
        o = 12 + k * 12
        ee_block, ee_len, ee_start_hi, ee_start_lo = struct.unpack('<IHHI', iblock[o:o + 12])
        pblock = (ee_start_hi << 32) | ee_start_lo
        extents.append((ee_block, ee_len, pblock))
    return extents

def parse_dir(blk):
    out = []
    pos = 0
    while pos < len(blk):
        if pos + 8 > len(blk): break
        ino = struct.unpack('<I', blk[pos:pos+4])[0]
        rec_len = struct.unpack('<H', blk[pos+4:pos+6])[0]
        name_len = blk[pos+6]
        ftype = blk[pos+7]
        if rec_len == 0: break
        if 0 < name_len <= rec_len - 8:
            name = blk[pos+8:pos+8+name_len].decode('latin1', 'replace')
            if ino != 0:
                out.append((ino, name, ftype))
        pos += rec_len
    return out

def get_block_from_dumps(part_byte, sysdata, etcdata, bindata):
    # Return BLK bytes at part_byte if any dump contains it
    if 0 <= part_byte < len(sysdata) - BLK:
        return sysdata[part_byte:part_byte+BLK]
    if etcdata and ETC_BASE <= part_byte < ETC_BASE + len(etcdata) - BLK:
        return etcdata[part_byte - ETC_BASE : part_byte - ETC_BASE + BLK]
    return None

def find_child(parent_inode, sysdata, etcdata, bindata, name):
    ino_bytes, _ = read_inode(sysdata, parent_inode)
    if ino_bytes is None: return None, None
    exts = parse_extents(ino_bytes)
    if not exts: return None, None
    for lb, ln, pb in exts:
        for b in range(ln):
            part_byte = (pb + b) * BLK
            blk = get_block_from_dumps(part_byte, sysdata, etcdata, bindata)
            if blk is None: continue
            for (ino, n, ft) in parse_dir(blk):
                if n == name:
                    return ino, ft
    return None, None

def report(label, ino_no, sysdata, etcdata, bindata):
    print(f'\n=== {label} (inode {ino_no}) ===')
    ino_bytes, _ = read_inode(sysdata, ino_no)
    if ino_bytes is None:
        print('  inode outside system.img dump'); return
    size = struct.unpack('<I', ino_bytes[4:8])[0]
    mode = struct.unpack('<H', ino_bytes[0:2])[0]
    exts = parse_extents(ino_bytes)
    print(f'  mode=0o{mode:o}  size={size} B  extents={exts}')
    if not exts: return
    for lb, ln, pb in exts:
        part_byte = pb * BLK
        abs_lba = (SYS_LBA * 512 + part_byte) // 512
        end_lba = abs_lba + ln * (BLK // 512) - 1
        print(f'  data block: part_byte={part_byte:,}  abs_LBA={abs_lba} (0x{abs_lba:X}) .. {end_lba} (0x{end_lba:X})  blocks={ln}')
        # Try to dump contents
        blk = get_block_from_dumps(part_byte, sysdata, etcdata, bindata)
        if blk:
            head = blk[:min(size, 200)]
            print(f'  content head: {head!r}')

def main():
    sysdata = load(SYS_IMG); assert sysdata, f'missing {SYS_IMG}'
    etcdata = load(ETC_IMG)
    bindata = load(BIN_IMG)
    print(f'system.img: {len(sysdata):,} B')
    print(f'etc-combined.img: {len(etcdata) if etcdata else 0:,} B  base=0x{ETC_BASE:X}')
    print(f'bin-head.img: {len(bindata) if bindata else 0:,} B')

    # Walk from /etc (inode 582) -> init -> init.esper.rc
    print('\n--- /system/etc -> init ---')
    init_ino, ft = find_child(582, sysdata, etcdata, bindata, 'init')
    print(f'/etc/init inode = {init_ino} (ftype {ft})')
    if init_ino:
        esper_ino, ft = find_child(init_ino, sysdata, etcdata, bindata, 'init.esper.rc')
        print(f'/etc/init/init.esper.rc inode = {esper_ino} (ftype {ft})')
        if esper_ino:
            report('init.esper.rc', esper_ino, sysdata, etcdata, bindata)

    # Need /system/bin inode. Root inode is 2.
    print('\n--- root -> bin -> set-device-owner.sh ---')
    bin_ino, ft = find_child(2, sysdata, etcdata, bindata, 'bin')
    print(f'/bin inode = {bin_ino} (ftype {ft})')
    if bin_ino:
        sdo_ino, ft = find_child(bin_ino, sysdata, etcdata, bindata, 'set-device-owner.sh')
        print(f'/bin/set-device-owner.sh inode = {sdo_ino} (ftype {ft})')
        if sdo_ino:
            report('set-device-owner.sh', sdo_ino, sysdata, etcdata, bindata)

if __name__ == '__main__':
    main()
