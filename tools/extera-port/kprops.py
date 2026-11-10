"""Convert Kotlin SwitchGroup.add$default / addIf$default calls (with MutablePropertyReference0Impl,
BooleanSupplier and Setter anonymous classes) and SwitchGroup constructors into plain Java."""
import re

def skip_str(s, j):
    q = s[j]; j += 1
    while s[j] != q:
        if s[j] == '\\': j += 1
        j += 1
    return j

def match(s, i, o, c):
    d = 0; j = i
    while j < len(s):
        ch = s[j]
        if ch in '"\'': j = skip_str(s, j)
        elif s.startswith('//', j):
            j = s.index('\n', j)
        elif s.startswith('/*', j):
            j = s.index('*/', j) + 1
        elif ch == o: d += 1
        elif ch == c:
            d -= 1
            if d == 0: return j
        j += 1
    raise ValueError

def split_args(a):
    out = []; d = 0; cur = ''; j = 0
    while j < len(a):
        ch = a[j]
        if ch in '"\'':
            k = skip_str(a, j); cur += a[j:k+1]; j = k + 1; continue
        if a.startswith('//', j):
            k = a.index('\n', j); j = k; continue
        if ch in '([{': d += 1
        elif ch in ')]}': d -= 1
        elif ch == ',' and d == 0:
            out.append(cur.strip()); cur = ''; j += 1; continue
        cur += ch; j += 1
    if cur.strip(): out.append(cur.strip())
    return out

def method_body(anon, name):
    m = re.search(r'\b' + name + r'\(([^)]*)\)\s*(?:throws [\w., ]+)?\{', anon)
    if not m: return None, None
    ob = m.end() - 1; cb = match(anon, ob, '{', '}')
    return m.group(1), anon[ob+1:cb].strip()

def getter(arg):
    if 'MutablePropertyReference0Impl' in arg:
        _, b = method_body(arg, 'get')
        mm = re.match(r'return Boolean\.valueOf\((.*)\);$', b, re.S)
        return '() -> ' + mm.group(1).strip()
    if 'BooleanSupplier' in arg:
        _, b = method_body(arg, 'getAsBoolean')
        mm = re.match(r'return (.*);$', b, re.S)
        return '() -> ' + mm.group(1).strip()
    raise ValueError('getter ' + arg[:80])

def setter(arg):
    if 'MutablePropertyReference0Impl' in arg:
        _, b = method_body(arg, 'set')
        b = b.replace('((Boolean) obj).booleanValue()', 'value')
        return 'value -> {\n            ' + b + '\n        }'
    if 'Setter' in arg:
        p, b = method_body(arg, 'set')
        name = p.split()[-1]
        return name + ' -> {\n            ' + b + '\n        }'
    raise ValueError('setter ' + arg[:80])

def convert(s, funrefs):
    # constructors: new SwitchGroup(this, ITEM, RES, title, "alias", false, 40, marker)
    def ctor(m):
        a = split_args(m.group(1))
        if len(a) == 8 and a[6].strip() in ('40', 'i'):
            return 'new SwitchGroup(%s, %s, %s, %s)' % (a[0], a[1], a[2], a[4])
        return m.group(0)
    s = re.sub(r'new SwitchGroup\(([^;]*?)\)(?=;)', ctor, s)
    # onChanged(new X$y$1$1(this)) -> method reference
    for cls, meth in funrefs.items():
        s = s.replace('.onChanged(new %s(this));' % cls, '.onChanged(this::%s);' % meth)
    # onChanged(new Function0() { ... invoke() { return EXPR; } })
    while True:
        i = s.find('.onChanged(new Function0() {')
        if i < 0: break
        ob = s.index('(', i); cb = match(s, ob, '(', ')')
        anon = s[ob+1:cb]
        _, b = method_body(anon, 'invoke')
        mm = re.match(r'return (.*);$', b, re.S)
        s = s[:i] + '.onChanged(() -> ' + mm.group(1).strip() + ')' + s[cb+1:]
    for kind in ('add', 'addIf'):
        tok = 'SwitchGroup.%s$default(' % kind
        while True:
            i = s.find(tok)
            if i < 0: break
            ob = i + len(tok) - 1; cb = match(s, ob, '(', ')')
            a = split_args(s[ob+1:cb])
            if kind == 'add':
                sg, item, res, prop = a[0], a[1], a[2], a[3]
                rep = '%s.add(%s, %s, %s, %s)' % (sg, item, res, getter(prop), setter(prop))
            else:
                sg, vis = a[0], a[1]
                if len(a) == 9:
                    item, res, g, st = a[2], a[3], a[4], a[5]
                    rep = '%s.addIf(%s, %s, %s, %s, %s)' % (sg, getter(vis), item, res, getter(g), setter(st))
                else:
                    item, res, prop = a[2], a[3], a[4]
                    rep = '%s.addIf(%s, %s, %s, %s, %s)' % (sg, getter(vis), item, res, getter(prop), setter(prop))
            s = s[:i] + rep + s[cb+1:]
    return s
