#!/usr/bin/env python3
"""Build a 512-byte sector patch that flips the three prop bytes in
/system/etc/prop.default.

Source: system-etc-combined.img (already on disk).
Output: dumps/prop-sector-original.bin  -- exact current sector content
        dumps/prop-sector-patched.bin   -- with 3 bytes flipped

Then we hand prop-sector-patched.bin to rkdeveloptool:
    rkdeveloptool wl 2079264 dumps/prop-sector-patched.bin
"""
import os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC  = os.path.join(ROOT, 'dumps', 'system-etc-combined.img')
ORIG = os.path.join(ROOT, 'dumps', 'prop-sector-original.bin')
PATCH= os.path.join(ROOT, 'dumps', 'prop-sector-patched.bin')

REGION_START_BYTE_IN_PART = 220 * 1024 * 1024
SECTOR_BYTE_IN_PART       = 238_305_280     # partition byte of first sector of prop.default block
ABS_LBA                   = 2_079_264       # corresponds; for reference

local_off = SECTOR_BYTE_IN_PART - REGION_START_BYTE_IN_PART
with open(SRC, 'rb') as f:
    f.seek(local_off)
    sector = f.read(512)
assert len(sector) == 512, f'short read: {len(sector)}'

with open(ORIG, 'wb') as f:
    f.write(sector)

# Expected current bytes
checks = [
    (36,  b'ro.secure=1',     46,  b'1', b'0', 'ro.secure'),
    (71,  b'ro.adb.secure=1', 85,  b'1', b'0', 'ro.adb.secure'),
    (112, b'ro.debuggable=0', 126, b'0', b'1', 'ro.debuggable'),
]

# Verify pre-patch state
print('Pre-patch verification:')
ok = True
for line_start, line_bytes, flip_off, cur, new, name in checks:
    actual_line = sector[line_start:line_start+len(line_bytes)]
    actual_byte = sector[flip_off:flip_off+1]
    print(f'  {name}: line@{line_start}={actual_line!r}, byte@{flip_off}={actual_byte!r} (expect {cur!r})')
    if actual_line != line_bytes or actual_byte != cur:
        print(f'    MISMATCH!')
        ok = False
if not ok:
    print('Aborting: source bytes do not match expectations.')
    sys.exit(1)

patched = bytearray(sector)
for _, _, flip_off, _, new, _ in checks:
    patched[flip_off:flip_off+1] = new

with open(PATCH, 'wb') as f:
    f.write(bytes(patched))

print('\nPatched sector written:')
print(f'  original: {ORIG}')
print(f'  patched : {PATCH}')

# Show the patched lines for visual confirmation
print('\nPatched content (first 200 bytes):')
print(bytes(patched[:200]).decode('latin1'))

print(f'\nWrite command:')
print(f'  rkdeveloptool.exe wl {ABS_LBA} {PATCH}')
