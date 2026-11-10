#!/usr/bin/env python3
"""Import exteraGram strings/drawables referenced by the port into our TMessagesProj res (run on sg)."""
import re, os, sys, glob, shutil
EX = '/root/exterastatic-src/decoded'
OUR = '/root/staticgram-client-extera/TMessagesProj/src/main/res'
refs = [l.strip() for l in open(sys.argv[1]) if l.strip()]

def elements(path):
    s = open(path, encoding='utf-8').read()
    d = {}
    for m in re.finditer(r'<(string|plurals|string-array)\s+name="([^"]+)"[^>]*?(?:/>|>.*?</\1>)', s, re.S):
        d[m.group(2)] = (m.group(1), m.group(0))
    return d

ours = {}
for f in glob.glob(OUR + '/values/*.xml'):
    ours.update(elements(f))
ours_ru = {}
for f in glob.glob(OUR + '/values-ru/*.xml'):
    ours_ru.update(elements(f))
ex_en = elements(EX + '/res/values/strings.xml')
ex_en.update({k: v for k, v in elements(EX + '/res/values/plurals.xml').items()} if os.path.exists(EX + '/res/values/plurals.xml') else {})
ex_ru = elements(EX + '/assets/extera_locales/values-ru/extera.xml')
ex_loc_en = elements(EX + '/assets/extera_locales/values/extera.xml')

# existing generated files (merge, so the script can be re-run with more refs)
gen_en_path = OUR + '/values/strings_extera.xml'
gen_ru_path = OUR + '/values-ru/strings_extera.xml'
gen_en = elements(gen_en_path) if os.path.exists(gen_en_path) else {}
gen_ru = elements(gen_ru_path) if os.path.exists(gen_ru_path) else {}

def clean(el):
    return re.sub(r'\s+translatable="false"', '', el)

missing = []
for r in refs:
    _, typ, name = r.split('.', 2)
    if typ in ('string', 'plurals'):
        if name not in ours or name in gen_en:
            src = ex_en.get(name) or ex_loc_en.get(name)
            if name in ours and name not in gen_en:
                pass
            elif src:
                gen_en[name] = src
            else:
                missing.append(r)
        if name in ex_ru and name not in ours_ru or name in gen_ru:
            if name in ex_ru:
                gen_ru[name] = ex_ru[name]
    elif typ in ('drawable', 'raw'):
        if glob.glob(OUR + '/%s*/%s.*' % (typ, name)):
            continue
        found = glob.glob(EX + '/res/%s*/%s.*' % (typ, name))
        if not found:
            missing.append(r)
        for f in found:
            d = os.path.basename(os.path.dirname(f))
            os.makedirs(OUR + '/' + d, exist_ok=True)
            shutil.copy(f, OUR + '/' + d + '/')
            print('copied', d + '/' + os.path.basename(f))

def write(path, d):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as fp:
        fp.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- exteraGram 12.10.6 strings imported for the StaticGram port (see EXTERA_PORT.md) -->\n<resources>\n')
        for k in sorted(d):
            fp.write('    ' + clean(d[k][1]) + '\n')
        fp.write('</resources>\n')
write(gen_en_path, gen_en)
write(gen_ru_path, gen_ru)
print('en', len(gen_en), 'ru', len(gen_ru))
print('MISSING', missing)
