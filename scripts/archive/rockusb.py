"""
rockusb.py - minimal Rockchip rockusb protocol client.

Speaks USB Bulk-Only Mass-Storage transport (CBW/CSW) over the
vendor-specific FF/FF/00 interface that Rockchip recoveries (and the
MaskROM) expose. Bypasses the standard rkdeveloptool's PID filter
which rejects PIDs with high byte == 0 (e.g. 0x0011 from this
recovery).

Protocol references:
  - Upstream rkdeveloptool source (RKComm.cpp)
  - USB Mass Storage Class Bulk-Only Transport spec (USB-IF)

Layout:

  CBW (Command Block Wrapper, 31 bytes, host -> device):
     0..3   signature  0x43425355  ("USBC", LE)
     4..7   tag        transaction id (LE u32)
     8..11  data_xfer  expected data length (LE u32)
    12      flags      0x80 = device->host data, 0x00 = host->device
    13      lun        always 0
    14      cmd_len    length of CBWCB in bytes (max 16)
    15..30  CBWCB      command block (Rockchip uses BIG-ENDIAN here)

  CSW (Command Status Wrapper, 13 bytes, device -> host):
     0..3   signature  0x53425355  ("USBS")
     4..7   tag        echoed from CBW
     8..11  residue    bytes not transferred
    12      status     0=success, 1=fail, 2=phase error

  Rockchip CBWCB opcodes (byte 0):
    0x00  TestUnitReady
    0x01  ReadFlashID         data: 5 bytes
    0x02  TestBadBlock
    0x14  ReadLBA             cbwcb[2..5] = start LBA (BE u32)
                              cbwcb[6..7] = sector count (BE u16)
                              data: count * 512 bytes
    0x15  WriteLBA            same param layout
    0x16  EraseNormal
    0x17  EraseForce
    0x1A  ReadFlashInfo       data: 11 bytes
    0x1B  ReadChipInfo        data: 16 bytes (also 0x1D in some builds)
    0x1D  ReadCapability      data: 8 bytes
    0xFF  DeviceReset

CLI:
    python rockusb.py probe                    # find device, list endpoints
    python rockusb.py info                     # TestUnitReady + ReadCapability + ReadFlashInfo + ReadChipInfo
    python rockusb.py read-lba <start> <count> <out.bin>
    python rockusb.py reset

This script is *read-only* by default. Write/erase commands are
not exposed in the CLI here - we'll add them only after the read
path is verified working on the actual device.
"""

import sys
import os
import struct
import time
import usb.core
import usb.util
import usb.backend.libusb1

# pyusb on Windows can't find libusb-1.0.dll on PATH reliably. Use the
# libusb-package pip wheel which bundles a matching-arch DLL.
try:
    import libusb_package
    _LIBUSB_DLL = libusb_package.find_library('libusb-1.0')
    _BACKEND = usb.backend.libusb1.get_backend(find_library=lambda _: _LIBUSB_DLL)
except Exception:
    _BACKEND = None  # let pyusb try its defaults

def _find(**kwargs):
    if _BACKEND is not None:
        return usb.core.find(backend=_BACKEND, **kwargs)
    return usb.core.find(**kwargs)

VID = 0x2207
# Common Rockchip rockusb PIDs we might encounter:
#   0x0006  main-system rockusb gadget (some builds)
#   0x0011  recovery rockusb gadget    (this Mabu)
#   0x0010, 0x0017, ...  sideload, fastboot, etc.
#   chip-specific 0x310B, 0x320A, etc. for true MaskROM
ALL_PIDS = [0x0006, 0x0010, 0x0011, 0x0017, 0x0019, 0x320a]

VENDOR_INTERFACE_CLASS = 0xff
VENDOR_INTERFACE_SUBCLASS = 0xff
VENDOR_INTERFACE_PROTOCOL = 0x00

CBW_SIGNATURE = 0x43425355
CSW_SIGNATURE = 0x53425355

CMD_TEST_UNIT_READY  = 0x00
CMD_READ_FLASH_ID    = 0x01
CMD_TEST_BAD_BLOCK   = 0x02
CMD_READ_LBA         = 0x14
CMD_WRITE_LBA        = 0x15
CMD_ERASE_NORMAL     = 0x16
CMD_ERASE_FORCE      = 0x17
CMD_READ_FLASH_INFO  = 0x1A
CMD_READ_CHIP_INFO   = 0x1B
CMD_READ_CAPABILITY  = 0x1D
CMD_DEVICE_RESET     = 0xFF


class RockusbError(Exception):
    pass


class Rockusb:
    def __init__(self, dev, intf, ep_in, ep_out):
        self.dev = dev
        self.intf = intf
        self.ep_in = ep_in
        self.ep_out = ep_out
        self._tag = 1

    @classmethod
    def open(cls, vid=VID, pids=ALL_PIDS):
        dev = None
        for pid in pids:
            d = _find(idVendor=vid, idProduct=pid)
            if d is not None:
                dev = d
                break
        if dev is None:
            raise RockusbError(f'No rockchip device found (tried PIDs: {[hex(p) for p in pids]})')

        # On Windows with a composite device where only ONE interface has
        # WinUSB bound (via Zadig), get_active_configuration() trips on the
        # other interfaces. Use the cached configuration descriptor instead -
        # iter(dev) gives us the configurations without triggering an access.
        target_intf = None
        try:
            cfg = next(iter(dev))
        except StopIteration:
            raise RockusbError('No configuration on device')

        # Accept any vendor-specific interface (class 0xFF). PID 0x0011 has
        # FF/FF/00 (true rockusb); PID 0x320A Loader has FF/06/05 (different
        # subclass but same CBW-based protocol with Rockchip opcodes).
        for intf in cfg:
            if intf.bInterfaceClass == VENDOR_INTERFACE_CLASS:
                target_intf = intf
                break
        if target_intf is None:
            raise RockusbError(
                f'No vendor-class (0xFF) interface on device VID={vid:04x} PID={dev.idProduct:04x}. '
                f'Interfaces present: ' + ', '.join(
                    f'{i.bInterfaceClass:02x}/{i.bInterfaceSubClass:02x}/{i.bInterfaceProtocol:02x}'
                    for i in cfg)
            )

        ep_in = ep_out = None
        for ep in target_intf:
            if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN:
                ep_in = ep
            else:
                ep_out = ep
        if not (ep_in and ep_out):
            raise RockusbError('Could not find both bulk endpoints on the FF/FF/00 interface')

        # Claim the interface (libusb-on-Windows-with-WinUSB requires it,
        # but it's a no-op against the kernel since WinUSB doesn't share).
        try:
            usb.util.claim_interface(dev, target_intf.bInterfaceNumber)
        except usb.core.USBError as e:
            # On Windows this often fails harmlessly because libusb uses
            # the WinUSB handle directly; try to continue anyway.
            print(f'  (claim_interface returned {e}; continuing)')

        return cls(dev, target_intf, ep_in, ep_out)

    def _next_tag(self):
        t = self._tag
        self._tag = (self._tag + 1) & 0xffffffff
        return t

    def _do_cbw(self, opcode, data_xfer_len=0, data_in=True, params_be=b''):
        """Send CBW. Returns the CBW tag for matching the CSW.

        params_be: command-specific bytes 1.. of CBWCB (Rockchip uses big-endian).
                   We always set CBWCB[0] = opcode and pad the rest with zeros to 16 bytes.
        """
        tag = self._next_tag()
        flags = 0x80 if data_in else 0x00
        cbwcb = bytes([opcode]) + params_be
        if len(cbwcb) > 16:
            raise RockusbError(f'CBWCB too long: {len(cbwcb)} bytes')
        cbwcb = cbwcb.ljust(16, b'\x00')
        cbw = struct.pack('<IIIBBB',
                          CBW_SIGNATURE,
                          tag,
                          data_xfer_len,
                          flags,
                          0,           # LUN
                          len(bytes([opcode]) + params_be))  # cmd_len (only meaningful bytes)
        cbw += cbwcb
        if len(cbw) != 31:
            raise RockusbError(f'CBW size wrong: {len(cbw)}')
        n = self.ep_out.write(cbw, timeout=2000)
        if n != 31:
            raise RockusbError(f'CBW write short: {n}/31')
        return tag

    def _read_csw(self, expected_tag):
        try:
            csw = bytes(self.ep_in.read(13, timeout=4000))
        except usb.core.USBError as e:
            raise RockusbError(f'CSW read failed: {e}')
        if len(csw) != 13:
            raise RockusbError(f'CSW size wrong: {len(csw)}')
        sig, tag, residue, status = struct.unpack('<IIIB', csw)
        if sig != CSW_SIGNATURE:
            raise RockusbError(f'CSW signature wrong: 0x{sig:08x}')
        if tag != expected_tag:
            raise RockusbError(f'CSW tag mismatch: got {tag} expected {expected_tag}')
        return status, residue

    def cmd(self, opcode, data_xfer_len=0, data_in=True, params_be=b''):
        """Run a command. Returns (status, residue, data_or_None)."""
        tag = self._do_cbw(opcode, data_xfer_len, data_in, params_be)
        data = None
        if data_xfer_len > 0:
            if data_in:
                data = bytes(self.ep_in.read(data_xfer_len, timeout=4000))
            else:
                # caller provides data via raw write before reading CSW (not supported here)
                raise RockusbError('OUT data phase not implemented in cmd(); use _do_cbw + raw write directly')
        status, residue = self._read_csw(tag)
        return status, residue, data

    # --- high-level helpers ---

    def test_unit_ready(self):
        return self.cmd(CMD_TEST_UNIT_READY, 0)

    def read_capability(self):
        s, r, d = self.cmd(CMD_READ_CAPABILITY, 8)
        return s, d

    def read_flash_info(self):
        s, r, d = self.cmd(CMD_READ_FLASH_INFO, 11)
        return s, d

    def read_chip_info(self):
        s, r, d = self.cmd(CMD_READ_CHIP_INFO, 16)
        return s, d

    def read_flash_id(self):
        s, r, d = self.cmd(CMD_READ_FLASH_ID, 5)
        return s, d

    def read_lba(self, start_sector, sector_count):
        params = struct.pack('>IH', start_sector, sector_count) + b'\x00\x00'  # pad to align with rkdeveloptool layout
        s, r, d = self.cmd(CMD_READ_LBA, sector_count * 512, data_in=True, params_be=params)
        return s, d


# ----------------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------------

def hex_dump(data, prefix='  '):
    if not data: return ''
    out = []
    for i in range(0, len(data), 16):
        chunk = data[i:i+16]
        hexs = ' '.join(f'{b:02x}' for b in chunk)
        ascii_repr = ''.join(chr(b) if 32 <= b < 127 else '.' for b in chunk)
        out.append(f'{prefix}{i:04x}  {hexs:<47s}  {ascii_repr}')
    return '\n'.join(out)


def cmd_probe():
    found_any = False
    for pid in ALL_PIDS:
        d = _find(idVendor=VID, idProduct=pid)
        if d is None: continue
        found_any = True
        print(f'Found: VID={VID:04x} PID={pid:04x}')
        # Use cached config descriptor (iter(d)) - get_active_configuration()
        # trips on composite devices with mixed driver bindings on Windows.
        try:
            cfg = next(iter(d))
        except Exception as e:
            print(f'  Could not enumerate config: {e}')
            continue
        for intf in cfg:
            print(f'  Interface {intf.bInterfaceNumber}: '
                  f'class={intf.bInterfaceClass:02x}/'
                  f'sub={intf.bInterfaceSubClass:02x}/'
                  f'prot={intf.bInterfaceProtocol:02x}'
                  f'  ({len(intf.endpoints())} endpoints)')
            for ep in intf:
                direction = 'IN' if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN else 'OUT'
                print(f'    EP 0x{ep.bEndpointAddress:02x} ({direction}) '
                      f'type={usb.util.endpoint_type(ep.bmAttributes)} '
                      f'maxPacket={ep.wMaxPacketSize}')
        return
    if not found_any:
        print(f'No device with VID {VID:04x} found. PIDs tried: {[hex(p) for p in ALL_PIDS]}')


def cmd_info():
    rk = Rockusb.open()
    print(f'Opened: VID={rk.dev.idVendor:04x} PID={rk.dev.idProduct:04x}, intf={rk.intf.bInterfaceNumber}')

    # 1. TestUnitReady
    s, _, _ = rk.test_unit_ready()
    print(f'TestUnitReady: status={s} ({"ok" if s == 0 else "fail"})')
    if s != 0:
        print('Device not ready - aborting further probes.')
        return

    # 2. ReadCapability
    s, d = rk.read_capability()
    print(f'ReadCapability: status={s}, 8 bytes:')
    print(hex_dump(d))

    # 3. ReadFlashID (NOR/NAND/eMMC manufacturer ID)
    s, d = rk.read_flash_id()
    print(f'ReadFlashID: status={s}, 5 bytes:')
    print(hex_dump(d))

    # 4. ReadFlashInfo (size, page size, block size)
    s, d = rk.read_flash_info()
    print(f'ReadFlashInfo: status={s}, 11 bytes:')
    print(hex_dump(d))
    if d and len(d) >= 4:
        flash_size_lba = struct.unpack('<I', d[:4])[0]
        print(f'  -> flash size: {flash_size_lba} sectors = {flash_size_lba * 512 / (1<<30):.2f} GiB')

    # 5. ReadChipInfo
    s, d = rk.read_chip_info()
    print(f'ReadChipInfo: status={s}, 16 bytes:')
    print(hex_dump(d))


def cmd_read_lba(args):
    if len(args) != 3:
        print('Usage: read-lba <start_sector> <count> <out.bin>')
        sys.exit(1)
    start, count, out = int(args[0], 0), int(args[1], 0), args[2]
    rk = Rockusb.open()
    s, d = rk.read_lba(start, count)
    print(f'ReadLBA: status={s}, got {len(d) if d else 0} bytes')
    if d:
        with open(out, 'wb') as f:
            f.write(d)
        print(f'Wrote {len(d)} bytes to {out}')


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == 'probe':
        cmd_probe()
    elif cmd == 'info':
        cmd_info()
    elif cmd == 'read-lba':
        cmd_read_lba(sys.argv[2:])
    elif cmd == 'reset':
        rk = Rockusb.open()
        rk.cmd(CMD_DEVICE_RESET, 0)
        print('Reset sent')
    else:
        print(f'Unknown command: {cmd}')
        print(__doc__)
        sys.exit(1)

if __name__ == '__main__':
    main()
