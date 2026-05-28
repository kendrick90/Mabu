"""
find-hash-formula.py - try every plausible SHA-1 input ordering against
the boot.img id field, find which one matches.
"""
import sys, os, struct, hashlib, gzip
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import importlib.util
spec = importlib.util.spec_from_file_location("pbi", os.path.join(os.path.dirname(os.path.abspath(__file__)), "patch-bootimg.py"))
pbi = importlib.util.module_from_spec(spec); spec.loader.exec_module(pbi)

def test(name, computer, kernel, ramdisk, second, header, target):
    h = computer(kernel, ramdisk, second, header)
    h_short = h[:20].hex()
    match = h_short == target.hex()
    print(f'  {"MATCH" if match else "miss "}  {name:<48}  {h_short}')
    return match

def main():
    with open(sys.argv[1] if len(sys.argv) > 1 else 'dumps/boot.img', 'rb') as f:
        data = f.read()
    bi = pbi.parse_bootimg(data)
    K = bi['kernel']; R = bi['ramdisk']; S = bi['second']
    target = bi['id_hash'][:20]
    print(f'Target SHA-1: {target.hex()}')
    print(f'Lengths: kernel={len(K)} ramdisk={len(R)} second={len(S)}')
    print()

    HEADER = data[:bi["page_size"]]
    HEADER_NOID = bytearray(HEADER)
    for i in range(576, 576+32): HEADER_NOID[i] = 0

    formulas = [
        ('AOSP standard: K|sz|R|sz|S|sz',
         lambda k,r,s,h: hashlib.sha1(k + struct.pack('<I', len(k)) + r + struct.pack('<I', len(r)) + s + struct.pack('<I', len(s))).digest()),
        ('K only',
         lambda k,r,s,h: hashlib.sha1(k).digest()),
        ('K|R',
         lambda k,r,s,h: hashlib.sha1(k+r).digest()),
        ('K|R|S',
         lambda k,r,s,h: hashlib.sha1(k+r+s).digest()),
        ('K|R|S no sizes',
         lambda k,r,s,h: hashlib.sha1(k+r+s).digest()),
        ('K|sz|R|sz no second',
         lambda k,r,s,h: hashlib.sha1(k + struct.pack('<I', len(k)) + r + struct.pack('<I', len(r))).digest()),
        ('header_no_id | K | R | S',
         lambda k,r,s,h: hashlib.sha1(bytes(HEADER_NOID) + k + r + s).digest()),
        ('header_no_id | K_padded | R_padded | S_padded',
         lambda k,r,s,h: hashlib.sha1(bytes(HEADER_NOID) + _pad(k, bi['page_size']) + _pad(r, bi['page_size']) + _pad(s, bi['page_size'])).digest()),
        ('K_padded | R_padded | S_padded',
         lambda k,r,s,h: hashlib.sha1(_pad(k, bi['page_size']) + _pad(r, bi['page_size']) + _pad(s, bi['page_size'])).digest()),
        ('K|sz_BE|R|sz_BE|S|sz_BE',
         lambda k,r,s,h: hashlib.sha1(k + struct.pack('>I', len(k)) + r + struct.pack('>I', len(r)) + s + struct.pack('>I', len(s))).digest()),
        ('sz|K|sz|R|sz|S',
         lambda k,r,s,h: hashlib.sha1(struct.pack('<I', len(k)) + k + struct.pack('<I', len(r)) + r + struct.pack('<I', len(s)) + s).digest()),
        ('K|R|S sizes appended',
         lambda k,r,s,h: hashlib.sha1(k + r + s + struct.pack('<III', len(k), len(r), len(s))).digest()),
        ('whole image up to id field',
         lambda k,r,s,h: hashlib.sha1(data[:576] + data[608:bi['page_size']] + k + r + s).digest()),
        ('whole image including header (id zeroed)',
         lambda k,r,s,h: hashlib.sha1(bytes(HEADER_NOID) + data[bi['page_size']:]).digest()),
    ]
    any_match = False
    for name, fn in formulas:
        if test(name, fn, K, R, S, HEADER, target):
            any_match = True
    if not any_match:
        print('\nNo formula matched. Rockchip is using a non-SHA-1 or non-standard input.')
        print('Maybe the id field is something else entirely (UUID? signature?).')
    else:
        print('\nFound a match - patcher needs to use that formula.')

def _pad(data, page):
    rem = len(data) % page
    return data + (b'\x00' * (page - rem)) if rem else data

if __name__ == '__main__':
    main()
