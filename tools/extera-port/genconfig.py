import re
src = open('ex/com/exteragram/messenger/ExteraConfig.java').read()
init = re.findall(r'\n\s+(\w+)\$delegate = new (\w+)\((.*?)\)\.provideDelegate\(null, kPropertyArr\[(\d+)\]\);', src)
getters = dict()
for m in re.finditer(r'public static final ([\w<>.]+) get(\w+)\(\) \{\n\s+return .*?(\w+)\$delegate\.getValue', src):
    getters[m.group(3)] = m.group(1)
for m in re.finditer(r'private static final ([\w<>.]+) get(\w+)\(\) \{\n\s+return .*?(\w+)\$delegate\.getValue', src):
    getters[m.group(3)] = m.group(1)
def split_args(a):
    parts=[];d=0;cur='';ins=False
    for ch in a:
        if ch=='"': ins=not ins
        if not ins:
            if ch in '([': d+=1
            elif ch in ')]': d-=1
            elif ch==',' and d==0: parts.append(cur.strip()); cur=''; continue
        cur+=ch
    parts.append(cur.strip()); return parts
out=[]
for name, cls, args, idx in sorted(init, key=lambda t:int(t[3])):
    a = split_args(args)
    d = a[0]
    if re.match(r'^(1|c\d?) == true \? 1 : 0$', d) or d == '1 == true ? 1 : 0': d = 'true'
    if d == 'z': d = 'false'
    if d.endswith('== true ? 1 : 0'): d = 'true'
    if cls in ('IntegerPref','SanitizedIntegerPref') and d == 'true': d = '1'
    if cls == 'FloatPref' and not d.endswith('f'): d += 'f'
    if cls == 'StringSetPref' and d == 'SetsKt.emptySet()': d = 'java.util.Collections.<String>emptySet()'
    key = name
    if len(a) > 1 and a[1].startswith('"'): key = a[1].strip('"')
    out.append((int(idx), name, cls, d, key, getters.get(name)))
for o in out: print(o)
import json; json.dump(out, open('tools/prefs.json','w'), indent=0)
