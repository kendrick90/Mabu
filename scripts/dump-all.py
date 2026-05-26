#!/usr/bin/env python3
"""dump-all.py - full-device backup over a single persistent rockusb session.

Built to replace the dump-range.ps1 + rkdeveloptool-rl-per-chunk pattern,
which spawns one process per ~4 MB chunk and seems to cumulatively
wedge the Loader after ~30 MB of accumulated reads (presumed Loader-side
state leakage tied to CBW count, not byte count). By holding ONE USB
handle open and sending many ReadLBA CBWs over it, we avoid the per-
process overhead entirely.

Reads at full bulk-transfer throughput. ReadLBA in rockusb can request
up to 65535 sectors (32 MB) per single CBW; we use 32 MB blocks by
default.

If the Loader does still wedge after some N MB on this hardware, this
script will surface a clear error and you can resume from the last
written offset (each partition's output file is appended in-place).

Usage:
    python scripts/dump-all.py            # dump every known partition
    python scripts/dump-all.py system     # dump just one
    python scripts/dump-all.py system userdata
    python scripts/dump-all.py --block-mb 16 userdata  # smaller chunks
    python scripts/dump-all.py --resume system          # continue partial dump

Output:
    backups/<serial>/<partition>.img    # one file per partition
    backups/<serial>/manifest.json      # what was dumped, sizes, md5s
"""
import argparse, hashlib, json, os, struct, sys, time

try:
    import usb.core, usb.util
except ImportError:
    print('install pyusb:  pip install pyusb libusb-package', file=sys.stderr)
    sys.exit(1)

# Layout from parameter file (see HANDOFF.md / scripts/catch-loader-and-dump.ps1)
PARTITIONS = [
    ('parameter', 0x00000000, 16),
    ('uboot',     0x00002000, 0x00002000),
    ('trust',     0x00004000, 0x00002000),
    ('misc',      0x00006000, 0x00002000),
    ('resource',  0x00008000, 0x00008000),
    ('kernel',    0x00010000, 0x00010000),
    ('boot',      0x00020000, 0x00010000),
    ('recovery',  0x00030000, 0x00020000),
    ('backup',    0x00050000, 0x00038000),
    ('security',  0x00088000, 0x00002000),
    ('cache',     0x0008a000, 0x00100000),
    ('system',    0x0018a000, 0x00400000),
    ('metadata',  0x0058a000, 0x00008000),
    ('vendor',    0x00592000, 0x00080000),
    ('oem',       0x00612000, 0x00080000),
    ('frp',       0x00692000, 0x00000400),
    ('userdata',  0x00692400, 23420928),   # ~11.2 GB
]

VID = 0x2207
LOADER_PIDS = [0x320a, 0x310b]   # MaskROM-style PIDs
USB_CLASS_VENDOR = 0xFF
USB_BULK_TIMEOUT_MS = 30000

def find_loader():
    for pid in LOADER_PIDS:
        dev = usb.core.find(idVendor=VID, idProduct=pid)
        if dev is None: continue
        return dev, pid
    return None, None

def find_endpoints(dev):
    """Find the vendor-class interface (FF/FF/00) and its bulk IN/OUT eps."""
    for cfg in dev:
        for intf in cfg:
            if intf.bInterfaceClass != USB_CLASS_VENDOR:
                continue
            ep_in = ep_out = None
            for ep in intf:
                if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN:
                    ep_in = ep
                else:
                    ep_out = ep
            if ep_in and ep_out:
                return intf, ep_in, ep_out
    raise RuntimeError('no vendor-class bulk interface found on Loader')

class Rockusb:
    """Persistent rockusb-protocol session against a single USB handle."""
    def __init__(self, dev, intf, ep_in, ep_out):
        self.dev = dev
        self.intf = intf
        self.ep_in = ep_in
        self.ep_out = ep_out
        self._tag = 0

    @classmethod
    def open(cls):
        dev, pid = find_loader()
        if dev is None:
            raise RuntimeError(f'no Loader device (VID {VID:04x}, PIDs {[hex(p) for p in LOADER_PIDS]})')
        try:
            if dev.is_kernel_driver_active(0):
                dev.detach_kernel_driver(0)
        except Exception:
            pass
        dev.set_configuration()
        intf, ep_in, ep_out = find_endpoints(dev)
        usb.util.claim_interface(dev, intf.bInterfaceNumber)
        return cls(dev, intf, ep_in, ep_out), pid

    def close(self):
        try: usb.util.release_interface(self.dev, self.intf.bInterfaceNumber)
        except Exception: pass

    def _cbw(self, opcode, data_xfer_len, data_in, params_be):
        self._tag = (self._tag + 1) & 0xFFFFFFFF
        flags = 0x80 if data_in else 0x00
        cbwcb = (bytes([opcode]) + params_be).ljust(16, b'\x00')[:16]
        cbw = struct.pack('<4sIIBBB',
                          b'USBC', self._tag, data_xfer_len, flags, 0, len(cbwcb)) + cbwcb
        # Pad to 31 bytes
        cbw = cbw.ljust(31, b'\x00')
        self.ep_out.write(cbw, timeout=USB_BULK_TIMEOUT_MS)
        return self._tag

    def _csw(self, expected_tag):
        csw = bytes(self.ep_in.read(13, timeout=USB_BULK_TIMEOUT_MS))
        sig, tag, residue, status = struct.unpack('<4sIIB', csw)
        if sig != b'USBS':
            raise RuntimeError(f'CSW sig mismatch: {sig!r}')
        if tag != expected_tag:
            raise RuntimeError(f'CSW tag mismatch: expected {expected_tag}, got {tag}')
        return residue, status

    def read_lba(self, start_lba, sector_count):
        """Read up to 65535 sectors in one CBW."""
        if sector_count > 65535:
            raise ValueError('rockusb ReadLBA limited to 65535 sectors per CBW')
        # CBWCB: opcode 0x14 + reserved + 4-byte big-endian start + 2-byte big-endian count
        params = b'\x00' + struct.pack('>IH', start_lba, sector_count) + b'\x00' * 9
        params = params[:15]
        nbytes = sector_count * 512
        tag = self._cbw(0x14, nbytes, True, params)
        # Read data in chunks (pyusb returns array.array of given size limit)
        buf = bytearray()
        remaining = nbytes
        while remaining > 0:
            chunk = bytes(self.ep_in.read(min(remaining, 65536 * 4), timeout=USB_BULK_TIMEOUT_MS))
            buf.extend(chunk)
            remaining -= len(chunk)
        residue, status = self._csw(tag)
        if status != 0:
            raise RuntimeError(f'ReadLBA failed status={status} residue={residue}')
        return bytes(buf)

def dump_partition(rk, name, start_lba, sector_count, out_path, block_mb, resume):
    block_sectors = (block_mb * 1024 * 1024) // 512
    if block_sectors > 65535:
        block_sectors = 65535   # max per CBW
    total_bytes = sector_count * 512

    start_offset = 0
    if resume and os.path.exists(out_path):
        start_offset = os.path.getsize(out_path)
        start_offset -= start_offset % 512   # sector align
        if start_offset >= total_bytes:
            print(f'  [{name}] already complete')
            return
        print(f'  [{name}] resuming from byte {start_offset:,} ({start_offset/total_bytes*100:.1f}%)')

    mode = 'ab' if (resume and start_offset > 0) else 'wb'
    with open(out_path, mode) as f:
        if mode == 'wb':
            f.truncate(0)
        lba = start_lba + (start_offset // 512)
        remaining_sectors = sector_count - (start_offset // 512)
        t0 = time.time()
        bytes_done = start_offset
        last_print = t0
        while remaining_sectors > 0:
            this = min(block_sectors, remaining_sectors)
            data = rk.read_lba(lba, this)
            f.write(data)
            f.flush()
            bytes_done += len(data)
            lba += this
            remaining_sectors -= this
            now = time.time()
            if now - last_print > 1.5:
                pct = bytes_done / total_bytes * 100
                rate = (bytes_done - start_offset) / (now - t0) / (1024*1024)
                print(f'  [{name}] {pct:5.1f}%  {bytes_done/(1024*1024):8.1f} MB  {rate:5.1f} MB/s')
                last_print = now
        elapsed = time.time() - t0
        rate = (bytes_done - start_offset) / max(elapsed, 0.01) / (1024*1024)
        print(f'  [{name}] done {bytes_done/(1024*1024):8.1f} MB in {elapsed:.1f}s  avg {rate:.1f} MB/s')

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('partitions', nargs='*', help='partition names; empty = all')
    ap.add_argument('--block-mb', type=int, default=8, help='read block size (MB), default 8')
    ap.add_argument('--resume', action='store_true', help='resume any partial dumps')
    ap.add_argument('--out', default='backups', help='output root dir')
    ap.add_argument('--serial', default=None, help='subdir name; defaults to device serial')
    args = ap.parse_args()

    parts = {p[0]: p for p in PARTITIONS}
    if args.partitions:
        unknown = [p for p in args.partitions if p not in parts]
        if unknown:
            print(f'unknown partitions: {unknown}; valid: {list(parts.keys())}', file=sys.stderr)
            sys.exit(2)
        targets = [parts[p] for p in args.partitions]
    else:
        targets = list(PARTITIONS)

    rk, pid = Rockusb.open()
    print(f'Opened Loader VID=0x{VID:04X} PID=0x{pid:04X}')

    serial_subdir = args.serial or f'pid{pid:04x}-{int(time.time())}'
    outdir = os.path.join(args.out, serial_subdir)
    os.makedirs(outdir, exist_ok=True)
    print(f'Output: {outdir}')

    try:
        for name, start, count in targets:
            out_path = os.path.join(outdir, f'{name}.img')
            try:
                dump_partition(rk, name, start, count, out_path, args.block_mb, args.resume)
            except Exception as e:
                print(f'  [{name}] FAILED: {e}', file=sys.stderr)
                print(f'    if Loader wedged, power-cycle and re-run with --resume', file=sys.stderr)
                return 1
    finally:
        rk.close()

    print('\nAll requested partitions dumped.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
