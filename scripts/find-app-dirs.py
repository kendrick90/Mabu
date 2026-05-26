#!/usr/bin/env python3
"""find-app-dirs.py

Find the data blocks of /system/app, /system/priv-app, /system/vendor/...,
and any other key dirs whose listings we need to enumerate APKs.

Uses existing system.img inode-table region (groups 0-15 all present).

For each directory inode we find, print:
  - inode number
  - size (dir block count)
  - extent block(s)
  - partition byte offset of each extent's data
  - whether the data is already in any existing dump, or needs to be dumped
"""
import os, struct, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SYS  = os.path.join(ROOT, 'dumps', 'system.img')

BLK         = 4096
INODE_SIZE  = 256
INODES_PER_GROUP = 8064
DESC_SIZE   = 64
GDT_START   = BLK
SYSTEM_LBA_START = 0x18A000

data = open(SYS,'rb').read()
print(f'system.img: {len(data):,} bytes (covers partition bytes 0..{len(data):,})')

def read_inode(num):
    group = (num - 1) // INODES_PER_GROUP
    idx   = (num - 1) % INODES_PER_GROUP
    gd = data[GDT_START + group*DESC_SIZE : GDT_START + group*DESC_SIZE + DESC_SIZE]
    it_lo = struct.unpack('<I', gd[8:12])[0]
    it_hi = struct.unpack('<I', gd[40:44])[0]
    it_block = (it_hi << 32) | it_lo
    off = it_block * BLK + idx * INODE_SIZE
    if off + INODE_SIZE > len(data):
        return None
    return data[off:off + INODE_SIZE]

def extents(ino):
    flags = struct.unpack('<I', ino[32:36])[0]
    iblock = ino[40:40+60]
    if not (flags & 0x80000): return None
    eh_magic, eh_entries, _, eh_depth, _ = struct.unpack('<HHHHI', iblock[0:12])
    if eh_magic != 0xF30A: return None
    out = []
    if eh_depth == 0:
        for k in range(eh_entries):
            o = 12 + k*12
            eb, el, sh, sl = struct.unpack('<IHHI', iblock[o:o+12])
            out.append(('leaf', eb, el, (sh<<32)|sl))
    else:
        for k in range(eh_entries):
            o = 12 + k*12
            ei, ll, lh, _ = struct.unpack('<IIHH', iblock[o:o+12])
            out.append(('idx', ei, (lh<<32)|ll))
    return out

# Known dirs from root listing
TARGETS = [
    (320,  '/system/app'),
    (1060, '/system/priv-app'),
    (1277, '/system/vendor (symlink, skipping)'),
    (1285, '/system/framework'),
    (1278, '/system/fake-libs'),
]

# Other dumps we have on disk — to check coverage
COVERAGE = [
    ('system.img',                  0,           len(data)),
    ('system-etc-below.img',        230*1024*1024,    240*1024*1024 + 1024*1024),     # 220..230 MiB
    ('system-etc-region.img',       230*1024*1024,    246*1024*1024),                 # 230..246 MiB (actually 230..246, but combined is 220..246)
    ('system-etc-combined.img',     220*1024*1024,    246*1024*1024),
]
# Simpler coverage list (start_byte, end_byte) within partition:
ranges = [
    (0,                        len(data)),         # system.img
    (220*1024*1024,            246*1024*1024 + 1024*1024),  # combined etc
]

def covered(byte_off):
    for s,e in ranges:
        if s <= byte_off < e:
            return True
    return False

for ino_num, label in TARGETS:
    print(f'\n--- {label} (inode {ino_num}) ---')
    ib = read_inode(ino_num)
    if ib is None:
        print('  inode outside dump?'); continue
    mode = struct.unpack('<H', ib[0:2])[0]
    sz   = struct.unpack('<I', ib[4:8])[0]
    print(f'  mode=0o{mode:o}  size={sz:,} bytes')
    ex = extents(ib)
    if not ex:
        print('  no extents (or other layout)'); continue
    for e in ex:
        if e[0] == 'leaf':
            _, lb, ln, pb = e
            for b in range(ln):
                pblk = pb + b
                byte_off = pblk * BLK
                abs_sec = (SYSTEM_LBA_START * 512 + byte_off) // 512
                in_dump = covered(byte_off)
                print(f'    block {pblk} -> partition_byte {byte_off:,} ({byte_off/1024/1024:.2f} MiB)  abs_LBA={abs_sec:,} (0x{abs_sec:X})  in_dump={in_dump}')
        else:
            _, lb, leaf = e
            byte_off = leaf * BLK
            print(f'    INDEX -> leaf block {leaf} at partition_byte {byte_off:,}')
