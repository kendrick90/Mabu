#!/usr/bin/env python3
"""Parse /system/app and /system/priv-app dirent blocks and surface any
package directory that looks like Esper / MDM / kiosk / device owner.

We dumped:
  system-app-region.img      : partition bytes 41,943,040 .. 54,525,952  (40..52 MiB)
  system-privapp-region.img  : partition bytes 257,949,696 .. 274,726,912 (246..262 MiB)
"""
import os, struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SYS  = open(os.path.join(ROOT,'firmware','system-probes','system.img'),'rb').read()
APP  = open(os.path.join(ROOT,'firmware','system-probes','system-app-region.img'),'rb').read()
PRIV = open(os.path.join(ROOT,'firmware','system-probes','system-privapp-region.img'),'rb').read()

BLK = 4096
INODE_SIZE = 256
INODES_PER_GROUP = 8064
DESC_SIZE = 64
GDT_START = BLK

REGIONS = [
    ('system',  0,           SYS),
    ('app',     41943040,    APP),
    ('priv',    257949696,   PRIV),
    ('etc-below',  230686720, open(os.path.join(ROOT,'firmware','system-probes','system-etc-below.img'),'rb').read()),
    ('etc-region', 241172480, open(os.path.join(ROOT,'firmware','system-probes','system-etc-region.img'),'rb').read()),
]

def read_block(part_byte):
    for tag, start, buf in REGIONS:
        if start <= part_byte < start + len(buf):
            local = part_byte - start
            if local + BLK <= len(buf):
                return buf[local:local+BLK]
    return None

def read_inode(num):
    group = (num-1) // INODES_PER_GROUP
    idx = (num-1) % INODES_PER_GROUP
    gd = SYS[GDT_START + group*DESC_SIZE : GDT_START + group*DESC_SIZE + DESC_SIZE]
    it_lo = struct.unpack('<I', gd[8:12])[0]
    it_hi = struct.unpack('<I', gd[40:44])[0]
    it_block = (it_hi<<32) | it_lo
    off = it_block*BLK + idx*INODE_SIZE
    if off + INODE_SIZE > len(SYS): return None
    return SYS[off:off+INODE_SIZE]

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

def parse_dir_block(blk):
    es = []
    pos = 0
    while pos < len(blk):
        if pos + 8 > len(blk): break
        ino = struct.unpack('<I', blk[pos:pos+4])[0]
        rl = struct.unpack('<H', blk[pos+4:pos+6])[0]
        nl = blk[pos+6]; ft = blk[pos+7]
        if rl == 0: break
        if 0 < nl <= rl-8:
            name = blk[pos+8:pos+8+nl].decode('latin1','replace')
            if ino != 0:
                es.append((ino, name, ft))
        pos += rl
    return es

def list_dir(dir_inode_num):
    ib = read_inode(dir_inode_num)
    if ib is None: return None
    ex = extents(ib)
    if not ex: return None
    entries = []
    for e in ex:
        if e[0] != 'leaf': continue
        _, lb, ln, pb = e
        for b in range(ln):
            pb_byte = (pb+b) * BLK
            blk = read_block(pb_byte)
            if blk is None:
                return ('missing', pb_byte)
            entries.extend(parse_dir_block(blk))
    return entries

# /system/app = inode 320
# /system/priv-app = inode 1060

for name, ino in [('app', 320), ('priv-app', 1060)]:
    print(f'\n===== /system/{name} (inode {ino}) =====')
    listing = list_dir(ino)
    if listing is None:
        print('  could not parse'); continue
    if isinstance(listing, tuple) and listing[0] == 'missing':
        print(f'  dir block not in dump: partition_byte {listing[1]:,}')
        continue
    print(f'  {len(listing)} entries:')
    # Skip . and ..; show all
    suspect_keys = ('esper','shoonya','kiosk','mdm','catalia','mabu','owner','dpc','enterprise','remote','device')
    for ino_n, ename, ft in listing:
        flag = '  <<< SUSPECT' if any(k in ename.lower() for k in suspect_keys) else ''
        print(f'    inode={ino_n:6d} ftype={ft} {ename}{flag}')
