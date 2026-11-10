#!/usr/bin/env python3
"""Clean JADX output of Kotlin/R8 artifacts: annotations, broken enums, synthetic throw helpers."""
import re, sys

def match_brace(s, i):
    # s[i] == '{'; return index of matching '}' (naive, skips strings/chars)
    depth = 0; j = i; n = len(s)
    while j < n:
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
        elif c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: return j
        j += 1
    raise ValueError('unbalanced')

def strip_annotation(s, name):
    out = []; i = 0
    pat = re.compile(r'@' + name + r'\b')
    while True:
        m = pat.search(s, i)
        if not m: out.append(s[i:]); break
        out.append(s[i:m.start()])
        j = m.end()
        if j < len(s) and s[j] == '(':
            depth = 0; k = j
            while True:
                if s[k] == '"':
                    k += 1
                    while s[k] != '"':
                        if s[k] == '\\': k += 1
                        k += 1
                elif s[k] == '(': depth += 1
                elif s[k] == ')':
                    depth -= 1
                    if depth == 0: break
                k += 1
            j = k + 1
        # eat trailing whitespace/newline
        while j < len(s) and s[j] in ' \t': j += 1
        if j < len(s) and s[j] == '\n' and (not out[-1] or out[-1].rstrip(' \t').endswith('\n') or out[-1].strip()==''):
            # annotation on its own line: drop the line
            out[-1] = out[-1].rstrip(' \t')
            j += 1
        i = j
    return ''.join(out)

def fix_enums(s):
    while True:
        m = re.search(r'/\* JADX WARN: Failed to restore enum class[^\n]*\n(\s*/\* JADX WARN: Unknown enum class pattern[^\n]*\n)?(\s*)public (static )?final class (\w+) \{', s)
        if not m: return s
        indent = m.group(2); name = m.group(4)
        ob = m.end() - 1; cb = match_brace(s, ob)
        body = s[ob+1:cb]
        consts = []
        def const_repl(mm):
            cname = mm.group(1); args = mm.group(2)
            parts = split_args(args)
            extra = parts[2:]
            consts.append(cname + ('(' + ', '.join(extra) + ')' if extra else ''))
            return ''
        body = re.sub(r'\n\s*public static final ' + name + r' (\w+) = new ' + name + r'\((.*?)\);(?=\n)', const_repl, body)
        body = re.sub(r'\n\s*private static final /\* synthetic \*/ (EnumEntries|' + name + r'\[\]) \$(ENTRIES|VALUES)[^;]*;', '', body)
        # remove $values(), values(), valueOf(), getEntries()
        for pat in [r'private static final /\* synthetic \*/ ' + name + r'\[\] \$values\(\) \{',
                    r'public static ' + name + r'\[\] values\(\) \{',
                    r'public static ' + name + r' valueOf\(String \w+\) \{',
                    r'public static EnumEntries<' + name + r'> getEntries\(\) \{']:
            mm = re.search(r'\n[ \t]*' + pat, body)
            if mm:
                e = match_brace(body, mm.end() - 1)
                body = body[:mm.start()] + body[e+1:]
        # static block with $VALUES
        mm = re.search(r'\n[ \t]*static \{\s*\n\s*' + name + r'\[\] [\w$]+ = \$values\(\);', body)
        if mm:
            e = match_brace(body, body.index('{', mm.start()))
            blk = body[mm.start():e+1]
            inner = blk[blk.index('{')+1:-1]
            inner = re.sub(r'\s*' + name + r'\[\] [\w$]+ = \$values\(\);', '', inner)
            inner = re.sub(r'\s*\$VALUES = [\w$]+;', '', inner)
            inner = re.sub(r'\s*\$ENTRIES = EnumEntriesKt\.enumEntries\([\w$]+\);', '', inner)
            body = body[:mm.start()] + (('\n' + indent + '    static {' + inner + '}') if inner.strip() else '') + body[e+1:]
        # constructor
        def ctor(mm):
            params = split_args(mm.group(1))[2:]
            return 'private ' + name + '(' + ', '.join(params) + ') {'
        body = re.sub(r'private ' + name + r'\((.*?)\) \{', ctor, body)
        getentries = '\n' + indent + '    public static java.util.List<' + name + '> getEntries() {\n' + indent + '        return java.util.Arrays.asList(values());\n' + indent + '    }\n'
        newbody = '\n' + indent + '    ' + ',\n'.join([indent + '    ' + c if k else c for k, c in enumerate(consts)]) + ';\n' + getentries + body
        s = s[:m.start()] + indent + 'public enum ' + name + ' {' + newbody + s[cb:]

def split_args(a):
    parts = []; depth = 0; cur = ''; instr = False
    for ch in a:
        if ch == '"': instr = not instr
        if not instr:
            if ch in '([{<': depth += 1
            elif ch in ')]}>': depth -= 1
            elif ch == ',' and depth == 0:
                parts.append(cur.strip()); cur = ''; continue
        cur += ch
    if cur.strip(): parts.append(cur.strip())
    return parts

def clean(s):
    for a in ['Metadata', 'SourceDebugExtension', 'JvmName', 'JvmOverloads', 'JvmStatic', 'JvmField', 'DebugMetadata', 'SinceKotlin', 'PublishedApi', 'JvmSuppressWildcards', 'JvmWildcard']:
        s = strip_annotation(s, a)
    s = re.sub(r'@Deprecated\(message = "[^"]*"(, [^)]*)?\)', '@Deprecated', s)
    s = re.sub(r'[ \t]*/\* (JADX INFO: )?loaded from: [^*]*\*/\n', '', s)
    s = re.sub(r'\w+\$\$ExternalSyntheticBUOutline\d+\.m\(\)', 'com.exteragram.messenger.utils.ExteraCompat.noWhenBranchMatched()', s)
    s = re.sub(r'\w+\$\$ExternalSyntheticBUOutline\d+\.m\(("[^"]*")\)', r'com.exteragram.messenger.utils.ExteraCompat.unsupported(\1)', s)
    s = re.sub(r'\w+\$\.ExternalSyntheticBUOutline0\.m\(\)', 'com.exteragram.messenger.utils.ExteraCompat.noWhenBranchMatched()', s)
    s = re.sub(r'\w+\$\.ExternalSyntheticBUOutline0\.m\(("[^"]*")\)', r'com.exteragram.messenger.utils.ExteraCompat.unsupported(\1)', s)
    # constants substituted by JADX: "X.Y /* 3 */" -> 3
    s = re.sub(r'\b[A-Z]\w*(?:\.\w+)+ /\* (-?\d+L?|\d+\.\d+f?) \*/', r'\1', s)
    s = s.replace('_UrlKt.FRAGMENT_ENCODE_SET', '""')
    s = s.replace('import kotlin.Deprecated;\n', '')
    s = re.sub(r'\w+\$\$ExternalSyntheticBUOutline\d+\.m\((\w+)\);\s*throw null;', r'throw new RuntimeException(\1);', s)
    s = s.replace('PluginsController.INSTANCE.getInstance()', 'PluginsController.getInstance()').replace('PluginsController.INSTANCE.', 'PluginsController.')
    s = s.replace('BadgesController.isDeveloper$default(BadgesController.INSTANCE, null, 1, null)', 'BadgesController.isDeveloper()')
    s = re.sub(r'\(\(BaseFragment\) ([A-Za-z0-9_]+)\)\.', r'\1.', s)
    s = s.replace('NotificationCenter.lambda$postNotificationNameOnUIThread$1(', 'NotificationCenter.postNotificationName(')
    s = re.sub(r'\.lambda\$postNotificationNameOnUIThread\$1\(', '.postNotificationName(', s)
    s = re.sub(r'StringsKt\.contains\$default\(([^,]+), ([^,]+), (\w+), 2, \(Object\) null\)', r'com.exteragram.messenger.utils.ExteraCompat.contains(\1, \2, false)', s)
    s = re.sub(r'StringsKt\.split\$default\(([^,]+), new String\[\]\{("[^"]*")\}, false, 0, 6, \(Object\) null\)', r'com.exteragram.messenger.utils.ExteraCompat.split(\1, \2)', s)
    s = re.sub(r'CollectionsKt\.joinToString\$default\((\w+), ("[^"]*"), null, null, 0, null, null, 62, null\)', r'android.text.TextUtils.join(\2, \1)', s)
    s = re.sub(r'\bIterator<T> ', 'Iterator ', s)
    s = re.sub(r"new ([\w.]+)\(\) \{( // from class: [^\n]*)?\n(\s*/\* JADX WARN: 'super' call moved[^\n]*\n)?\s*\{\n\s*super\(([^;]*)\);\n\s*\}\n", lambda m: 'new %s(%s) {%s\n' % (m.group(1), m.group(4), m.group(2) or ''), s)
    s = re.sub(r'(public (?:void|boolean) on(?:Long)?Click\(UItem item, View view, int position, float x, float y\)) throws [\w., ]+\{', r'\1 {', s)
    s = fix_enums(s)
    s = prune_imports(s)
    s = re.sub(r'\n{3,}', '\n\n', s)
    return s

def prune_imports(s):
    lines = s.split('\n')
    body = '\n'.join(l for l in lines if not l.startswith('import '))
    out = []
    for l in lines:
        m = re.match(r'import (static )?([\w.$]+);', l)
        if m and not m.group(2).endswith('*'):
            simple = m.group(2).split('.')[-1]
            if not re.search(r'(?<![\w$])' + re.escape(simple) + r'(?![\w$])', body):
                continue
        out.append(l)
    return '\n'.join(out)

if __name__ == '__main__':
    for f in sys.argv[1:]:
        src = open(f).read()
        try:
            out = clean(src)
        except Exception as e:
            print('FAIL', f, e); continue
        open(f, 'w').write(out)
