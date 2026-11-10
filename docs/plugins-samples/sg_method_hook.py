"""Sample: an Xposed-style method hook on a client class."""

from base_plugin import BasePlugin, MethodHook
from hook_utils import find_class
from ui.bulletin import BulletinHelper

__id__ = "sg_method_hook"
__name__ = "Resume counter"
__description__ = "Hooks `LaunchActivity.onResume()` and counts how often the app came to the foreground."
__author__ = "StaticGram"
__version__ = "1.0.0"
__icon__ = "exteraPlugins/3"
__sdk_version__ = ">=1.4.4.3"


class ResumeHook(MethodHook):
    def __init__(self, plugin):
        self.plugin = plugin

    def after_hooked_method(self, param):
        count = int(self.plugin.get_setting("resumes", 0)) + 1
        self.plugin.set_setting("resumes", count)
        self.plugin.logger.info("LaunchActivity.onResume #%d (%s)", count, param.thisObject.getClass().getSimpleName())
        if count % 5 == 0:
            BulletinHelper.show_info("Resumed %d times" % count)


class ResumeCounterPlugin(BasePlugin):
    def on_plugin_load(self):
        clazz = find_class("org.telegram.ui.LaunchActivity")
        if clazz is None:
            self.logger.error("LaunchActivity not found")
            return
        method = clazz.getDeclaredMethod("onResume")
        self.unhook = self.hook_method(method, ResumeHook(self))
        self.logger.info("hooked %s: %s", method, bool(self.unhook))
