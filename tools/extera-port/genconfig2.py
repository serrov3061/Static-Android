import json
prefs = json.load(open('tools/prefs.json'))
box = {'boolean':'Boolean','int':'Integer','float':'Float','long':'Long'}
lines = []
decl = []
for idx, name, cls, d, key, typ in prefs:
    T = box.get(typ, typ)
    gen = '<%s>' % typ if cls == 'EnumPref' else ''
    decl.append('    private static final %s%s %s = new %s%s(%s, "%s");' % (cls, gen, name, cls, '<>' if cls=='EnumPref' else '', d, key))
    cap = name[0].upper() + name[1:]
    vis = 'private' if name == 'sectionsSeparatedHeadersPreference' else 'public'
    if typ in box:
        lines.append('    %s static %s get%s() {\n        return %s.get();\n    }\n' % (vis, typ, cap, name))
    else:
        lines.append('    %s static %s get%s() {\n        return %s.get();\n    }\n' % (vis, typ, cap, name))
    lines.append('    %s static void set%s(%s value) {\n        %s.set(value);\n    }\n' % (vis, cap, typ, name))
open('tools/config_decls.txt','w').write('\n'.join(decl) + '\n')
open('tools/config_accessors.txt','w').write('\n'.join(lines))
