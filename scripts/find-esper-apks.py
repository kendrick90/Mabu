#!/usr/bin/env python3
"""For each Esper directory inode, list its contents and find the .apk
file(s). Print the inode, size, and physical extent for the APK so we
can plan a targeted patch to its zip header."""
import os, struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SYS  = open(os.path.join(ROOT,'firmware','system-probes','system.img'),'rb').read()

BLK = 4096
INODE_SIZE = 256
INODES_PER_GROUP = 8064
DESC_SIZE = 64
GDT_START = BLK
SYSTEM_LBA_START = 0x18A000

REGIONS = []
for fname, start in [
    ('system.img', 0),
    ('system-app-region.img', 41943040),
    ('system-privapp-region.img', 257949696),
    ('system-etc-below.img', 230686720),
    ('system-etc-region.img', 241172480),
]:
    p = os.path.join(ROOT,'firmware','system-probes',fname)
    if os.path.exists(p):
        REGIONS.append((fname, start, open(p,'rb').read()))

def read_block(part_byte):
    for tag, start, buf in REGIONS:
        if start <= part_byte < start + len(buf):
            local = part_byte - start
            if local + BLK <= len(buf):
                return buf[local:local+BLK]
    return None

def read_inode(num):
    group = (num-1)//INODES_PER_GROUP
    idx   = (num-1)%INODES_PER_GROUP
    gd = SYS[GDT_START + group*DESC_SIZE : GDT_START + group*DESC_SIZE + DESC_SIZE]
    it_lo = struct.unpack('<I', gd[8:12])[0]
    it_hi = struct.unpack('<I', gd[40:44])[0]
    it_block = (it_hi<<32)|it_lo
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

ESPER_DIRS = [
    (441, 'espersupervisor'),
    (475, 'esperdpc'),
    (518, 'esperhelper'),
]

results = []

for ino_num, label in ESPER_DIRS:
    print(f'\n===== /system/app/{label} (inode {ino_num}) =====')
    ib = read_inode(ino_num)
    if ib is None:
        print('  inode not in dump'); continue
    sz = struct.unpack('<I', ib[4:8])[0]
    print(f'  dir size={sz}')
    ex = extents(ib)
    if not ex:
        print('  no extents'); continue
    children = []
    for e in ex:
        if e[0] != 'leaf': continue
        _, lb, ln, pb = e
        for b in range(ln):
            pb_byte = (pb+b)*BLK
            print(f'    dir block @ partition_byte {pb_byte:,} ({pb_byte/1024/1024:.2f} MiB)')
            blk = read_block(pb_byte)
            if blk is None:
                print(f'      BLOCK NOT IN ANY DUMP'); continue
            es = parse_dir_block(blk)
            for cino, cname, cft in es:
                if cname in ('.','..'): continue
                children.append((cino, cname, cft))
                print(f'      inode={cino:6d} ftype={cft} {cname}')

    # Find APK files among children
    for cino, cname, cft in children:
        if cft == 1 and cname.lower().endswith('.apk'):
            apk_ino = read_inode(cino)
            if apk_ino is None:
                print(f'  APK {cname} inode {cino} out of dump'); continue
            asz = struct.unpack('<I', apk_ino[4:8])[0]
            aex = extents(apk_ino)
            print(f'  >>> APK: {cname}  inode={cino}  size={asz:,} bytes')
            first_block = None
            if aex:
                for ae in aex:
                    if ae[0] == 'leaf':
                        _, lb, ln, pb = ae
                        if first_block is None and lb == 0:
                            first_block = pb
                        print(f'      extent: logical={lb} len={ln} physical={pb} (byte {pb*BLK:,} = {pb*BLK/1024/1024:.2f} MiB)')
                    else:
                        print(f'      INDEX extent (depth>0): {ae}')
            if first_block is not None:
                first_byte_in_part = first_block * BLK
                abs_lba = (SYSTEM_LBA_START * 512 + first_byte_in_part) // 512
                results.append((label, cname, abs_lba, first_block, asz))
                print(f'      FIRST BLOCK: partition_byte {first_byte_in_part:,}, abs_LBA={abs_lba:,} (0x{abs_lba:X})')

print('\n\n=== SUMMARY (target APKs) ===')
for label, name, lba, blk, sz in results:
    print(f'  {label}/{name}: abs_LBA={lba} (0x{lba:X})  size={sz:,}')
