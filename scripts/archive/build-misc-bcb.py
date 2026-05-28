#!/usr/bin/env python3
"""Build an Android Bootloader Control Block (BCB) that triggers a
recovery-mode factory reset on next boot.

Layout (bootable/recovery/bootloader_message_writer/bootloader_message.h):
  char command[32];     // "boot-recovery"
  char status[32];      // bootloader-set; we leave zero
  char recovery[768];   // "recovery\\n--wipe_data\\n--reason=...\\n"
  char stage[32];       // empty
  char reserved[1184];  // zero padding -> total 2048 bytes

Writes dumps/misc-bcb-wipe.bin (2048 bytes).
We also write a 4 KB padded variant for easier rkdeveloptool wl (writes
8 sectors), since misc is intended to be writable at sector resolution.
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT  = os.path.join(ROOT, 'dumps', 'misc-bcb-wipe.bin')

command  = b'boot-recovery'
status   = b''
recovery = b'recovery\n--wipe_data\n--reason=wipe_data_from_user\n'
stage    = b''

assert len(command)  <= 32
assert len(status)   <= 32
assert len(recovery) <= 768
assert len(stage)    <= 32

bcb  = command.ljust(32, b'\x00')
bcb += status.ljust(32, b'\x00')
bcb += recovery.ljust(768, b'\x00')
bcb += stage.ljust(32, b'\x00')
bcb += b'\x00' * 1184

assert len(bcb) == 2048, len(bcb)

with open(OUT, 'wb') as f:
    f.write(bcb)
print(f'BCB written: {OUT} ({len(bcb)} bytes = 4 sectors)')
print(f'  command:  {command!r}')
print(f'  recovery: {recovery!r}')
print(f'\nWrite to misc partition (sector 0x6000):')
print(f'  rkdeveloptool.exe wl 0x6000 {OUT}')
print(f'\nWhich is abs LBA {0x6000} (decimal {0x6000}).')
