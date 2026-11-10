"""Client helpers: queues, raw requests, message sending, controllers of an account.

Multi-account: every account-scoped helper takes ``account=``. Without it the account
selected in the UI is used; when that differs from the account a hook is handling
(``get_hook_account()``), a warning is logged once per helper. ``get_client(account)``
returns an :class:`AccountClient` bound to one account.
"""

import contextlib
import contextvars
import os
import threading
from typing import Any, Callable, Dict, Optional

from java import dynamic_proxy

from _sg_java import J, PyRunnable, file_log, safe_call, to_array_list

STAGE_QUEUE = "stageQueue"
GLOBAL_QUEUE = "globalQueue"
CACHE_CLEAR_QUEUE = "cacheClearQueue"
SEARCH_QUEUE = "searchQueue"
PHONE_BOOK_QUEUE = "phoneBookQueue"
THEME_QUEUE = "themeQueue"
EXTERNAL_NETWORK_QUEUE = "externalNetworkQueue"
PLUGINS_QUEUE = "pluginsQueue"

__all__ = [
    "STAGE_QUEUE", "GLOBAL_QUEUE", "CACHE_CLEAR_QUEUE", "SEARCH_QUEUE", "PHONE_BOOK_QUEUE",
    "THEME_QUEUE", "EXTERNAL_NETWORK_QUEUE", "PLUGINS_QUEUE",
    "run_on_queue", "get_queue_by_name", "send_request", "RequestCallback",
    "send_text", "send_photo", "send_document", "send_video", "send_audio", "send_message", "edit_message",
    "get_last_fragment", "get_account_instance", "get_messages_controller", "get_contacts_controller",
    "get_media_data_controller", "get_connections_manager", "get_location_controller",
    "get_notifications_controller", "get_messages_storage", "get_send_messages_helper",
    "get_file_loader", "get_secret_chat_helper", "get_download_controller",
    "get_notifications_settings", "get_notification_center", "get_media_controller",
    "get_user_config", "NotificationCenterDelegate", "AccountClient",
    "get_client", "get_hook_account", "get_selected_account", "account_scope",
]


def _Utilities():
    return J("org.telegram.messenger.Utilities")


# ---------------------------------------------------------------- account scope

_hook_account = contextvars.ContextVar("sg_hook_account", default=None)
_warned_account_mismatches = set()
_warn_lock = threading.Lock()


def get_selected_account() -> int:
    """The account selected in the UI (global state, not the account a hook fired for)."""
    return int(J("org.telegram.messenger.UserConfig").selectedAccount)


def get_hook_account() -> Optional[int]:
    """The account the current hook / scheduled work / request callback runs for, or None."""
    return _hook_account.get()


@contextlib.contextmanager
def account_scope(account: Optional[int]):
    """Runs a block with ``account`` as the hook account (None clears the scope)."""
    token = _hook_account.set(None if account is None else int(account))
    try:
        yield
    finally:
        _hook_account.reset(token)


def _run_in_scope(account, fn, *args, **kwargs):
    if account is None:
        return fn(*args, **kwargs)
    with account_scope(account):
        return fn(*args, **kwargs)


def _resolve_account(account, helper=None):
    """Explicit account wins; otherwise the selected one (warning once per helper when a
    hook is handling another account)."""
    if account is not None:
        return int(account)
    selected = get_selected_account()
    hook = _hook_account.get()
    if helper and hook is not None and hook != selected:
        with _warn_lock:
            first = helper not in _warned_account_mismatches
            _warned_account_mismatches.add(helper)
        if first:
            try:
                from log_utils import Logger
                Logger("pysdk.accounts", show_location=False).warning(
                    "%s() is using account %d because it is the one selected in the UI, "
                    "but it was called while handling account %d. Pass account=%d, or use "
                    "get_client(%d), if you meant that account. Logged once per helper.",
                    helper, selected, hook, hook, hook)
            except Exception:
                pass
    return selected


def _selected(account, helper=None):
    return _resolve_account(account, helper)


# ---------------------------------------------------------------- queues

def get_queue_by_name(name: str):
    if name == PLUGINS_QUEUE:
        q = _Utilities().pluginsQueue
        if q is None:
            # created lazily by the controller
            J("com.exteragram.messenger.plugins.PluginsController").runOnPluginsQueue(PyRunnable(lambda: None))
            q = _Utilities().pluginsQueue
        return q
    try:
        return getattr(_Utilities(), name)
    except Exception:
        return None


def run_on_queue(fn: Callable[[], Any], queue: str = PLUGINS_QUEUE, delay: int = 0, delay_ms: Optional[int] = None):
    """Runs ``fn`` on a DispatchQueue; the current hook account stays in scope."""
    if delay_ms is not None:
        delay = delay_ms
    q = get_queue_by_name(queue)
    if q is None:
        raise ValueError("Unknown queue: %r" % (queue,))
    scoped = _hook_account.get()
    r = PyRunnable(_run_in_scope, scoped, fn) if scoped is not None else PyRunnable(fn)
    if delay and int(delay) > 0:
        q.postRunnable(r, int(delay))
    else:
        q.postRunnable(r)


# ---------------------------------------------------------------- requests

class RequestCallback(dynamic_proxy(J("org.telegram.tgnet.RequestDelegate"))):
    def __init__(self, fn: Callable[[Any, Any], None], account: Optional[int] = None):
        super().__init__()
        self._fn = fn
        self._account = account

    def run(self, response, error):
        safe_call(_run_in_scope, self._account, self._fn, response, error, where="request callback")


def send_request(request, fn: Optional[Callable[[Any, Any], None]] = None, account: Optional[int] = None):
    """Sends a raw TLObject; ``fn(response, error)`` runs with the account in scope.
    Returns the request id."""
    acc = _resolve_account(account, "send_request")
    if isinstance(fn, RequestCallback):
        cb = fn
        if cb._account is None:
            cb._account = acc
    else:
        cb = RequestCallback(fn or (lambda r, e: None), acc)
    return get_connections_manager(acc).sendRequest(request, cb)


# ---------------------------------------------------------------- accounts / controllers

def get_account_instance(account: Optional[int] = None):
    return J("org.telegram.messenger.AccountInstance").getInstance(_selected(account, "get_account_instance"))


def _per_account(cls_name, helper):
    def getter(account: Optional[int] = None):
        return J(cls_name).getInstance(_selected(account, helper))
    getter.__name__ = getter.__qualname__ = helper
    return getter


get_messages_controller = _per_account("org.telegram.messenger.MessagesController", "get_messages_controller")
get_contacts_controller = _per_account("org.telegram.messenger.ContactsController", "get_contacts_controller")
get_media_data_controller = _per_account("org.telegram.messenger.MediaDataController", "get_media_data_controller")
get_connections_manager = _per_account("org.telegram.tgnet.ConnectionsManager", "get_connections_manager")
get_location_controller = _per_account("org.telegram.messenger.LocationController", "get_location_controller")
get_notifications_controller = _per_account("org.telegram.messenger.NotificationsController", "get_notifications_controller")
get_messages_storage = _per_account("org.telegram.messenger.MessagesStorage", "get_messages_storage")
get_send_messages_helper = _per_account("org.telegram.messenger.SendMessagesHelper", "get_send_messages_helper")
get_file_loader = _per_account("org.telegram.messenger.FileLoader", "get_file_loader")
get_secret_chat_helper = _per_account("org.telegram.messenger.SecretChatHelper", "get_secret_chat_helper")
get_download_controller = _per_account("org.telegram.messenger.DownloadController", "get_download_controller")
get_notification_center = _per_account("org.telegram.messenger.NotificationCenter", "get_notification_center")
get_user_config = _per_account("org.telegram.messenger.UserConfig", "get_user_config")


def get_notifications_settings(account: Optional[int] = None):
    return J("org.telegram.messenger.MessagesController").getNotificationsSettings(
        _selected(account, "get_notifications_settings"))


def get_media_controller():
    return J("org.telegram.messenger.MediaController").getInstance()


def get_last_fragment():
    return J("org.telegram.ui.LaunchActivity").getLastFragment()


class NotificationCenterDelegate(dynamic_proxy(J("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate"))):
    """Subclass and implement ``didReceivedNotification(self, id, account, args)``."""

    def didReceivedNotification(self, id, account, args):
        pass


# ---------------------------------------------------------------- sending

def _apply_parse_mode(params: Dict[str, Any], parse_mode):
    if not parse_mode:
        return params
    from extera_utils.text_formatting import parse_text_raw
    for key in ("message", "caption"):
        if params.get(key):
            parsed = parse_text_raw(params[key], parse_mode)
            params[key] = parsed.text
            params["entities"] = parsed.tlrpc_entities()
            break
    return params


def _hashmap(d):
    if d is None:
        return None
    m = J("java.util.HashMap")()
    for k, v in dict(d).items():
        m.put(str(k), str(v))
    return m


_FIELD_ALIASES = {"params": "params", "replyToMsg": "replyToMsg", "replyToTopMsg": "replyToTopMsg"}


_OBJECT_METHOD_NAMES = frozenset(("notify", "notifyAll", "wait", "hashCode", "equals", "toString", "getClass"))


def _set_java_field(obj, name, value):
    """Set a public Java field; names clashing with java.lang.Object methods (``notify``) go through reflection."""
    if name not in _OBJECT_METHOD_NAMES:
        try:
            setattr(obj, name, value)
            return
        except Exception as e:
            if "is not a field" not in str(e):
                raise
    f = obj.getClass().getField(name)
    tname = str(f.getType().getName())
    if tname == "boolean":
        f.setBoolean(obj, bool(value))
    elif tname == "int":
        f.setInt(obj, int(value))
    elif tname == "long":
        f.setLong(obj, int(value))
    else:
        f.set(obj, value)


def _get_java_field(obj, name):
    if name not in _OBJECT_METHOD_NAMES:
        try:
            return getattr(obj, name)
        except Exception as e:
            if "is not a field" not in str(e):
                raise
    return obj.getClass().getField(name).get(obj)


def send_message(params: Dict[str, Any], parse_mode: Optional[str] = None, account: Optional[int] = None):
    """Low-level sender: maps a dict onto SendMessagesHelper.SendMessageParams fields."""
    params = _apply_parse_mode(dict(params), parse_mode)
    account = _resolve_account(params.pop("account", account), "send_message")
    SMP = J("org.telegram.messenger.SendMessagesHelper$SendMessageParams")
    smp = SMP.of(params.get("message") or "", int(params.get("peer", 0)))
    smp.message = params.get("message")
    for key, value in params.items():
        if key in ("message", "account"):
            continue
        if key == "entities":
            value = to_array_list(value) if value is not None else None
        elif key == "params":
            value = _hashmap(value)
        elif key == "peer":
            value = int(value)
        try:
            _set_java_field(smp, key, value)
        except Exception as e:
            file_log("send_message: cannot set %s: %r" % (key, e), error=True)
    if "notify" not in params:
        _set_java_field(smp, "notify", True)
    J("org.telegram.messenger.AndroidUtilities").runOnUIThread(
        PyRunnable(lambda: get_send_messages_helper(account).sendMessage(smp)))
    return smp


def _check_media(path):
    if not path or not os.path.isfile(str(path)):
        raise FileNotFoundError("Media file not found at path: %s" % (path,))
    return str(path)


def send_text(peer_id: int, text: str, replyToMsg=None, replyToTopMsg=None, parse_mode: Optional[str] = None,
              account: Optional[int] = None, **kwargs):
    p = {"peer": peer_id, "message": text, "replyToMsg": replyToMsg, "replyToTopMsg": replyToTopMsg}
    p.update(kwargs)
    return send_message(p, parse_mode=parse_mode, account=_resolve_account(account, "send_text"))


def _caption_entities(caption, parse_mode):
    if not caption or not parse_mode:
        return caption, None
    from extera_utils.text_formatting import parse_text_raw
    parsed = parse_text_raw(caption, parse_mode)
    return parsed.text, to_array_list(parsed.tlrpc_entities())


def send_photo(peer_id: int, photo_path: str, caption: Optional[str] = None, high_quality: bool = False,
               parse_mode: Optional[str] = None, replyToMsg=None, account: Optional[int] = None):
    photo_path = _check_media(photo_path)
    caption, entities = _caption_entities(caption, parse_mode)
    SMH = J("org.telegram.messenger.SendMessagesHelper")
    ai = get_account_instance(_resolve_account(account, "send_photo"))
    run = lambda: SMH.prepareSendingPhoto(
        ai, str(photo_path), None, None, int(peer_id), replyToMsg, None, None, None, entities, None, None, 0,
        None, None, True, 0, 0, 1 if high_quality else 0, False, caption, None, 0, 0, 0, None)
    J("org.telegram.messenger.AndroidUtilities").runOnUIThread(PyRunnable(run))


def send_document(peer_id: int, file_path: str, caption: Optional[str] = None, parse_mode: Optional[str] = None,
                  replyToMsg=None, account: Optional[int] = None):
    file_path = _check_media(file_path)
    caption, _ = _caption_entities(caption, parse_mode)
    SMH = J("org.telegram.messenger.SendMessagesHelper")
    ai = get_account_instance(_resolve_account(account, "send_document"))
    run = lambda: SMH.prepareSendingDocument(
        ai, str(file_path), str(file_path), None, caption, None, int(peer_id), replyToMsg, None, None, None, None,
        True, 0, None, None, False)
    J("org.telegram.messenger.AndroidUtilities").runOnUIThread(PyRunnable(run))


def send_audio(peer_id: int, audio_path: str, caption: Optional[str] = None, parse_mode: Optional[str] = None,
               replyToMsg=None, account: Optional[int] = None):
    return send_document(peer_id, audio_path, caption, parse_mode, replyToMsg, account)


def send_video(peer_id: int, video_path: str, caption: Optional[str] = None, parse_mode: Optional[str] = None,
               replyToMsg=None, account: Optional[int] = None):
    video_path = _check_media(video_path)
    caption, entities = _caption_entities(caption, parse_mode)
    SMH = J("org.telegram.messenger.SendMessagesHelper")
    ai = get_account_instance(_resolve_account(account, "send_video"))
    run = lambda: SMH.prepareSendingVideo(
        ai, str(video_path), None, None, None, int(peer_id), replyToMsg, None, None, None, entities, 0, None,
        True, 0, 0, False, False, caption, None, 0, 0)
    J("org.telegram.messenger.AndroidUtilities").runOnUIThread(PyRunnable(run))


_IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp", ".bmp", ".heic", ".heif")


def _prepare_document(path, account):
    """TL_document for a local file (attributes filename / audio / video when known)."""
    TLRPC = "org.telegram.tgnet.TLRPC$"
    doc = J(TLRPC + "TL_document")()
    doc.file_reference = J("java.lang.reflect.Array").newInstance(J("java.lang.Byte").TYPE, 0)
    doc.date = int(get_connections_manager(account).getCurrentTime())
    ext = os.path.splitext(path)[1].lower().lstrip(".")
    mime = None
    try:
        mime = J("android.webkit.MimeTypeMap").getSingleton().getMimeTypeFromExtension(ext)
    except Exception:
        mime = None
    doc.mime_type = str(mime) if mime else "application/octet-stream"
    doc.size = os.path.getsize(path)
    doc.dc_id = 0
    name_attr = J(TLRPC + "TL_documentAttributeFilename")()
    name_attr.file_name = os.path.basename(path)
    doc.attributes.add(name_attr)
    if doc.mime_type.startswith("video/"):
        _add_video_attributes(doc, path)
    elif doc.mime_type.startswith("audio/"):
        _add_audio_attributes(doc, path)
    return doc


def _metadata(path):
    retriever = J("android.media.MediaMetadataRetriever")()
    try:
        retriever.setDataSource(path)
        return retriever, (lambda key: retriever.extractMetadata(key))
    except Exception:
        retriever.release()
        raise


def _add_video_attributes(doc, path):
    MMR = J("android.media.MediaMetadataRetriever")
    try:
        retriever, get = _metadata(path)
    except Exception as e:
        file_log("Could not get video metadata for '%s': %r" % (path, e), error=True)
        return
    try:
        attr = J("org.telegram.tgnet.TLRPC$TL_documentAttributeVideo")()
        attr.duration = int(get(MMR.METADATA_KEY_DURATION) or 0) / 1000.0
        attr.w = int(get(MMR.METADATA_KEY_VIDEO_WIDTH) or 0)
        attr.h = int(get(MMR.METADATA_KEY_VIDEO_HEIGHT) or 0)
        attr.supports_streaming = True
        doc.attributes.add(attr)
    except Exception as e:
        file_log("Could not get video metadata for '%s': %r" % (path, e), error=True)
    finally:
        retriever.release()


def _add_audio_attributes(doc, path):
    MMR = J("android.media.MediaMetadataRetriever")
    try:
        retriever, get = _metadata(path)
    except Exception as e:
        file_log("Could not get audio metadata for '%s': %r" % (path, e), error=True)
        return
    try:
        attr = J("org.telegram.tgnet.TLRPC$TL_documentAttributeAudio")()
        attr.duration = int(get(MMR.METADATA_KEY_DURATION) or 0) / 1000.0
        title = get(MMR.METADATA_KEY_TITLE)
        performer = get(MMR.METADATA_KEY_ARTIST)
        if title:
            attr.title = str(title)
            attr.flags |= 1
        if performer:
            attr.performer = str(performer)
            attr.flags |= 2
        doc.attributes.add(attr)
    except Exception as e:
        file_log("Could not get audio metadata for '%s': %r" % (path, e), error=True)
    finally:
        retriever.release()


def _generate_photo_sizes(path, account):
    try:
        return get_send_messages_helper(account).generatePhotoSizes(path, None)
    except Exception as e:
        file_log("Error generating photo sizes for '%s': %r" % (path, e), error=True)
        return None


def edit_message(message_obj, text: Optional[str] = None, file_path: Optional[str] = None,
                 with_spoiler: bool = False, parse_mode: Optional[str] = None, account: Optional[int] = None):
    """Edits the text/caption of a MessageObject and/or replaces its media with ``file_path``."""
    if parse_mode and str(parse_mode).strip().lower() not in ("html", "markdown"):
        raise ValueError("Invalid parse mode: %s. Must be 'html' or 'markdown'." % (parse_mode,))
    if account is None:
        account = getattr(message_obj, "currentAccount", None)
    acc = _resolve_account(account, "edit_message")
    entities = None
    if text is not None and parse_mode:
        from extera_utils.text_formatting import parse_text_raw
        parsed = parse_text_raw(text, parse_mode)
        text, entities = parsed.text, parsed.tlrpc_entities()

    if file_path:
        path = _check_media(file_path)
        photo = document = None
        if os.path.splitext(path)[1].lower() in _IMAGE_EXTS:
            photo = _generate_photo_sizes(path, acc)
            if photo is None:
                raise RuntimeError("Failed to generate photo sizes for: %s" % path)
        else:
            document = _prepare_document(path, acc)
        if text is not None:
            message_obj.editingMessage = text
            message_obj.editingMessageEntities = to_array_list(entities or [])
        params = J("java.util.HashMap")()

        def run():
            get_send_messages_helper(acc).editMessage(message_obj, photo, None, document, path, None, params,
                                                       False, bool(with_spoiler), None)
        J("org.telegram.messenger.AndroidUtilities").runOnUIThread(PyRunnable(run))
        return None

    req = J("org.telegram.tgnet.TLRPC$TL_messages_editMessage")()
    req.peer = get_messages_controller(acc).getInputPeer(message_obj.getDialogId())
    req.id = message_obj.getId()
    if text is not None:
        req.message = text
        req.flags |= 2048
        if entities:
            req.entities = to_array_list(entities)
            req.flags |= 8
    return send_request(req, lambda r, e: r is not None and get_messages_controller(acc).processUpdates(r, False),
                        account=acc)


_CLIENT_GETTERS = (
    "get_account_instance", "get_messages_controller", "get_contacts_controller", "get_media_data_controller",
    "get_connections_manager", "get_location_controller", "get_notifications_controller",
    "get_messages_storage", "get_send_messages_helper", "get_file_loader", "get_secret_chat_helper",
    "get_download_controller", "get_notifications_settings", "get_notification_center", "get_user_config",
)


class AccountClient:
    """The account-scoped helpers of this module bound to one account
    (``BasePlugin.client(account)`` / ``get_client(account)``)."""

    __slots__ = ("account",)

    def __init__(self, account: int):
        self.account = int(account)

    def __repr__(self):
        return "AccountClient(account=%d)" % self.account

    def __eq__(self, other):
        return isinstance(other, AccountClient) and other.account == self.account

    def __hash__(self):
        return hash(("AccountClient", self.account))

    # ----- controllers

    def get_account_instance(self):
        return get_account_instance(self.account)

    def get_messages_controller(self):
        return get_messages_controller(self.account)

    def get_contacts_controller(self):
        return get_contacts_controller(self.account)

    def get_media_data_controller(self):
        return get_media_data_controller(self.account)

    def get_connections_manager(self):
        return get_connections_manager(self.account)

    def get_location_controller(self):
        return get_location_controller(self.account)

    def get_notifications_controller(self):
        return get_notifications_controller(self.account)

    def get_messages_storage(self):
        return get_messages_storage(self.account)

    def get_send_messages_helper(self):
        return get_send_messages_helper(self.account)

    def get_file_loader(self):
        return get_file_loader(self.account)

    def get_secret_chat_helper(self):
        return get_secret_chat_helper(self.account)

    def get_download_controller(self):
        return get_download_controller(self.account)

    def get_notifications_settings(self):
        return get_notifications_settings(self.account)

    def get_notification_center(self):
        return get_notification_center(self.account)

    def get_user_config(self):
        return get_user_config(self.account)

    # ----- requests and messages

    def send_request(self, request, fn=None):
        return send_request(request, fn, account=self.account)

    def send_message(self, params, parse_mode=None):
        return send_message(params, parse_mode=parse_mode, account=self.account)

    def send_text(self, peer_id, text, **kw):
        return send_text(peer_id, text, account=self.account, **kw)

    def send_photo(self, peer_id, photo_path, *args, **kw):
        return send_photo(peer_id, photo_path, *args, account=self.account, **kw)

    def send_document(self, peer_id, file_path, *args, **kw):
        return send_document(peer_id, file_path, *args, account=self.account, **kw)

    def send_video(self, peer_id, video_path, *args, **kw):
        return send_video(peer_id, video_path, *args, account=self.account, **kw)

    def send_audio(self, peer_id, audio_path, *args, **kw):
        return send_audio(peer_id, audio_path, *args, account=self.account, **kw)

    def edit_message(self, message_obj, *args, **kw):
        return edit_message(message_obj, *args, account=self.account, **kw)


def get_client(account: Optional[int] = None) -> AccountClient:
    """AccountClient for ``account``; without it, the hook account in scope, else the
    account selected in the UI."""
    if account is None:
        account = _hook_account.get()
        if account is None:
            account = get_selected_account()
    return AccountClient(int(account))
