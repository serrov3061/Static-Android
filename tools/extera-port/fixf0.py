"""Replace JADX lambda captures `this.f$0` / `this.this$0` with `<InnermostNamedClass>.this`."""
import re, sys
def mask(s):
    s = re.sub(r'/\*.*?\*/', lambda m: ' ' * len(m.group(0)), s, flags=re.S)
    s = re.sub(r'//[^\n]*', lambda m: ' ' * len(m.group(0)), s)
    s = re.sub(r'"(?:\\.|[^"\\])*"', lambda m: '"' + ' ' * (len(m.group(0)) - 2) + '"', s)
    return s
def spans(s):
    s = mask(s)
    res = []
    for m in re.finditer(r'\b(?:class|enum|interface)\s+(\w+)[^{;]*\{', s):
        ob = m.end() - 1
        d = 0; j = ob
        while j < len(s):
            c = s[j]
            if c == '"':
                j += 1
                while j < len(s) and s[j] != '"':
                    if s[j] == '\\': j += 1
                    j += 1
            elif c == "'":
                j += 1
                while j < len(s) and s[j] != "'":
                    if s[j] == '\\': j += 1
                    j += 1
            elif c == '{': d += 1
            elif c == '}':
                d -= 1
                if d == 0: break
            j += 1
        res.append((ob, j, m.group(1)))
    return res
for f in sys.argv[1:]:
    s = open(f).read()
    if 'this.f$0' not in s and 'this.this$0' not in s and 'ExternalSyntheticLambda' not in s:
        continue
    sp = spans(s)
    ms = s_mask = mask(s)
    meth = []
    for m in re.finditer(r'\bstatic\b[^;{}()=]*?\b(\w+)\(([^()]*)\)\s*(?:throws [\w., ]+)?\{', ms):
        ob = m.end() - 1
        d = 0; j = ob
        while j < len(ms):
            if ms[j] == '{': d += 1
            elif ms[j] == '}':
                d -= 1
                if d == 0: break
            j += 1
        params = {}
        for prm in m.group(2).split(','):
            prm = re.sub(r'\bfinal\s+', '', prm.strip())
            mm = re.match(r'([\w.<>\[\]]+)\s+(\w+)$', prm)
            if mm: params[mm.group(1).split('.')[-1]] = mm.group(2)
        meth.append((ob, j, params))
    out = []; last = 0
    lam = []
    for m in re.finditer(r'\{ // from class: [\w.$]+\$\$ExternalSyntheticLambda\d+', s):
        ob = m.start(); d = 0; j = ob
        while j < len(ms):
            if ms[j] == '{': d += 1
            elif ms[j] == '}':
                d -= 1
                if d == 0: break
            j += 1
        lam.append((ob, j))
    targets = []
    for m in re.finditer(r'this\.(f\$0|this\$0)\b', s):
        targets.append((m.start(), m.end()))
    for m in re.finditer(r'(?<![\w.])this\b(?!\s*\.)(?!\s*\()', ms):
        if any(ob < m.start() < cb for ob, cb in lam):
            targets.append((m.start(), m.end()))
    targets.sort()
    class _M:
        def __init__(self, a, b): self.a = a; self.b = b
        def start(self): return self.a
        def end(self): return self.b
    for m in [_M(a, b) for a, b in targets]:
        pos = m.start()
        best = None
        for ob, cb, name in sp:
            if ob < pos < cb and (best is None or ob > best[0]):
                best = (ob, name)
        name = best[1] if best else f.split('/')[-1][:-5]
        repl = name + '.this'
        inner = None
        for ob, cb, params in meth:
            if ob < pos < cb and (inner is None or ob > inner[0]):
                inner = (ob, params)
        if inner is not None and (best is None or inner[0] > best[0]) and name in inner[1]:
            repl = inner[1][name]
        out.append(s[last:pos]); out.append(repl); last = m.end()
    out.append(s[last:])
    open(f, 'w').write(''.join(out))
