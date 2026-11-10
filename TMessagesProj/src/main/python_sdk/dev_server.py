"""Development server for desktop tooling (enabled by the plugins developer mode).

Listens on 127.0.0.1:42690 (use ``adb forward tcp:42690 tcp:42690``). The connection carries
consecutive UTF-8 JSON objects: ``{"@": command, "#": request id, ...}``; every response
repeats ``#``. Commands: ping, get_plugins, enable_plugin, disable_plugin, reload_plugin,
write_plugin, remove_plugin, start_debugger, stop_debugger.

Elyx structured plugins (docs "Development and Build"): elyx_ping, get_elyx_plugins,
elyx_compare_folder, elyx_changes. Their payload is in ``data`` as base64-encoded,
zlib-compressed JSON (plain top-level fields are accepted too); responses carry the result
both as plain fields and encoded the same way in ``data``.

The engine calls ``DevServer.start_server()`` / ``DevServer.stop_server()``.
"""

import base64
import json
import os
import socket
import tempfile
import threading
import traceback
from enum import Enum

from java import dynamic_proxy

from _sg_java import J, file_log, plugins_controller

__all__ = ["DevServer", "DebuggerPlatform"]

HOST = "127.0.0.1"
PORT = 42690
_MAX_BUFFER = 32 * 1024 * 1024


class DebuggerPlatform(Enum):
    VSCODE = "vscode"
    PYCHARM = "pycharm"


class Callback(dynamic_proxy(J("org.telegram.messenger.Utilities$Callback"))):
    def __init__(self, fn):
        super().__init__()
        self._fn = fn

    def run(self, arg):
        try:
            self._fn(arg)
        except Exception:
            file_log("dev server callback failed: %s" % traceback.format_exc(), error=True)


class DebuggerEventListener:
    """Receives app events while a remote debugger is attached (engine calls on_app_event)."""

    def __init__(self, platform):
        self.platform = platform

    def on_app_event(self, event_type):
        if str(getattr(event_type, "value", event_type)) == "app_stop":
            DevServer.stop_remote_debugging()


def _set_log_dev_mode(value):
    # the developer mode switch also sets the default plugin log level
    try:
        import log_utils
        log_utils._set_dev_mode(value)
    except Exception:
        pass


def _engine():
    return J("com.exteragram.messenger.plugins.PluginsController").getEngines().get("python")


def _engine_for(plugin_id):
    """The engine that owns ``plugin_id`` (Elyx plugins live in the ``elyx`` engine)."""
    try:
        engine = plugins_controller().getPluginEngine(plugin_id)
        if engine is not None:
            return engine
    except Exception:
        pass
    return _engine()


def _elyx_engine():
    return J("com.exteragram.messenger.plugins.PluginsController").getEngines().get("elyx")


def _decode_payload(request):
    data = request.get("data")
    if isinstance(data, str) and data:
        import zlib
        return json.loads(zlib.decompress(base64.b64decode(data)).decode("utf-8"))
    return {k: v for k, v in request.items() if k not in ("@", "#")}


def _encode_payload(payload):
    import zlib
    raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    return base64.b64encode(zlib.compress(raw)).decode("ascii")


def _elyx_response(payload):
    out = dict(payload)
    out["data"] = _encode_payload(payload)
    return out


class DevServer:
    _server_socket = None
    _server_thread = None
    _is_running = False
    _active_platform = None
    _lock = threading.RLock()

    # ----- lifecycle

    @classmethod
    def start_server(cls, host=HOST, port=PORT):
        with cls._lock:
            if cls._is_running:
                return True
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind((host, int(port)))
            sock.listen(4)
            sock.settimeout(1.0)
            cls._server_socket = sock
            cls._is_running = True
            cls._server_thread = threading.Thread(target=cls._server_thread_function, name="sg-dev-server",
                                                  daemon=True)
            cls._server_thread.start()
        _set_log_dev_mode(True)
        return True

    @classmethod
    def stop_server(cls):
        with cls._lock:
            cls._is_running = False
            sock, cls._server_socket = cls._server_socket, None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
        cls.stop_remote_debugging()
        _set_log_dev_mode(None)
        return True

    @classmethod
    def _server_thread_function(cls):
        while cls._is_running:
            sock = cls._server_socket
            if sock is None:
                break
            try:
                client, _ = sock.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=cls._handle_client, args=(client,), name="sg-dev-client", daemon=True).start()

    # ----- protocol

    @classmethod
    def _handle_client(cls, client):
        send_lock = threading.Lock()

        def send(obj):
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            with send_lock:
                try:
                    client.sendall(data)
                except OSError:
                    pass

        decoder = json.JSONDecoder()
        buffer = ""
        pending = b""
        try:
            client.settimeout(None)
            while cls._is_running:
                chunk = client.recv(65536)
                if not chunk:
                    break
                pending += chunk
                try:
                    text = pending.decode("utf-8")
                    pending = b""
                except UnicodeDecodeError:
                    continue  # wait for the rest of a multibyte character
                buffer += text
                if len(buffer) > _MAX_BUFFER:
                    send({"error": "request too large"})
                    break
                buffer = cls._process_buffer(buffer, decoder, send)
        except OSError:
            pass
        finally:
            try:
                client.close()
            except OSError:
                pass

    @classmethod
    def _process_buffer(cls, buffer, decoder, send):
        while True:
            stripped = buffer.lstrip()
            if not stripped:
                return ""
            try:
                obj, end = decoder.raw_decode(stripped)
            except json.JSONDecodeError:
                return stripped  # incomplete object
            buffer = stripped[end:]
            if isinstance(obj, dict):
                cls._process_command(obj, send)
            else:
                send({"error": "expected a JSON object"})

    @classmethod
    def _process_command(cls, request, send):
        command = request.get("@")
        request_id = request.get("#")
        handler = getattr(cls, "_handle_%s" % command, None) if isinstance(command, str) else None

        def reply(payload):
            payload = dict(payload)
            payload["#"] = request_id
            send(payload)

        if handler is None:
            reply({"error": "unknown command: %r" % (command,)})
            return
        try:
            result = handler(request, reply)
            if result is not None:
                reply(result)
        except Exception as e:
            reply({"error": "%s: %s" % (type(e).__name__, e)})

    # ----- commands

    @classmethod
    def _handle_ping(cls, request, reply):
        return {"pong": True}

    @classmethod
    def _get_plugins_map(cls):
        out = {}
        plugins = plugins_controller().getPlugins()
        for pid in list(plugins.keySet().toArray()):
            p = plugins.get(pid)
            if p is None:
                continue
            err = p.getError()
            out[str(pid)] = {
                "name": str(p.getName()) if p.getName() is not None else None,
                "author": str(p.getAuthor()) if p.getAuthor() is not None else None,
                "version": str(p.getVersion()) if p.getVersion() is not None else None,
                "description": str(p.getDescription()) if p.getDescription() is not None else None,
                "engine": str(p.getEngine()) if p.getEngine() is not None else None,
                "enabled": bool(p.isEnabled()),
                "error": str(err.getLocalizedMessage() or err.toString()) if err is not None else None,
            }
        return out

    @classmethod
    def _handle_get_plugins(cls, request, reply):
        return {"plugins": cls._get_plugins_map()}

    @staticmethod
    def _plugin_id(request):
        pid = request.get("plugin_id")
        if not isinstance(pid, str) or not pid:
            raise ValueError("plugin_id is required")
        return pid

    @classmethod
    def _set_enabled(cls, request, reply, enabled):
        pid = cls._plugin_id(request)
        # through the controller: enabling a plugin that was not analysed shows the static analysis
        plugins_controller().setPluginEnabled(pid, bool(enabled), Callback(
            lambda error: reply({"success": error is None, "error": None if error is None else str(error)})))
        return None

    @classmethod
    def _handle_enable_plugin(cls, request, reply):
        return cls._set_enabled(request, reply, True)

    @classmethod
    def _handle_disable_plugin(cls, request, reply):
        return cls._set_enabled(request, reply, False)

    @classmethod
    def _reload_plugin(cls, pid, reply):
        engine = _engine_for(pid)
        path = engine.getPluginPath(pid)
        if path is None or not os.path.isfile(str(path)):
            raise FileNotFoundError("plugin %s is not installed" % pid)

        def enabled_again(error):
            reply({"success": error is None, "error": None if error is None else str(error)})

        def disabled(error):
            if error is not None:
                reply({"success": False, "error": str(error)})
                return
            plugins_controller().setPluginEnabled(pid, True, Callback(enabled_again))
        engine.setPluginEnabled(pid, False, Callback(disabled))

    @classmethod
    def _handle_reload_plugin(cls, request, reply):
        cls._reload_plugin(cls._plugin_id(request), reply)
        return None

    @classmethod
    def _handle_write_plugin(cls, request, reply):
        pid = cls._plugin_id(request)
        content = request.get("content")
        if not isinstance(content, str):
            raise ValueError("content must be the plugin source as a string")
        tmp_dir = J("org.telegram.messenger.ApplicationLoader").applicationContext.getCacheDir().getAbsolutePath()
        fd, tmp_path = tempfile.mkstemp(prefix="dev_", suffix=".plugin", dir=str(tmp_dir))  # .plugin: picked by the python engine
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(content)
            f.flush()
            os.fsync(f.fileno())

        def done(error):
            try:
                os.remove(tmp_path)
            except OSError:
                pass
            reply({"success": error is None, "error": None if error is None else str(error)})
        # static analysis: the file is installed from the install sheet (an update of a reviewed plugin
        # that adds no new capabilities goes straight in); with the analysis off it is installed directly
        J("com.exteragram.messenger.plugins.analyzer.PluginAnalysisGate").requestInstall(tmp_path, True, False, Callback(done))
        return None

    @classmethod
    def _handle_remove_plugin(cls, request, reply):
        pid = cls._plugin_id(request)
        _engine_for(pid).deletePlugin(pid, Callback(
            lambda error: reply({"success": error is None, "error": None if error is None else str(error)})))
        return None

    # ----- Elyx structured plugins

    @classmethod
    def _elyx_plugins(cls):
        return {pid: info for pid, info in cls._get_plugins_map().items() if info.get("engine") == "elyx"}

    @classmethod
    def _handle_elyx_ping(cls, request, reply):
        return _elyx_response({"pong": True, "elyx": _elyx_engine() is not None})

    @classmethod
    def _handle_get_elyx_plugins(cls, request, reply):
        plugins = cls._elyx_plugins()
        try:
            from elyxcore import _engine as elyx_engine
            for pid, info in plugins.items():
                meta = json.loads(elyx_engine.read_installed(pid))
                if meta.get("ok"):
                    info["metainfo"] = meta.get("plugin")
                info["path"] = elyx_engine.plugin_dir(pid)
        except Exception:
            pass
        return _elyx_response({"plugins": plugins})

    @staticmethod
    def _elyx_plugin_id(payload):
        pid = payload.get("plugin_id")
        if not isinstance(pid, str) or not pid:
            raise ValueError("plugin_id is required")
        return pid

    @classmethod
    def _handle_elyx_compare_folder(cls, request, reply):
        from elyxcore import _engine as elyx_engine
        payload = _decode_payload(request)
        pid = cls._elyx_plugin_id(payload)
        if pid not in cls._elyx_plugins():
            return _elyx_response({"success": False, "error": "Plugin with id %s not found!" % pid})
        try:
            result = elyx_engine.compare_folder(pid, payload.get("hashes") or payload.get("files") or {})
        except Exception as e:
            return _elyx_response({"success": False, "error": "Error comparing folder: %s" % e})
        result["success"] = True
        return _elyx_response(result)

    @classmethod
    def _handle_elyx_changes(cls, request, reply):
        from elyxcore import _engine as elyx_engine
        payload = _decode_payload(request)
        pid = cls._elyx_plugin_id(payload)
        engine = _elyx_engine()
        if engine is None or pid not in cls._elyx_plugins():
            return _elyx_response({"success": False, "errors": ["Plugin with id %s not found!" % pid]})
        try:
            errors = elyx_engine.apply_changes(pid, payload.get("changes") or [])
        except Exception as e:
            return _elyx_response({"success": False, "errors": ["Error applying changes: %s" % e]})

        def reloaded(error):
            all_errors = list(errors) + ([str(error)] if error is not None else [])
            reply(_elyx_response({"success": error is None and not errors, "errors": all_errors}))
        engine.reloadPlugin(pid, Callback(reloaded))
        return None

    # ----- remote debugging

    @classmethod
    def _handle_start_debugger(cls, request, reply):
        host = request.get("host") or "127.0.0.1"
        port = int(request.get("port") or 5678)
        platform = DebuggerPlatform(str(request.get("platform") or "vscode").lower())
        cls.setup_remote_debugging(host, port, platform)
        return {"success": True, "platform": platform.value}

    @classmethod
    def _handle_stop_debugger(cls, request, reply):
        cls.stop_remote_debugging()
        return {"success": True}

    @classmethod
    def setup_remote_debugging(cls, host, port, platform):
        platform = DebuggerPlatform(platform) if not isinstance(platform, DebuggerPlatform) else platform
        cls.stop_remote_debugging()
        if platform is DebuggerPlatform.VSCODE:
            import debugpy  # needs ``debugpy`` installed through the plugins PIP
            debugpy.connect((host, port))
        else:
            import pydevd_pycharm  # needs ``pydevd-pycharm`` installed through the plugins PIP
            pydevd_pycharm.settrace(host, port=port, stdoutToServer=True, stderrToServer=True, suspend=False)
        cls._active_platform = platform
        try:
            _engine().setDebuggerListener(DebuggerEventListener(platform))
        except Exception:
            pass

    @classmethod
    def stop_remote_debugging(cls):
        platform, cls._active_platform = cls._active_platform, None
        if platform is None:
            return
        try:
            if platform is DebuggerPlatform.PYCHARM:
                import pydevd_pycharm
                pydevd_pycharm.stoptrace()
        except Exception:
            pass
        try:
            _engine().setDebuggerListener(None)
        except Exception:
            pass
