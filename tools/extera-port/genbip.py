exec(open("/tmp/icscan.py").read().split("for nm,sec")[0])
our=names(OUR)  # refresh after copy
# icscan.py cuts sections at the FIRST occurrence of the lambda name, which is the call site in the
# Lazy initialiser -> the remix section would swallow the whole solar body. Anchor on the definitions.
sol=section("SparseIntArray solar_delegate$lambda$1()","return sparseIntArray")
rem=section("SparseIntArray remix_delegate$lambda$5()","return sparseIntArray")
def body(sec):
    out=[];skip=[]
    for k,v in pair.findall(sec):
        if k in our and v in our: out.append("        map.put(R.drawable.%s, R.drawable.%s);"%(k,v))
        else: skip.append(k)
    return out,skip
so,ss=body(sol); ro,rs=body(rem)
java='''package com.exteragram.messenger.icons;

import android.util.SparseIntArray;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/**
 * Built-in icon packs of exteraGram (BaseIconPacks.kt): Telegram drawable id -> Solar / Remix replacement.
 * Generated from the exteraGram 12.10.6 map; pairs whose source drawable does not exist in StaticGram
 * (exteraGram-only icons such as filter_*, plugins_filled ...) are skipped: %d (solar), %d (remix).
 * "base.default" deliberately has no replacements here (exteraGram maps popup_fixed_alert* there),
 * so the default pack keeps Telegram's resources untouched.
 */
public final class BaseIconPacks {

    public static final BaseIconPacks INSTANCE = new BaseIconPacks();

    private static SparseIntArray solar;
    private static SparseIntArray remix;

    private BaseIconPacks() {
    }

    public synchronized SparseIntArray getSolar() {
        if (solar == null) {
            solar = createSolar();
        }
        return solar;
    }

    public synchronized SparseIntArray getRemix() {
        if (remix == null) {
            remix = createRemix();
        }
        return remix;
    }

    public SparseIntArray getDef() {
        return new SparseIntArray(0);
    }

    private static SparseIntArray createSolar() {
        SparseIntArray map = new SparseIntArray(%d);
%s
        return map;
    }

    private static SparseIntArray createRemix() {
        SparseIntArray map = new SparseIntArray(%d);
%s
        return map;
    }

    public IconPack getBasePack(String id) {
        if (id == null) {
            return null;
        }
        switch (id) {
            case "base.remix":
                return new IconPack("base.remix", "Remix Icon", "[Remix-Design](https://github.com/Remix-Design/RemixIcon)", getRemix());
            case "base.solar":
                return new IconPack("base.solar", "Solar Icon Set", "@Design480", getSolar());
            case "base.default":
                return new IconPack("base.default", LocaleController.getString(R.string.Default), "Telegram", getDef());
            default:
                return null;
        }
    }
}
'''%(len(ss),len(rs),len(so),"\n".join(so),len(ro),"\n".join(ro))
# createSolar() is kept verbatim from the committed file (it carries hand-added Cherrygram pairs and
# must not change); only the Remix map (and the header skip count) is regenerated.
import re as _re
_P="/root/staticgram-client-extera/TMessagesProj/src/main/java/com/exteragram/messenger/icons/BaseIconPacks.java"
_old=open(_P).read()
_m=lambda s:_re.search(r"    private static SparseIntArray createSolar\(\) \{.*?\n    \}\n",s,_re.S).group(0)
java=java.replace(_m(java),_m(_old))
_h=_re.search(r"pairs whose source.*?skipped: (\d+) \(solar\), \d+ \(remix\)",_old,_re.S)
java=_re.sub(r"skipped: \d+ \(solar\), (\d+) \(remix\)",lambda m:"skipped: %s (solar), %d (remix)"%(_h.group(1) if False else _re.search(r"skipped: (\d+) \(solar\)",_old).group(1),len(rs)),java,1)
open("/root/staticgram-client-extera/TMessagesProj/src/main/java/com/exteragram/messenger/icons/BaseIconPacks.java","w").write(java)
print(len(so),len(ro),sorted(set(ss)))
