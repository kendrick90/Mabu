"""
patch-system-props.py - binary search-and-replace in a /system partition dump
to disable ADB auth.

With dm-verity disabled (via parameter file cmdline patch), modified
/system bytes will be accepted by the kernel at mount time. We only need
to change a few property values to 0/1 - same-length swaps that don't
disturb file sizes or ext4 metadata.

The targets are properties that adbd reads at startup:
  ro.adb.secure=1     -> 0    (adbd will skip RSA key check)
  ro.secure=1         -> 0    (engineering build behaviour)
  ro.debuggable=0     -> 1    (allows adb root, broader access)

These live in:
  /system/build.prop
  /system/etc/prop.default
  Possibly /vendor/build.prop (but that's a separate partition - we don't touch it here)

Usage:
    python patch-system-props.py inspect <system.img>
    python patch-system-props.py patch <in.img> <out.img>
"""
import sys
import os

# (old, new) pairs. Lengths MUST match so we don't shift any byte offsets.
PATCHES = [
    (b'ro.adb.secure=1', b'ro.adb.secure=0'),
    (b'ro.secure=1',     b'ro.secure=0'),
    (b'ro.debuggable=0', b'ro.debuggable=1'),
]


def find_all(data: bytes, needle: bytes):
    """Yield every offset where needle starts in data."""
    pos = 0
    while True:
        i = data.find(needle, pos)
        if i < 0:
            return
        yield i
        pos = i + 1   # allow overlapping matches (paranoid)


def cmd_inspect(path):
    print(f'Reading {path}...')
    data = open(path, 'rb').read()
    print(f'  size: {len(data):,} bytes ({len(data)/(1<<30):.2f} GiB)')
    print()
    for old, new in PATCHES:
        hits = list(find_all(data, old))
        print(f'{old.decode()!r}: {len(hits)} occurrences')
        for off in hits[:5]:
            # Show ~40 bytes of context
            ctx_start = max(0, off - 16)
            ctx_end = min(len(data), off + len(old) + 16)
            context = data[ctx_start:ctx_end].replace(b'\n', b'|')
            try:
                context_str = context.decode('utf-8', errors='replace')
            except Exception:
                context_str = repr(context)
            print(f'    @ 0x{off:08x}  context: ...{context_str}...')
        if len(hits) > 5:
            print(f'    ... and {len(hits)-5} more')


def cmd_patch(in_path, out_path):
    print(f'Reading {in_path}...')
    data = bytearray(open(in_path, 'rb').read())
    print(f'  size: {len(data):,} bytes')

    total_changes = 0
    for old, new in PATCHES:
        if len(old) != len(new):
            raise ValueError(f'patch lengths differ: {old} ({len(old)}) vs {new} ({len(new)})')
        hits = list(find_all(bytes(data), old))
        print(f'\n{old.decode()!r} -> {new.decode()!r}: {len(hits)} occurrences')
        for off in hits:
            data[off:off+len(old)] = new
            total_changes += 1
            ctx_start = max(0, off - 16)
            ctx_end = min(len(data), off + len(new) + 16)
            ctx = bytes(data[ctx_start:ctx_end]).replace(b'\n', b'|').decode('utf-8', errors='replace')
            print(f'    patched @ 0x{off:08x}: ...{ctx}...')

    print(f'\nTotal byte-replacements: {total_changes}')
    if total_changes == 0:
        print('No patterns found - nothing to write. Aborting.')
        sys.exit(1)
    print(f'\nWriting {out_path}...')
    open(out_path, 'wb').write(bytes(data))
    print(f'  done.')


def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    cmd = sys.argv[1]
    if cmd == 'inspect':
        cmd_inspect(sys.argv[2])
    elif cmd == 'patch':
        if len(sys.argv) != 4:
            print('Usage: patch-system-props.py patch <in.img> <out.img>'); sys.exit(1)
        cmd_patch(sys.argv[2], sys.argv[3])
    else:
        print(f'Unknown command: {cmd}'); sys.exit(1)


if __name__ == '__main__':
    main()
