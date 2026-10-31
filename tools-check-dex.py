#!/usr/bin/env python3
"""Confere se todas as classes declaradas no AndroidManifest existem no classes.dex.
Uso: tools-check-dex.py AndroidManifest.xml classes.dex"""
import re, struct, sys
manifest, dex = open(sys.argv[1]).read(), open(sys.argv[2], 'rb').read()
pkg = re.search(r'package="([\w.]+)"', manifest).group(1)
names = re.findall(r'<(?:application|activity|receiver|service|provider)\b[^>]*android:name="([\w.]+)"', manifest)
wanted = {('L' + (pkg + n if n.startswith('.') else n).replace('.', '/') + ';') for n in names}
assert dex[:4] == b'dex\n', 'não é um arquivo DEX'
string_ids_size, string_ids_off, type_ids_size, type_ids_off = struct.unpack_from('<IIII', dex, 0x38)
def string(i):
    off = struct.unpack_from('<I', dex, string_ids_off + 4 * i)[0]
    while dex[off] & 0x80: off += 1   # pula o tamanho em ULEB128
    off += 1
    return dex[off:dex.index(b'\0', off)].decode('utf-8', 'replace')
types = {string(struct.unpack_from('<I', dex, type_ids_off + 4 * i)[0]) for i in range(type_ids_size)}
class_defs_size, class_defs_off = struct.unpack_from('<II', dex, 0x60)
defined = {string(struct.unpack_from('<I', dex, type_ids_off + 4 * struct.unpack_from('<I', dex, class_defs_off + 32 * i)[0])[0]) for i in range(class_defs_size)}
missing = sorted(wanted - defined)
if missing:
    print('ERRO: componentes do manifest ausentes no DEX:', *missing, sep='\n  '); sys.exit(1)
print(f'   ok: {len(wanted)} componentes do manifest presentes; {len(defined)} classes no DEX (versão {dex[4:7].decode()})')
