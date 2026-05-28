#!/usr/bin/env python3
"""For each Esper APK, compute the absolute LBA of the sector containing
the EOCD (End Of Central Directory) record near the file's end.

We then dump that sector and locate the PK\\x05\\x06 magic inside it."""
import os, struct, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SYS  = open(os.path.join(ROOT,'firmware','system-probes','system.img'),'rb').read()

BLK = 4096
INODE_SIZE = 256
INODES_PER_GROUP = 8064
DESC_SIZE = 64
GDT_START = BLK
SYSTEM_LBA_START = 0x18A000

def read_inode(num):
    group = (num-1)//INODES_PER_GROUP
    idx = (num-1)%INODES_PER_GROUP
    gd = SYS[GDT_START + group*DESC_SIZE : GDT_START + group*DESC_SIZE + DESC_SIZE]
    it_lo = struct.unpack('<I', gd[8:12])[0]
    it_hi = struct.unpack('<I', gd[40:44])[0]
    it_block = (it_hi<<32)|it_lo
    off = it_block*BLK + idx*INODE_SIZE
    if off+INODE_SIZE > len(SYS): return None
    return SYS[off:off+INODE_SIZE]

def extents(ino):
    iblock = ino[40:40+60]
    eh_magic, eh_entries, _, eh_depth, _ = struct.unpack('<HHHHI', iblock[0:12])
    if eh_magic != 0xF30A or eh_depth != 0: return []
    out = []
    for k in range(eh_entries):
        o = 12 + k*12
        eb, el, sh, sl = struct.unpack('<IHHI', iblock[o:o+12])
        out.append((eb, el, (sh<<32)|sl))
    return out

# (apk_inode_num, label, expected_size)
TARGETS = [
    (446, 'espersupervisor.apk',  609374),
    (480, 'esperdpc.apk',         24663356),
    (523, 'esperhelper.apk',      3775346),
]

# For each, locate the sector containing the last byte.
# EOCD is at file_end - 22 (no comment) up to file_end - 22 - 65535 (with max comment).
# We'll dump a small window around the end and find PK\x05\x06.

print('APK end-sector computations:\n')
print(f'{"label":24s} {"size":>10s} {"last_byte":>10s} {"last_block":>8s} {"phys_blk":>9s} {"sec_in_blk":>11s} {"abs_LBA":>10s} {"abs_LBA_hex":>14s}')
for ino, label, expected_sz in TARGETS:
    ib = read_inode(ino)
    sz = struct.unpack('<I', ib[4:8])[0]
    assert sz == expected_sz, f'size mismatch {sz} vs {expected_sz}'
    ex = extents(ib)
    last_byte = sz - 1
    last_logical_block = last_byte // BLK
    byte_in_block = last_byte % BLK
    sec_in_block = byte_in_block // 512

    # Find which extent contains last_logical_block
    phys_blk = None
    for eb, el, pb in ex:
        if eb <= last_logical_block < eb + el:
            phys_blk = pb + (last_logical_block - eb)
            break
    abs_lba = SYSTEM_LBA_START + phys_blk * 8 + sec_in_block
    print(f'{label:24s} {sz:>10d} {last_byte:>10d} {last_logical_block:>8d} {phys_blk:>9d} {sec_in_block:>11d} {abs_lba:>10d} 0x{abs_lba:08X}')

    # Note: EOCD spans 22 bytes minimum. If it straddles into earlier sector
    # we need to fetch the previous sector too. Print start_sec for safety:
    # We'll dump 2 sectors (1 KB) ending at the file's last sector.
EOF
print('\nTo find EOCD, dump these end-sectors and grep for PK\\x05\\x06')
