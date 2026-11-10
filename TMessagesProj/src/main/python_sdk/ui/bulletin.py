"""BulletinHelper: small toast-like bulletins (Telegram's BulletinFactory)."""

from typing import Callable, Optional

from _sg_java import J, PyRunnable, file_log


def _fragment(fragment):
    if fragment is not None:
        return fragment
    try:
        return J("org.telegram.ui.LaunchActivity").getSafeLastFragment()
    except Exception:
        return None


def _factory(fragment):
    BulletinFactory = J("org.telegram.ui.Components.BulletinFactory")
    frag = _fragment(fragment)
    try:
        if frag is not None and BulletinFactory.canShowBulletin(frag):
            return BulletinFactory.of(frag)
    except Exception:
        pass
    return BulletinFactory.global_() if hasattr(BulletinFactory, "global_") else getattr(BulletinFactory, "global")()


def _raw(name):
    return getattr(J("org.telegram.messenger.R$raw"), name)


def _on_ui(fn):
    def runner():
        try:
            bulletin = fn()
            if bulletin is not None:
                bulletin.show()
        except Exception as e:
            file_log("BulletinHelper failed: %r" % (e,), error=True)

    J("org.telegram.messenger.AndroidUtilities").runOnUIThread(PyRunnable(runner))


class BulletinHelper:
    DURATION_SHORT = 1500
    DURATION_LONG = 2750
    DURATION_PROLONG = 5000

    @classmethod
    def show_info(cls, message: str, fragment=None):
        cls.show_simple(message, _raw("info"), fragment)

    @classmethod
    def show_error(cls, message: str, fragment=None):
        _on_ui(lambda: _factory(fragment).createErrorBulletin(str(message)))

    @classmethod
    def show_success(cls, message: str, fragment=None):
        _on_ui(lambda: _factory(fragment).createSuccessBulletin(str(message)))

    @classmethod
    def show_simple(cls, text: str, icon_res_id: int, fragment=None):
        _on_ui(lambda: _factory(fragment).createSimpleBulletin(int(icon_res_id), str(text)))

    @classmethod
    def show_two_line(cls, title: str, subtitle: str, icon_res_id: int, fragment=None):
        _on_ui(lambda: _factory(fragment).createSimpleBulletin(int(icon_res_id), str(title), str(subtitle)))

    @classmethod
    def show_with_button(cls, text: str, icon_res_id: int, button_text: str,
                         on_click: Optional[Callable[[], None]], fragment=None,
                         duration: int = DURATION_PROLONG):
        cb = PyRunnable(on_click) if on_click else None
        _on_ui(lambda: _factory(fragment).createSimpleBulletin(
            int(icon_res_id), str(text), str(button_text), int(duration), cb))

    @classmethod
    def show_undo(cls, text: str, on_undo: Callable[[], None], on_action: Optional[Callable[[], None]] = None,
                  subtitle: Optional[str] = None, fragment=None):
        undo = PyRunnable(on_undo)
        action = PyRunnable(on_action) if on_action else None
        if subtitle:
            _on_ui(lambda: _factory(fragment).createUndoBulletin(str(text), str(subtitle), undo, action))
        else:
            _on_ui(lambda: _factory(fragment).createUndoBulletin(str(text), undo, action))

    @classmethod
    def show_copied_to_clipboard(cls, message: Optional[str] = None, fragment=None):
        if message:
            _on_ui(lambda: _factory(fragment).createCopyBulletin(str(message)))
        else:
            _on_ui(lambda: _factory(fragment).createCopyBulletin(
                J("org.telegram.messenger.LocaleController").getString(J("org.telegram.messenger.R$string").TextCopied)))

    @classmethod
    def show_link_copied(cls, is_private_link_info: bool = False, fragment=None):
        _on_ui(lambda: _factory(fragment).createCopyLinkBulletin(bool(is_private_link_info)))

    @classmethod
    def show_file_saved_to_gallery(cls, is_video: bool = False, amount: int = 1, fragment=None):
        FileType = J("org.telegram.ui.Components.BulletinFactory$FileType")
        ft = FileType.VIDEO if is_video else FileType.PHOTO
        if amount > 1:
            ft = FileType.VIDEOS if is_video else FileType.PHOTOS
        _on_ui(lambda: _factory(fragment).createDownloadBulletin(ft, int(amount), None))

    @classmethod
    def show_file_saved_to_downloads(cls, file_type_enum_name: str = "UNKNOWN", amount: int = 1, fragment=None):
        FileType = J("org.telegram.ui.Components.BulletinFactory$FileType")
        ft = FileType.valueOf(str(file_type_enum_name))
        _on_ui(lambda: _factory(fragment).createDownloadBulletin(ft, int(amount), None))
