"""
sign-ota.py - sign a zip as an Android OTA "whole-file" signed package.

Implements the AOSP recovery whole-file signature format from
bootable/recovery/install/verifier.cpp:

  Layout of the signed file:

    [original zip body with EOCD record's comment_size set to N]
    [PKCS#7 SignedData, DER-encoded]
    [6-byte footer: <signature_start LE 2><0xff><0xff><comment_size LE 2>]

  Where:
    signature_start = SignedData_length + 6    (bytes from end of file
                                                 to start of SignedData)
    comment_size    = SignedData_length + 6    (also)
    The SHA hashed range is [0, file_size - signature_start).

Usage:
    python sign-ota.py <input.zip> <output.zip> <cert.x509.pem> <key.pk8>

The .pk8 is expected to be PKCS#8 DER (AOSP testkey format).

This is a minimal, auditable implementation of what
build/tools/signapk/SignApk.java does for whole-file signing.
"""

import sys
import struct
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.serialization import pkcs7

EOCD_MAGIC = b'\x50\x4b\x05\x06'
FOOTER_SIZE = 6

def find_eocd(data: bytes) -> int:
    pos = data.rfind(EOCD_MAGIC)
    if pos < 0:
        raise ValueError("not a zip - no EOCD record found")
    return pos

def set_eocd_comment_size(data: bytes, eocd_pos: int, n: int) -> bytes:
    if n < 0 or n > 0xffff:
        raise ValueError(f"comment_size out of range: {n}")
    buf = bytearray(data)
    buf[eocd_pos + 20] = n & 0xff
    buf[eocd_pos + 21] = (n >> 8) & 0xff
    return bytes(buf)

def build_pkcs7_signed_data(data: bytes, cert, key) -> bytes:
    """Detached-content, DER-encoded PKCS#7 SignedData over `data`."""
    builder = (
        pkcs7.PKCS7SignatureBuilder()
            .set_data(data)
            .add_signer(cert, key, hashes.SHA256())
    )
    return builder.sign(
        serialization.Encoding.DER,
        [
            pkcs7.PKCS7Options.DetachedSignature,
            pkcs7.PKCS7Options.Binary,
            pkcs7.PKCS7Options.NoAttributes,
        ],
    )

def sign_ota(input_path: str, output_path: str, cert_path: str, key_path: str) -> None:
    with open(cert_path, 'rb') as f:
        cert = x509.load_pem_x509_certificate(f.read())
    with open(key_path, 'rb') as f:
        key_bytes = f.read()
    # AOSP testkey.pk8 is PKCS#8 DER, no password.
    key = serialization.load_der_private_key(key_bytes, password=None)

    with open(input_path, 'rb') as f:
        original = f.read()

    eocd_pos = find_eocd(original)
    # If input zip had a non-empty comment, strip it for a clean signing.
    eocd_end = eocd_pos + 22
    if eocd_end < len(original):
        original = original[:eocd_end]
    original = set_eocd_comment_size(original, eocd_pos, 0)

    # Iterate: PKCS#7 length depends on signed bytes which depend on
    # comment_size which depends on PKCS#7 length. Two passes converge for
    # a stable cert/key pair.
    comment_size = 0
    signed = b''
    for i in range(8):
        modified = set_eocd_comment_size(original, eocd_pos, comment_size)
        signed = build_pkcs7_signed_data(modified, cert, key)
        new_size = len(signed) + FOOTER_SIZE
        if new_size == comment_size:
            break
        comment_size = new_size
    else:
        raise RuntimeError("comment_size did not converge")

    signature_start = len(signed) + FOOTER_SIZE
    footer = struct.pack('<HBBH',
        signature_start,
        0xff,
        0xff,
        comment_size,
    )
    if len(footer) != FOOTER_SIZE:
        raise RuntimeError(f"footer wrong size: {len(footer)}")

    with open(output_path, 'wb') as f:
        f.write(modified)
        f.write(signed)
        f.write(footer)

    # Sanity-check.
    with open(output_path, 'rb') as f:
        out = f.read()
    assert out[-FOOTER_SIZE:] == footer
    assert out[-3] == 0xff and out[-4] == 0xff
    sig_start_check = out[-6] | (out[-5] << 8)
    cs_check = out[-2] | (out[-1] << 8)
    assert sig_start_check == signature_start, f"sig_start {sig_start_check} != {signature_start}"
    assert cs_check == comment_size, f"cs {cs_check} != {comment_size}"

    print(f"  input zip:       {input_path}  ({len(original)} bytes)")
    print(f"  signed output:   {output_path}  ({len(out)} bytes)")
    print(f"  pkcs7 size:      {len(signed)} bytes")
    print(f"  signature_start: {signature_start}")
    print(f"  comment_size:    {comment_size}")
    print(f"  iterations:      {i+1}")

if __name__ == '__main__':
    if len(sys.argv) != 5:
        print(__doc__)
        sys.exit(1)
    sign_ota(*sys.argv[1:])
