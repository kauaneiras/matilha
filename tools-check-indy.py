#!/usr/bin/env python3
"""Garante que nenhum invokedynamic alcançável chegue ao DEX.

O ART não tem java.lang.invoke.LambdaMetafactory e o dx não faz desugaring. Regras:
  1. Código do app: zero invokedynamic.
  2. Bibliotecas: métodos com invokedynamic não podem ser chamados pelo app nem por outro
     método de biblioteca (exceto entre eles). Caso contrário, falha.
Uso: tools-check-indy.py app.jar lib1.jar lib2.jar ...
"""
import re, subprocess, sys, zipfile

def javap(jar):
    classes = [n[:-6].replace('/', '.') for n in zipfile.ZipFile(jar).namelist() if n.endswith('.class') and 'META-INF' not in n]
    out = []
    for i in range(0, len(classes), 400):
        out.append(subprocess.run(['javap', '-c', '-p', '-cp', jar] + classes[i:i+400], capture_output=True, text=True).stdout)
    return '\n'.join(out)

def scan(text):
    """Retorna (métodos com indy, referências: método -> set(alvos))."""
    cls = meth = None
    indy, refs = set(), {}
    for line in text.splitlines():
        m = re.search(r'\b(?:class|interface|enum)\s+([\w.$]+)', line)
        if m and not line.startswith(' '):
            cls = m.group(1).replace('.', '/'); continue
        if line.startswith('  ') and not line.startswith('   ') and '(' in line:
            name = re.search(r'([\w$<>]+)\(', line)
            meth = f"{cls}.{name.group(1) if name else '?'}"; continue
        if 'invokedynamic' in line and meth: indy.add(meth)
        r = re.search(r'// (?:Interface)?Method ([\w/$]+)\.([\w$<>"]+):', line)
        if r and meth: refs.setdefault(meth, set()).add(f"{r.group(1)}.{r.group(2).strip(chr(34))}")
    return indy, refs

app, libs = sys.argv[1], sys.argv[2:]
app_indy, app_refs = scan(javap(app))
if app_indy:
    print("ERRO: invokedynamic no código do app:", *sorted(app_indy)[:20], sep="\n  "); sys.exit(1)
lib_indy, lib_refs = set(), {}
for j in libs:
    i, r = scan(javap(j)); lib_indy |= i; lib_refs.update(r)

def norm(m):  # facades: ComparisonsKt -> ComparisonsKt__ComparisonsKt
    c, n = m.rsplit('.', 1); return re.sub(r'__\w+$', '', c) + '.' + n
bad_norm = {norm(m) for m in lib_indy}
problems = []
for src, targets in list(app_refs.items()) + list(lib_refs.items()):
    if norm(src) in bad_norm: continue
    for t in targets:
        if norm(t) in bad_norm: problems.append(f"{src} -> {t}")
if problems:
    print("ERRO: chamadas a métodos com invokedynamic:", *problems[:30], sep="\n  "); sys.exit(1)
print(f"   ok: app sem invokedynamic; {len(lib_indy)} método(s) de biblioteca com indy são inalcançáveis")
