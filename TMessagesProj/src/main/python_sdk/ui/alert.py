"""AlertDialogBuilder: a Pythonic wrapper around Telegram's AlertDialog.Builder."""

from typing import Callable, Dict, List, Optional

from java import dynamic_proxy, jarray

from _sg_java import J, safe_call

_Builder = J("org.telegram.ui.ActionBar.AlertDialog$Builder")


class _ButtonListener(dynamic_proxy(J("org.telegram.ui.ActionBar.AlertDialog$OnButtonClickListener"))):
    def __init__(self, owner, fn):
        super().__init__()
        self._owner, self._fn = owner, fn

    def onClick(self, dialog, which):
        safe_call(self._fn, self._owner, which, where="alert button")


class _ItemsListener(dynamic_proxy(J("android.content.DialogInterface$OnClickListener"))):
    def __init__(self, owner, fn):
        super().__init__()
        self._owner, self._fn = owner, fn

    def onClick(self, dialog, which):
        safe_call(self._fn, self._owner, which, where="alert item")


class _DismissListener(dynamic_proxy(J("android.content.DialogInterface$OnDismissListener"))):
    def __init__(self, owner, fn):
        super().__init__()
        self._owner, self._fn = owner, fn

    def onDismiss(self, dialog):
        safe_call(self._fn, self._owner, where="alert dismiss")


class _CancelListener(dynamic_proxy(J("android.content.DialogInterface$OnCancelListener"))):
    def __init__(self, owner, fn):
        super().__init__()
        self._owner, self._fn = owner, fn

    def onCancel(self, dialog):
        safe_call(self._fn, self._owner, where="alert cancel")


class AlertDialogBuilder:
    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 2
    ALERT_TYPE_SPINNER = 3
    BUTTON_POSITIVE = -1
    BUTTON_NEGATIVE = -2
    BUTTON_NEUTRAL = -3

    def __init__(self, context, progress_style: int = ALERT_TYPE_MESSAGE, resources_provider=None):
        self._builder = _Builder(context, int(progress_style), resources_provider)
        self._dialog = None
        self._keep = []  # keep Python listeners alive as long as the builder

    def _listener(self, cls, fn):
        if fn is None:
            return None
        lst = cls(self, fn)
        self._keep.append(lst)
        return lst

    def set_title(self, title: str):
        self._builder.setTitle(title)
        return self

    def set_message(self, message: str):
        self._builder.setMessage(message)
        return self

    def set_message_text_view_clickable(self, clickable: bool):
        self._builder.setMessageTextViewClickable(bool(clickable))
        return self

    def set_view(self, view, height: int = -2):
        self._builder.setView(view, int(height))
        return self

    def set_items(self, items: List[str], listener: Optional[Callable] = None, icons: Optional[List[int]] = None):
        arr = jarray(J("java.lang.CharSequence"))([str(i) for i in items])
        lst = self._listener(_ItemsListener, listener)
        if icons:
            self._builder.setItems(arr, jarray("I")([int(i) for i in icons]), lst)
        else:
            self._builder.setItems(arr, lst)
        return self

    def set_positive_button(self, text: str, listener: Optional[Callable] = None):
        self._builder.setPositiveButton(text, self._listener(_ButtonListener, listener))
        return self

    def set_negative_button(self, text: str, listener: Optional[Callable] = None):
        self._builder.setNegativeButton(text, self._listener(_ButtonListener, listener))
        return self

    def set_neutral_button(self, text: str, listener: Optional[Callable] = None):
        self._builder.setNeutralButton(text, self._listener(_ButtonListener, listener))
        return self

    def make_button_red(self, button_type: int):
        self._builder.makeRed(int(button_type))
        return self

    def set_on_back_button_listener(self, listener: Optional[Callable] = None):
        self._builder.setOnBackButtonListener(self._listener(_ButtonListener, listener))
        return self

    def set_on_dismiss_listener(self, listener: Optional[Callable] = None):
        self._builder.setOnDismissListener(self._listener(_DismissListener, listener))
        return self

    def set_on_cancel_listener(self, listener: Optional[Callable] = None):
        self._builder.setOnCancelListener(self._listener(_CancelListener, listener))
        return self

    def set_top_image(self, res_id: int, background_color: int):
        self._builder.setTopImage(int(res_id), int(background_color))
        return self

    def set_top_drawable(self, drawable, background_color: int):
        self._builder.setTopImage(drawable, int(background_color))
        return self

    def set_top_animation(self, res_id: int, size: int, auto_repeat: bool, background_color: int,
                          layer_colors: Optional[Dict[str, int]] = None):
        if layer_colors:
            m = J("java.util.HashMap")()
            for k, v in layer_colors.items():
                m.put(str(k), J("java.lang.Integer")(int(v)))
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat), int(background_color), m)
        else:
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat), int(background_color))
        return self

    def set_top_animation_is_new(self, is_new: bool):
        self._builder.setTopAnimationIsNew(bool(is_new))
        return self

    def set_on_pre_dismiss_listener(self, listener: Optional[Callable] = None):
        """``listener(builder)`` runs right before the dialog starts dismissing."""
        self._builder.setOnPreDismissListener(self._listener(_DismissListener, listener))
        return self

    def get_context(self):
        return self._builder.getContext()

    def set_dim_enabled(self, enabled: bool):
        self._builder.setDimEnabled(bool(enabled))
        return self

    def set_dialog_button_color_key(self, theme_key: int):
        self._builder.setDialogButtonColorKey(int(theme_key))
        return self

    def set_blurred_background(self, blur: bool, blur_behind_if_possible: bool = True):
        self._builder.setBlurredBackground(bool(blur))
        return self

    def set_cancelable(self, cancelable: bool):
        self._cancelable = bool(cancelable)
        if self._dialog is not None:
            self._dialog.setCancelable(self._cancelable)
        return self

    def set_canceled_on_touch_outside(self, cancel: bool):
        self._touch_outside = bool(cancel)
        if self._dialog is not None:
            self._dialog.setCanceledOnTouchOutside(self._touch_outside)
        return self

    def _apply_flags(self):
        if self._dialog is None:
            return
        if hasattr(self, "_cancelable"):
            self._dialog.setCancelable(self._cancelable)
        if hasattr(self, "_touch_outside"):
            self._dialog.setCanceledOnTouchOutside(self._touch_outside)

    def create(self):
        if self._dialog is None:
            self._dialog = self._builder.create()
            self._apply_flags()
        return self

    def show(self):
        if self._dialog is None:
            self.create()
        self._dialog.show()
        return self

    def dismiss(self):
        if self._dialog is not None:
            self._dialog.dismiss()

    def get_dialog(self):
        return self._dialog

    def get_button(self, button_type: int):
        return self._dialog.getButton(int(button_type)) if self._dialog is not None else None

    def set_progress(self, progress: int):
        if self._dialog is not None:
            self._dialog.setProgress(int(progress))
        return self
