import re,os,glob,collections
SRC="/root/.cache/jadx-extera/full2/sources/com/exteragram/messenger/icons/BaseIconPacks.java"
s=open(SRC).read()
def section(start,end):
    i=s.index(start); j=s.index(end,i); return s[i:j]
sol=section("solar_delegate$lambda$1()","return sparseIntArray")
rem=section("remix_delegate$lambda$5()","return sparseIntArray")
pair=re.compile(r"put\(R\.drawable\.(\w+), R\.drawable\.(\w+)\)")
OUR="/root/staticgram-client-extera/TMessagesProj/src/main/res"
EXT="/root/exterastatic-src/decoded/res"
def names(root):
    d=collections.defaultdict(list)
    for f in glob.glob(root+"/drawable*/*")+glob.glob(root+"/mipmap*/*"):
        n=os.path.basename(f).split(".")[0]
        d[n].append(f)
    return d
our=names(OUR); ext=names(EXT)
for nm,sec in (("solar",sol),("remix",rem)):
    ps=pair.findall(sec)
    mk=[k for k,v in ps if k not in our]
    mt=[v for k,v in ps if v not in our]
    mte=[v for v in mt if v not in ext]
    print(nm,len(ps),"missing keys",len(mk),mk[:20],"missing targets",len(set(mt)),"not in ext",mte[:10])
