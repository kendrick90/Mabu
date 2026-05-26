"""
patch-parameter.py - modify the kernel cmdline in a Rockchip parameter file
and recompute the trailing CRC32 with Rockchip's custom polynomial.

Parameter file format (at sector 0 of eMMC):
    bytes  0..3   "PARM" magic (4 bytes)
    bytes  4..7   text length (LE u32)
    bytes  8..len text content (ASCII)
    bytes  len+8..len+11  CRC32 (Rockchip polynomial 0x04C10DB7, init=0, xor=0, no reflect)
    bytes  ..end  zero padding

This patcher:
  - parses the existing parameter file
  - applies modifications to the CMDLINE line
  - recomputes the CRC
  - writes a new file

Usage:
    python patch-parameter.py inspect <parameter.img>
    python patch-parameter.py patch <in.img> <out.img>
        - default modification: veritymode=enforcing -> veritymode=disabled
                                add androidboot.selinux=permissive if missing
"""
import sys
import os

ROCKCHIP_CRC_POLY = 0x04C10DB7

def rockchip_crc32(data: bytes) -> int:
    """CRC32 with Rockchip's polynomial 0x04C10DB7, init=0, no reflection, no final xor."""
    crc = 0
    for b in data:
        crc ^= (b << 24)
        for _ in range(8):
            if crc & 0x80000000:
                crc = (crc << 1) ^ ROCKCHIP_CRC_POLY
            else:
                crc <<= 1
            crc &= 0xffffffff
    return crc

def parse_parameter(data: bytes):
    if data[:4] != b'PARM':
        raise ValueError(f"Not a parameter file (no PARM magic, got {data[:4]!r})")
    text_len = int.from_bytes(data[4:8], 'little')
    text = data[8:8+text_len].decode('ascii')
    stored_crc = int.from_bytes(data[8+text_len:8+text_len+4], 'little')
    return {
        'text': text,
        'text_len': text_len,
        'stored_crc': stored_crc,
        'computed_crc': rockchip_crc32(data[8:8+text_len]),
        'total_size': len(data),
    }

def build_parameter(text: str, total_size: int = 8192) -> bytes:
    """Build a parameter file from text content."""
    text_bytes = text.encode('ascii')
    text_len = len(text_bytes)
    crc = rockchip_crc32(text_bytes)
    out = bytearray()
    out += b'PARM'
    out += text_len.to_bytes(4, 'little')
    out += text_bytes
    out += crc.to_bytes(4, 'little')
    out += b'\x00' * (total_size - len(out))
    if len(out) > total_size:
        raise ValueError(f"text too large: {len(out)} bytes won't fit in {total_size}")
    return bytes(out)

def cmd_inspect(path):
    data = open(path, 'rb').read()
    p = parse_parameter(data)
    print(f"PARM file: {path}  ({p['total_size']} bytes total)")
    print(f"  text length:  {p['text_len']}")
    print(f"  stored CRC:   0x{p['stored_crc']:08X}")
    print(f"  computed CRC: 0x{p['computed_crc']:08X}  {'OK' if p['stored_crc'] == p['computed_crc'] else 'MISMATCH'}")
    print(f"---- text content ----")
    print(p['text'])
    print(f"---- end ----")

def cmd_patch(in_path, out_path):
    data = open(in_path, 'rb').read()
    p = parse_parameter(data)
    text = p['text']

    print("Original cmdline (truncated):")
    for line in text.splitlines():
        if line.startswith('CMDLINE:'):
            print(f"  {line[:120]}{'...' if len(line) > 120 else ''}")

    new_text = text

    # 1. veritymode: enforcing -> disabled
    if 'androidboot.veritymode=enforcing' in new_text:
        new_text = new_text.replace('androidboot.veritymode=enforcing', 'androidboot.veritymode=disabled')
        print("  - changed androidboot.veritymode=enforcing -> disabled")
    elif 'androidboot.veritymode=disabled' in new_text:
        print("  - veritymode already disabled")
    else:
        print("  - veritymode not found in cmdline; adding")
        new_text = new_text.replace('CMDLINE: ', 'CMDLINE: androidboot.veritymode=disabled ', 1)

    # 2. selinux=permissive (add if missing)
    if 'androidboot.selinux=' not in new_text:
        new_text = new_text.replace('CMDLINE: ', 'CMDLINE: androidboot.selinux=permissive ', 1)
        print("  - added androidboot.selinux=permissive")
    else:
        # Force permissive
        import re
        new_text = re.sub(r'androidboot\.selinux=\w+', 'androidboot.selinux=permissive', new_text)
        print("  - set androidboot.selinux=permissive (was already present, normalized)")

    if new_text == text:
        print("\n!! no changes made !!")
    else:
        print("\nNew cmdline (truncated):")
        for line in new_text.splitlines():
            if line.startswith('CMDLINE:'):
                print(f"  {line[:120]}{'...' if len(line) > 120 else ''}")

    new_data = build_parameter(new_text, total_size=p['total_size'])
    open(out_path, 'wb').write(new_data)

    # Verify round-trip
    verify = parse_parameter(new_data)
    print(f"\nWrote {len(new_data)} bytes to {out_path}")
    print(f"  new text length: {verify['text_len']}")
    print(f"  new CRC:         0x{verify['computed_crc']:08X}  (stored 0x{verify['stored_crc']:08X}, OK={verify['stored_crc']==verify['computed_crc']})")

def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    cmd = sys.argv[1]
    if cmd == 'inspect':
        cmd_inspect(sys.argv[2])
    elif cmd == 'patch':
        if len(sys.argv) != 4:
            print('Usage: patch-parameter.py patch <in.img> <out.img>'); sys.exit(1)
        cmd_patch(sys.argv[2], sys.argv[3])
    else:
        print(f'Unknown command: {cmd}'); sys.exit(1)

if __name__ == '__main__':
    main()
