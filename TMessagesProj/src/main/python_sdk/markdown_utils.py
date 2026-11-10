"""Pure-Python HTML / Markdown parsers producing Telegram message entities.

Offsets and lengths are counted in UTF-16 code units, as Telegram requires.
The parsers return ``ParsedText(text, entities)`` with ``RawEntity`` items; call
``RawEntity.to_tlrpc_object()`` (or ``ParsedText.tlrpc_entities()``) to get
``TLRPC.MessageEntity`` Java objects.
"""

from dataclasses import dataclass, field
from enum import Enum
from html.parser import HTMLParser
from typing import List, Optional

__all__ = ["RawEntity", "ParsedText", "ParsedMessage", "TLEntityType", "parse_html", "parse_markdown",
           "utf16_len", "to_utf16_len", "count_chars_until"]


class TLEntityType(str, Enum):
    """Entity kinds supported by the parsers (compares equal to the plain string)."""
    CODE = "code"
    PRE = "pre"
    STRIKETHROUGH = "strikethrough"
    TEXT_LINK = "text_link"
    BOLD = "bold"
    ITALIC = "italic"
    UNDERLINE = "underline"
    SPOILER = "spoiler"
    CUSTOM_EMOJI = "custom_emoji"
    BLOCKQUOTE = "blockquote"

    def __str__(self):
        return self.value

    @classmethod
    def from_(cls, entity):
        """TLEntityType of a TLRPC.MessageEntity (or None when unsupported)."""
        name = type(entity).__name__
        try:
            name = str(entity.getClass().getSimpleName())
        except Exception:
            pass
        for key, tl_name in ENTITY_CLASSES.items():
            if tl_name == name:
                return cls(key)
        return None

ENTITY_CLASSES = {
    "bold": "TL_messageEntityBold",
    "italic": "TL_messageEntityItalic",
    "underline": "TL_messageEntityUnderline",
    "strikethrough": "TL_messageEntityStrike",
    "code": "TL_messageEntityCode",
    "pre": "TL_messageEntityPre",
    "text_link": "TL_messageEntityTextUrl",
    "spoiler": "TL_messageEntitySpoiler",
    "blockquote": "TL_messageEntityBlockquote",
    "custom_emoji": "TL_messageEntityCustomEmoji",
}


def utf16_len(s: str) -> int:
    return len(s.encode("utf-16-le")) // 2


to_utf16_len = utf16_len


def count_chars_until(text: str, utf16_offset: int) -> int:
    """Number of Python characters of ``text`` that cover the first ``utf16_offset``
    UTF-16 code units (an offset inside a surrogate pair counts the whole character)."""
    units = 0
    for i, ch in enumerate(text):
        if units >= utf16_offset:
            return i
        units += 2 if ord(ch) > 0xFFFF else 1
    return len(text)


@dataclass
class RawEntity:
    type: str
    offset: int
    length: int
    url: Optional[str] = None
    language: Optional[str] = None
    document_id: Optional[int] = None
    collapsed: bool = False

    TLRPC_ENTITIES_MAP = ENTITY_CLASSES

    def to_tlrpc_object(self):
        from _sg_java import J
        obj = J("org.telegram.tgnet.TLRPC$" + ENTITY_CLASSES[str(self.type)])()
        obj.offset = int(self.offset)
        obj.length = int(self.length)
        if self.url is not None:
            obj.url = self.url
        if self.type == "pre":
            obj.language = self.language or ""
        if self.document_id is not None:
            obj.document_id = int(self.document_id)
        if self.collapsed:
            obj.collapsed = True
        return obj


@dataclass
class ParsedText:
    text: str
    entities: List[RawEntity] = field(default_factory=list)

    def tlrpc_entities(self):
        return [e.to_tlrpc_object() for e in self.entities]


ParsedMessage = ParsedText


def _finish(text, entities):
    # strip surrounding whitespace like Telegram does, keeping entities consistent
    lead = len(text) - len(text.lstrip())
    stripped = text.strip()
    shift = utf16_len(text[:lead])
    total = utf16_len(stripped)
    out = []
    for e in entities:
        start = max(0, e.offset - shift)
        end = min(total, e.offset - shift + e.length)
        if end > start:
            e.offset, e.length = start, end - start
            out.append(e)
    out.sort(key=lambda e: (e.offset, -e.length))
    return ParsedText(stripped, out)


# ---------------------------------------------------------------- HTML

_SIMPLE_TAGS = {
    "b": "bold", "strong": "bold",
    "i": "italic", "em": "italic",
    "u": "underline", "ins": "underline",
    "s": "strikethrough", "strike": "strikethrough", "del": "strikethrough",
    "tg-spoiler": "spoiler",
    "code": "code",
    "pre": "pre",
    "blockquote": "blockquote",
    "a": "text_link",
    "tg-emoji": "custom_emoji",
}


class _HtmlEntityParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.pos = 0
        self.stack = []
        self.entities = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "br":
            self.handle_data("\n")
            return
        kind = _SIMPLE_TAGS.get(tag)
        if tag == "span" and "tg-spoiler" in (attrs.get("class") or ""):
            kind = "spoiler"
        self.stack.append((tag, kind, self.pos, attrs))

    def handle_startendtag(self, tag, attrs):
        if tag == "br":
            self.handle_data("\n")

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                _, kind, start, attrs = self.stack.pop(i)
                self._close(kind, start, attrs)
                return

    def _close(self, kind, start, attrs):
        length = self.pos - start
        if kind is None or length <= 0:
            return
        if kind == "code" and any(t == "pre" for t, *_ in self.stack):
            # <pre><code class="language-x"> -> language of the enclosing pre
            cls = attrs.get("class") or ""
            if cls.startswith("language-"):
                for j in range(len(self.stack) - 1, -1, -1):
                    if self.stack[j][0] == "pre":
                        self.stack[j][3]["__language"] = cls[len("language-"):]
                        break
            return
        e = RawEntity(kind, start, length)
        if kind == "text_link":
            href = attrs.get("href")
            if not href:
                return
            e.url = href
        elif kind == "pre":
            e.language = attrs.get("__language") or attrs.get("language") or ""
        elif kind == "custom_emoji":
            try:
                e.document_id = int(attrs.get("emoji-id"))
            except (TypeError, ValueError):
                return
        elif kind == "blockquote":
            e.collapsed = "expandable" in attrs
        self.entities.append(e)

    def handle_data(self, data):
        self.parts.append(data)
        self.pos += utf16_len(data)


def parse_html(text: str) -> ParsedText:
    p = _HtmlEntityParser()
    p.feed(text)
    p.close()
    while p.stack:
        _, kind, start, attrs = p.stack.pop()
        p._close(kind, start, attrs)
    return _finish("".join(p.parts), p.entities)


# ---------------------------------------------------------------- Markdown

_DELIMS = [
    ("**", "bold"), ("__", "underline"), ("~~", "strikethrough"), ("||", "spoiler"),
    ("*", "bold"), ("_", "italic"), ("~", "strikethrough"),
]
_ESCAPABLE = set("\\`*_~|[]()>#+-=.!{}")


def _find_closing(s, start, delim):
    i = start
    while i < len(s):
        if s[i] == "\\":
            i += 2
            continue
        if s.startswith(delim, i):
            # a single-char delimiter must not match the start of its doubled form
            if len(delim) == 1 and s.startswith(delim * 2, i):
                i += 2
                continue
            return i
        i += 1
    return -1


def _md(s, base):
    out = []
    ents = []
    pos = base
    i = 0
    n = len(s)

    def emit(t):
        nonlocal pos
        out.append(t)
        pos += utf16_len(t)

    while i < n:
        c = s[i]
        if c == "\\" and i + 1 < n and s[i + 1] in _ESCAPABLE:
            emit(s[i + 1])
            i += 2
            continue
        if s.startswith("```", i):
            end = s.find("```", i + 3)
            if end != -1:
                body = s[i + 3:end]
                lang = ""
                nl = body.find("\n")
                if nl != -1 and body[:nl].strip() and " " not in body[:nl].strip():
                    lang, body = body[:nl].strip(), body[nl + 1:]
                elif body.startswith("\n"):
                    body = body[1:]
                if body.endswith("\n"):
                    body = body[:-1]
                start = pos
                emit(body)
                if pos > start:
                    ents.append(RawEntity("pre", start, pos - start, language=lang))
                i = end + 3
                continue
        if c == "`":
            end = s.find("`", i + 1)
            if end != -1:
                start = pos
                emit(s[i + 1:end])
                if pos > start:
                    ents.append(RawEntity("code", start, pos - start))
                i = end + 1
                continue
        if c == "[":
            close = _find_closing(s, i + 1, "]")
            if close != -1 and s.startswith("(", close + 1):
                end = s.find(")", close + 2)
                if end != -1:
                    inner_text, inner_ents = _md(s[i + 1:close], pos)
                    start = pos
                    emit(inner_text)
                    ents.extend(inner_ents)
                    url = s[close + 2:end].strip()
                    if pos > start and url:
                        ents.append(RawEntity("text_link", start, pos - start, url=url))
                    i = end + 1
                    continue
        matched = False
        for delim, kind in _DELIMS:
            if s.startswith(delim, i):
                end = _find_closing(s, i + len(delim), delim)
                if end > i + len(delim):
                    inner_text, inner_ents = _md(s[i + len(delim):end], pos)
                    start = pos
                    emit(inner_text)
                    ents.extend(inner_ents)
                    ents.append(RawEntity(kind, start, pos - start))
                    i = end + len(delim)
                    matched = True
                break
        if matched:
            continue
        emit(c)
        i += 1
    return "".join(out), ents


def parse_markdown(text: str) -> ParsedText:
    t, ents = _md(text, 0)
    return _finish(t, ents)
