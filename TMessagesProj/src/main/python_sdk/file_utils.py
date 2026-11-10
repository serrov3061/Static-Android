"""File helpers: standard directories and simple file I/O."""

import os
import secrets
import threading
import traceback
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional

from _sg_java import J, file_log, find_methods, sdk_hook

__all__ = [
    "get_plugins_dir", "get_cache_dir", "get_files_dir", "get_images_dir", "get_videos_dir",
    "get_audios_dir", "get_documents_dir", "ensure_dir_exists", "list_dir", "write_file", "read_file",
    "write_file_bytes", "read_file_bytes", "delete_file", "FilesController",
    "ExtensionAlreadyRegistered", "ExtensionNotRegistered", "SecretInvalid",
]


def get_plugins_dir() -> str:
    return str(J("com.exteragram.messenger.plugins.PluginsController").getInstance().getPluginsDir().getAbsolutePath())


def get_cache_dir() -> str:
    return str(J("org.telegram.messenger.ApplicationLoader").applicationContext.getCacheDir().getAbsolutePath())


def get_files_dir() -> str:
    return str(J("org.telegram.messenger.ApplicationLoader").getFilesDirFixed().getAbsolutePath())


def _media_dir(kind):
    FileLoader = J("org.telegram.messenger.FileLoader")
    return str(FileLoader.getDirectory(getattr(FileLoader, kind)).getAbsolutePath())


def get_images_dir() -> str:
    return _media_dir("MEDIA_DIR_IMAGE")


def get_videos_dir() -> str:
    return _media_dir("MEDIA_DIR_VIDEO")


def get_audios_dir() -> str:
    return _media_dir("MEDIA_DIR_AUDIO")


def get_documents_dir() -> str:
    return _media_dir("MEDIA_DIR_DOCUMENT")


def ensure_dir_exists(path: str) -> None:
    os.makedirs(path, exist_ok=True)


def list_dir(path: str, recursive: bool = False, include_files: bool = True, include_dirs: bool = False,
             extensions: Optional[List[str]] = None) -> List[str]:
    exts = None
    if extensions:
        exts = tuple(e.lower() if e.startswith(".") else "." + e.lower() for e in extensions)
    result = []
    if not os.path.isdir(path):
        return result
    walker = os.walk(path) if recursive else [(path, *next(os.walk(path))[1:])]
    for root, dirs, files in walker:
        if include_dirs:
            result.extend(os.path.join(root, d) for d in dirs)
        if include_files:
            for f in files:
                if exts is None or f.lower().endswith(exts):
                    result.append(os.path.join(root, f))
    return result


def write_file(path: str, content: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def read_file(path: str) -> Optional[str]:
    try:
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        return None


def write_file_bytes(path: str, content: bytes) -> None:
    with open(path, "wb") as f:
        f.write(content)


def read_file_bytes(path: str) -> Optional[bytes]:
    try:
        with open(path, "rb") as f:
            return f.read()
    except Exception:
        return None


def delete_file(path: str) -> bool:
    try:
        os.remove(path)
        return True
    except Exception:
        return False


class staticproperty:
    """A read-only property on the class itself."""

    def __init__(self, fget):
        self.fget = fget
        self.__doc__ = getattr(fget, "__doc__", None)

    def __get__(self, instance, owner=None):
        return self.fget()


class ExtensionAlreadyRegistered(Exception):
    def __init__(self, ext):
        super().__init__("The file extension '%s' is already in use." % (ext,))
        self.ext = ext


class ExtensionNotRegistered(Exception):
    def __init__(self, ext):
        super().__init__("The file extension '%s' is not registered." % (ext,))
        self.ext = ext


class SecretInvalid(Exception):
    def __init__(self, ext, secret=None):
        super().__init__("Provided secret '%s' for extension '%s' is invalid." % (secret, ext))
        self.ext = ext


class _Place(Enum):
    UNKNOWN = 0
    ChatActivity = 1
    FilteredSearchView = 2
    SharedMediaLayout = 3
    SearchDownloadsContainer = 4
    ChannelAdminLogActivity = 5


@dataclass
class _FileInfo:
    ext: str
    on_click: Callable[["FilesController.OnClickArgs"], None]
    whitelist_places: list = field(default_factory=list)
    blacklist_places: list = field(default_factory=list)
    get_icon: Optional[Callable[[], Any]] = None

    def __post_init__(self):
        if not isinstance(self.ext, str) or not self.ext.strip(".").strip():
            raise ValueError("FileInfo.ext must be a non-empty extension such as 'zip'")
        if not callable(self.on_click):
            raise TypeError("FileInfo.on_click must be callable")
        self.whitelist_places = list(self.whitelist_places or [])
        self.blacklist_places = list(self.blacklist_places or [])
        if self.whitelist_places and self.blacklist_places:
            raise ValueError("whitelist_places and blacklist_places cannot be used together")
        for p in self.whitelist_places + self.blacklist_places:
            if not isinstance(p, _Place):
                raise TypeError("places must be FilesController.Place values, got %r" % (p,))
        if self.get_icon is not None and not FilesController.SUPPORT_ICONS:
            raise ValueError("get_icon requires FilesController.SUPPORT_ICONS")


@dataclass
class _OnClickArgs:
    place: _Place
    file: Any
    file_name: Optional[str]
    message: Any = None
    activity: Any = None
    parent_fragment: Any = None


class FilesController:
    """Handlers for opening files by extension. A registered handler replaces the client's
    open flow (``AndroidUtilities.openDocument`` / ``openForView``) for its extension."""

    Place = _Place
    FileInfo = _FileInfo
    OnClickArgs = _OnClickArgs
    ExtensionAlreadyRegistered = ExtensionAlreadyRegistered
    ExtensionNotRegistered = ExtensionNotRegistered
    SecretInvalid = SecretInvalid

    # Document cells draw icons registered via PluginsController.registerFileIcon.
    SUPPORT_ICONS = True

    @staticproperty
    def DIRECT_FILE_ICONS():
        """Whether icons are drawn by the client from PluginsController.registerFileIcon."""
        return True

    __lock = threading.RLock()
    __m: Dict[str, tuple] = {}
    __h = []

    @staticmethod
    def _norm_ext(ext) -> str:
        return str(ext).strip().lstrip(".").lower()

    @classmethod
    def register(cls, file_info: _FileInfo) -> str:
        if not isinstance(file_info, _FileInfo):
            raise TypeError("register() expects a FilesController.FileInfo")
        ext = cls._norm_ext(file_info.ext)
        with cls.__lock:
            if ext in cls.__m:
                raise ExtensionAlreadyRegistered(ext)
            icon = cls._resolve_icon(file_info)
            secret = secrets.token_hex(16)
            cls.__m[ext] = (secret, file_info)
            if icon is not None:
                J("com.exteragram.messenger.plugins.PluginsController").registerFileIcon(ext, icon)
            cls.__ih()
        return secret

    @classmethod
    def unregister(cls, ext: str, secret: str) -> bool:
        ext = cls._norm_ext(ext)
        with cls.__lock:
            entry = cls.__m.get(ext)
            if entry is None:
                raise ExtensionNotRegistered(ext)
            if entry[0] != secret:
                raise SecretInvalid(ext, secret)
            del cls.__m[ext]
            cls._drop_icon(ext, entry[1])
        return True

    @classmethod
    def _stop(cls):
        with cls.__lock:
            for ext, entry in list(cls.__m.items()):
                cls._drop_icon(ext, entry[1])
            cls.__m.clear()
            hooks, cls.__h = cls.__h, []
        for u in hooks:
            try:
                u.unhook()
            except Exception:
                pass

    @staticmethod
    def _resolve_icon(info):
        """Call FileInfo.get_icon and coerce the result to an android Drawable (or None).
        Accepts Drawable, Bitmap, or a path to an image file."""
        if info.get_icon is None:
            return None
        try:
            icon = info.get_icon()
            if icon is None:
                return None
            if isinstance(icon, (str, os.PathLike)):
                icon = J("android.graphics.BitmapFactory").decodeFile(str(icon))
                if icon is None:
                    return None
            ctx = J("org.telegram.messenger.ApplicationLoader").applicationContext
            if hasattr(icon, "getByteCount") and hasattr(icon, "copy"):
                return J("android.graphics.drawable.BitmapDrawable")(ctx.getResources(), icon)
            if hasattr(icon, "getIntrinsicWidth"):
                return icon
            file_log("FilesController: get_icon returned unsupported %r" % (type(icon),), error=True)
        except Exception as e:
            file_log("FilesController: get_icon failed: %r" % (e,), error=True)
        return None

    @staticmethod
    def _drop_icon(ext, info):
        if info.get_icon is None:
            return
        try:
            J("com.exteragram.messenger.plugins.PluginsController").unregisterFileIcon(ext)
        except Exception:
            pass

    # ----- hooks

    @classmethod
    def __ih(cls):
        if cls.__h:
            return
        AU = "org.telegram.messenger.AndroidUtilities"
        try:
            for m in find_methods(AU, "openDocument") + find_methods(AU, "openForView"):
                types = [str(t.getName()) for t in m.getParameterTypes()]
                if types and types[0] in ("org.telegram.messenger.MessageObject", "java.io.File"):
                    cls.__h.append(sdk_hook(m, _OpenHook(m, types), 60))
        except Exception as e:
            file_log("FilesController: cannot hook file opening: %r" % (e,), error=True)

    @classmethod
    def _lookup(cls, file_name):
        if not file_name or "." not in str(file_name):
            return None
        ext = cls._norm_ext(str(file_name).rsplit(".", 1)[1])
        with cls.__lock:
            entry = cls.__m.get(ext)
        return entry[1] if entry else None

    @staticmethod
    def _get_place():
        try:
            for el in J("java.lang.Thread").currentThread().getStackTrace():
                name = str(el.getClassName()).rsplit(".", 1)[-1].split("$", 1)[0]
                try:
                    place = _Place[name]
                except KeyError:
                    continue
                if place is not _Place.UNKNOWN:
                    return place
        except Exception:
            pass
        return _Place.UNKNOWN

    @staticmethod
    def _allowed(info, place):
        if info.whitelist_places:
            return place in info.whitelist_places
        if info.blacklist_places:
            return place not in info.blacklist_places
        return True


def _message_file(message):
    owner = message.messageOwner
    f = None
    path = getattr(owner, "attachPath", None)
    if path:
        f = J("java.io.File")(path)
    if f is None or not f.exists():
        f = J("org.telegram.messenger.FileLoader").getInstance(message.currentAccount).getPathToMessage(owner)
    return f


class _OpenHook:
    def __init__(self, method, types):
        self._void = str(method.getReturnType().getName()) == "void"
        self._by_message = types[0] == "org.telegram.messenger.MessageObject"
        self._types = types

    def before_hooked_method(self, param):
        args = param.args
        try:
            if self._by_message:
                message = args[0]
                if message is None:
                    return
                file_name = message.getFileName()
                info = FilesController._lookup(file_name)
                if info is None:
                    return
                file = _message_file(message)
                activity = args[1] if len(args) > 1 else None
                fragment = args[2] if self._void and len(args) > 2 else None
            else:
                file, file_name = args[0], (args[1] if len(args) > 1 else None)
                if file_name is None and file is not None:
                    file_name = file.getName()
                info = FilesController._lookup(file_name)
                if info is None:
                    return
                message, activity, fragment = None, (args[3] if len(args) > 3 else None), None
            place = FilesController._get_place()
            if not FilesController._allowed(info, place):
                return
            info.on_click(_OnClickArgs(place=place, file=file, file_name=str(file_name) if file_name else None,
                                       message=message, activity=activity, parent_fragment=fragment))
        except Exception:
            file_log("FilesController handler failed: %s" % traceback.format_exc(), error=True)
            return
        param.setResult(None if self._void else J("java.lang.Boolean")(True))
