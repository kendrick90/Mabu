#!/usr/bin/env python3
"""mabu-motor-test.py

First-light motor test for the Mabu, on-device via Termux.
Copies ElectroNick's protocol (https://github.com/electronick-co/mabu)
plus our findings from factorymode decompile.

Run on the tablet from Termux after:
    pkg install python
    pip install pyserial

Then:
    python mabu-motor-test.py

It will:
  1. Open /dev/ttyS1 at 57600 8N1
  2. Send the power-on command
  3. Center all 7 motors (logical 50)
  4. Wiggle the neck-tilt motor 30..70..50
  5. Wink each eyelid in turn
  6. Send power-off
"""
import sys, time

try:
    import serial
except ImportError:
    sys.stderr.write("pyserial not installed. Run: pip install pyserial\n")
    sys.exit(1)

PORT = '/dev/ttyS1'
BAUD = 57600

# Motor bitmask values (from MotorNames.java + ElectroNick)
LDL = 0x40   # eyelid left
LDR = 0x20   # eyelid right
ELR = 0x10   # eyes left-right
EUD = 0x08   # eyes up-down
NE  = 0x04   # neck elevation
NR  = 0x02   # neck rotation
NT  = 0x01   # neck tilt

ALL = (LDL, LDR, ELR, EUD, NE, NR, NT)

def fletcher8(data):
    s1 = s2 = 0
    for b in data:
        s1 = (s1 + b) % 255
        s2 = (s2 + s1) % 255
    return (s2 << 8) | s1

def frame_with_checksum(payload):
    """Wrap payload in FA 00 <len> <payload> <fletcher8>."""
    header = bytearray([0xFA, 0x00, len(payload)]) + bytearray(payload)
    ck = fletcher8(header)
    return bytes(header + bytearray([ck >> 8, ck & 0xFF]))

def power_on(ser):
    # Same bytes as ElectroNick's published power-on; verifies our framing matches.
    cmd = bytes([0xFA, 0x00, 0x02, 0x4F, 0x7F, 0x0B, 0xCB])
    ser.write(cmd)
    print(f"  TX power-on: {cmd.hex()}")

def power_off(ser):
    cmd = bytes([0xFA, 0x00, 0x02, 0x4F, 0x8B, 0x4C])
    ser.write(cmd)
    print(f"  TX power-off: {cmd.hex()}")

def move(ser, bitmask, value_0_100):
    """Move one or more motors. Value 0-100 logical, 50 = center."""
    wire = int(value_0_100 * 2.55)
    wire = max(0, min(255, wire))
    payload = bytearray([0x01, bitmask, 0x01, wire])
    cmd = frame_with_checksum(payload)
    ser.write(cmd)
    print(f"  TX move bitmask=0x{bitmask:02X} val={value_0_100}({wire}): {cmd.hex()}")

def center_all(ser):
    print("Centering all 7 motors")
    for bit in ALL:
        move(ser, bit, 50)
        time.sleep(0.05)

def read_drain(ser, label):
    """Read any pending bytes, print as hex."""
    data = ser.read(64)
    if data:
        print(f"  RX [{label}]: {data.hex()}")
    else:
        print(f"  RX [{label}]: (no response)")

def main():
    print(f"Opening {PORT} @ {BAUD}...")
    ser = serial.Serial(PORT, BAUD, timeout=0.3)
    try:
        print("Power on")
        power_on(ser); time.sleep(0.5); read_drain(ser, 'after power-on')

        center_all(ser); time.sleep(1.0); read_drain(ser, 'after center')

        print("Wiggle neck-tilt: 30 -> 70 -> 50")
        for v in (30, 70, 50):
            move(ser, NT, v); time.sleep(0.6)
        read_drain(ser, 'after neck wiggle')

        print("Wink: left eyelid down then up, then right")
        move(ser, LDL, 20); time.sleep(0.4)
        move(ser, LDL, 50); time.sleep(0.3)
        move(ser, LDR, 20); time.sleep(0.4)
        move(ser, LDR, 50); time.sleep(0.3)
        read_drain(ser, 'after winks')

        print("Power off")
        power_off(ser); time.sleep(0.2); read_drain(ser, 'after power-off')
    finally:
        ser.close()
    print("Done.")

if __name__ == '__main__':
    main()
