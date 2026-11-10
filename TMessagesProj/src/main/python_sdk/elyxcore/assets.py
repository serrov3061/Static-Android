"""Assets / Asset: read-only access to a plugin resource directory."""

import json
import os
import pathlib
import re
import tempfile

from _sg_java import J

__all__ = ["Asset", "Assets", "AssetNotFoundException", "AssetsDirNotFoundException", "normalize_name",
           "load_data_file"]


class AssetNotFoundException(Exception):
    def __init__(self, name, dir_path=None):
        super().__init__("Asset '%s' not found%s" % (name, " in %s" % dir_path if dir_path else ""))
        self.name = name


class AssetsDirNotFoundException(Exception):
    def __init__(self, dir_path):
        super().__init__("Assets directory not found: %s" % (dir_path,))
        self.dir_path = dir_path


def normalize_name(filename: str) -> str:
    """Logical name of a file: the stem before the first dot, non-alphanumerics -> ``_``."""
    stem = str(filename).split(".", 1)[0]
    return re.sub(r"[^A-Za-z0-9]", "_", stem)


def load_data_file(path):
    """Parses .json / .yaml / .yml files into Python data."""
    path = pathlib.Path(path)
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() == ".json":
        return json.loads(text)
    if path.suffix.lower() in (".yaml", ".yml"):
        import yaml
        return yaml.safe_load(text)
    raise ValueError("unsupported data file: %s" % path.name)


class Asset:
    def __init__(self, dir_path, filename, name=None):
        self._dir = pathlib.Path(dir_path)
        self.filename = str(filename)
        self.name = name or normalize_name(self.filename)
        self.ext = self.filename.rsplit(".", 1)[1].lower() if "." in self.filename else ""

    # ----- paths

    @property
    def path(self) -> pathlib.Path:
        return self._dir / self.filename

    @property
    def path_str(self) -> str:
        return str(self.path)

    @property
    def java_file(self):
        return J("java.io.File")(self.path_str)

    @classmethod
    def from_path(cls, path):
        if hasattr(path, "getAbsolutePath"):
            path = str(path.getAbsolutePath())
        p = pathlib.Path(path)
        if not p.is_file():
            raise AssetNotFoundException(p.name, p.parent)
        return cls(p.parent, p.name)

    @classmethod
    def temp_asset_from_url(cls, url, filename):
        """Downloads ``url`` into the cache (blocking network I/O: use a background queue)."""
        import urllib.request
        tmp_root = pathlib.Path(tempfile.gettempdir()) / "elyx_assets"
        tmp_root.mkdir(parents=True, exist_ok=True)
        target = tmp_root / os.path.basename(str(filename))
        with urllib.request.urlopen(url, timeout=30) as r, open(target, "wb") as f:
            f.write(r.read())
        return cls(tmp_root, target.name)

    # ----- content

    def content_bytes(self) -> bytes:
        return self.path.read_bytes()

    def content_string(self, encoding="utf-8") -> str:
        return self.path.read_text(encoding=encoding)

    def content_json(self):
        return json.loads(self.content_string())

    def content_yaml(self):
        import yaml
        return yaml.safe_load(self.content_string())

    def content(self):
        if self.ext == "json":
            return self.content_json()
        if self.ext in ("yaml", "yml"):
            return self.content_yaml()
        data = self.content_bytes()
        try:
            return data.decode("utf-8")
        except UnicodeDecodeError:
            return data

    # ----- Android conversions

    def to_image_location(self):
        return J("org.telegram.messenger.ImageLocation").getForPath(self.path_str)

    def to_drawable(self):
        return J("android.graphics.drawable.Drawable").createFromPath(self.path_str)

    def to_bitmap_drawable(self, width=32, height=32):
        bmp = J("android.graphics.BitmapFactory").decodeFile(self.path_str)
        if bmp is None:
            return None
        bmp = J("android.graphics.Bitmap").createScaledBitmap(bmp, int(width), int(height), True)
        res = J("org.telegram.messenger.ApplicationLoader").applicationContext.getResources()
        return J("android.graphics.drawable.BitmapDrawable")(res, bmp)

    def to_lottie_drawable(self, width=32, height=32):
        dp = J("org.telegram.messenger.AndroidUtilities").dp
        return J("org.telegram.ui.Components.RLottieDrawable")(
            self.java_file, self.content_string(), dp(width), dp(height), None, False, None, 0, False)

    def to_svg_drawable(self, width=None, height=None):
        SvgHelper = J("org.telegram.messenger.SvgHelper")
        if width is not None and height is not None:
            bmp = SvgHelper.getBitmap(self.java_file, int(width), int(height), False)
            res = J("org.telegram.messenger.ApplicationLoader").applicationContext.getResources()
            return J("android.graphics.drawable.BitmapDrawable")(res, bmp)
        return SvgHelper.getDrawable(self.content_string())

    def to_svg_thumb(self, color_key, alpha):
        d = J("org.telegram.messenger.SvgHelper").getDrawable(self.content_string())
        if d is not None:
            d.setColorKey(int(color_key))
            d.setAlpha(int(max(0.0, min(1.0, float(alpha))) * 255))
        return d

    def to_svg_bitmap(self, width=32, height=32, white=False):
        return J("org.telegram.messenger.SvgHelper").getBitmap(self.java_file, int(width), int(height), bool(white))

    def __str__(self):
        return self.path_str

    def __repr__(self):
        return "Asset(%r)" % self.path_str


class Assets:
    def __init__(self, dir_path, root=None):
        self.dir_path = pathlib.Path(dir_path)
        self._root = pathlib.Path(root) if root is not None else self.dir_path
        if not self.dir_path.is_dir():
            raise AssetsDirNotFoundException(self.dir_path)

    def _entries(self):
        if not self.dir_path.is_dir():
            raise AssetsDirNotFoundException(self.dir_path)
        return sorted(os.listdir(self.dir_path))

    def get(self, name):
        name = str(name).strip("/")
        if "/" in name:
            head, rest = name.split("/", 1)
            node = self.get(head)
            if not isinstance(node, Assets):
                raise AssetNotFoundException(name, self.dir_path)
            return node.get(rest)
        entries = self._entries()
        if name in entries:
            p = self.dir_path / name
            return Assets(p, self._root) if p.is_dir() else Asset(self.dir_path, name)
        for entry in entries:
            if normalize_name(entry) == name or entry.split(".", 1)[0] == name:
                p = self.dir_path / entry
                return Assets(p, self._root) if p.is_dir() else Asset(self.dir_path, entry)
        raise AssetNotFoundException(name, self.dir_path)

    __getitem__ = get

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return self.get(name)

    def __contains__(self, name):
        try:
            self.get(name)
            return True
        except AssetNotFoundException:
            return False

    def __iter__(self):
        return iter(self._entries())

    def __len__(self):
        return len(self._entries())

    @property
    def parent(self):
        if self.dir_path == self._root:
            raise ValueError("%s is the asset root" % self.dir_path)
        return Assets(self.dir_path.parent, self._root)

    def __str__(self):
        return str(self.dir_path)

    def __repr__(self):
        return "Assets(%r)" % str(self.dir_path)
