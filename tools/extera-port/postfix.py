"""Scripted fixes applied after regen (idempotent). FIX(path, old, new)."""
import os, re, sys
B = '/tmp/claude-0/-root/940cb9f5-d4d4-4fa3-a290-20db57db5d96/scratchpad/port/com/exteragram/messenger/'
fails = []
def FIX(path, old, new, count=1):
    p = B + path
    s = open(p).read()
    if new in s:
        return
    if old not in s:
        fails.append((path, old[:80])); return
    s = s.replace(old, new, count)
    open(p, 'w').write(s)
def SUB(path, pat, rep):
    p = B + path; s = open(p).read(); s2 = re.sub(pat, rep, s)
    open(p, 'w').write(s2)

FIX('utils/ui/PopupUtils.java', 'checkBoxCell.setChecked(!r0.isChecked(), true);', 'checkBoxCell.setChecked(!checkBoxCell.isChecked(), true);')
FIX('utils/chats/DoubleTapUtils.java', 'return getDoubleTapActions(z)[Math.min(Math.max(i, 0), r2.length - 1)];', 'CharSequence[] actions = getDoubleTapActions(z);\n        return actions[Math.min(Math.max(i, 0), actions.length - 1)];')
FIX('utils/chats/DoubleTapUtils.java', 'return getDoubleTapIcons(z)[Math.min(Math.max(i, 0), r2.length - 1)];', 'int[] icons = getDoubleTapIcons(z);\n        return icons[Math.min(Math.max(i, 0), icons.length - 1)];')

def _match(s, i):
    d = 0; j = i
    while j < len(s):
        c = s[j]
        if c == '"':
            j += 1
            while s[j] != '"':
                if s[j] == '\\': j += 1
                j += 1
        elif c == "'":
            j += 1
            while s[j] != "'":
                if s[j] == '\\': j += 1
                j += 1
        elif c == '{': d += 1
        elif c == '}':
            d -= 1
            if d == 0: return j
        j += 1
    raise ValueError
def METHOD(path, prefix, new):
    """Replace the whole member starting with `prefix` (incl. leading annotations/comments on preceding lines kept) by `new`."""
    p = B + path; s = open(p).read()
    if new.strip() and new.strip() in s:
        return
    i = s.find(prefix)
    if i < 0:
        if new.strip():
            fails.append((path, 'METHOD ' + prefix[:60]))
        return
    ob = s.index('{', i); cb = _match(s, ob)
    s = s[:i] + new.strip() + s[cb+1:]
    open(p, 'w').write(s)

exec(open(os.path.join(os.path.dirname(__file__), 'postfix_rules.py')).read())

if __name__ == '__main__':
    for f in fails: print('POSTFIX FAIL', f)

# final pass: drop imports that became unused after the rules
import importlib.util as _u
_spec = _u.spec_from_file_location('clean', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'clean.py'))
_clean = _u.module_from_spec(_spec); _spec.loader.exec_module(_clean)
for _root, _d, _files in os.walk(B):
    for _f in _files:
        if _f.endswith('.java'):
            _p = os.path.join(_root, _f); _s = open(_p).read(); _s2 = _clean.prune_imports(_s)
            if _s2 != _s: open(_p, 'w').write(_s2)
