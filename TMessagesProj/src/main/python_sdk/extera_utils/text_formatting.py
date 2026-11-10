"""Text formatting: HTML / Markdown to plain text + Telegram entities, and back.

``parse_text(text, parse_mode="HTML", is_caption=False)`` returns
``{"message"|"caption": str, "entities": [TLRPC.MessageEntity, ...]}``.
``HTML`` and ``Markdown`` expose ``parse(text)`` (plain text + RawEntity list) and
``unparse(text, entities)`` (formatted string from plain text and entities).
"""

import html
import re
import struct
from typing import Any, Dict, List, Optional, Tuple

from markdown_utils import ENTITY_CLASSES, ParsedText, RawEntity, TLEntityType, parse_html, parse_markdown, utf16_len

__all__ = ["parse_text", "parse_text_raw", "HTML", "Markdown", "TLEntityType", "RawEntity",
           "add_surrogates", "remove_surrogates", "replace_once"]

_SMP_RE = re.compile(r"[\U00010000-\U0010FFFF]")


def add_surrogates(text: str) -> str:
    """Replaces astral characters with surrogate pairs, so indices count UTF-16 units."""
    return _SMP_RE.sub(
        lambda m: "".join(chr(i) for i in struct.unpack("<HH", m.group().encode("utf-16-le"))), text)


def remove_surrogates(text: str) -> str:
    """Inverse of add_surrogates."""
    return text.encode("utf-16", "surrogatepass").decode("utf-16")


def replace_once(source: str, old: str, new: str, start: int) -> str:
    """Replaces the first ``old`` at or after ``start``."""
    return source[:start] + source[start:].replace(old, new, 1)


def parse_text_raw(text, parse_mode="HTML") -> ParsedText:
    mode = (parse_mode or "").strip().lower()
    if mode == "html":
        return parse_html(text)
    if mode in ("markdown", "md", "markdownv2"):
        return parse_markdown(text)
    raise ValueError("Unsupported parse_mode: %r (use 'HTML' or 'Markdown')" % (parse_mode,))


def parse_text(text: str, parse_mode: Optional[str] = "HTML", is_caption: bool = False) -> Dict[str, Any]:
    parsed = parse_text_raw(text, parse_mode)
    return {
        ("caption" if is_caption else "message"): parsed.text,
        "entities": parsed.tlrpc_entities(),
    }


# ---------------------------------------------------------------- unparse

def _entity_fields(e):
    """(TLEntityType, offset, length, extra) for a RawEntity or a TLRPC.MessageEntity."""
    if isinstance(e, RawEntity):
        return TLEntityType(str(e.type)), e.offset, e.length, {
            "url": e.url, "language": e.language, "document_id": e.document_id, "collapsed": e.collapsed}
    kind = TLEntityType.from_(e)
    if kind is None:
        return None
    extra = {}
    for name in ("url", "language", "document_id", "collapsed"):
        try:
            extra[name] = getattr(e, name)
        except Exception:
            extra[name] = None
    return kind, int(e.offset), int(e.length), extra


def _unparse(text, entities, open_tag, close_tag, escape):
    """Inserts tags at UTF-16 offsets; nested entities close in reverse order."""
    text16 = add_surrogates(text)
    inserts = []  # (position, order, string)
    items = [f for f in (_entity_fields(e) for e in entities or ()) if f is not None]
    for idx, (kind, offset, length, extra) in enumerate(items):
        start, end = offset, offset + length
        inserts.append((start, 1, -end, idx, open_tag(kind, extra)))
        inserts.append((end, 0, -start, -idx, close_tag(kind, extra)))
    inserts.sort(key=lambda t: (t[0], t[1], t[2], t[3]))
    out, pos = [], 0
    for position, _, _, _, tag in inserts:
        position = max(0, min(position, len(text16)))
        out.append(escape(text16[pos:position]))
        out.append(tag)
        pos = position
    out.append(escape(text16[pos:]))
    return remove_surrogates("".join(out))


class HTML:
    _OPEN = {
        TLEntityType.BOLD: "<b>", TLEntityType.ITALIC: "<i>", TLEntityType.UNDERLINE: "<u>",
        TLEntityType.STRIKETHROUGH: "<s>", TLEntityType.CODE: "<code>", TLEntityType.SPOILER: "<spoiler>",
    }
    _CLOSE = {
        TLEntityType.BOLD: "</b>", TLEntityType.ITALIC: "</i>", TLEntityType.UNDERLINE: "</u>",
        TLEntityType.STRIKETHROUGH: "</s>", TLEntityType.CODE: "</code>", TLEntityType.SPOILER: "</spoiler>",
        TLEntityType.PRE: "</pre>", TLEntityType.TEXT_LINK: "</a>", TLEntityType.CUSTOM_EMOJI: "</emoji>",
        TLEntityType.BLOCKQUOTE: "</blockquote>",
    }

    @staticmethod
    def parse(text: str) -> Dict[str, Any]:
        parsed = parse_html(text)
        return {"message": parsed.text, "entities": list(parsed.entities)}

    @classmethod
    def _open(cls, kind, extra):
        if kind is TLEntityType.PRE:
            lang = extra.get("language")
            return '<pre language="%s">' % html.escape(lang, quote=True) if lang else "<pre>"
        if kind is TLEntityType.TEXT_LINK:
            return '<a href="%s">' % html.escape(extra.get("url") or "", quote=True)
        if kind is TLEntityType.CUSTOM_EMOJI:
            return '<emoji id="%s">' % (extra.get("document_id") or 0)
        if kind is TLEntityType.BLOCKQUOTE:
            return "<blockquote expandable>" if extra.get("collapsed") else "<blockquote>"
        return cls._OPEN[kind]

    @classmethod
    def unparse(cls, text: str, entities: List[Any]) -> str:
        return _unparse(text, entities, cls._open, lambda k, e: cls._CLOSE[k],
                        lambda s: html.escape(s, quote=False))


class Markdown:
    _DELIMS = {
        TLEntityType.BOLD: "*", TLEntityType.ITALIC: "_", TLEntityType.UNDERLINE: "__",
        TLEntityType.STRIKETHROUGH: "~", TLEntityType.SPOILER: "||", TLEntityType.CODE: "`",
    }
    ESCAPABLE = "\\_*[]()~`>#+-=|{}.!"

    @staticmethod
    def parse(text: str) -> Dict[str, Any]:
        parsed = parse_markdown(text)
        return {"message": parsed.text, "entities": list(parsed.entities)}

    @classmethod
    def protect_escaped_chars(cls, text: str) -> Tuple[str, Dict[str, str]]:
        """Replaces ``\\x`` escapes with placeholders; returns (text, placeholders)."""
        mapping = {}

        def replace(m):
            key = "\x00%d\x00" % len(mapping)
            mapping[key] = m.group(1)
            return key
        return re.sub(r"\\(.)", replace, text), mapping

    @staticmethod
    def restore_escaped_chars(text: str, mapping: Dict[str, str]) -> str:
        for key, ch in mapping.items():
            text = text.replace(key, ch)
        return text

    @classmethod
    def _escape(cls, s):
        return "".join("\\" + c if c in "_*[]()~`|\\" else c for c in s)

    @classmethod
    def _open(cls, kind, extra):
        if kind is TLEntityType.PRE:
            return "```%s\n" % (extra.get("language") or "")
        if kind in (TLEntityType.TEXT_LINK, TLEntityType.CUSTOM_EMOJI):
            return "![" if kind is TLEntityType.CUSTOM_EMOJI else "["
        if kind is TLEntityType.BLOCKQUOTE:
            return "\x01Q%s\x01" % ("E" if extra.get("collapsed") else "")
        return cls._DELIMS[kind]

    @classmethod
    def _close(cls, kind, extra):
        if kind is TLEntityType.PRE:
            return "\n```"
        if kind is TLEntityType.TEXT_LINK:
            return "](%s)" % (extra.get("url") or "")
        if kind is TLEntityType.CUSTOM_EMOJI:
            return "](tg://emoji?id=%s)" % (extra.get("document_id") or 0)
        if kind is TLEntityType.BLOCKQUOTE:
            return "\x01q\x01"
        return cls._DELIMS[kind]

    @classmethod
    def unparse(cls, text: str, entities: List[Any]) -> str:
        out = _unparse(text, entities, cls._open, cls._close, cls._escape)
        return cls._quotes(out)

    @staticmethod
    def _quotes(text):
        """Turns the blockquote markers into ``>`` / ``**>`` line prefixes."""
        result, depth_expandable, inside = [], False, False
        for segment in re.split(r"(\x01Q?E?\x01|\x01q\x01)", text):
            if segment in ("\x01Q\x01", "\x01QE\x01"):
                inside, depth_expandable = True, segment == "\x01QE\x01"
                result.append("**>" if depth_expandable else ">")
                continue
            if segment == "\x01q\x01":
                inside = False
                if depth_expandable:
                    result.append("||")
                continue
            if inside:
                segment = segment.replace("\n", "\n>")
            result.append(segment)
        return "".join(result)
