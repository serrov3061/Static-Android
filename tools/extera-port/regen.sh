#!/bin/bash
# Re-copy JADX-derived exteraGram sources into port/ (keeps hand-written files listed in tools/handwritten.txt)
S=/tmp/claude-0/-root/940cb9f5-d4d4-4fa3-a290-20db57db5d96/scratchpad
cd $S
for f in $(cat tools/derived.txt); do
  if grep -qx "$f" tools/handwritten.txt; then continue; fi
  mkdir -p port/com/exteragram/messenger/$(dirname $f)
  cp ex/com/exteragram/messenger/$f port/com/exteragram/messenger/$f
  python3 tools/clean.py port/com/exteragram/messenger/$f
  python3 tools/fixf0.py port/com/exteragram/messenger/$f
done
python3 $S/tools/postfix.py
