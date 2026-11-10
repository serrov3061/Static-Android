"""Strings: a locale-aware, read-only lookup over {locale: {key: value}}."""

__all__ = ["Strings"]

_language_override = None


def _system_language():
    try:
        from _sg_java import J
        lang = J("org.telegram.messenger.LocaleController").getInstance().getCurrentLocaleInfo().getLangCode()
        if lang:
            return str(lang).split("_")[0].split("-")[0].lower()
    except Exception:
        pass
    try:
        from _sg_java import J
        return str(J("java.util.Locale").getDefault().getLanguage()).lower()
    except Exception:
        return "en"


class Strings:
    def __init__(self, all_strings):
        self._strings = {str(k).lower(): dict(v or {}) for k, v in dict(all_strings or {}).items()}

    @staticmethod
    def get_current_language():
        return _language_override or _system_language()

    def _lookup(self, key, locale=None):
        for lang in (locale or self.get_current_language(), "en"):
            table = self._strings.get(str(lang).lower())
            if table is not None and key in table:
                return True, table[key]
        return False, None

    def get_with_locale(self, key, default=None, locale=None):
        found, value = self._lookup(key, locale)
        if found:
            return value
        return key if default is None else default

    def get(self, key, default=None):
        return self.get_with_locale(key, default)

    def __call__(self, key, *args, default=None, locale=None, **kwargs):
        value = self.get_with_locale(key, default, locale)
        if isinstance(value, str) and (args or kwargs):
            try:
                return value.format(*args, **kwargs)
            except (IndexError, KeyError, ValueError):
                return value
        return value

    def __getitem__(self, key):
        return self.get(key)

    def __getattr__(self, key):
        if key.startswith("_"):
            raise AttributeError(key)
        return self.get(key)

    def __setitem__(self, key, value):
        raise TypeError("Strings is read-only")

    def __contains__(self, key):
        return self._lookup(key)[0]

    @staticmethod
    def pluralization_string(count, forms):
        """Slavic-style choice between three forms (one / few / many)."""
        forms = list(forms or [])
        if not forms:
            return ""
        while len(forms) < 3:
            forms.append(forms[-1])
        n = abs(int(count))
        if n % 10 == 1 and n % 100 != 11:
            return forms[0]
        if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
            return forms[1]
        return forms[2]

    def pluralize(self, count, key, locale=None):
        forms = self.get_with_locale(key, None, locale)
        if isinstance(forms, str):
            forms = [forms]
        return "%s %s" % (count, self.pluralization_string(count, forms))

    def __repr__(self):
        return "Strings(%s)" % ", ".join(sorted(self._strings))
