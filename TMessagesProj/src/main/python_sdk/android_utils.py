"""Android helpers: Java listener wrappers, UI thread, logging, clipboard."""

from java import dynamic_proxy

from _sg_java import J, PyRunnable, safe_call
from log_utils import log as _log

__all__ = ["R", "OnClickListener", "OnLongClickListener", "run_on_ui_thread", "log", "copy_to_clipboard"]


def R(fn, *args):
    """Returns a java.lang.Runnable that calls ``fn(*args)``."""
    return PyRunnable(fn, *args)


class OnClickListener(dynamic_proxy(J("android.view.View$OnClickListener"))):
    def __init__(self, fn):
        super().__init__()
        self._fn = fn

    def onClick(self, view):
        safe_call(self._fn, view, where="OnClickListener")


class OnLongClickListener(dynamic_proxy(J("android.view.View$OnLongClickListener"))):
    def __init__(self, fn):
        super().__init__()
        self._fn = fn

    def onLongClick(self, view):
        return bool(safe_call(self._fn, view, where="OnLongClickListener"))


def run_on_ui_thread(func, delay=0):
    J("org.telegram.messenger.AndroidUtilities").runOnUIThread(R(func), int(delay))


def log(data):
    _log(data)


def copy_to_clipboard(text):
    ok = bool(J("org.telegram.messenger.AndroidUtilities").addToClipboard(str(text)))
    if ok:
        try:
            from ui.bulletin import BulletinHelper
            BulletinHelper.show_copied_to_clipboard()
        except Exception:
            pass
    return ok
