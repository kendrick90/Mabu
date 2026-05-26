#!/usr/bin/env python3
"""find-prop-default.py

Uses the existing system.img (start of partition, inode tables intact)
and the new system-etc-region.img (16 MB around /etc dirent) to:

  1. Parse the /etc directory block (in etc-region) to find prop.default's
     inode number.
  2. Look up that inode in the inode-table region of system.img.
  3. Resolve its extent(s) to a physical block / byte offset in the
     partition.
  4. If the data block is inside system-etc-region, dump its contents
     and search for ro.adb.secure / ro.secure / ro.debuggable.

Outputs the byte offset (within the /system partition) of each interesting
prop string, plus the sector + absolute LBA needed for a targeted write.
"""

import os, struct, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SYSTEM_IMG       = os.path.join(ROOT, 'dumps', 'system.img')
ETC_REGION_IMG   = os.path.join(ROOT, 'dumps', 'system-etc-combined.img')

# /system partition (absolute) and the etc-region's start *within* /system
SYSTEM_LBA_START  = 0x18A000                 # absolute LBA where /system begins
ETC_REGION_START_BYTE_IN_PART = 220 * 1024 * 1024   # combined: 220 MiB .. 246 MiB

BLK         = 4096
INODE_SIZE  = 256
INODES_PER_GROUP = 8064
BLOCKS_PER_GROUP = 32768
GDT_START   = BLK            # block 1

ETC_INODE   = 582            # found earlier from root-dir listing


def load(p):
    with open(p, 'rb') as f:
        return f.read()


def read_inode(sysdata, num):
    """Look up inode `num` from the inode-table region inside system.img.

    flex_bg packs every group's inode table near the start of the partition,
    so all 16 inode tables are inside the first ~34 MB dump."""
    group = (num - 1) // INODES_PER_GROUP
    idx_in_group = (num - 1) % INODES_PER_GROUP
    gd = sysdata[GDT_START + group * 64 : GDT_START + group * 64 + 64]
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
    if not (flags & 0x80000):
        return None  # not extents-based
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


def parse_dir_block(blk):
    entries = []
    pos = 0
    while pos < len(blk):
        if pos + 8 > len(blk):
            break
        inode_no = struct.unpack('<I', blk[pos:pos + 4])[0]
        rec_len  = struct.unpack('<H', blk[pos + 4:pos + 6])[0]
        name_len = blk[pos + 6]
        ftype    = blk[pos + 7]
        if rec_len == 0:
            break
        if 0 < name_len <= rec_len - 8:
            name = blk[pos + 8:pos + 8 + name_len].decode('latin1', 'replace')
            if inode_no != 0:
                entries.append((inode_no, name, ftype))
        pos += rec_len
    return entries


def main():
    if not os.path.exists(ETC_REGION_IMG):
        print(f'NOT FOUND: {ETC_REGION_IMG}')
        sys.exit(1)
    sysdata = load(SYSTEM_IMG)
    etcdata = load(ETC_REGION_IMG)
    print(f'system.img         : {len(sysdata):,} bytes')
    print(f'system-etc-region  : {len(etcdata):,} bytes, covers partition bytes {ETC_REGION_START_BYTE_IN_PART:,} .. {ETC_REGION_START_BYTE_IN_PART + len(etcdata):,}')

    # Look up /etc inode (582) extents
    etc_ino, etc_off = read_inode(sysdata, ETC_INODE)
    if etc_ino is None:
        print(f'/etc inode 582 outside dump (offset {etc_off})')
        sys.exit(1)
    etc_size = struct.unpack('<I', etc_ino[4:8])[0]
    extents = parse_extents(etc_ino)
    print(f'/etc inode 582: size={etc_size}, extents={extents}')
    if not extents:
        print('Could not parse /etc extents')
        sys.exit(1)

    # Read /etc dirent block(s) from etc-region
    etc_entries = []
    for lb, ln, pb in extents:
        for b in range(ln):
            byte_in_part = (pb + b) * BLK
            if not (ETC_REGION_START_BYTE_IN_PART <= byte_in_part < ETC_REGION_START_BYTE_IN_PART + len(etcdata)):
                print(f'  dir block at partition byte {byte_in_part:,} OUTSIDE etc-region')
                continue
            local_off = byte_in_part - ETC_REGION_START_BYTE_IN_PART
            blk = etcdata[local_off:local_off + BLK]
            es = parse_dir_block(blk)
            print(f'  dir block @part_byte {byte_in_part:,} (region_off 0x{local_off:X}): {len(es)} entries')
            etc_entries.extend(es)

    # Find prop.default and friends
    print('\n/etc top-level entries of interest:')
    targets = []
    for ino, name, ft in etc_entries:
        if name in ('prop.default', 'default.prop', 'build.prop'):
            print(f'  {name}: inode={ino}, ftype={ft}')
            targets.append((name, ino))

    # Also show what we have in /etc (for orientation)
    print(f'\nAll {len(etc_entries)} /etc entries (first 80):')
    for ino, name, ft in etc_entries[:80]:
        print(f'  {name:40s} inode={ino:6d} ftype={ft}')

    # For each target file, look up its inode, get extents, read content
    for name, ino_no in targets:
        print(f'\n=== {name} (inode {ino_no}) ===')
        ino_bytes, ino_off = read_inode(sysdata, ino_no)
        if ino_bytes is None:
            print(f'  inode {ino_no} outside dump (would need byte offset {ino_off})')
            continue
        size = struct.unpack('<I', ino_bytes[4:8])[0]
        exts = parse_extents(ino_bytes)
        print(f'  size={size}, extents={exts}')
        if not exts:
            continue
        # Try to fetch contents
        contents = b''
        for lb, ln, pb in exts:
            for b in range(ln):
                byte_in_part = (pb + b) * BLK
                if ETC_REGION_START_BYTE_IN_PART <= byte_in_part < ETC_REGION_START_BYTE_IN_PART + len(etcdata):
                    local_off = byte_in_part - ETC_REGION_START_BYTE_IN_PART
                    contents += etcdata[local_off:local_off + BLK]
                    print(f'  block at part_byte {byte_in_part:,} -> got from etc-region')
                else:
                    # try original system.img (small chance)
                    if byte_in_part + BLK <= len(sysdata):
                        contents += sysdata[byte_in_part:byte_in_part + BLK]
                        print(f'  block at part_byte {byte_in_part:,} -> got from system.img')
                    else:
                        print(f'  block at part_byte {byte_in_part:,} -> NOT IN ANY DUMP')
        if contents:
            contents = contents[:size]
            # save for inspection
            outp = os.path.join(ROOT, 'dumps', f'{name}.bin')
            with open(outp, 'wb') as f:
                f.write(contents)
            print(f'  saved {len(contents)} bytes to {outp}')
            # search for adb/secure props
            for needle in (b'ro.adb.secure', b'ro.secure', b'ro.debuggable', b'ro.build.tags', b'ro.build.type'):
                i = contents.find(needle)
                if i >= 0:
                    line_end = contents.find(b'\n', i)
                    line = contents[i:line_end if line_end > 0 else min(i+80, len(contents))]
                    # offset within partition for the start of this line/needle:
                    file_first_block = exts[0][2]
                    part_byte_of_needle = file_first_block * BLK + i
                    abs_sector = (SYSTEM_LBA_START * 512 + part_byte_of_needle) // 512
                    print(f'  HIT {needle.decode()}: line={line!r}')
                    print(f'       offset_in_file={i}, partition_byte={part_byte_of_needle:,}, abs_LBA={abs_sector:,} (0x{abs_sector:X})')

if __name__ == '__main__':
    main()
