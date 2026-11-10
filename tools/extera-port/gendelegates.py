"""Generate Java pref fields+accessors from a JADX'ed Kotlin object with BasePref delegates."""
import re, sys
src = open(sys.argv[1]).read()
init = re.findall(r'\n\s+(\w+)\$delegate = new (\w+)\((.*?)\)\.provideDelegate\(null, \w+\[(\d+)\]\);', src)
getters = {}
for m in re.finditer(r'(?:public|private) static final ([\w<>.]+) get(\w+)\(\) \{\n\s+return .*?(\w+)\$delegate\.getValue', src):
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
for name, cls, args, idx in sorted(init, key=lambda t:int(t[3])):
    a = split_args(args); d = a[0]
    if d.endswith('== true ? 1 : 0'): d = 'true'
    if d == 'z': d = 'false'
    if cls == 'FloatPref' and not d.endswith('f'): d += 'f'
    key = a[1].strip('"') if len(a) > 1 and a[1].startswith('"') else name
    typ = getters.get(name)
    gen = '<%s>' % typ if cls == 'EnumPref' else ''
    print('    private static final %s%s %s = new %s%s(%s, "%s");' % (cls, gen, name, cls, '<>' if cls=='EnumPref' else '', d, key))
print()
for name, cls, args, idx in sorted(init, key=lambda t:int(t[3])):
    typ = getters.get(name); cap = name[0].upper()+name[1:]
    print('    public static %s get%s() {\n        return %s.get();\n    }\n' % (typ, cap, name))
    print('    public static void set%s(%s value) {\n        %s.set(value);\n    }\n' % (cap, typ, name))
