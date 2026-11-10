"""Sample: an outgoing-message hook that appends a suffix to text messages."""

from base_plugin import BasePlugin, HookResult, HookStrategy
from ui.settings import Input, Switch

__id__ = "sg_send_suffix"
__name__ = "Message suffix"
__description__ = "Appends a configurable suffix to every outgoing text message."
__author__ = "StaticGram"
__version__ = "1.0.0"
__icon__ = "exteraPlugins/2"
__sdk_version__ = ">=1.4.4.3"

DEFAULT_SUFFIX = " ✨"


class SendSuffixPlugin(BasePlugin):
    def on_plugin_load(self):
        self.add_on_send_message_hook()

    def create_settings(self):
        return [
            Switch(key="enabled", text="Append suffix", default=True),
            Input(key="suffix", text="Suffix", default=DEFAULT_SUFFIX),
        ]

    def on_send_message_hook(self, account, params):
        if not self.get_setting("enabled", True):
            return HookResult()
        text = getattr(params, "message", None)
        if not text or text.startswith("/"):
            return HookResult()  # media without caption or a bot command
        suffix = self.get_setting("suffix", DEFAULT_SUFFIX)
        if not suffix or text.endswith(suffix):
            return HookResult()
        params.message = text + suffix
        self.logger.debug("account %s: suffix appended", account)
        return HookResult(strategy=HookStrategy.MODIFY, params=params)
